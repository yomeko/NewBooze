"""Validate the additive sake catalog against an isolated MySQL 8.4 container.

Run from the repository root:
    python3 scripts/tests/test_sake_catalog_mysql.py

The default run starts and removes its own container without publishing a port.
An existing disposable container can be supplied with --container; it must have
the label codex.purpose=sake-catalog-test. No application database is contacted.
"""

import argparse
import importlib.util
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import time
import unicodedata
import uuid


ROOT = Path(__file__).resolve().parents[2]
IMAGE = "mysql:8.4"
LABEL = "codex.purpose=sake-catalog-test"
COMPANY_WORDS = (
    "株式会社", "有限会社", "合名会社", "合資会社", "合同会社", "(株)", "(有)", "㈱", "㈲",
)
TYPE_NAMES = {
    "純米": "純米酒", "特別純米酒": "特別純米", "純米吟醸酒": "純米吟醸",
    "純米大吟醸酒": "純米大吟醸", "吟醸酒": "吟醸", "大吟醸酒": "大吟醸",
    "本醸造酒": "本醸造", "特別本醸造酒": "特別本醸造",
}


def product_key(value):
    return re.sub(r"[\s\u3000]+", "", unicodedata.normalize("NFKC", value)).casefold()


def brewery_key(value):
    result = unicodedata.normalize("NFKC", value)
    for word in COMPANY_WORDS:
        result = result.replace(unicodedata.normalize("NFKC", word), "")
    return product_key(result)


def sql_literal(value):
    # Hex text literals also work when NO_BACKSLASH_ESCAPES is enabled.
    return "CONVERT(X'" + value.encode("utf-8").hex() + "' USING utf8mb4)"


def fixture_sql(attributes=None, attributes_only=False, **overrides):
    """Exercise the real generator with small independent, temporary source data."""
    record = {
        "prefecture_code": 1, "prefecture": "北海道", "brewery": "検証酒造株式会社",
        "name": "検証酒 純米", "sake_type": "純米", "checked_on": "2026-11-10",
        "source_url": "https://example.invalid/catalog-test", "source_note": "隔離DB専用の検証データ",
    }
    record.update(overrides)
    return fixture_catalog_sql([record], attributes, attributes_only)


def builder_module():
    spec = importlib.util.spec_from_file_location("sake_catalog_builder", ROOT / "scripts/build-sake-catalog.py")
    builder = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(builder)
    return builder


def fixture_catalog_sql(records, attributes=None, attributes_only=False):
    """Generate a temporary catalog without changing the checked-in source files."""
    builder = builder_module()
    with tempfile.TemporaryDirectory(prefix="newbooze-catalog-fixture-") as temporary:
        directory = Path(temporary)
        (directory / "fixture.json").write_text(json.dumps(records, ensure_ascii=False), encoding="utf-8")
        loaded = builder.load_records(directory)
        if attributes is not None:
            enrichment = directory / "enrichment"
            enrichment.mkdir()
            (enrichment / "fixture.json").write_text(json.dumps(attributes, ensure_ascii=False), encoding="utf-8")
            builder.apply_enrichment(loaded, enrichment)
        return builder.make_sql(loaded, attributes_only=attributes_only)


class MysqlSandbox:
    def __init__(self, container=None):
        self.container = container or "newbooze-catalog-test-" + uuid.uuid4().hex[:10]
        self.owned = container is None
        self.environment = os.environ.copy()
        for name in ("DOCKER_HOST", "DOCKER_CONTEXT", "DOCKER_TLS", "DOCKER_TLS_VERIFY", "DOCKER_CERT_PATH"):
            self.environment.pop(name, None)

    def docker(self, *arguments, input=None, check=True):
        return subprocess.run(
            ["docker", "--host=unix:///var/run/docker.sock", *arguments],
            input=input, text=True, encoding="utf-8", capture_output=True,
            check=check, env=self.environment,
        )

    def __enter__(self):
        self.docker("info", "--format", "{{.ServerVersion}}")
        if self.owned:
            self.docker(
                "run", "--detach", "--name", self.container, "--label", LABEL,
                "--env", "MYSQL_ALLOW_EMPTY_PASSWORD=yes", IMAGE,
                "--skip-log-bin", "--character-set-server=utf8mb4",
                "--collation-server=utf8mb4_unicode_ci",
            )
        label = self.docker(
            "inspect", "--format", '{{index .Config.Labels "codex.purpose"}}', self.container,
        ).stdout.strip()
        if label != "sake-catalog-test":
            raise RuntimeError("Refusing to use a container without the disposable catalog-test label")
        deadline = time.monotonic() + 60
        while time.monotonic() < deadline:
            # The image's initialization server accepts socket connections but
            # disables TCP. Wait for the final server before executing fixtures.
            ready = self.docker(
                "exec", self.container, "mysqladmin", "--protocol=tcp", "--host=127.0.0.1",
                "ping", "--silent", check=False,
            )
            if ready.returncode == 0:
                return self
            time.sleep(1)
        raise RuntimeError("The isolated MySQL container did not become ready within 60 seconds")

    def __exit__(self, *_):
        if self.owned:
            self.docker("rm", "--force", "--volumes", self.container, check=False)

    def mysql(self, sql, database=None, check=True):
        args = [
            "exec", "-i", self.container, "mysql", "--default-character-set=utf8mb4",
            "--batch", "--raw", "--skip-column-names", "-uroot",
        ]
        if database is not None:
            args.append(database)
        return self.docker(*args, input=sql, check=check)

    def fresh_database(self):
        database = "catalog_test_" + uuid.uuid4().hex[:12]
        ddl = (ROOT / "data/create_issho_no_deai.sql").read_text(encoding="utf-8")
        if ddl.count("CREATE DATABASE `issho_no_deai`") != 1 or ddl.count("USE `issho_no_deai`;") != 1:
            raise AssertionError("The fixture database declarations changed; review the integration test")
        ddl = ddl.replace("CREATE DATABASE `issho_no_deai`", f"CREATE DATABASE `{database}`")
        ddl = ddl.replace("USE `issho_no_deai`;", f"USE `{database}`;")
        self.mysql(ddl)
        return database

    def json_rows(self, database, statement):
        return [json.loads(line) for line in self.mysql(statement, database).stdout.splitlines() if line]


