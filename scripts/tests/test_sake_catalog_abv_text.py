"""Validate official ABV text without changing the ten-column sake schema.

Run from the repository root with Docker and Python's standard library:
    python3 scripts/tests/test_sake_catalog_abv_text.py --image mariadb:10.11
    python3 scripts/tests/test_sake_catalog_abv_text.py --container disposable-mariadb

An existing container must have codex.purpose=sake-catalog-test. Only randomly
named fixture databases are created; no application database is contacted.
"""

import argparse
from contextlib import contextmanager
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import time

from test_sake_catalog_mysql import (
    DATABASE_IMAGES, MysqlSandbox, builder_module, catalog_rows,
    fixture_catalog_sql, product_key, sql_literal,
)


ROOT = Path(__file__).resolve().parents[2]
RANGE_TEXT = "15度以上16度未満"


@contextmanager
def fixture_database(sandbox):
    database = sandbox.fresh_database()
    try:
        yield database
    finally:
        sandbox.mysql(f"DROP DATABASE `{database}`;", check=False)


def source_record(**overrides):
    record = {
        "prefecture_code": 1, "prefecture": "北海道", "brewery": "範囲検証酒造株式会社",
        "name": "範囲検証酒 純米", "sake_type": "純米酒", "checked_on": "2026-11-12",
        "source_url": "https://example.invalid/abv-text-test", "source_note": "隔離DB専用の検証商品",
    }
    return record | overrides


def range_attributes(record, **overrides):
    return {
        "name": record["name"], "brewery": record["brewery"],
        "prefecture_code": record["prefecture_code"], "abv": None,
        "abv_text": RANGE_TEXT, "abv_source_url": "https://example.invalid/abv-text-test",
        "checked_on": "2026-11-12", "notes": "隔離DB専用の範囲表記",
    } | overrides


def range_fixture_sql(record, attributes=None, *, mode="range"):
    builder = builder_module()
    with tempfile.TemporaryDirectory(prefix="newbooze-abv-text-fixture-") as temporary:
        directory = Path(temporary)
        (directory / "fixture.json").write_text(json.dumps([record], ensure_ascii=False), encoding="utf-8")
        loaded = builder.load_records(directory)
        if attributes is not None:
            enrichment = directory / "enrichment"
            enrichment.mkdir()
            (enrichment / "fixture.json").write_text(json.dumps([attributes], ensure_ascii=False), encoding="utf-8")
            builder.apply_enrichment(loaded, enrichment)
        return builder.make_sql(loaded, attributes_only=mode == "attributes", abv_text_only=mode == "range")


def unchanged_tables(sandbox, database):
    tables = ("sake", "breweries", "sake_types", "sake_tags", "users", "favorites", "sake_reviews")
    return {table: sandbox.mysql(f"SELECT * FROM `{table}` ORDER BY 1, 2;", database).stdout
            for table in tables}


def detail_rows(sandbox, database):
    return sandbox.mysql("SELECT sake_id, detail_json FROM sake_page_details ORDER BY sake_id;", database).stdout


def assert_all_official_ranges(sandbox, database, records):
    expected = {product_key(record["name"]): record["_attributes"]["abv_text"] for record in records
                if record.get("_attributes", {}).get("abv") is None
                and record.get("_attributes", {}).get("abv_text")}
    assert len(expected) == 196, "Review the range expectation if verified source coverage changes"
    pages = sandbox.json_rows(database, """
        SELECT JSON_ARRAY(s.name, d.detail_json, s.abv)
        FROM sake s JOIN sake_page_details d ON d.sake_id = s.id ORDER BY s.id;
    """)
    actual = {}
    for name, serialized, numeric in pages:
        page = json.loads(serialized)
        if product_key(name) in expected:
            assert numeric is None, ("An official range was guessed into a numeric ABV", name, numeric)
            assert page.get("name") == name, ("ABV text was stored on a different detail product", name, page)
            actual[product_key(name)] = page.get("abvText")
    assert actual == expected, "Stored ABV text differs from one or more official source records"
    columns = sandbox.mysql("SHOW COLUMNS FROM sake;", database).stdout.splitlines()
    assert [line.split("\t", 1)[0] for line in columns] == [
        "id", "brewery_id", "sake_type_id", "name", "region", "abv", "price", "image_url", "description", "created_at",
    ], "ABV text changed the requested ten-column sake schema"


