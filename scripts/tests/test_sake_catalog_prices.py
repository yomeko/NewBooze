"""Verify capacity-aware price backfill in randomly named disposable databases.

Run from the repository root (Docker and the Python standard library only):
    python3 scripts/tests/test_sake_catalog_prices.py --image mariadb:10.11
    python3 scripts/tests/test_sake_catalog_prices.py --container disposable-mariadb

An existing container must have codex.purpose=sake-catalog-test. The runner never
connects to an application database and removes only its own fixture databases.
"""

import argparse
from contextlib import contextmanager
import csv
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import time

from test_sake_catalog_mysql import (
    DATABASE_IMAGES, MysqlSandbox, builder_module, catalog_rows, product_key, sql_literal,
)

ROOT = Path(__file__).resolve().parents[2]


@contextmanager
def fixture_database(sandbox):
    database = sandbox.fresh_database()
    try:
        yield database
    finally:
        sandbox.mysql(f"DROP DATABASE `{database}`;", check=False)


def source_record(**overrides):
    return {
        "prefecture_code": 1, "prefecture": "北海道", "brewery": "価格検証酒造株式会社",
        "name": "価格検証酒 純米", "sake_type": "純米酒", "checked_on": "2026-11-13",
        "source_url": "https://example.invalid/price-test", "source_note": "隔離DB専用の検証データ",
    } | overrides


def price_attributes(record, **overrides):
    return {
        "name": record["name"], "brewery": record["brewery"], "prefecture_code": record["prefecture_code"],
        "price": 1650, "volume_ml": 500, "price_tax_included": True,
        "price_source_url": "https://example.invalid/price-test", "checked_on": "2026-11-13",
        "abv": 15.5, "abv_source_url": "https://example.invalid/price-test",
        "image_url": "https://example.invalid/price-test.jpg", "image_source_url": "https://example.invalid/price-test",
        "image_http_status": 200, "image_content_type": "image/jpeg",
    } | overrides


def price_fixture_sql(record, attributes=None, *, mode="prices"):
    builder = builder_module()
    with tempfile.TemporaryDirectory(prefix="newbooze-prices-fixture-") as temporary:
        directory = Path(temporary)
        (directory / "fixture.json").write_text(json.dumps([record], ensure_ascii=False), encoding="utf-8")
        loaded = builder.load_records(directory)
        if attributes is not None:
            enrichment = directory / "enrichment"
            enrichment.mkdir()
            (enrichment / "fixture.json").write_text(json.dumps([attributes], ensure_ascii=False), encoding="utf-8")
            builder.apply_enrichment(loaded, enrichment)
        return builder.make_sql(loaded, prices_only=mode == "prices", attributes_only=mode == "attributes")


def detail_rows(sandbox, database):
    return sandbox.mysql("SELECT sake_id, detail_json FROM sake_page_details ORDER BY sake_id;", database).stdout


def related_snapshot(sandbox, database):
    tables = ("breweries", "sake_types", "sake_tags", "users", "favorites", "sake_reviews")
    return {table: sandbox.mysql(f"SELECT * FROM `{table}` ORDER BY 1, 2;", database).stdout for table in tables}


def without_prices(rows):
    return [row[:7] + row[8:] for row in rows]


def read_page(sandbox, database, sake_id):
    return sandbox.json_rows(database, f"SELECT detail_json FROM sake_page_details WHERE sake_id = {sake_id};")[0]


def set_page(sandbox, database, sake_id, page):
    serialized = page if isinstance(page, str) else json.dumps(page, ensure_ascii=False)
    sandbox.mysql("INSERT INTO sake_page_details (sake_id, detail_json) VALUES "
                  f"({sake_id}, {sql_literal(serialized)}) ON DUPLICATE KEY UPDATE detail_json = VALUES(detail_json);", database)
    return serialized