def catalog_rows(sandbox, database):
    return sandbox.json_rows(database, """
        SELECT JSON_ARRAY(s.id, s.name, s.region, b.name, b.prefecture, t.name,
                          s.abv, s.price, s.description, s.image_url, s.brewery_id)
        FROM sake s
        LEFT JOIN breweries b ON b.id = s.brewery_id
        JOIN sake_types t ON t.id = s.sake_type_id
        ORDER BY s.id;
    """)


def seed_snapshot(sandbox, database):
    return sandbox.mysql("""
        SELECT JSON_ARRAY(id, brewery_id, sake_type_id, name, region, abv, price,
                          description, image_url, DATE_FORMAT(created_at, '%Y-%m-%d %H:%i:%s'))
        FROM sake WHERE id <= 10 ORDER BY id;
        SELECT JSON_ARRAY(sake_id, tag_id, score) FROM sake_tags ORDER BY sake_id, tag_id;
    """, database).stdout


def expected_new_records(records, existing):
    occupied = {product_key(row[1]) for row in existing}
    additions = []
    for record in records:
        keys = {product_key(record["name"]), *(product_key(name) for name in record.get("aliases", []))}
        if occupied.intersection(keys):
            continue
        additions.append(record)
        occupied.update(keys)
    return additions


def assert_foreign_keys(sandbox, database):
    orphans = sandbox.mysql("""
        SELECT COUNT(*) FROM sake s
        LEFT JOIN sake_types t ON t.id = s.sake_type_id
        LEFT JOIN breweries b ON b.id = s.brewery_id
        WHERE t.id IS NULL OR (s.brewery_id IS NOT NULL AND b.id IS NULL);
    """, database).stdout.strip()
    assert orphans == "0", "Catalog insertion left an orphan foreign key"


def test_catalog(sandbox, catalog_sql, records):
    database = sandbox.fresh_database()
    before = catalog_rows(sandbox, database)
    original = seed_snapshot(sandbox, database)
    additions = expected_new_records(records, before)
    sandbox.mysql(catalog_sql, database)
    after = catalog_rows(sandbox, database)
    assert len(after) == len(before) + len(additions), (len(after), len(before), len(additions))
    assert seed_snapshot(sandbox, database) == original, "Existing products or diagnosis tags changed"
    new_rows = {product_key(row[1]): row for row in after if row[0] > 10}
    price_details = {page["id"]: page for page in sandbox.json_rows(database, "SELECT detail_json FROM sake_page_details;")}
    for record in additions:
        row = new_rows[product_key(record["name"])]
        assert row[2] == record["prefecture"], ("Wrong prefecture", row)
        assert brewery_key(row[3]) == brewery_key(record["brewery"]), ("Wrong brewery", row)
        assert row[4] == record["prefecture"], ("Wrong brewery prefecture", row)
        assert row[5] == TYPE_NAMES.get(record["sake_type"], record["sake_type"]), ("Wrong sake type", row)
        attributes = record.get("_attributes", {})
        assert row[6:10] == [attributes.get("abv"), attributes.get("price"), None, attributes.get("image_url")], \
            ("Verified product attributes do not match their sources", row)
        if attributes.get("price") is not None:
            assert row[0] in price_details, ("Verified price is missing capacity metadata", record["name"], row[0])
            assert price_details[row[0]]["prices"] == [{"volume": f"{attributes['volume_ml']}ml", "yen": attributes["price"]}], \
                ("Price capacity or tax-inclusive value was not recorded", row)
    codes = {product_key(record["name"]): int(record["prefecture_code"]) for record in additions}
    inserted_codes = [codes[product_key(row[1])] for row in after if row[0] > 10]
    assert inserted_codes == sorted(inserted_codes), "New records are not in prefecture-code order"
    assert_foreign_keys(sandbox, database)
    second_before = sandbox.mysql("SELECT COUNT(*) FROM breweries; SELECT COUNT(*) FROM sake_types;", database).stdout
    details_before = sandbox.mysql("SELECT sake_id, detail_json FROM sake_page_details ORDER BY sake_id;", database).stdout
    sandbox.mysql(catalog_sql, database)
    assert catalog_rows(sandbox, database) == after, "A second application added or changed products"
    second_after = sandbox.mysql("SELECT COUNT(*) FROM breweries; SELECT COUNT(*) FROM sake_types;", database).stdout
    assert second_before == second_after, "A second application duplicated master data"
    assert sandbox.mysql("SELECT sake_id, detail_json FROM sake_page_details ORDER BY sake_id;", database).stdout == details_before, \
        "A second application changed saved price details"
    assert seed_snapshot(sandbox, database) == original, "A second application changed existing records"
    print(f"PASS: {len(additions)} new products; existing 10 unchanged; rerun unchanged; order and foreign keys correct")