def test_real_difference_file(sandbox, records):
    with fixture_database(sandbox) as database:
        # Source loading deliberately strips private enrichment fields, as in the
        # earlier catalog where imported products had no ABV/price/image values.
        sandbox.mysql(fixture_catalog_sql(records), database)
        before = unchanged_tables(sandbox, database)
        sql = (ROOT / "data/sake_catalog_abv_text.sql").read_text(encoding="utf-8")
        sandbox.mysql(sql, database)
        assert unchanged_tables(sandbox, database) == before, \
            "The dedicated ABV-text file changed products, attributes, masters, users, or associations"
        assert_all_official_ranges(sandbox, database, records)
        first_details = detail_rows(sandbox, database)
        sandbox.mysql(sql, database)
        assert unchanged_tables(sandbox, database) == before
        assert detail_rows(sandbox, database) == first_details, "Reapplying ABV text changed saved JSON bytes"
    print("PASS: the real difference file stores all 196 official ranges, preserves all catalog data, "
          "keeps ten columns, and is byte-stable when reapplied")


def test_other_import_paths(sandbox, records):
    for mode, filename in (("catalog", "sake_catalog_prefecture_order.sql"),
                           ("attributes", "sake_catalog_attributes.sql"),
                           ("rebuild", "sake_catalog_rebuild.sql")):
        if mode == "rebuild" and not sandbox.image.startswith("mariadb:"):
            continue
        with fixture_database(sandbox) as database:
            if mode == "attributes":
                sandbox.mysql(fixture_catalog_sql(records), database)
            sandbox.mysql((ROOT / "data" / filename).read_text(encoding="utf-8"), database)
            assert_all_official_ranges(sandbox, database, records)
    print("PASS: normal catalog, attribute backfill, and supported rebuild imports also retain all official ranges")