def test_real_price_file(sandbox, records):
    builder = builder_module()
    expected = {record["name"]: record["_attributes"] for record in records
                if record.get("_attributes", {}).get("price") is not None}
    assert expected and any(value["volume_ml"] != 720 for value in expected.values())
    # This is the user's rebuilt shape: every product already has a detail JSON,
    # but later discoveries need to fill price and the empty prices array.
    priceless = [record | {"_attributes": record.get("_attributes", {}) | {"price": None}}
                 for record in records]
    with fixture_database(sandbox) as database:
        if not sandbox.image.startswith("mariadb:"):
            from test_sake_catalog_mysql import fixture_catalog_sql
            sandbox.mysql(fixture_catalog_sql(records), database)
        else:
            sandbox.mysql(builder.make_sql(priceless, rebuild=True), database)
        before = catalog_rows(sandbox, database)
        related = related_snapshot(sandbox, database)
        before_pages = {row[0]: json.loads(row[1]) for row in sandbox.json_rows(
            database, "SELECT JSON_ARRAY(sake_id, detail_json) FROM sake_page_details;")}
        difference = (ROOT / "data/sake_catalog_prices.sql").read_text(encoding="utf-8")
        sandbox.mysql(difference, database)
        after = catalog_rows(sandbox, database)
        assert without_prices(after) == without_prices(before), "Price-only import changed product IDs, names, other attributes, or links"
        assert related_snapshot(sandbox, database) == related, "Price-only import changed related or master data"
        by_name = {row[1]: row for row in after}
        after_pages = {row[0]: json.loads(row[1]) for row in sandbox.json_rows(
            database, "SELECT JSON_ARRAY(sake_id, detail_json) FROM sake_page_details;")}
        for record in records:
            row = by_name[record["name"]]
            attributes = expected.get(record["name"])
            assert row[7] == (attributes["price"] if attributes else None), ("Wrong numeric price", record["name"], row[7])
            if attributes:
                page = after_pages[row[0]]
                assert page["prices"] == [{"volume": f"{attributes['volume_ml']}ml", "yen": attributes["price"]}], \
                    ("Capacity or tax-inclusive price differs from the official record", record["name"], page)
                if row[0] in before_pages:
                    assert {key: value for key, value in page.items() if key != "prices"} == {
                        key: value for key, value in before_pages[row[0]].items() if key != "prices"
                    }, ("Price merge changed another JSON field", record["name"])
        columns = sandbox.mysql("SHOW COLUMNS FROM sake;", database).stdout.splitlines()
        assert [line.split("\t", 1)[0] for line in columns] == [
            "id", "brewery_id", "sake_type_id", "name", "region", "abv", "price", "image_url", "description", "created_at"]
        first_details = detail_rows(sandbox, database)
        sandbox.mysql(difference, database)
        assert catalog_rows(sandbox, database) == after and detail_rows(sandbox, database) == first_details, \
            "Reapplying the price file changed product values or JSON bytes"
    print(f"PASS: real price file stores {len(expected)} official capacity/price pairs, preserves all other data and ten columns, and is stable on rerun")