def test_spacing_variant(sandbox, catalog_sql, records):
    database = sandbox.fresh_database()
    existing = catalog_rows(sandbox, database)
    record = next(record for record in expected_new_records(records, existing) if len(record["name"]) <= 33)
    spaced_name = "\u3000" + " \t".join(record["name"]) + "\u3000"
    assert len(spaced_name) <= 100, "Select a shorter product for the spacing fixture"
    sandbox.mysql(
        "INSERT INTO sake (sake_type_id, name, region, description) VALUES (7, "
        + sql_literal(spaced_name) + ", " + sql_literal(record["prefecture"])
        + ", 'preserve-test-record');", database,
    )
    sandbox.mysql(catalog_sql, database)
    matching = [row for row in catalog_rows(sandbox, database) if product_key(row[1]) == product_key(record["name"])]
    assert len(matching) == 1 and matching[0][1] == spaced_name, "Spacing variants were duplicated or overwritten"
    print("PASS: existing ASCII/fullwidth-space and tab variant is preserved without duplication")


def test_catalog_upgrade(sandbox, catalog_sql, records):
    database = sandbox.fresh_database()
    initial_rows = catalog_rows(sandbox, database)
    original = seed_snapshot(sandbox, database)
    earlier_records = records[::5]
    sandbox.mysql(fixture_catalog_sql(earlier_records), database)
    earlier_rows = catalog_rows(sandbox, database)
    sandbox.mysql(catalog_sql, database)
    expanded_rows = catalog_rows(sandbox, database)
    expanded_by_id = {row[0]: row for row in expanded_rows}
    assert len(expanded_rows) == len(initial_rows) + len(expected_new_records(records, initial_rows)), \
        "Expanding an earlier catalog duplicated or missed products"
    for row in earlier_rows:
        expanded = expanded_by_id[row[0]]
        assert expanded[:6] == row[:6] and expanded[8] == row[8] and expanded[10] == row[10], \
            "Expanding the catalog changed a product identity, description, ID, or foreign key"
        for index in (6, 7, 9):
            if row[index] is not None:
                assert expanded[index] == row[index], "Expanding the catalog overwrote an existing attribute"
    assert seed_snapshot(sandbox, database) == original, "Catalog upgrade changed initial products or diagnosis tags"
    assert_foreign_keys(sandbox, database)
    print(f"PASS: catalog upgrade preserves {len(earlier_rows)} product identities and existing attribute values")


