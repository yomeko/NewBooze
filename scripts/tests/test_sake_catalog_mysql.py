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


def fixture_sql(**overrides):
    """Exercise the real generator with small independent, temporary source data."""
    record = {
        "prefecture_code": 1, "prefecture": "北海道", "brewery": "検証酒造株式会社",
        "name": "検証酒 純米", "sake_type": "純米", "checked_on": "2026-11-10",
        "source_url": "https://example.invalid/catalog-test", "source_note": "隔離DB専用の検証データ",
    }
    record.update(overrides)
    spec = importlib.util.spec_from_file_location("sake_catalog_builder", ROOT / "scripts/build-sake-catalog.py")
    builder = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(builder)
    with tempfile.TemporaryDirectory(prefix="newbooze-catalog-fixture-") as temporary:
        directory = Path(temporary)
        (directory / "fixture.json").write_text(json.dumps([record], ensure_ascii=False), encoding="utf-8")
        return builder.make_sql(builder.load_records(directory))


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
            ready = self.docker(
                "exec", self.container, "mysqladmin", "ping", "--silent", check=False,
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
    for record in additions:
        row = new_rows[product_key(record["name"])]
        assert row[2] == record["prefecture"], ("Wrong prefecture", row)
        assert brewery_key(row[3]) == brewery_key(record["brewery"]), ("Wrong brewery", row)
        assert row[4] == record["prefecture"], ("Wrong brewery prefecture", row)
        assert row[5] == TYPE_NAMES.get(record["sake_type"], record["sake_type"]), ("Wrong sake type", row)
        assert row[6:10] == [None] * 4, ("Unverified product attributes were populated", row)
    codes = {product_key(record["name"]): int(record["prefecture_code"]) for record in additions}
    inserted_codes = [codes[product_key(row[1])] for row in after if row[0] > 10]
    assert inserted_codes == sorted(inserted_codes), "New records are not in prefecture-code order"
    assert_foreign_keys(sandbox, database)
    second_before = sandbox.mysql("SELECT COUNT(*) FROM breweries; SELECT COUNT(*) FROM sake_types;", database).stdout
    sandbox.mysql(catalog_sql, database)
    assert catalog_rows(sandbox, database) == after, "A second application added or changed products"
    second_after = sandbox.mysql("SELECT COUNT(*) FROM breweries; SELECT COUNT(*) FROM sake_types;", database).stdout
    assert second_before == second_after, "A second application duplicated master data"
    assert seed_snapshot(sandbox, database) == original, "A second application changed existing records"
    print(f"PASS: {len(additions)} new products; existing 10 unchanged; rerun unchanged; order and foreign keys correct")


def test_spacing_variant(sandbox, catalog_sql, records):
    database = sandbox.fresh_database()
    existing = catalog_rows(sandbox, database)
    record = expected_new_records(records, existing)[0]
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


def test_unavailable_named_lock(sandbox):
    database = sandbox.fresh_database()
    lock_name = "CONCAT('sake_catalog_', LEFT(SHA2(DATABASE(), 256), 48))"
    sql = fixture_sql()
    wait_expression = "GET_LOCK(@sake_catalog_lock_name, 30)"
    assert sql.count(wait_expression) == 1, "Review the fixture when the catalog's lock expression changes"
    sql = sql.replace(wait_expression, "GET_LOCK(@sake_catalog_lock_name, 0)")
    counts_sql = "SELECT COUNT(*) FROM sake; SELECT COUNT(*) FROM breweries; SELECT COUNT(*) FROM sake_types;"
    before_counts = sandbox.mysql(counts_sql, database).stdout
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
        assert seed_snapshot(sandbox, database) == original, "Lock contention changed existing products or tags"
    finally:
        try:
            holder.communicate(f"SELECT RELEASE_LOCK({lock_name});\n", timeout=5)
        except (BrokenPipeError, subprocess.TimeoutExpired):
            holder.kill()
            holder.communicate(timeout=5)
    print("PASS: unavailable named lock reports not_applied and leaves all three catalog tables unchanged")


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


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--container", help="Existing disposable MySQL container with the catalog-test label")
    args = parser.parse_args()
    catalog_sql = (ROOT / "data/sake_catalog_prefecture_order.sql").read_text(encoding="utf-8")
    records = []
    for path in sorted((ROOT / "data/sake-catalog/regions").glob("*.json")):
        records.extend(json.loads(path.read_text(encoding="utf-8")))
    if not records:
        raise SystemExit("No source records exist; generate the catalog before the integration test")
    records.sort(key=lambda record: (int(record["prefecture_code"]), product_key(record["name"])))
    with MysqlSandbox(args.container) as sandbox:
        print("MySQL version:", sandbox.mysql("SELECT VERSION();").stdout.strip())
        test_catalog(sandbox, catalog_sql, records)
        test_spacing_variant(sandbox, catalog_sql, records)
        test_registered_alias(sandbox, catalog_sql, records)
        test_width_variants_and_brewery_reuse(sandbox)
        test_unavailable_named_lock(sandbox)
        test_brewery_collision(sandbox, catalog_sql, records)
        test_brewery_collision(sandbox, catalog_sql, records, ambiguous=True)


if __name__ == "__main__":
    main()