def test_price_merge_and_guards(sandbox):
    record = source_record()
    attributes = price_attributes(record)
    sql = price_fixture_sql(record, attributes)
    with fixture_database(sandbox) as database:
        sandbox.mysql(price_fixture_sql(record, mode="catalog"), database)
        sake_id = catalog_rows(sandbox, database)[-1][0]
        original = {"id": sake_id, "name": record["name"], "introduction": "既存の紹介文を保持",
                    "abvText": "15度以上16度未満", "purchaseLinks": [{"label": "公式", "url": "https://example.invalid/shop", "official": True}],
                    "customData": {"keep": [1, 2, 3]}}
        baseline = catalog_rows(sandbox, database)
        for prices in ("missing", None, []):
            page = original.copy()
            if prices != "missing":
                page["prices"] = prices
            set_page(sandbox, database, sake_id, page)
            sandbox.mysql(f"UPDATE sake SET price = NULL WHERE id = {sake_id};", database)
            sandbox.mysql(sql, database)
            assert read_page(sandbox, database, sake_id) == original | {"prices": [{"volume": "500ml", "yen": 1650}]}
            after = catalog_rows(sandbox, database)
            assert without_prices(after) == without_prices(baseline) and after[-1][7] == 1650
        # Explicit zero and manual prices are existing values, not missing ones.
        for price in (0, 9999):
            set_page(sandbox, database, sake_id, original | {"prices": []})
            sandbox.mysql(f"UPDATE sake SET price = {price} WHERE id = {sake_id};", database)
            before, details = catalog_rows(sandbox, database), detail_rows(sandbox, database)
            sandbox.mysql(sql, database)
            assert catalog_rows(sandbox, database) == before and detail_rows(sandbox, database) == details, \
                "Price import overwrote a manual price or attached a different capacity to it"
        # Existing arrays, even incomplete ones, are the editor's saved detail.
        for prices in ([{"volume": "300ml", "yen": 700}], [{"volume": "", "yen": 1800}], [{"volume": "720ml", "yen": None}]):
            serialized = set_page(sandbox, database, sake_id, original | {"prices": prices})
            sandbox.mysql(f"UPDATE sake SET price = NULL WHERE id = {sake_id};", database)
            sandbox.mysql(sql, database)
            assert sandbox.mysql(f"SELECT detail_json FROM sake_page_details WHERE sake_id = {sake_id};", database).stdout.strip() == serialized
            assert catalog_rows(sandbox, database)[-1][7] == 1650
        sandbox.mysql(f"UPDATE sake SET price = 1650 WHERE id = {sake_id};", database)
        invalid = ["{broken", "[]", '"scalar"', original | {"id": sake_id + 1},
                   original | {"id": str(sake_id)}, original | {"name": "異なる商品"},
                   {key: value for key, value in original.items() if key != "id"},
                   {key: value for key, value in original.items() if key != "name"},
                   original | {"prices": {}}, original | {"prices": "empty"},
                   original | {"prices": {"volume": "500ml", "yen": 1650}}]
        for page in invalid:
            serialized = set_page(sandbox, database, sake_id, page)
            before = catalog_rows(sandbox, database)
            result = sandbox.mysql(sql, database)
            assert sandbox.mysql(f"SELECT detail_json FROM sake_page_details WHERE sake_id = {sake_id};", database).stdout.strip() == serialized
            assert catalog_rows(sandbox, database) == before
            assert "existing_price_or_invalid_or_mismatched_or_nonempty_detail" in result.stdout, \
                "A malformed or mismatched price detail was not reported as pending"
    print("PASS: price merge fills only missing arrays, preserves intro/ABV/links/custom fields/manual prices, and rejects invalid or mismatched JSON")


def test_classpath_fallback(sandbox):
    source_path = ROOT / "src/main/resources/data/sake-pages.json"
    source_bytes = source_path.read_bytes()
    pages = json.loads(source_bytes)
    with fixture_database(sandbox) as database:
        existing = {row[0]: row for row in catalog_rows(sandbox, database)}
        for page in (next(page for page in pages if page.get("introduction") and not page.get("prices")),
                     next(page for page in pages if page.get("introduction") and page.get("prices"))):
            product = existing[page["id"]]
            brewery = page.get("brewery") or "価格検証の同梱紹介用酒造"
            sandbox.mysql("INSERT INTO breweries (name, prefecture) VALUES (" + sql_literal(brewery) + ", "
                          + sql_literal(product[2]) + ");"
                          f"UPDATE sake SET brewery_id = LAST_INSERT_ID(), price = NULL WHERE id = {product[0]};", database)
            record = source_record(name=product[1], brewery=brewery, prefecture=product[2],
                                   prefecture_code=builder_module().PREFECTURES.index(product[2]) + 1, sake_type=product[5])
            result = sandbox.mysql(price_fixture_sql(record, price_attributes(record)), database)
            saved = sandbox.json_rows(database, f"SELECT detail_json FROM sake_page_details WHERE sake_id = {product[0]};")
            expected = page if page.get("prices") else page | {"prices": [{"volume": "500ml", "yen": 1650}]}
            if saved:
                assert saved[0] == expected, "New price JSON shadowed or changed the existing classpath introduction"
            else:
                assert page.get("prices"), "An empty classpath price was not filled"
                assert "existing_price_or_invalid_or_mismatched_or_nonempty_detail" in result.stdout, \
                    "A preserved but different classpath price was not reported"
        assert source_path.read_bytes() == source_bytes
    print("PASS: capacity backfill preserves matching classpath introductions and existing price arrays")