def test_detail_merge_and_guards(sandbox):
    record = source_record()
    # Non-range attributes are deliberately present: the dedicated text file
    # must never use them to overwrite or fill numeric/product columns.
    attributes = range_attributes(record, price=9999, volume_ml=720, price_tax_included=True,
                                  price_source_url="https://example.invalid/source-price")
    sql = range_fixture_sql(record, attributes)
    with fixture_database(sandbox) as database:
        sandbox.mysql(range_fixture_sql(record, mode="catalog"), database)
        product = catalog_rows(sandbox, database)[-1]
        sake_id = product[0]
        sandbox.mysql(f"UPDATE sake SET abv = 14.7, price = 1234, image_url = '/manual.png' WHERE id = {sake_id};", database)
        before = unchanged_tables(sandbox, database)
        original = {
            "id": sake_id, "name": product[1], "introduction": "管理者が保存した紹介文",
            "prices": [{"volume": "300ml", "yen": 700}],
            "purchaseLinks": [{"label": "公式", "url": "https://example.invalid/shop", "official": True}],
            "customData": {"preserve": [1, 2, 3]},
        }
        for empty in ("missing", None, ""):
            page = original.copy()
            if empty != "missing":
                page["abvText"] = empty
            sandbox.mysql(
                "INSERT INTO sake_page_details (sake_id, detail_json) VALUES "
                f"({sake_id}, {sql_literal(json.dumps(page, ensure_ascii=False))}) "
                "ON DUPLICATE KEY UPDATE detail_json = VALUES(detail_json);", database)
            sandbox.mysql(sql, database)
            merged = sandbox.json_rows(database, f"SELECT detail_json FROM sake_page_details WHERE sake_id = {sake_id};")[0]
            assert merged == original | {"abvText": RANGE_TEXT}, "ABV-text merge changed an existing introduction, price, link, or custom field"
            assert unchanged_tables(sandbox, database) == before, "ABV-text merge changed a numeric ABV or other product data"
        retained = original | {"abvText": "管理者が確認した別の表記"}
        serialized = json.dumps(retained, ensure_ascii=False)
        sandbox.mysql(f"UPDATE sake_page_details SET detail_json = {sql_literal(serialized)} WHERE sake_id = {sake_id};", database)
        sandbox.mysql(sql, database)
        assert sandbox.mysql(f"SELECT detail_json FROM sake_page_details WHERE sake_id = {sake_id};", database).stdout.strip() == serialized, \
            "ABV-text backfill overwrote an existing nonblank ABV text"
        invalid = ["{broken", "[]", '"scalar"',
                   json.dumps(original | {"id": sake_id + 1}, ensure_ascii=False),
                   json.dumps(original | {"id": sake_id + 1, "abvText": RANGE_TEXT}, ensure_ascii=False),
                   json.dumps(original | {"id": str(sake_id)}, ensure_ascii=False),
                   json.dumps(original | {"name": "別の商品"}, ensure_ascii=False),
                   json.dumps(original | {"name": "別の商品", "abvText": RANGE_TEXT}, ensure_ascii=False),
                   json.dumps({key: value for key, value in original.items() if key != "id"}, ensure_ascii=False),
                   json.dumps({key: value for key, value in original.items() if key != "name"}, ensure_ascii=False)]
        for serialized in invalid:
            sandbox.mysql(f"UPDATE sake_page_details SET detail_json = {sql_literal(serialized)} WHERE sake_id = {sake_id};", database)
            result = sandbox.mysql(sql, database)
            assert "existing_text_or_invalid_or_mismatched_detail" in result.stdout, \
                "Invalid or mismatched detail JSON was not reported as pending"
            assert sandbox.mysql(f"SELECT detail_json FROM sake_page_details WHERE sake_id = {sake_id};", database).stdout.strip() == serialized, \
                ("Backfill changed invalid or mismatched detail JSON", serialized)
            assert unchanged_tables(sandbox, database) == before
    print("PASS: merging fills missing/null/empty text, preserves numeric and manually saved values, "
          "and leaves invalid or mismatched detail JSON untouched")


def test_classpath_fallback(sandbox):
    source_path = ROOT / "src/main/resources/data/sake-pages.json"
    original_source = source_path.read_bytes()
    page = next(page for page in json.loads(original_source) if page.get("introduction") and page.get("brewery"))
    with fixture_database(sandbox) as database:
        product = next(row for row in catalog_rows(sandbox, database) if row[0] == page["id"])
        sandbox.mysql(
            "INSERT INTO breweries (name, prefecture) VALUES (" + sql_literal(page["brewery"])
            + ", " + sql_literal(product[2]) + ");"
            f"UPDATE sake SET brewery_id = LAST_INSERT_ID() WHERE id = {product[0]};", database)
        record = source_record(name=product[1], brewery=page["brewery"], prefecture=product[2],
                               prefecture_code=builder_module().PREFECTURES.index(product[2]) + 1,
                               sake_type=product[5])
        before = unchanged_tables(sandbox, database)
        sandbox.mysql(range_fixture_sql(record, range_attributes(record)), database)
        merged = sandbox.json_rows(database, f"SELECT detail_json FROM sake_page_details WHERE sake_id = {product[0]};")[0]
        assert merged == page | {"abvText": RANGE_TEXT}, "Adding ABV text hid or changed a classpath introduction, price, or purchase link"
        assert unchanged_tables(sandbox, database) == before
        # The same low ID can belong to a newly rebuilt product. Its detail must
        # never inherit the previous seed product's introduction or purchase URLs.
        name = "範囲検証の採番し直された商品"
        sandbox.mysql(f"DELETE FROM sake_page_details WHERE sake_id = {product[0]};"
                      f"UPDATE sake SET name = {sql_literal(name)} WHERE id = {product[0]};", database)
        reassigned = record | {"name": name}
        sandbox.mysql(range_fixture_sql(reassigned, range_attributes(reassigned)), database)
        new_page = sandbox.json_rows(database, f"SELECT detail_json FROM sake_page_details WHERE sake_id = {product[0]};")[0]
        assert new_page["name"] == name and new_page["abvText"] == RANGE_TEXT
        assert not new_page.get("introduction") and not new_page.get("purchaseLinks"), \
            "A reassigned product ID inherited the old seed product's introduction or links"
        assert source_path.read_bytes() == original_source, "The classpath source file was changed"
    print("PASS: a new ABV-text detail preserves the matching classpath page and does not reuse it for a different product")


