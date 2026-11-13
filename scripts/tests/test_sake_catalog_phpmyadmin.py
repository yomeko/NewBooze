"""Import the rebuild and ABV-text update through real phpMyAdmin.

Run from the repository root (Docker and Python's standard library only):
    python3 scripts/tests/test_sake_catalog_phpmyadmin.py
    python3 scripts/tests/test_sake_catalog_phpmyadmin.py --container disposable-mariadb

The optional existing container must carry codex.purpose=sake-catalog-test.
Only a new, randomly named fixture database is used. phpMyAdmin is published on
an ephemeral loopback port; no application database or user credentials are used.
"""

import argparse
from html.parser import HTMLParser
from http.cookiejar import CookieJar
import json
from pathlib import Path
import subprocess
import sys
import time
from urllib.error import URLError
from urllib.parse import parse_qs, urlencode, urljoin, urlsplit
from urllib.request import build_opener, HTTPCookieProcessor, ProxyHandler, Request
import uuid

from test_sake_catalog_mysql import LABEL, MysqlSandbox, builder_module, sql_literal


ROOT = Path(__file__).resolve().parents[2]
PMA_IMAGE = "phpmyadmin:5.2.3-fpm-alpine"


class ImportForm(HTMLParser):
    """Read phpMyAdmin's own import action, hidden fields, and CSRF token."""

    def __init__(self, html):
        super().__init__()
        self.action = None
        self.fields = {}
        self.active = False
        self.feed(html)
        if not self.action or not self.fields.get("token"):
            raise AssertionError("phpMyAdmin's SQL file import form was not found")

    def handle_starttag(self, tag, attributes):
        attrs = dict(attributes)
        if tag == "form":
            action = attrs.get("action", "")
            self.active = parse_qs(urlsplit(action).query).get("route") == ["/import"]
            if self.active:
                self.action = action
        elif tag == "input" and self.active and attrs.get("name"):
            if attrs.get("type") not in ("checkbox", "file", "submit"):
                self.fields[attrs["name"]] = attrs.get("value", "")

    def handle_endtag(self, tag):
        if tag == "form":
            self.active = False


class QueryResults(HTMLParser):
    """Read displayed result cells, excluding SQL source and hidden form values."""

    def __init__(self, html):
        super().__init__()
        self.tables = []
        self.depth = 0
        self.row = None
        self.cell = None
        self.feed(html)

    def handle_starttag(self, tag, attributes):
        if tag == "table":
            if self.depth:
                self.depth += 1
            elif "table_results" in dict(attributes).get("class", "").split():
                self.tables.append([])
                self.depth = 1
        elif self.depth and tag == "tr":
            self.row = []
        elif self.depth and tag in ("th", "td"):
            self.cell = []

    def handle_data(self, value):
        if self.cell is not None:
            self.cell.append(value)

    def handle_endtag(self, tag):
        if self.depth and tag in ("th", "td") and self.cell is not None:
            self.row.append(" ".join("".join(self.cell).split()))
            self.cell = None
        elif self.depth and tag == "tr" and self.row is not None:
            self.tables[-1].append(self.row)
            self.row = None
        elif self.depth and tag == "table":
            self.depth -= 1

    def rows(self, columns):
        for table in self.tables:
            # phpMyAdmin adds a final empty header for its grid controls.
            if table and table[0][:len(columns)] == columns and not any(table[0][len(columns):]):
                return table[1:]
        raise AssertionError("phpMyAdmin did not display result columns " + repr(columns))