def test_catalog_attribute_upgrade(sandbox, attributes_sql, records):
    database = sandbox.fresh_database()
    initial_rows = catalog_rows(sandbox, database)
    original = seed_snapshot(sandbox, database)
    additions = expected_new_records(records, initial_rows)
    # Reloading through the public fixture path removes private enrichment fields,
    # reproducing the previously imported catalog with all added attributes NULL.
    sandbox.mysql(fixture_catalog_sql(records), database)
    before = catalog_rows(sandbox, database)
    assert len(before) == len(initial_rows) + len(additions), "The earlier catalog fixture missed products"
    initial_ids = {row[0] for row in initial_rows}
    assert all(row[6:10] == [None] * 4 for row in before if row[0] not in initial_ids), \
        "The earlier catalog fixture already contains enriched attributes"
    identity_sql = """
        SELECT JSON_ARRAY(id, brewery_id, sake_type_id, name, region, description,
                          DATE_FORMAT(created_at, '%Y-%m-%d %H:%i:%s'))
        FROM sake ORDER BY id;
    """
    master_sql = """
        SELECT JSON_ARRAY(id, name, prefecture) FROM breweries ORDER BY id;
        SELECT JSON_ARRAY(id, name) FROM sake_types ORDER BY id;
    """
    identities_before = sandbox.mysql(identity_sql, database).stdout
    masters_before = sandbox.mysql(master_sql, database).stdout
    sandbox.mysql(attributes_sql, database)
    after = catalog_rows(sandbox, database)
    assert len(after) == len(before), "The real attributes-only file inserted or deleted products"
    assert sandbox.mysql(identity_sql, database).stdout == identities_before, \
        "The real attributes-only file changed product identities or foreign keys"
    assert sandbox.mysql(master_sql, database).stdout == masters_before, \
        "The real attributes-only file changed brewery or sake-type master records"
    assert seed_snapshot(sandbox, database) == original, "The real attributes-only file changed initial products or tags"
    new_rows = {product_key(row[1]): row for row in after if row[0] not in initial_ids}
    price_details = {
        value[0]: json.loads(value[1])
        for value in sandbox.json_rows(database, "SELECT JSON_ARRAY(sake_id, detail_json) FROM sake_page_details;")
    }
    expected_price_ids = set()
    for record in additions:
        row = new_rows[product_key(record["name"])]
        attributes = record.get("_attributes", {})
        assert row[6:10] == [attributes.get("abv"), attributes.get("price"), None, attributes.get("image_url")], \
            ("The real attributes-only file does not match verified source values", record["name"], row)
        if attributes.get("price") is not None:
            expected_price_ids.add(row[0])
            assert attributes["price_tax_included"] is True, ("The source price is not verified tax-inclusive", record["name"])
            assert row[0] in price_details, ("The real attributes-only file missed price capacity metadata", record["name"])
            page = price_details[row[0]]
            assert page["id"] == row[0] and page["prices"] == [
                {"volume": f"{attributes['volume_ml']}ml", "yen": attributes["price"]}
            ], ("The real attributes-only file changed tax-inclusive price or capacity", record["name"], page)
    assert set(price_details) == expected_price_ids, "The real attributes-only file created unexpected price details"
    details_before = sandbox.mysql("SELECT sake_id, detail_json FROM sake_page_details ORDER BY sake_id;", database).stdout
    sandbox.mysql(attributes_sql, database)
    assert catalog_rows(sandbox, database) == after, "Reapplying the real attributes-only file changed products or attributes"
    assert sandbox.mysql(identity_sql, database).stdout == identities_before, "Reapplying attributes changed product foreign keys"
    assert sandbox.mysql(master_sql, database).stdout == masters_before, "Reapplying attributes changed master records"
    assert sandbox.mysql("SELECT sake_id, detail_json FROM sake_page_details ORDER BY sake_id;", database).stdout == details_before, \
        "Reapplying the real attributes-only file changed price details"
    assert seed_snapshot(sandbox, database) == original, "Reapplying attributes changed initial products or tags"
    assert_foreign_keys(sandbox, database)
    print(f"PASS: real attributes-only file enriches {len(additions)} earlier catalog products; "
          f"{len(expected_price_ids)} price capacities match; identities, masters, initial products, tags and rerun are preserved")


def test_registered_alias(sandbox, catalog_sql, records):
    database = sandbox.fresh_database()
    additions = expected_new_records(records, catalog_rows(sandbox, database))
    record = next((record for record in additions if record.get("aliases")), None)
    if record is None:
        original = seed_snapshot(sandbox, database)
        alias_sql = fixture_sql(
            prefecture_code=15, prefecture="新潟県", brewery="八海醸造株式会社",
            name="特別本醸造 八海山", sake_type="特別本醸造", aliases=["八海山 特別本醸造"],
        )
        sandbox.mysql(alias_sql, database)
        assert len(catalog_rows(sandbox, database)) == 10, "A reordered official product alias was duplicated"
        assert seed_snapshot(sandbox, database) == original, "The aliased existing product changed"
        print("PASS: a reordered product alias prevents duplication of the existing 八海山 product")
        return
    alias = record["aliases"][0]
    sandbox.mysql(
        "INSERT INTO sake (sake_type_id, name, region, description) VALUES (7, "
        + sql_literal(alias) + ", " + sql_literal(record["prefecture"])
        + ", 'preserve-alias-record');", database,
    )
    sandbox.mysql(catalog_sql, database)
    keys = {product_key(record["name"]), *(product_key(value) for value in record["aliases"])}
    matching = [row for row in catalog_rows(sandbox, database) if product_key(row[1]) in keys]
    assert len(matching) == 1 and matching[0][1] == alias, "A registered alias was duplicated or overwritten"
    print("PASS: an explicitly registered product alias prevents duplicate insertion")