def test_identity_guards(sandbox):
    alias = "範囲検証酒 別名 純米"
    record = source_record(aliases=[alias])
    sql = range_fixture_sql(record, range_attributes(record))
    with fixture_database(sandbox) as database:
        sandbox.mysql(range_fixture_sql(record, mode="catalog"), database)
        sandbox.mysql("INSERT INTO sake (brewery_id, sake_type_id, name, region) "
                      "SELECT brewery_id, sake_type_id, " + sql_literal(alias)
                      + ", region FROM sake WHERE name = " + sql_literal(record["name"]) + " COLLATE utf8mb4_unicode_ci;", database)
        before, details = unchanged_tables(sandbox, database), detail_rows(sandbox, database)
        sandbox.mysql(sql, database)
        assert unchanged_tables(sandbox, database) == before and detail_rows(sandbox, database) == details, \
            "An ambiguous canonical/alias match applied ABV text to one of two products"
        sandbox.mysql("DELETE FROM sake WHERE name = " + sql_literal(alias) + " COLLATE utf8mb4_unicode_ci;"
                      "UPDATE sake SET region = '沖縄県' WHERE name = " + sql_literal(record["name"]) + " COLLATE utf8mb4_unicode_ci;", database)
        before, details = unchanged_tables(sandbox, database), detail_rows(sandbox, database)
        sandbox.mysql(sql, database)
        assert unchanged_tables(sandbox, database) == before and detail_rows(sandbox, database) == details, \
            "ABV text crossed a mismatched product region"
    print("PASS: ambiguous aliases and mismatched regions do not receive ABV text")


def test_lock_contention(sandbox):
    record = source_record()
    sql = range_fixture_sql(record, range_attributes(record))
    lock_expression = "GET_LOCK(@sake_catalog_lock_name, 30)"
    assert sql.count(lock_expression) == 1, "Review the lock-contention fixture after SQL lock changes"
    sql = sql.replace(lock_expression, "GET_LOCK(@sake_catalog_lock_name, 0)")
    with fixture_database(sandbox) as database:
        sandbox.mysql(range_fixture_sql(record, mode="catalog"), database)
        before, details = unchanged_tables(sandbox, database), detail_rows(sandbox, database)
        lock_name = "CONCAT('sake_catalog_', LEFT(SHA2(DATABASE(), 256), 48))"
        holder = subprocess.Popen(
            ["docker", "--host=unix:///var/run/docker.sock", "exec", "-i", sandbox.container,
             sandbox.client, "--default-character-set=utf8mb4", "--batch", "--raw", "--skip-column-names",
             "--unbuffered", "-uroot", database], stdin=subprocess.PIPE, stdout=subprocess.PIPE,
            stderr=subprocess.PIPE, text=True, encoding="utf-8", env=sandbox.environment)
        try:
            holder.stdin.write(f"SELECT GET_LOCK({lock_name}, 0);\n")
            holder.stdin.flush()
            deadline = time.monotonic() + 5
            while time.monotonic() < deadline:
                if sandbox.mysql(f"SELECT IS_USED_LOCK({lock_name}) IS NOT NULL;", database).stdout.strip() == "1":
                    break
                if holder.poll() is not None:
                    raise AssertionError("The ABV-text fixture lock holder exited prematurely")
                time.sleep(0.1)
            else:
                raise AssertionError("The ABV-text fixture lock was not acquired")
            result = sandbox.mysql(sql, database)
            assert "not_applied_lock_unavailable" in result.stdout, "ABV-text lock contention was not reported"
            assert unchanged_tables(sandbox, database) == before and detail_rows(sandbox, database) == details, \
                "ABV-text import changed permanent data while another session held the lock"
        finally:
            try:
                holder.communicate(f"DO RELEASE_LOCK({lock_name});\n", timeout=5)
            except (BrokenPipeError, subprocess.TimeoutExpired):
                holder.kill()
                holder.communicate(timeout=5)
    print("PASS: an unavailable catalog lock prevents every ABV-text change")