class PhpMyAdminSandbox:
    def __init__(self, database):
        self.database = database
        suffix = uuid.uuid4().hex[:10]
        self.network = "newbooze-pma-test-" + suffix
        self.container = "newbooze-pma-test-" + suffix
        self.network_created = False
        self.database_connected = False
        self.container_started = False
        self.http = build_opener(ProxyHandler({}), HTTPCookieProcessor(CookieJar()))

    def __enter__(self):
        docker = self.database.docker
        try:
            docker("network", "create", "--label", LABEL, self.network)
            self.network_created = True
            docker("network", "connect", self.network, self.database.container)
            self.database_connected = True
            # The official entrypoint creates config.secret.inc.php for php-fpm.
            # Its configuration test initializes that file before the CLI server.
            self.container_started = True
            docker(
                "run", "--detach", "--name", self.container, "--label", LABEL,
                "--network", self.network, "--publish", "127.0.0.1::8080",
                "--env", "PMA_HOST=" + self.database.container,
                "--env", "PMA_USER=root", "--env", "PMA_PASSWORD=",
                "--env", "UPLOAD_LIMIT=32M", PMA_IMAGE,
                "sh", "-c", "/docker-entrypoint.sh php-fpm -t && exec php -S 0.0.0.0:8080 -t /var/www/html",
            )
            port = json.loads(docker("inspect", "--format", "{{json .NetworkSettings.Ports}}",
                                     self.container).stdout)["8080/tcp"][0]["HostPort"]
            self.base_url = "http://127.0.0.1:" + port + "/"
            deadline = time.monotonic() + 60
            while time.monotonic() < deadline:
                try:
                    page = self.get("index.php")
                    if "phpMyAdmin 5.2.3" in page:
                        return self
                except (URLError, ConnectionError, TimeoutError):
                    pass
                time.sleep(1)
            raise RuntimeError("The isolated phpMyAdmin 5.2.3 server did not become ready")
        except BaseException:
            self.__exit__()
            raise

    def __exit__(self, *_):
        docker = self.database.docker
        if self.container_started:
            docker("rm", "--force", "--volumes", self.container, check=False)
        if self.database_connected:
            docker("network", "disconnect", self.network, self.database.container, check=False)
        if self.network_created:
            docker("network", "rm", self.network, check=False)

    def get(self, path):
        with self.http.open(urljoin(self.base_url, path), timeout=15) as response:
            return response.read().decode("utf-8")

    def import_sql(self, database, sql):
        form = ImportForm(self.get("index.php?" + urlencode({
            "route": "/database/import", "db": database, "server": 1,
        })))
        assert form.fields.get("db") == database, "The import form selected a different database"
        fields = {**form.fields, "fk_checks": "1", "charset_of_file": "utf-8", "format": "sql",
                  "sql_compatibility": "NONE", "sql_no_auto_value_on_zero": "something"}
        # Omit allow_interrupt: stopping midway would split the transaction.
        boundary = "newbooze-import-" + uuid.uuid4().hex
        parts = []
        for name, value in fields.items():
            parts.append((f'--{boundary}\r\nContent-Disposition: form-data; name="{name}"\r\n\r\n'
                          f"{value}\r\n").encode("utf-8"))
        parts.append((f'--{boundary}\r\nContent-Disposition: form-data; name="import_file"; '
                      'filename="rebuild.sql"\r\nContent-Type: application/sql\r\n\r\n').encode("ascii"))
        parts.extend([sql.encode("utf-8"), f"\r\n--{boundary}--\r\n".encode("ascii")])
        request = Request(urljoin(self.base_url, form.action), data=b"".join(parts), headers={
            "Content-Type": "multipart/form-data; boundary=" + boundary,
        })
        with self.http.open(request, timeout=180) as response:
            html = response.read().decode("utf-8")
        assert "#2014" not in html and "Commands out of sync" not in html, \
            "phpMyAdmin could not execute its post-import SET FOREIGN_KEY_CHECKS = ON"
        return html


