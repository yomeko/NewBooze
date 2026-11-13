"""Validate the additive and explicit rebuild catalogs in isolated database containers.

Run from the repository root:
    python3 scripts/tests/test_sake_catalog_mysql.py
    python3 scripts/tests/test_sake_catalog_mysql.py --image mariadb:10.11

The default run starts and removes its own container without publishing a port.
An existing disposable container can be supplied with --container and its --image;
it must have the label codex.purpose=sake-catalog-test. No application database is
contacted. The explicit rebuild uses MariaDB anonymous blocks and is exercised
only with --image mariadb:10.11; MySQL still runs all additive/backfill cases.
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
DATABASE_IMAGES = {
    "mysql:8.4": ("MYSQL_ALLOW_EMPTY_PASSWORD", "mysql", "mysqladmin"),
    "mariadb:10.11": ("MARIADB_ALLOW_EMPTY_ROOT_PASSWORD", "mariadb", "mariadb-admin"),
}
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
    def __init__(self, container=None, image=IMAGE):
        self.container = container or "newbooze-catalog-test-" + uuid.uuid4().hex[:10]
        self.owned = container is None
        self.image = image
        self.empty_password_variable, self.client, self.admin_client = DATABASE_IMAGES[image]
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
                "--env", self.empty_password_variable + "=yes", self.image,
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
                "exec", self.container, self.admin_client, "--protocol=tcp", "--host=127.0.0.1", "-uroot",
                "ping", "--silent", check=False,
            )
            if ready.returncode == 0:
                return self
            time.sleep(1)
        if self.owned:
            self.docker("rm", "--force", "--volumes", self.container, check=False)
        raise RuntimeError(f"The isolated {self.image} container did not become ready within 60 seconds")

    def __exit__(self, *_):
        if self.owned:
            self.docker("rm", "--force", "--volumes", self.container, check=False)

    def mysql(self, sql, database=None, check=True):
        args = [
            "exec", "-i", self.container, self.client, "--default-character-set=utf8mb4",
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


def test_catalog_rebuild(sandbox, rebuild_sql, catalog_sql, records):
    """Reassigning IDs must clear old associations and preserve unrelated data."""
    database = sandbox.fresh_database()
    # Reproduce a previously imported catalog, including a manual product and
    # the old description/image order, even after the initial DDL is updated.
    sandbox.mysql(catalog_sql, database)
    sandbox.mysql("""
        ALTER TABLE sake MODIFY COLUMN description TEXT DEFAULT NULL AFTER price;
        INSERT INTO sake (id, sake_type_id, name, region, created_at)
          VALUES (90001, 7, '検証旧カタログ専用銘柄', '北海道', '2020-01-01 00:00:00');
        INSERT INTO users (id, name, email, password_hash, created_at) VALUES
          (901, '隔離検証利用者', 'catalog-901@example.invalid', 'test-only', '2020-01-01 00:00:00'),
          (902, '隔離検証管理者', 'catalog-902@example.invalid', 'test-only', '2020-01-01 00:00:00');
        INSERT INTO favorites (user_id, sake_id) VALUES (901, 1), (902, 90001);
        INSERT INTO sake_reviews (user_id, sake_id, published, rating, comment)
          VALUES (901, 1, TRUE, 4, '旧IDのレビュー'), (902, 90001, FALSE, 3, '手動銘柄のレビュー');
        INSERT INTO sake_tags (sake_id, tag_id, score) VALUES (90001, 1, 5);
        INSERT INTO sake_page_details (sake_id, detail_json)
          VALUES (1, '{"id":1,"name":"旧銘柄","introduction":"旧IDの紹介文"}'),
                 (90001, '{"id":90001,"name":"旧手動銘柄","introduction":"旧IDの紹介文"}')
          ON DUPLICATE KEY UPDATE detail_json = VALUES(detail_json);
        INSERT INTO breweries (id, name, prefecture) VALUES (90001, '検証用保存酒造', '北海道');
        INSERT INTO sake_types (id, name) VALUES (90001, '検証用保存酒種');
        INSERT INTO tags (id, name, category) VALUES (90001, '検証用保存タグ', '検証');
        INSERT INTO diagnosis_sessions (id, user_id, taken_at) VALUES (901, 901, '2020-01-01 00:00:00');
        INSERT INTO diagnosis_answers (session_id, question_id, choice_id) VALUES (901, 1, 1);
        INSERT INTO user_preferences (user_id, tag_id, score) VALUES (901, 90001, 8);
        INSERT INTO user_profile_images (user_id, image_data, content_type) VALUES (901, X'74657374', 'image/png');
        INSERT INTO drink_posts (id, user_id, sake_name, comment, created_at)
          VALUES (901, 901, '記録に保存された銘柄名', 'カタログ外の活動を保持', '2020-01-01 00:00:00');
        INSERT INTO drink_post_likes (user_id, post_id) VALUES (902, 901);
        INSERT INTO drink_post_reports (reporter_id, post_id, reason) VALUES (902, 901, '検証用記録');
    """, database)
    preserved_tables = (
        "users", "tags", "diagnosis_questions", "diagnosis_choices", "choice_tags",
        "diagnosis_sessions", "diagnosis_answers", "user_preferences", "user_profile_images",
        "drink_posts", "drink_post_likes", "drink_post_reports",
    )

    def preserved_snapshot():
        return {table: sandbox.mysql(f"SELECT * FROM `{table}` ORDER BY 1, 2;", database).stdout
                for table in preserved_tables}

    def master_rows(table):
        columns = "id, name, prefecture" if table == "breweries" else "id, name"
        return {row[0]: row for row in sandbox.json_rows(
            database, f"SELECT JSON_ARRAY({columns}) FROM `{table}` ORDER BY id;")}

    def schema_snapshot():
        columns = sandbox.json_rows(database, """
            SELECT JSON_ARRAY(COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_DEFAULT,
                              EXTRA, COLUMN_COMMENT, CHARACTER_SET_NAME, COLLATION_NAME)
            FROM INFORMATION_SCHEMA.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sake' ORDER BY COLUMN_NAME;
        """)
        constraints = sandbox.mysql("""
            SELECT TABLE_NAME, CONSTRAINT_NAME, COLUMN_NAME, ORDINAL_POSITION,
                   REFERENCED_TABLE_NAME, REFERENCED_COLUMN_NAME
            FROM INFORMATION_SCHEMA.KEY_COLUMN_USAGE
            WHERE TABLE_SCHEMA = DATABASE() ORDER BY TABLE_NAME, CONSTRAINT_NAME, ORDINAL_POSITION;
            SELECT TABLE_NAME, CONSTRAINT_NAME, UPDATE_RULE, DELETE_RULE
            FROM INFORMATION_SCHEMA.REFERENTIAL_CONSTRAINTS
            WHERE CONSTRAINT_SCHEMA = DATABASE() ORDER BY TABLE_NAME, CONSTRAINT_NAME;
            SELECT INDEX_NAME, NON_UNIQUE, SEQ_IN_INDEX, COLUMN_NAME, INDEX_TYPE
            FROM INFORMATION_SCHEMA.STATISTICS
            WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sake' ORDER BY INDEX_NAME, SEQ_IN_INDEX;
        """, database).stdout
        return columns, constraints

    preserved = preserved_snapshot()
    masters = {table: master_rows(table) for table in ("breweries", "sake_types")}
    schema_before = schema_snapshot()
    source_path = ROOT / "src/main/resources/data/sake-pages.json"
    classpath_source = source_path.read_bytes()
    classpath_pages = {page["id"]: page for page in json.loads(classpath_source)}
    expected = sorted(records, key=lambda record: record["_seq"])
    assert len(expected) == 2296, "Review the explicit rebuild expectation when source coverage changes"

    def check_rebuilt():
        columns = sandbox.mysql("""
            SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sake' ORDER BY ORDINAL_POSITION;
        """, database).stdout.splitlines()
        assert columns == ["id", "brewery_id", "sake_type_id", "name", "region", "abv", "price",
                           "image_url", "description", "created_at"], ("Wrong rebuilt column order", columns)
        assert schema_snapshot() == schema_before, "Rebuild changed column types, defaults, indexes, or foreign keys"
        after = catalog_rows(sandbox, database)
        assert [row[0] for row in after] == list(range(1, len(expected) + 1)), "Rebuild did not reset contiguous product IDs"
        for record, row in zip(expected, after):
            assert row[1] == record["name"] and row[2] == record["prefecture"], \
                ("Rebuild changed source order or product identity", record["name"], row)
            assert brewery_key(row[3]) == brewery_key(record["brewery"]) and row[4] == record["prefecture"], \
                ("Rebuild linked the wrong brewery", record["name"], row)
            assert row[5] == TYPE_NAMES.get(record["sake_type"], record["sake_type"]), \
                ("Rebuild linked the wrong sake type", record["name"], row)
            attributes = record.get("_attributes", {})
            assert row[6:10] == [attributes.get("abv"), attributes.get("price"), None, attributes.get("image_url")], \
                ("Rebuild did not use only verified product attributes", record["name"], row)
        assert [record["prefecture_code"] for record in expected] == sorted(
            record["prefecture_code"] for record in expected), "Rebuild source order is not prefecture order"
        assert sandbox.mysql("SELECT COUNT(*) FROM favorites; SELECT COUNT(*) FROM sake_reviews; SELECT COUNT(*) FROM sake_tags;",
                             database).stdout.splitlines() == ["0", "0", "0"], \
            "Old favorites, reviews, or tags became associated with reassigned product IDs"
        details = {row[0]: json.loads(row[1]) for row in sandbox.json_rows(
            database, "SELECT JSON_ARRAY(sake_id, detail_json) FROM sake_page_details ORDER BY sake_id;")}
        by_id = {row[0]: row for row in after}
        assert set(details) == set(by_id), "Rebuild did not create matching detail JSON for every new product"
        for sake_id, page in details.items():
            assert sake_id in by_id, ("An obsolete detail survived the rebuild", sake_id)
            row = by_id[sake_id]
            assert page["id"] == sake_id and page["name"] == row[1] \
                and brewery_key(page["brewery"]) == brewery_key(row[3]), \
                ("A detail page belongs to a different product", sake_id, page)
            assert page.get("introduction", "") != "旧IDの紹介文", "A manual old-ID introduction survived"
            if sake_id in classpath_pages:
                assert page.get("introduction", "") != classpath_pages[sake_id].get("introduction"), \
                    ("A seed introduction is now attached to a different product", sake_id)
            attributes = expected[sake_id - 1].get("_attributes", {})
            prices = ([{"volume": f"{attributes['volume_ml']}ml", "yen": attributes["price"]}]
                      if attributes.get("price") is not None else [])
            assert page.get("prices", []) == prices, ("Rebuilt price capacity differs from verified evidence", sake_id, page)
            expected_abv_text = attributes.get("abv_text") if attributes.get("abv") is None else None
            assert page.get("abvText") == expected_abv_text, ("Rebuilt ABV text differs from verified evidence", sake_id, page)
        assert_foreign_keys(sandbox, database)
        assert preserved_snapshot() == preserved, "Rebuild changed users, diagnosis data, or unrelated activity"
        for table, original_rows in masters.items():
            current = master_rows(table)
            assert all(current.get(key) == row for key, row in original_rows.items()), \
                ("Rebuild modified or deleted existing master rows", table)
        assert source_path.read_bytes() == classpath_source, "Rebuild changed the classpath detail source"
        assert sandbox.mysql("""
            SELECT COUNT(*) FROM sake WHERE created_at IS NULL
              OR created_at < UTC_TIMESTAMP() - INTERVAL 1 DAY
              OR created_at > UTC_TIMESTAMP() + INTERVAL 1 DAY;
        """, database).stdout.strip() == "0", "Rebuild did not give products a valid execution-time creation date"
        assert sandbox.mysql("SELECT AUTO_INCREMENT FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='sake';",
                             database).stdout.strip() == str(len(expected) + 1), "Rebuild did not reset the next product ID"
        return after, details

    old_catalog = sandbox.mysql("""
        SELECT JSON_ARRAY(id, brewery_id, sake_type_id, name, region, abv, price,
                          image_url, description, DATE_FORMAT(created_at, '%Y-%m-%d %H:%i:%s'))
        FROM sake ORDER BY id;
    """, database).stdout
    old_children = {table: sandbox.mysql(f"SELECT * FROM `{table}` ORDER BY 1, 2;", database).stdout
                    for table in ("favorites", "sake_reviews", "sake_tags", "sake_page_details")}
    sandbox.mysql("""
        CREATE TRIGGER reject_catalog_rebuild BEFORE INSERT ON sake FOR EACH ROW
          SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'integration-rebuild-reject';
    """, database)
    try:
        rejected = sandbox.mysql(rebuild_sql, database, check=False)
        assert rejected.returncode != 0 and "integration-rebuild-reject" in rejected.stderr, \
            ("The failure fixture did not reject reloading the catalog", rejected.stderr)
        current_catalog = sandbox.mysql("""
            SELECT JSON_ARRAY(id, brewery_id, sake_type_id, name, region, abv, price,
                              image_url, description, DATE_FORMAT(created_at, '%Y-%m-%d %H:%i:%s'))
            FROM sake ORDER BY id;
        """, database).stdout
        assert current_catalog == old_catalog, "A failed rebuild did not roll back the old catalog"
        assert {table: sandbox.mysql(f"SELECT * FROM `{table}` ORDER BY 1, 2;", database).stdout
                for table in old_children} == old_children, "A failed rebuild lost old cascade associations or details"
        assert preserved_snapshot() == preserved, "A failed rebuild changed users or unrelated activity"
        assert {table: master_rows(table) for table in masters} == masters, "A failed rebuild changed master rows"
    finally:
        sandbox.mysql("DROP TRIGGER reject_catalog_rebuild;", database)
    result = sandbox.mysql(rebuild_sql + "\nSELECT @@FOREIGN_KEY_CHECKS;\n", database)
    assert result.stdout.splitlines()[-1] == "1", "Rebuild left foreign key checks disabled in its session"
    first = check_rebuilt()
    first_masters = {table: master_rows(table) for table in masters}
    sandbox.mysql(rebuild_sql, database)
    assert check_rebuilt() == first, "A second explicit rebuild changed catalog identities, attributes, or details"
    assert {table: master_rows(table) for table in masters} == first_masters, "A second rebuild duplicated master records"
    print("PASS: explicit rebuild imports 2296 ordered products with IDs 1..2296 and the exact 10 columns; "
          "failure rolls back; old associations cleared; details, users, masters, activity, foreign keys and rerun are correct")


def test_rebuild_dependency_guards(sandbox, rebuild_sql):
    for dependency in ("unknown_local", "unknown_external", "missing_cascade"):
        database = sandbox.fresh_database()
        if dependency != "missing_cascade":
            child_database = database
            if dependency == "unknown_external":
                child_database = "catalog_test_external_" + uuid.uuid4().hex[:12]
                sandbox.mysql(f"CREATE DATABASE `{child_database}`;")
            sandbox.mysql(f"""
                CREATE TABLE `{child_database}`.unrelated_sake_activity (
                  sake_id BIGINT UNSIGNED NOT NULL PRIMARY KEY, note VARCHAR(50) NOT NULL,
                  CONSTRAINT fk_unrelated_sake_activity FOREIGN KEY (sake_id) REFERENCES `{database}`.sake(id) ON DELETE CASCADE
                ) ENGINE=InnoDB;
                INSERT INTO `{child_database}`.unrelated_sake_activity VALUES (1, '未知の活動を削除しない');
            """, database)
            guarded_rows = f"SELECT * FROM `{child_database}`.unrelated_sake_activity;"
            expected_error = "Unexpected dependent table"
        else:
            sandbox.mysql("""
                INSERT INTO users (id, name, email, password_hash)
                  VALUES (901, '隔離検証利用者', 'guard@example.invalid', 'test-only');
                INSERT INTO favorites (user_id, sake_id) VALUES (901, 1);
                ALTER TABLE favorites DROP FOREIGN KEY fk_favorite_sake;
            """, database)
            guarded_rows = "SELECT * FROM favorites;"
            expected_error = "Expected four sake CASCADE relations"
        before = seed_snapshot(sandbox, database)
        guarded_before = sandbox.mysql(guarded_rows, database).stdout
        result = sandbox.mysql(rebuild_sql, database, check=False)
        assert result.returncode != 0 and expected_error in result.stderr, \
            ("An unsafe dependency layout did not stop the rebuild", result.stderr)
        assert seed_snapshot(sandbox, database) == before, "Dependency rejection changed the old catalog or tags"
        assert sandbox.mysql(guarded_rows, database).stdout == guarded_before, \
            "Dependency rejection changed unrelated activity or old favorites"
    print("PASS: explicit rebuild rejects unknown cascade activity in the same or another database, "
          "and a missing required FK, before deleting old data")


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
        if attributes.get("abv") is None and attributes.get("abv_text"):
            assert price_details[row[0]]["abvText"] == attributes["abv_text"], \
                ("Official ABV range was not retained as text", record["name"])
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
    expected_range_ids = set()
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
        if attributes.get("abv") is None and attributes.get("abv_text"):
            expected_range_ids.add(row[0])
            assert price_details[row[0]]["abvText"] == attributes["abv_text"], \
                ("The real attributes-only file changed an official ABV range", record["name"])
    assert set(price_details) == expected_price_ids | expected_range_ids, \
        "The real attributes-only file created unexpected detail records"
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


def test_unavailable_named_lock(sandbox, attributes_only=False, rebuild_sql=None):
    database = sandbox.fresh_database()
    lock_name = "CONCAT('sake_catalog_', LEFT(SHA2(DATABASE(), 256), 48))"
    if rebuild_sql is not None:
        sql = rebuild_sql
    elif attributes_only:
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
         sandbox.client, "--default-character-set=utf8mb4", "--batch", "--raw", "--skip-column-names",
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
        result = sandbox.mysql(sql, database, check=rebuild_sql is None)
        if rebuild_sql is not None:
            assert result.returncode != 0 and "Catalog lock unavailable" in result.stderr, \
                ("Rebuild lock contention was not reported as an error", result.stderr)
        else:
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
    operation = "explicit rebuild" if rebuild_sql is not None else "attribute backfill" if attributes_only else "catalog insertion"
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
        ({"name": "検証 ガ 純米"}, {"name": "検証 ｶﾞ 純米"}, True),
        ({"name": "検証 ガ 純米"}, {"name": "検証 カ\u3099 純米"}, True),
        ({"name": "検証 ｶﾞ 純米"}, {"name": "検証 カ\u3099 純米"}, True),
        ({"name": "検証 パ 純米"}, {"name": "検証 ﾊﾟ 純米"}, True),
        ({"name": "検証 カ 純米"}, {"name": "検証 ｶﾞ 純米"}, False),
        ({"brewery": "ガ酒造株式会社"}, {"brewery": "ｶﾞ酒造株式会社"}, True),
        ({"brewery": "ガ酒造株式会社"}, {"brewery": "カ\u3099酒造株式会社"}, True),
        ({"brewery": "ｶﾞ酒造株式会社"}, {"brewery": "カ\u3099酒造株式会社"}, True),
        ({"brewery": "パ酒造株式会社"}, {"brewery": "ﾊﾟ酒造株式会社"}, True),
        ({"brewery": "カ酒造株式会社"}, {"brewery": "ｶﾞ酒造株式会社"}, False),
        ({"brewery": "(株）検証酒造"}, {"brewery": "株式会社 検証酒造"}, True),
        ({"name": "検証 神心 純米", "brewery": "神酒造株式会社"},
         {"name": "検証 神心 純米", "brewery": "神酒造株式会社"}, True),
        ({"name": "検証 神心 純米", "brewery": "神酒造株式会社"},
         {"name": "検証 神心 純米", "brewery": "神酒造株式会社"}, True),
    ]
    for existing, source, should_match in cases:
        database = sandbox.fresh_database()
        sandbox.mysql(fixture_sql(**existing), database)
        before = catalog_rows(sandbox, database)
        assert len(before) == 11 and before[-1][1] == existing.get("name", "検証酒 純米") \
            and before[-1][3] == existing.get("brewery", "検証酒造株式会社"), \
            ("The identity fixture did not insert the expected existing product and brewery", existing, before[-1])
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
    print("PASS: attribute identities distinguish voicing, kana script and accents while preserving width, "
          "case, combining marks, corporate-name variants and compatibility kanji")


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
        page = sandbox.json_rows(database, f"SELECT detail_json FROM sake_page_details WHERE sake_id = {row[0]};")[0]
        assert page["abvText"] == attributes["abv_text"], "The official ABV range was not retained for display"
    print("PASS: ABV ranges retain official display text and remain numeric NULL during insertion and backfill")


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


def test_generated_sql_portability(catalog_sql, attributes_sql, rebuild_sql):
    for name, sql in (("sake_catalog_prefecture_order.sql", catalog_sql),
                      ("sake_catalog_attributes.sql", attributes_sql),
                      ("sake_catalog_rebuild.sql", rebuild_sql)):
        assert re.search(r"\butf8mb4_[a-z0-9_]*0900[a-z0-9_]*\b", sql, re.IGNORECASE) is None, \
            f"{name} contains a MySQL-only 0900 collation unsupported by MariaDB 10.11"
    assert re.search(r"\bFOREIGN_KEY_CHECKS\s*=\s*0\b", rebuild_sql, re.IGNORECASE) is None, \
        "The rebuild disables foreign key protection while reassigning product IDs"
    print("PASS: all three generated SQL files avoid MySQL-only 0900 collations; rebuild keeps foreign key checks enabled")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--image", choices=DATABASE_IMAGES, default=IMAGE,
                        help="Database image to start or use with --container (default: %(default)s)")
    parser.add_argument("--container", help="Existing disposable container with the catalog-test label")
    args = parser.parse_args()
    catalog_sql = (ROOT / "data/sake_catalog_prefecture_order.sql").read_text(encoding="utf-8")
    attributes_sql = (ROOT / "data/sake_catalog_attributes.sql").read_text(encoding="utf-8")
    rebuild_sql = (ROOT / "data/sake_catalog_rebuild.sql").read_text(encoding="utf-8")
    builder = builder_module()
    records = builder.apply_enrichment(builder.load_records(ROOT / "data/sake-catalog/regions"), ROOT / "data/sake-catalog/enrichment")
    if not records:
        raise SystemExit("No source records exist; generate the catalog before the integration test")
    records.sort(key=lambda record: (int(record["prefecture_code"]), product_key(record["name"])))
    test_generated_sql_portability(catalog_sql, attributes_sql, rebuild_sql)
    test_attribute_source_validation()
    with MysqlSandbox(args.container, args.image) as sandbox:
        print("Database version:", sandbox.mysql("SELECT VERSION();").stdout.strip())
        if args.image.startswith("mariadb:"):
            test_catalog_rebuild(sandbox, rebuild_sql, catalog_sql, records)
            test_rebuild_dependency_guards(sandbox, rebuild_sql)
            test_unavailable_named_lock(sandbox, rebuild_sql=rebuild_sql)
        else:
            print("SKIP: explicit rebuild requires MariaDB 10.11 anonymous blocks; additive/backfill cases follow")
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