def test_width_variants_and_brewery_reuse(sandbox):
    database = sandbox.fresh_database()
    existing_name = "検証 abc123 サケ 純米"
    sandbox.mysql(
        "INSERT INTO sake (sake_type_id, name, region) VALUES (7, "
        + sql_literal(existing_name) + ", '北海道');", database,
    )
    sandbox.mysql(fixture_sql(name="検証 ＡＢＣ１２３ ｻｹ 純米"), database)
    assert len(catalog_rows(sandbox, database)) == 11, "Latin-width, case, or kana-width variant was duplicated"

    for abbreviation in ("㈱", "㍿"):
        database = sandbox.fresh_database()
        brewery_name = abbreviation + "　検証酒造"
        sandbox.mysql(
            "INSERT INTO breweries (name, prefecture) VALUES (" + sql_literal(brewery_name) + ", '北海道');",
            database,
        )
        sandbox.mysql(fixture_sql(brewery="株式会社 検証酒造"), database)
        counts = sandbox.mysql("SELECT COUNT(*) FROM breweries;", database).stdout.strip()
        assert counts == "1", "A corporate-name variant created a second brewery: " + abbreviation
        added = catalog_rows(sandbox, database)[-1]
        assert added[1] == "検証酒 純米" and added[3] == brewery_name, "The existing equivalent brewery was not reused"
        assert_foreign_keys(sandbox, database)
    print("PASS: fullwidth Latin/digits, case and halfwidth kana deduplicate; corporate-name variant reuses the brewery")


def test_unavailable_named_lock(sandbox, attributes_only=False):
    database = sandbox.fresh_database()
    lock_name = "CONCAT('sake_catalog_', LEFT(SHA2(DATABASE(), 256), 48))"
    if attributes_only:
        sandbox.mysql(fixture_sql(), database)
        sql = fixture_sql(attributes=[verified_fixture_attributes()], attributes_only=True)
    else:
        sql = fixture_sql()
    wait_expression = "GET_LOCK(@sake_catalog_lock_name, 30)"
    assert sql.count(wait_expression) == 1, "Review the fixture when the catalog's lock expression changes"
    sql = sql.replace(wait_expression, "GET_LOCK(@sake_catalog_lock_name, 0)")
    counts_sql = "SELECT COUNT(*) FROM sake; SELECT COUNT(*) FROM breweries; SELECT COUNT(*) FROM sake_types; SELECT COUNT(*) FROM sake_page_details;"
    before_counts = sandbox.mysql(counts_sql, database).stdout
    before_rows = catalog_rows(sandbox, database)
    before_details = sandbox.mysql("SELECT sake_id, detail_json FROM sake_page_details ORDER BY sake_id;", database).stdout
    original = seed_snapshot(sandbox, database)
    holder = subprocess.Popen(
        ["docker", "--host=unix:///var/run/docker.sock", "exec", "-i", sandbox.container,
         "mysql", "--default-character-set=utf8mb4", "--batch", "--raw", "--skip-column-names",
         "--unbuffered", "-uroot", database],
        stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
        text=True, encoding="utf-8", env=sandbox.environment,
    )
    try:
        holder.stdin.write(f"SELECT GET_LOCK({lock_name}, 0);\n")
        holder.stdin.flush()
        deadline = time.monotonic() + 5
        while time.monotonic() < deadline:
            acquired = sandbox.mysql(f"SELECT IS_USED_LOCK({lock_name}) IS NOT NULL;", database).stdout.strip()
            if acquired == "1":
                break
            if holder.poll() is not None:
                raise AssertionError("The separate MySQL lock holder exited before acquiring the lock")
            time.sleep(0.1)
        else:
            raise AssertionError("The separate MySQL session did not acquire the named lock")
        result = sandbox.mysql(sql, database)
        assert "not_applied_lock_unavailable" in result.stdout, "Lock contention was not reported"
        assert sandbox.mysql(counts_sql, database).stdout == before_counts, "Lock contention changed catalog/master counts"
        assert catalog_rows(sandbox, database) == before_rows, "Lock contention changed existing attributes"
        assert sandbox.mysql("SELECT sake_id, detail_json FROM sake_page_details ORDER BY sake_id;", database).stdout == before_details, \
            "Lock contention inserted or changed price details"
        assert seed_snapshot(sandbox, database) == original, "Lock contention changed existing products or tags"
    finally:
        try:
            holder.communicate(f"SELECT RELEASE_LOCK({lock_name});\n", timeout=5)
        except (BrokenPipeError, subprocess.TimeoutExpired):
            holder.kill()
            holder.communicate(timeout=5)
    operation = "attribute backfill" if attributes_only else "catalog insertion"
    print(f"PASS: unavailable named lock blocks {operation} and preserves products, masters and price details")