def test_identity_and_lock_guards(sandbox):
    alias = "価格検証酒 別名 純米"
    record = source_record(aliases=[alias])
    sql = price_fixture_sql(record, price_attributes(record))
    lock_expression = "GET_LOCK(@sake_catalog_lock_name, 30)"
    assert sql.count(lock_expression) == 1
    immediate = sql.replace(lock_expression, "GET_LOCK(@sake_catalog_lock_name, 0)")
    with fixture_database(sandbox) as database:
        sandbox.mysql(price_fixture_sql(record, mode="catalog"), database)
        sandbox.mysql("INSERT INTO sake (brewery_id,sake_type_id,name,region) SELECT brewery_id,sake_type_id,"
                      + sql_literal(alias) + ",region FROM sake WHERE name=" + sql_literal(record["name"])
                      + " COLLATE utf8mb4_unicode_ci;", database)
        before, details = catalog_rows(sandbox, database), detail_rows(sandbox, database)
        sandbox.mysql(sql, database)
        assert catalog_rows(sandbox, database) == before and detail_rows(sandbox, database) == details
        sandbox.mysql("DELETE FROM sake WHERE name=" + sql_literal(alias) + " COLLATE utf8mb4_unicode_ci;"
                      "UPDATE sake SET region='沖縄県' WHERE name=" + sql_literal(record["name"]) + " COLLATE utf8mb4_unicode_ci;", database)
        before, details = catalog_rows(sandbox, database), detail_rows(sandbox, database)
        sandbox.mysql(sql, database)
        assert catalog_rows(sandbox, database) == before and detail_rows(sandbox, database) == details
        sandbox.mysql("UPDATE sake SET region='北海道' WHERE name=" + sql_literal(record["name"]) + " COLLATE utf8mb4_unicode_ci;", database)
        before, details = catalog_rows(sandbox, database), detail_rows(sandbox, database)
        holder = subprocess.Popen(
            ["docker", "--host=unix:///var/run/docker.sock", "exec", "-i", sandbox.container,
             sandbox.client, "--default-character-set=utf8mb4", "--batch", "--raw", "--skip-column-names", "--unbuffered", "-uroot", database],
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, encoding="utf-8", env=sandbox.environment)
        try:
            holder.stdin.write("SELECT GET_LOCK(CONCAT('sake_catalog_', LEFT(SHA2(DATABASE(),256),48)),0);\n")
            holder.stdin.flush()
            for _ in range(50):
                if sandbox.mysql("SELECT IS_USED_LOCK(CONCAT('sake_catalog_',LEFT(SHA2(DATABASE(),256),48))) IS NOT NULL;", database).stdout.strip() == "1":
                    break
                time.sleep(0.1)
            else:
                raise AssertionError("Fixture did not acquire its named lock")
            sandbox.mysql(immediate, database)
            assert catalog_rows(sandbox, database) == before and detail_rows(sandbox, database) == details
        finally:
            try:
                holder.communicate("DO RELEASE_LOCK(CONCAT('sake_catalog_', LEFT(SHA2(DATABASE(),256),48)));\n", timeout=5)
            except (BrokenPipeError, subprocess.TimeoutExpired):
                holder.kill()
                holder.communicate(timeout=5)
    print("PASS: ambiguous aliases, mismatched regions and unavailable locks prevent price changes")