def test_detail_failure_rolls_back(sandbox):
    record = source_record()
    attributes = range_attributes(record, price=1650, volume_ml=720, price_tax_included=True,
                                  price_source_url="https://example.invalid/source-price")
    sql = range_fixture_sql(record, attributes, mode="attributes")
    with fixture_database(sandbox) as database:
        sandbox.mysql(range_fixture_sql(record, mode="catalog"), database)
        sake_id = catalog_rows(sandbox, database)[-1][0]
        page = {"id": sake_id, "name": record["name"], "introduction": "失敗時に保持する紹介文"}
        sandbox.mysql(f"INSERT INTO sake_page_details VALUES ({sake_id}, "
                      + sql_literal(json.dumps(page, ensure_ascii=False)) + ");"
                      "CREATE TRIGGER reject_abv_text BEFORE UPDATE ON sake_page_details FOR EACH ROW "
                      "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'abv-text-rollback-test';", database)
        before, details = unchanged_tables(sandbox, database), detail_rows(sandbox, database)
        result = sandbox.mysql(sql, database, check=False)
        assert result.returncode != 0 and "abv-text-rollback-test" in result.stderr
        assert unchanged_tables(sandbox, database) == before and detail_rows(sandbox, database) == details, \
            "A detail merge failure committed preceding attribute changes or damaged JSON"
        assert sandbox.mysql("SELECT IS_FREE_LOCK(CONCAT('sake_catalog_', LEFT(SHA2(DATABASE(), 256), 48)));",
                             database).stdout.strip() == "1", "A failed import retained its catalog lock"
    print("PASS: a detail-merge failure rolls back earlier attribute updates and releases the lock")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--image", choices=DATABASE_IMAGES, default="mariadb:10.11")
    parser.add_argument("--container", help="Existing disposable container with the catalog-test label")
    arguments = parser.parse_args()
    builder = builder_module()
    records = builder.apply_enrichment(builder.load_records(ROOT / "data/sake-catalog/regions"),
                                       ROOT / "data/sake-catalog/enrichment")
    with MysqlSandbox(arguments.container, arguments.image) as sandbox:
        print("Database version:", sandbox.mysql("SELECT VERSION();").stdout.strip())
        test_real_difference_file(sandbox, records)
        test_other_import_paths(sandbox, records)
        test_detail_merge_and_guards(sandbox)
        test_classpath_fallback(sandbox)
        test_identity_guards(sandbox)
        test_lock_contention(sandbox)
        test_detail_failure_rolls_back(sandbox)


if __name__ == "__main__":
    try:
        main()
    except subprocess.CalledProcessError as error:
        print(error.stderr.strip() if error.stderr else str(error), file=sys.stderr)
        raise SystemExit(error.returncode)