def test_brewery_collision(sandbox, catalog_sql, records, ambiguous=False):
    database = sandbox.fresh_database()
    original = seed_snapshot(sandbox, database)
    record = expected_new_records(records, catalog_rows(sandbox, database))[0]
    wrong_prefecture = "沖縄県" if record["prefecture"] != "沖縄県" else "北海道"
    name = record["brewery"]
    sandbox.mysql(
        "INSERT INTO breweries (name, prefecture) VALUES (" + sql_literal(name)
        + ", " + sql_literal(record["prefecture"] if ambiguous else wrong_prefecture) + ");", database,
    )
    if ambiguous:
        sandbox.mysql(
            "INSERT INTO breweries (name, prefecture) VALUES (" + sql_literal("株式会社 " + name)
            + ", " + sql_literal(record["prefecture"]) + ");", database,
        )
    result = sandbox.mysql(catalog_sql, database)
    assert "completed" in result.stdout, "The catalog did not complete when holding a conflicted brewery"
    assert "brewery_conflict_or_lock_unavailable" in result.stdout and record["name"] in result.stdout, \
        "The conflicted product was not reported as pending"
    matching = [row for row in catalog_rows(sandbox, database) if product_key(row[1]) == product_key(record["name"])]
    assert not matching, "A conflicted or ambiguous brewery was linked to a new product"
    assert seed_snapshot(sandbox, database) == original, "Brewery conflict changed existing products or tags"
    assert_foreign_keys(sandbox, database)
    reason = "ambiguous normalized brewery" if ambiguous else "existing brewery in a different prefecture"
    print(f"PASS: {reason} is reported as pending; existing products and tags are preserved")


def verified_fixture_attributes():
    return {
        "name": "検証酒 純米", "prefecture_code": 1, "brewery": "検証酒造株式会社",
        "abv": 15.5, "abv_text": "15.5%", "abv_source_url": "https://example.invalid/product",
        "price": 1650, "volume_ml": 720, "price_tax_included": True,
        "price_source_url": "https://example.invalid/product", "image_url": "https://example.invalid/bottle.jpg",
        "image_source_url": "https://example.invalid/product", "image_http_status": 200,
        "image_content_type": "image/jpeg", "checked_on": "2026-11-11", "notes": "隔離DBの検証用出典",
    }


def test_attribute_backfill(sandbox):
    attributes = verified_fixture_attributes()
    update_sql = fixture_sql(attributes=[attributes], attributes_only=True)
    database = sandbox.fresh_database()
    original = seed_snapshot(sandbox, database)
    empty_counts = sandbox.mysql("SELECT COUNT(*) FROM sake; SELECT COUNT(*) FROM breweries; SELECT COUNT(*) FROM sake_types;", database).stdout
    sandbox.mysql(update_sql, database)
    assert sandbox.mysql("SELECT COUNT(*) FROM sake; SELECT COUNT(*) FROM breweries; SELECT COUNT(*) FROM sake_types;", database).stdout == empty_counts, \
        "The attributes-only file inserted catalog or master records"
    sandbox.mysql(fixture_sql(), database)
    sandbox.mysql("UPDATE sake SET image_url = '' WHERE name = '検証酒 純米';", database)
    before = catalog_rows(sandbox, database)[-1]
    sandbox.mysql(update_sql, database)
    after = catalog_rows(sandbox, database)[-1]
    assert before[0] == after[0] and after[6:10] == [15.5, 1650, None, attributes["image_url"]], \
        "Verified attributes did not fill the existing blank fields"
    page = sandbox.json_rows(database, f"SELECT detail_json FROM sake_page_details WHERE sake_id = {after[0]};")[0]
    assert page["prices"] == [{"volume": "720ml", "yen": 1650}] and page["id"] == after[0]
    manual_page = json.dumps({"id": after[0], "name": after[1], "introduction": "管理者の編集", "prices": [{"volume": "300ml", "yen": 700}]}, ensure_ascii=False)
    sandbox.mysql(
        f"UPDATE sake SET abv = 16.7, price = 2500, image_url = '/images/sake/manual.jpg' WHERE id = {after[0]};"
        f"UPDATE sake_page_details SET detail_json = {sql_literal(manual_page)} WHERE sake_id = {after[0]};", database,
    )
    sandbox.mysql(update_sql, database)
    manual = catalog_rows(sandbox, database)[-1]
    assert manual[6:10] == [16.7, 2500, None, "/images/sake/manual.jpg"], "Backfill overwrote existing nonblank attributes"
    assert sandbox.mysql(f"SELECT detail_json FROM sake_page_details WHERE sake_id = {after[0]};", database).stdout.strip() == manual_page, \
        "Backfill overwrote a manually saved detail page"
    sandbox.mysql(f"UPDATE sake SET abv = NULL, price = NULL, image_url = NULL, region = '沖縄県' WHERE id = {after[0]};", database)
    sandbox.mysql(update_sql, database)
    assert catalog_rows(sandbox, database)[-1][6:10] == [None] * 4, "Attributes crossed a mismatched product region"
    assert seed_snapshot(sandbox, database) == original, "Attribute backfill changed initial products or tags"
    assert_foreign_keys(sandbox, database)
    print("PASS: attributes-only import fills blanks with capacity metadata, preserves saved values/details, and rejects identity mismatches")