def test_price_failure_rolls_back(sandbox):
    record = source_record()
    sql = price_fixture_sql(record, price_attributes(record))
    with fixture_database(sandbox) as database:
        sandbox.mysql(price_fixture_sql(record, mode="catalog"), database)
        sake_id = catalog_rows(sandbox, database)[-1][0]
        set_page(sandbox, database, sake_id, {"id": sake_id, "name": record["name"], "prices": [], "abvText": "原文を保持"})
        sandbox.mysql("CREATE TRIGGER reject_price_merge BEFORE UPDATE ON sake_page_details FOR EACH ROW "
                      "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='price-rollback-test';", database)
        before, related, details = catalog_rows(sandbox, database), related_snapshot(sandbox, database), detail_rows(sandbox, database)
        rejected = sandbox.mysql(sql, database, check=False)
        assert rejected.returncode != 0 and "price-rollback-test" in rejected.stderr
        assert catalog_rows(sandbox, database) == before and related_snapshot(sandbox, database) == related and detail_rows(sandbox, database) == details
        assert sandbox.mysql("SELECT IS_FREE_LOCK(CONCAT('sake_catalog_',LEFT(SHA2(DATABASE(),256),48)));", database).stdout.strip() == "1"
    print("PASS: a detail merge error rolls back its preceding numeric price update and releases the lock")


def test_missing_reports(sandbox, records):
    missing = {record["name"] for record in records if record.get("_attributes", {}).get("price") is None}
    with (ROOT / "data/sake-catalog/missing-prices.csv").open(encoding="utf-8-sig", newline="") as source:
        exported = list(csv.DictReader(source))
    assert {row["name"] for row in exported} == missing and len(exported) == len(missing), \
        "Missing-price CSV does not match the source catalog"
    assert not exported or "id" not in exported[0], "A source sequence must not be presented as a database ID"
    assert [int(row["catalog_sequence"]) for row in exported] == sorted(int(row["catalog_sequence"]) for row in exported)
    expected_prices = {record["name"]: record["_attributes"] for record in records
                       if record.get("_attributes", {}).get("price") is not None}
    with (ROOT / "data/sake-catalog/verified-prices.csv").open(encoding="utf-8-sig", newline="") as source:
        verified = list(csv.DictReader(source))
    assert {row["name"] for row in verified} == set(expected_prices)
    for row in verified:
        attributes = expected_prices[row["name"]]
        assert int(row["volume_ml"]) == attributes["volume_ml"] and int(row["price"]) == attributes["price"]
        assert row["price_display"] == f"{attributes['volume_ml']}ml {attributes['price']:,}円（税込）"
    with fixture_database(sandbox) as database:
        sandbox.mysql("UPDATE sake SET price = NULL WHERE id=1; UPDATE sake SET price = 0 WHERE id=2;", database)
        before, details = catalog_rows(sandbox, database), detail_rows(sandbox, database)
        result = sandbox.mysql((ROOT / "data/sake_catalog_missing_prices.sql").read_text(encoding="utf-8"), database)
        assert "獺祭 純米大吟醸45" in result.stdout and "新政 No.6" in result.stdout
        assert catalog_rows(sandbox, database) == before and detail_rows(sandbox, database) == details
    print(f"PASS: missing-price CSV lists {len(missing)} unconfirmed source products and the SQL reports NULL/zero prices without writes")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--image", choices=DATABASE_IMAGES, default="mariadb:10.11")
    parser.add_argument("--container", help="Existing disposable container with the catalog-test label")
    args = parser.parse_args()
    builder = builder_module()
    records = builder.apply_enrichment(builder.load_records(ROOT / "data/sake-catalog/regions"), ROOT / "data/sake-catalog/enrichment")
    with MysqlSandbox(args.container, args.image) as sandbox:
        print("Database version:", sandbox.mysql("SELECT VERSION();").stdout.strip())
        test_real_price_file(sandbox, records)
        test_price_merge_and_guards(sandbox)
        test_classpath_fallback(sandbox)
        test_identity_and_lock_guards(sandbox)
        test_price_failure_rolls_back(sandbox)
        test_missing_reports(sandbox, records)


if __name__ == "__main__":
    try:
        main()
    except subprocess.CalledProcessError as error:
        print(error.stderr.strip() if error.stderr else str(error), file=sys.stderr)
        raise SystemExit(error.returncode)