def test_rebuild_import(database, phpmyadmin, rebuild_sql):
    fixture = database.fresh_database()
    try:
        database.mysql("""
            INSERT INTO users (id, name, email, password_hash)
              VALUES (901, 'phpMyAdmin隔離検証', 'pma-test@example.invalid', 'test-only');
            INSERT INTO favorites (user_id, sake_id) VALUES (901, 1);
            INSERT INTO sake_reviews (user_id, sake_id, rating, comment)
              VALUES (901, 1, 4, '失敗時に保持するレビュー');
            INSERT INTO sake_page_details (sake_id, detail_json)
              VALUES (1, '{"id":1,"name":"保持する旧銘柄"}');
            CREATE TRIGGER reject_pma_rebuild BEFORE INSERT ON sake FOR EACH ROW
              SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'phpmyadmin-rebuild-reject';
        """, fixture)
        tables = ("sake", "favorites", "sake_reviews", "sake_tags", "sake_page_details",
                  "users", "breweries", "sake_types")

        def snapshot():
            return {table: database.mysql(f"SELECT * FROM `{table}` ORDER BY 1, 2;", fixture).stdout
                    for table in tables}

        original = snapshot()
        original_count = database.mysql("SELECT COUNT(*) FROM sake;", fixture).stdout.strip()
        rejected = phpmyadmin.import_sql(fixture, rebuild_sql)
        assert "phpmyadmin-rebuild-reject" in rejected, "phpMyAdmin hid the expected rebuild failure"
        assert snapshot() == original, "A failed phpMyAdmin import lost old products, children, or masters"
        database.mysql("DROP TRIGGER reject_pma_rebuild;", fixture)
        print("PASS: phpMyAdmin reports the insert failure and preserves old products and associated rows")

        # Both probes execute on the very same PHP/MySQL connection as the block.
        # The second SELECT must work after the first result has been displayed.
        probes = """
            SELECT @@FOREIGN_KEY_CHECKS AS import_fk_checks;
            SELECT 'pma_same_connection_followup_ok' AS import_followup_probe;
        """
        imported = phpmyadmin.import_sql(fixture, rebuild_sql + probes)
        assert "<h1>Error</h1>" not in imported, "phpMyAdmin reported an import SQL error"
        results = QueryResults(imported)
        assert results.rows(["status", "target_database", "removed_products", "inserted_products",
                             "first_id", "last_id"]) == [["rebuilt", fixture, original_count, "2296", "1", "2296"]]
        assert results.rows(["import_fk_checks"]) == [["1"]], "Import disabled foreign key checks"
        assert results.rows(["import_followup_probe"]) == [["pma_same_connection_followup_ok"]], \
            "phpMyAdmin did not execute the subsequent SELECT"
        rows = database.mysql("""
            SELECT COUNT(*), MIN(id), MAX(id) FROM sake;
            SELECT COUNT(*) FROM sake_page_details;
            SELECT COUNT(*) FROM favorites;
            SELECT COUNT(*) FROM sake_reviews;
            SELECT COUNT(*) FROM sake_tags;
        """, fixture).stdout.splitlines()
        assert rows == ["2296\t1\t2296", "2296", "0", "0", "0"], rows
        before_verification = snapshot()
        verified_html = phpmyadmin.import_sql(
            fixture, (ROOT / "data/sake_catalog_verify.sql").read_text(encoding="utf-8"))
        assert "<h1>Error</h1>" not in verified_html, "phpMyAdmin reported a verification SQL error"
        verified = QueryResults(verified_html)
        assert verified.rows(["total_rows", "first_id", "last_id"]) == [["2296", "1", "2296"]]
        assert verified.rows(["abv_rows", "price_rows", "image_url_rows"]) == [["1521", "919", "1914"]]
        assert verified.rows(["detail_rows"]) == [["2296"]]
        assert snapshot() == before_verification, "The read-only verification file changed fixture data"
        print("PASS: phpMyAdmin 5.2.3 imports 2296 products/details with FK checks enabled, "
              "runs its automatic post-import query, and displays subsequent verification SELECTs")
    finally:
        database.mysql(f"DROP DATABASE `{fixture}`;", check=False)