def test_attribute_identity_variants(sandbox):
    cases = [
        ({"name": "検証 にこり 純米"}, {"name": "検証 にごり 純米"}, False),
        ({"name": "検証 さけ 純米"}, {"name": "検証 サケ 純米"}, False),
        ({"name": "検証 E 純米"}, {"name": "検証 É 純米"}, False),
        ({"brewery": "にこり酒造株式会社"}, {"brewery": "にごり酒造株式会社"}, False),
        ({"brewery": "さけ酒造株式会社"}, {"brewery": "サケ酒造株式会社"}, False),
        ({"brewery": "E酒造株式会社"}, {"brewery": "É酒造株式会社"}, False),
        ({"name": "検証 abc123 サケ 純米", "brewery": "株式会社 検証 abc123 サケ 酒造"},
         {"name": "検証 ＡＢＣ１２３ ｻｹ 純米", "brewery": "㈱ 検証 ＡＢＣ１２３ ｻｹ 酒造"}, True),
        ({"name": "検証 神心 純米", "brewery": "神酒造株式会社"},
         {"name": "検証 神心 純米", "brewery": "神酒造株式会社"}, True),
    ]
    for existing, source, should_match in cases:
        database = sandbox.fresh_database()
        sandbox.mysql(fixture_sql(**existing), database)
        before = catalog_rows(sandbox, database)
        attributes = {**verified_fixture_attributes(), **source}
        sandbox.mysql(fixture_sql(attributes=[attributes], attributes_only=True, **source), database)
        after = catalog_rows(sandbox, database)
        if should_match:
            assert after[-1][0] == before[-1][0] and after[-1][6:10] == [15.5, 1650, None, attributes["image_url"]], \
                ("Width, case, compatibility-kanji or corporate-name variants failed to match", existing, source, after[-1])
            assert sandbox.mysql("SELECT COUNT(*) FROM sake_page_details;", database).stdout.strip() == "1"
        else:
            assert after == before, ("Attributes crossed a different product or brewery name", existing, source)
            assert sandbox.mysql("SELECT COUNT(*) FROM sake_page_details;", database).stdout.strip() == "0", \
                ("Price details crossed a different product or brewery name", existing, source)
    print("PASS: attribute identities distinguish voicing, kana script and accents while preserving width, case and compatibility kanji")


def test_attribute_ambiguous_alias(sandbox):
    database = sandbox.fresh_database()
    alias = "検証酒 手仕込み 純米"
    sandbox.mysql(fixture_sql(aliases=[alias]), database)
    sandbox.mysql(
        "INSERT INTO sake (brewery_id, sake_type_id, name, region) "
        "SELECT brewery_id, sake_type_id, " + sql_literal(alias)
        + ", region FROM sake WHERE name = '検証酒 純米';", database,
    )
    before = catalog_rows(sandbox, database)
    result = sandbox.mysql(fixture_sql(attributes=[verified_fixture_attributes()], attributes_only=True, aliases=[alias]), database)
    assert catalog_rows(sandbox, database) == before, "An ambiguous canonical/alias match changed existing attributes"
    assert sandbox.mysql("SELECT COUNT(*) FROM sake_page_details;", database).stdout.strip() == "0", \
        "An ambiguous canonical/alias match created price details"
    assert "product_identity_not_unique_or_mismatched" in result.stdout, "An ambiguous alias was not reported"
    print("PASS: canonical and alias names matching two existing products leave both products unchanged")


def test_attribute_abv_range(sandbox):
    attributes = {**verified_fixture_attributes(), "abv": None, "abv_text": "15度以上16度未満"}
    for attributes_only in (False, True):
        database = sandbox.fresh_database()
        if attributes_only:
            sandbox.mysql(fixture_sql(), database)
        sandbox.mysql(fixture_sql(attributes=[attributes], attributes_only=attributes_only), database)
        row = catalog_rows(sandbox, database)[-1]
        assert row[6:10] == [None, 1650, None, attributes["image_url"]], \
            ("An ABV range became a numeric value or blocked other verified attributes", row)
    print("PASS: ABV ranges remain numeric NULL during insertion and backfill while other verified attributes are imported")


def test_attribute_classpath_detail_preserved(sandbox):
    source_path = ROOT / "src/main/resources/data/sake-pages.json"
    original_source = source_path.read_bytes()
    page = next(page for page in json.loads(original_source) if page.get("introduction"))
    database = sandbox.fresh_database()
    row = next(row for row in catalog_rows(sandbox, database) if row[0] == page["id"])
    brewery = page["brewery"]
    prefecture = row[2]
    sandbox.mysql(
        "INSERT INTO breweries (name, prefecture) VALUES (" + sql_literal(brewery)
        + ", " + sql_literal(prefecture) + ");"
        f"UPDATE sake SET brewery_id = LAST_INSERT_ID(), price = NULL WHERE id = {page['id']};", database,
    )
    attributes = {**verified_fixture_attributes(), "name": row[1], "brewery": brewery,
                  "prefecture_code": builder_module().PREFECTURES.index(prefecture) + 1,
                  "abv": None, "abv_text": None, "image_url": None}
    before = catalog_rows(sandbox, database)
    sandbox.mysql(fixture_sql(
        attributes=[attributes], attributes_only=True, name=row[1], brewery=brewery,
        prefecture=prefecture, prefecture_code=attributes["prefecture_code"], sake_type=row[5],
    ), database)
    expected = [row.copy() for row in before]
    next(row for row in expected if row[0] == page["id"])[7] = attributes["price"]
    assert catalog_rows(sandbox, database) == expected, "The classpath product's blank price was not filled independently"
    assert sandbox.mysql(f"SELECT COUNT(*) FROM sake_page_details WHERE sake_id = {page['id']};", database).stdout.strip() == "0", \
        "A minimal price detail shadowed an existing classpath introduction"
    assert source_path.read_bytes() == original_source, "The existing classpath detail was changed"
    print("PASS: filling a classpath product's blank price preserves its existing introduction fallback")


def test_attribute_source_validation():
    valid = verified_fixture_attributes()
    invalid = [
        {"abv": "15-16"}, {"abv": 15.55}, {"abv_text": "15度以上16度未満"},
        {"price": 0}, {"volume_ml": None},
        {"price_tax_included": False}, {"price_source_url": None},
        {"image_url": "http://example.invalid/bottle.jpg"}, {"image_http_status": 404},
        {"image_content_type": "text/html"}, {"brewery": "別の酒造"}, {"prefecture_code": 2},
    ]
    for changes in invalid:
        try:
            fixture_sql(attributes=[{**valid, **changes}])
        except (ValueError, TypeError):
            continue
        raise AssertionError(f"Invalid attribute evidence was accepted: {changes}")
    try:
        fixture_sql(attributes=[valid, valid])
    except ValueError:
        pass
    else:
        raise AssertionError("Duplicate attribute sources were accepted")
    fixture_sql(attributes=[{**valid, "abv": None, "abv_text": "15度以上16度未満"}])
    print("PASS: attribute sources require exact numeric ABV, tax-inclusive capacity prices and verified HTTPS images; ranges remain text")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--container", help="Existing disposable MySQL container with the catalog-test label")
    args = parser.parse_args()
    catalog_sql = (ROOT / "data/sake_catalog_prefecture_order.sql").read_text(encoding="utf-8")
    attributes_sql = (ROOT / "data/sake_catalog_attributes.sql").read_text(encoding="utf-8")
    builder = builder_module()
    records = builder.apply_enrichment(builder.load_records(ROOT / "data/sake-catalog/regions"), ROOT / "data/sake-catalog/enrichment")
    if not records:
        raise SystemExit("No source records exist; generate the catalog before the integration test")
    records.sort(key=lambda record: (int(record["prefecture_code"]), product_key(record["name"])))
    test_attribute_source_validation()
    with MysqlSandbox(args.container) as sandbox:
        print("MySQL version:", sandbox.mysql("SELECT VERSION();").stdout.strip())
        test_catalog(sandbox, catalog_sql, records)
        test_catalog_upgrade(sandbox, catalog_sql, records)
        test_catalog_attribute_upgrade(sandbox, attributes_sql, records)
        test_spacing_variant(sandbox, catalog_sql, records)
        test_registered_alias(sandbox, catalog_sql, records)
        test_width_variants_and_brewery_reuse(sandbox)
        test_unavailable_named_lock(sandbox)
        test_unavailable_named_lock(sandbox, attributes_only=True)
        test_brewery_collision(sandbox, catalog_sql, records)
        test_brewery_collision(sandbox, catalog_sql, records, ambiguous=True)
        test_attribute_backfill(sandbox)
        test_attribute_identity_variants(sandbox)
        test_attribute_ambiguous_alias(sandbox)
        test_attribute_abv_range(sandbox)
        test_attribute_classpath_detail_preserved(sandbox)


if __name__ == "__main__":
    try:
        main()
    except subprocess.CalledProcessError as error:
        print(error.stderr.strip() if error.stderr else str(error), file=sys.stderr)
        raise SystemExit(error.returncode)