def test_abv_text_upgrade(database, phpmyadmin, rebuild_sql):
    """Upgrade the already rebuilt 2296-product catalog without reinitializing it."""
    fixture = database.fresh_database()
    try:
        database.mysql(rebuild_sql, fixture)
        # Removing only the optional new field reproduces the previous JSON
        # format even when this regression runs against a future rebuild file.
        database.mysql("""
            UPDATE sake_page_details
            SET detail_json = JSON_REMOVE(detail_json, '$.abvText');
        """, fixture)
        builder = builder_module()
        records = builder.apply_enrichment(builder.load_records(ROOT / "data/sake-catalog/regions"),
                                           ROOT / "data/sake-catalog/enrichment")
        expected = {record["name"]: record["_attributes"]["abv_text"] for record in records
                    if record.get("_attributes", {}).get("abv") is None
                    and record.get("_attributes", {}).get("abv_text")}
        assert len(expected) == 196, "Review the official ABV-text expectation when source coverage changes"

        def detail_pages():
            return {row[0]: (row[1], json.loads(row[2])) for row in database.json_rows(fixture, """
                SELECT JSON_ARRAY(s.id, s.name, d.detail_json)
                FROM sake s JOIN sake_page_details d ON d.sake_id = s.id ORDER BY s.id;
            """)}

        selected_id, (_, selected_page) = next(
            (sake_id, saved) for sake_id, saved in detail_pages().items() if saved[0] in expected)
        selected_page.update({
            "introduction": "既存の紹介文を保持するHTTP差分検証",
            "purchaseLinks": [{"label": "既存の購入先", "url": "https://example.invalid/shop", "official": True}],
            "preservedCustomField": {"nested": ["original", 1, True]},
        })
        database.mysql(
            "UPDATE sake_page_details SET detail_json = " + sql_literal(json.dumps(selected_page, ensure_ascii=False))
            + f" WHERE sake_id = {selected_id};", fixture)
        database.mysql(f"""
            INSERT INTO users (id, name, email, password_hash)
              VALUES (901, '範囲差分HTTP検証', 'pma-abv-test@example.invalid', 'test-only');
            INSERT INTO favorites (user_id, sake_id) VALUES (901, {selected_id});
            INSERT INTO sake_reviews (user_id, sake_id, rating, comment)
              VALUES (901, {selected_id}, 4, '再構築せずに保持するレビュー');
            INSERT INTO sake_tags (sake_id, tag_id, score) VALUES ({selected_id}, 1, 3);
            CREATE TABLE pma_abv_followup_probe (
              id INT NOT NULL AUTO_INCREMENT PRIMARY KEY,
              fk_checks INT NOT NULL, marker VARCHAR(80) NOT NULL
            ) ENGINE=InnoDB;
        """, fixture)
        tables = ("sake", "breweries", "sake_types", "users", "favorites", "sake_reviews", "sake_tags", "tags")

        def snapshot():
            return {table: database.mysql(f"SELECT * FROM `{table}` ORDER BY 1, 2;", fixture).stdout
                    for table in tables}

        def raw_details():
            return database.mysql("SELECT sake_id, detail_json FROM sake_page_details ORDER BY sake_id;", fixture).stdout

        original, original_pages = snapshot(), detail_pages()
        original_schema = database.mysql("SHOW CREATE TABLE sake;", fixture).stdout
        assert len(original_pages) == 2296 and all("abvText" not in page for _, page in original_pages.values())
        update_sql = (ROOT / "data/sake_catalog_abv_text.sql").read_text(encoding="utf-8")
        # phpMyAdmin summarizes long imports rather than rendering every result
        # table. A fixture-only marker proves its connection can still execute
        # a SELECT-backed write after all statements in the real update file.
        probes = """
            INSERT INTO pma_abv_followup_probe (fk_checks, marker)
              SELECT @@FOREIGN_KEY_CHECKS, 'pma_abv_update_followup_ok';
        """
        imported = phpmyadmin.import_sql(fixture, update_sql + probes)
        assert "<h1>Error</h1>" not in imported, "phpMyAdmin reported an ABV-text import error"
        assert "Import has been successfully finished" in imported, "phpMyAdmin did not finish the ABV-text import"
        probe_rows = database.mysql("SELECT fk_checks, marker FROM pma_abv_followup_probe ORDER BY id;", fixture).stdout.splitlines()
        assert probe_rows == ["1\tpma_abv_update_followup_ok"], \
            "phpMyAdmin could not continue the same connection with FK checks enabled after the ABV-text update"
        updated_pages = detail_pages()
        assert updated_pages.keys() == original_pages.keys(), "ABV-text update added or removed product details"
        actual = {}
        for sake_id, (name, page) in updated_pages.items():
            old_name, old_page = original_pages[sake_id]
            assert name == old_name
            if name in expected:
                assert page == old_page | {"abvText": expected[name]}, \
                    ("ABV-text import changed existing JSON fields or the official text", name)
                actual[name] = page["abvText"]
            else:
                assert page == old_page, ("ABV-text import changed an unrelated detail", name)
        assert actual == expected, "phpMyAdmin did not import all 196 exact official ABV texts"
        assert snapshot() == original, "ABV-text update changed products, numeric values, users, or associations"
        assert database.mysql("SHOW CREATE TABLE sake;", fixture).stdout == original_schema, \
            "ABV-text update changed the requested ten-column schema"
        assert database.mysql("SELECT COUNT(*), MIN(id), MAX(id) FROM sake;", fixture).stdout.strip() == "2296\t1\t2296"
        first_details = raw_details()
        repeated = phpmyadmin.import_sql(fixture, update_sql + probes)
        assert "Import has been successfully finished" in repeated
        assert database.mysql("SELECT fk_checks, marker FROM pma_abv_followup_probe ORDER BY id;", fixture).stdout.splitlines() \
            == ["1\tpma_abv_update_followup_ok", "1\tpma_abv_update_followup_ok"]
        assert snapshot() == original and raw_details() == first_details, "Repeating the ABV-text import changed saved data"
        print("PASS: phpMyAdmin upgrades the existing 2296-product catalog with all 196 official ABV texts; "
              "existing JSON, numeric values, IDs, associations and schema are preserved, and reimport is byte-stable")
    finally:
        database.mysql(f"DROP DATABASE `{fixture}`;", check=False)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--container", help="Existing disposable, labeled MariaDB 10.11 container")
    arguments = parser.parse_args()
    rebuild_sql = (ROOT / "data/sake_catalog_rebuild.sql").read_text(encoding="utf-8")
    with MysqlSandbox(container=arguments.container, image="mariadb:10.11") as database:
        with PhpMyAdminSandbox(database) as phpmyadmin:
            test_rebuild_import(database, phpmyadmin, rebuild_sql)
            test_abv_text_upgrade(database, phpmyadmin, rebuild_sql)


if __name__ == "__main__":
    try:
        main()
    except subprocess.CalledProcessError as error:
        print(error.stderr.strip() if error.stderr else str(error), file=sys.stderr)
        raise SystemExit(error.returncode)
