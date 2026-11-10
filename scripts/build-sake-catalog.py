#!/usr/bin/env python3
"""公式確認済みJSONから、既存データを更新しないMySQL 8用の追加SQLを生成する。"""

import argparse
import csv
import datetime
import io
import json
from pathlib import Path
import re
import sys
import unicodedata
from urllib.parse import urlsplit


ROOT = Path(__file__).resolve().parents[1]
PREFECTURES = (
    "北海道 青森県 岩手県 宮城県 秋田県 山形県 福島県 茨城県 栃木県 群馬県 "
    "埼玉県 千葉県 東京都 神奈川県 新潟県 富山県 石川県 福井県 山梨県 長野県 "
    "岐阜県 静岡県 愛知県 三重県 滋賀県 京都府 大阪府 兵庫県 奈良県 和歌山県 "
    "鳥取県 島根県 岡山県 広島県 山口県 徳島県 香川県 愛媛県 高知県 福岡県 "
    "佐賀県 長崎県 熊本県 大分県 宮崎県 鹿児島県 沖縄県"
).split()
TYPE_ALIASES = {
    "純米": "純米酒", "純米大吟醸酒": "純米大吟醸", "純米吟醸酒": "純米吟醸",
    "大吟醸酒": "大吟醸", "吟醸酒": "吟醸", "特別純米酒": "特別純米",
    "特別本醸造酒": "特別本醸造", "本醸造酒": "本醸造",
}
ALLOWED_TYPES = {
    "純米酒", "純米大吟醸", "純米吟醸", "大吟醸", "吟醸", "特別純米",
    "特別本醸造", "本醸造", "普通酒", "生酒", "貴醸酒", "にごり酒", "発泡清酒",
}
CORPORATE_WORDS = ("株式会社", "有限会社", "合名会社", "合資会社", "合同会社", "(株)", "(有)")


def normalized(value):
    return re.sub(r"\s+", "", unicodedata.normalize("NFKC", value)).casefold()


def brewery_key(value):
    result = normalized(value)
    for word in CORPORATE_WORDS:
        result = result.replace(word, "")
    return result


def sql_string(value):
    # バックスラッシュはsql_modeで意味が変わるため入力で拒否。引用符はSQL標準の二重化。
    if "\\" in value or "\x00" in value:
        raise ValueError("SQL文字列にバックスラッシュまたはNULは使用できません")
    return "'" + value.replace("'", "''") + "'"


def sql_key(column, brewery=False):
    expression = f"REGEXP_REPLACE({column}, '[[:space:]　]+', '')"
    if brewery:
        # MySQLのREPLACEは照合順序に依存せず置換するため全角・略字も明示する。
        for word in (*CORPORATE_WORDS, "（株）", "（有）", "㈱", "㈲", "㍿"):
            expression = f"REPLACE({expression}, {sql_string(word)}, '')"
    return f"({expression} COLLATE utf8mb4_unicode_ci)"


def sql_type(column):
    expression = sql_key(column)
    cases = " ".join(f"WHEN {sql_string(a)} THEN {sql_string(b)}" for a, b in TYPE_ALIASES.items())
    return f"(CASE {expression} {cases} ELSE {expression} END)"


def load_records(directory):
    files = sorted(directory.glob("*.json"))
    if not files:
        raise ValueError(f"出典JSONがありません: {directory}")
    records, names, breweries = [], {}, {}
    for path in files:
        data = json.loads(path.read_text(encoding="utf-8"))
        if not isinstance(data, list):
            raise ValueError(f"JSONの先頭は配列にしてください: {path}")
        for position, original in enumerate(data, 1):
            row = dict(original)
            label = f"{path.name}:{position}"
            for field in ("prefecture", "brewery", "name", "sake_type", "source_url", "checked_on", "source_note"):
                if not isinstance(row.get(field), str) or not row[field].strip():
                    raise ValueError(f"{label}: {field}は空でない文字列が必要です")
                sql_string(row[field])
                if any(ord(char) < 32 for char in row[field]):
                    raise ValueError(f"{label}: {field}に制御文字があります")
                row[field] = re.sub(r"\s+", " ", row[field].strip())
            code = row.get("prefecture_code")
            if type(code) is not int or not 1 <= code <= 47 or PREFECTURES[code - 1] != row["prefecture"]:
                raise ValueError(f"{label}: 都道府県コードと県名が一致しません")
            datetime.date.fromisoformat(row["checked_on"])
            for field in ("source_url", "brewery_source_url"):
                value = row.get(field, "")
                url = urlsplit(value)
                if value and (url.scheme not in {"https", "http"} or not url.hostname or url.username or url.password):
                    raise ValueError(f"{label}: 出典URLが不正です: {value}")
                sql_string(value)
            row["sake_type"] = TYPE_ALIASES.get(row["sake_type"], row["sake_type"])
            if row["sake_type"] not in ALLOWED_TYPES:
                raise ValueError(f"{label}: 未対応の酒種を確認してください: {row['sake_type']}")
            for field, limit in (("name", 100), ("brewery", 100), ("sake_type", 50)):
                if len(row[field]) > limit:
                    raise ValueError(f"{label}: {field}がDBの{limit}文字を超えます")
            key = normalized(row["name"])
            if key in names:
                raise ValueError(f"商品名が重複: {row['name']} ({names[key]} / {label})")
            names[key] = label
            bkey = brewery_key(row["brewery"])
            if not bkey:
                raise ValueError(f"{label}: 酒蔵名の照合キーが空です")
            if bkey in breweries and breweries[bkey] != code:
                raise ValueError(f"同名酒蔵が複数県にあります: {row['brewery']}")
            breweries[bkey] = code
            aliases = row.get("aliases", [])
            if not isinstance(aliases, list) or any(not isinstance(a, str) or not a.strip() for a in aliases):
                raise ValueError(f"{label}: aliasesは空でない文字列の配列にしてください")
            for alias in aliases:
                sql_string(alias)
            row["_name_key"], row["_brewery_key"] = key, bkey
            row["_source_file"] = path.name
            records.append(row)
    if not records:
        raise ValueError("追加対象の商品がありません")
    records.sort(key=lambda r: (r["prefecture_code"], r["_brewery_key"], r["_name_key"]))
    # 一つの法人の表記だけが異なる場合、DBに作成する酒蔵名は一定にする。
    canonical_breweries = {}
    for row in records:
        canonical_breweries.setdefault(row["_brewery_key"], row["brewery"])
    for seq, row in enumerate(records, 1):
        row["_seq"] = seq
        row["_canonical_brewery"] = canonical_breweries[row["_brewery_key"]]
    # 別商品の別名として登録されている場合も二重登録を許さない。
    alias_owners = {}
    for row in records:
        for key in {row["_name_key"], *(normalized(a) for a in row.get("aliases", []))}:
            if key in alias_owners and alias_owners[key] != row["_seq"]:
                raise ValueError(f"別名が他の商品と重複しています: {row['name']}")
            alias_owners[key] = row["_seq"]
    return records


def make_sql(records):
    lines = [
        "-- 一升の出会い：公式確認済み日本酒を都道府県コード01→47順で追加。",
        "-- MySQL 8.0以降用。対象DBを選択し、同じ接続でファイル全体を実行する。",
        "-- USE/CREATE DATABASE/既存データのUPDATE・DELETEは行わない。mysql --forceは禁止。",
        "-- 先に data/sake-catalog/README.md を読み、接続先・バックアップを確認する。",
        "-- 出典・確認日・県別件数: data/sake-catalog/sources.csv / prefecture-counts.csv",
        f"-- 収録商品数: {len(records)}。容量違いは統合。日本全国の全商品の網羅ではない。",
        "SET NAMES utf8mb4 COLLATE utf8mb4_unicode_ci;",
        "SELECT DATABASE() AS target_database;",
    ]
    tables = ("source", "aliases", "candidates", "brewery_ids", "type_ids", "inserted")
    lines += [f"DROP TEMPORARY TABLE IF EXISTS tmp_sake_catalog_{table};" for table in tables]
    lines += [
        "CREATE TEMPORARY TABLE tmp_sake_catalog_source (",
        "  seq INT NOT NULL PRIMARY KEY, prefecture_code TINYINT UNSIGNED NOT NULL,",
        "  prefecture VARCHAR(50) NOT NULL, brewery VARCHAR(100) NOT NULL,",
        "  brewery_key VARCHAR(200) NOT NULL, name VARCHAR(100) NOT NULL,",
        "  name_key VARCHAR(200) NOT NULL UNIQUE, sake_type VARCHAR(50) NOT NULL,",
        "  source_url TEXT NOT NULL, checked_on DATE NOT NULL",
        ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;",
        "CREATE TEMPORARY TABLE tmp_sake_catalog_aliases (",
        "  seq INT NOT NULL, alias_key VARCHAR(200) NOT NULL, PRIMARY KEY (seq, alias_key)",
        ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;",
    ]
    for code in sorted({r["prefecture_code"] for r in records}):
        subset = [r for r in records if r["prefecture_code"] == code]
        lines += [f"\n-- {code:02d} {PREFECTURES[code - 1]} / {len(subset)}商品",
                  "INSERT INTO tmp_sake_catalog_source",
                  "  (seq, prefecture_code, prefecture, brewery, brewery_key, name, name_key, sake_type, source_url, checked_on) VALUES"]
        values = []
        for row in subset:
            strings = [row["prefecture"], row["_canonical_brewery"], row["_brewery_key"], row["name"],
                       row["_name_key"], row["sake_type"], row["source_url"], row["checked_on"]]
            values.append(f"  ({row['_seq']}, {code}, " + ", ".join(map(sql_string, strings)) + ")")
        lines.append(",\n".join(values) + ";")
    aliases = [(r["_seq"], key) for r in records
               for key in sorted({r["_name_key"], *(normalized(a) for a in r.get("aliases", []))})]
    lines += ["\nINSERT INTO tmp_sake_catalog_aliases (seq, alias_key) VALUES",
              ",\n".join(f"  ({seq}, {sql_string(key)})" for seq, key in aliases) + ";",
              "\n-- 同一DBに対するこの追加SQLの並行実行を直列化する。取得失敗時は全INSERTを止める。",
              "SET @sake_catalog_lock_name = CONCAT('sake_catalog_', LEFT(SHA2(DATABASE(), 256), 48));",
              "SET @sake_catalog_lock_acquired = GET_LOCK(@sake_catalog_lock_name, 30);",
              "START TRANSACTION;",
              "CREATE TEMPORARY TABLE tmp_sake_catalog_candidates AS",
              "SELECT src.* FROM tmp_sake_catalog_source src",
              "WHERE NOT EXISTS (",
              "  SELECT 1 FROM tmp_sake_catalog_aliases a JOIN sake s",
              f"    ON {sql_key('s.name')} = a.alias_key WHERE a.seq = src.seq",
              ");",
              "\n-- 法人格・空白の表記違いを同じ酒蔵として扱い、既存の酒蔵は更新しない。",
              "INSERT INTO breweries (name, prefecture)",
              "SELECT c.brewery, c.prefecture FROM tmp_sake_catalog_candidates c",
              "WHERE @sake_catalog_lock_acquired = 1",
              f"  AND NOT EXISTS (SELECT 1 FROM breweries b WHERE {sql_key('b.name', True)} = c.brewery_key)",
              "GROUP BY c.brewery, c.prefecture ORDER BY MIN(c.seq);",
              "\n-- 同名酒蔵が複数ある場合・既存県名が異なる場合は、誤った外部キーを付けず保留する。",
              "CREATE TEMPORARY TABLE tmp_sake_catalog_brewery_ids AS",
              "SELECT c.brewery_key, c.prefecture, MIN(b.id) AS brewery_id",
              "FROM (SELECT DISTINCT brewery_key, prefecture FROM tmp_sake_catalog_candidates) c",
              f"JOIN breweries b ON {sql_key('b.name', True)} = c.brewery_key",
              "GROUP BY c.brewery_key, c.prefecture",
              "HAVING COUNT(*) = 1 AND MAX(b.prefecture IS NULL OR TRIM(b.prefecture) = c.prefecture) = 1;",
              "\nINSERT INTO sake_types (name)",
              "SELECT c.sake_type FROM tmp_sake_catalog_candidates c",
              "JOIN tmp_sake_catalog_brewery_ids b ON b.brewery_key = c.brewery_key AND b.prefecture = c.prefecture",
              "WHERE @sake_catalog_lock_acquired = 1",
              f"  AND NOT EXISTS (SELECT 1 FROM sake_types t WHERE {sql_type('t.name')} = c.sake_type)",
              "GROUP BY c.sake_type ORDER BY MIN(c.seq);",
              "CREATE TEMPORARY TABLE tmp_sake_catalog_type_ids AS",
              "SELECT c.sake_type, MIN(t.id) AS sake_type_id",
              "FROM (SELECT DISTINCT sake_type FROM tmp_sake_catalog_candidates) c",
              f"JOIN sake_types t ON {sql_type('t.name')} = c.sake_type",
              "GROUP BY c.sake_type;",
              "\n-- ORDER BY seqで北海道から都道府県コード順に採番。IDの固定値は使わない。",
              "-- 未確認の価格・度数・紹介文・画像はNULL。根拠のない診断タグは追加しない。",
              "INSERT INTO sake (brewery_id, sake_type_id, name, region, abv, price, description, image_url)",
              "SELECT b.brewery_id, t.sake_type_id, c.name, c.prefecture, NULL, NULL, NULL, NULL",
              "FROM tmp_sake_catalog_candidates c",
              "JOIN tmp_sake_catalog_brewery_ids b ON b.brewery_key = c.brewery_key AND b.prefecture = c.prefecture",
              "JOIN tmp_sake_catalog_type_ids t ON t.sake_type = c.sake_type",
              "WHERE @sake_catalog_lock_acquired = 1 ORDER BY c.seq;",
              "SET @sake_catalog_inserted_rows = ROW_COUNT();",
              "CREATE TEMPORARY TABLE tmp_sake_catalog_inserted AS",
              "SELECT c.seq, s.id FROM tmp_sake_catalog_candidates c",
              f"JOIN sake s ON {sql_key('s.name')} = c.name_key",
              "WHERE @sake_catalog_lock_acquired = 1;",
              "COMMIT;",
              "\nSELECT CASE WHEN @sake_catalog_lock_acquired = 1 THEN 'completed'",
              "            ELSE 'not_applied_lock_unavailable' END AS status,",
              "       @sake_catalog_inserted_rows AS inserted_products;",
              "SELECT src.prefecture_code, src.prefecture, COUNT(*) AS source_products,",
              "       SUM(i.seq IS NOT NULL) AS inserted_products,",
              "       SUM(c.seq IS NULL) AS skipped_existing_products,",
              "       SUM(c.seq IS NOT NULL AND i.seq IS NULL) AS pending_products",
              "FROM tmp_sake_catalog_source src",
              "LEFT JOIN tmp_sake_catalog_candidates c ON c.seq = src.seq",
              "LEFT JOIN tmp_sake_catalog_inserted i ON i.seq = src.seq",
              "GROUP BY src.prefecture_code, src.prefecture ORDER BY src.prefecture_code;",
              "SELECT c.prefecture_code, c.name, c.brewery, 'brewery_conflict_or_lock_unavailable' AS reason",
              "FROM tmp_sake_catalog_candidates c LEFT JOIN tmp_sake_catalog_inserted i ON i.seq = c.seq",
              "WHERE i.seq IS NULL ORDER BY c.seq;",
              "SELECT RELEASE_LOCK(@sake_catalog_lock_name) AS released_catalog_lock;",
              *[f"DROP TEMPORARY TABLE tmp_sake_catalog_{table};" for table in reversed(tables)],
              "",
              ]
    return "\n".join(lines)


def make_sources(records):
    buffer = io.StringIO(newline="")
    fields = ["sequence", "prefecture_code", "prefecture", "brewery", "name", "sake_type", "source_url",
              "brewery_source_url", "checked_on", "source_note", "source_file"]
    writer = csv.DictWriter(buffer, fields, lineterminator="\n")
    writer.writeheader()
    for row in records:
        writer.writerow({**{k: row.get(k, "") for k in fields}, "sequence": row["_seq"],
                         "prefecture_code": f"{row['prefecture_code']:02d}", "source_file": row["_source_file"]})
    return buffer.getvalue()


def make_counts(records):
    buffer = io.StringIO(newline="")
    writer = csv.writer(buffer, lineterminator="\n")
    writer.writerow(["prefecture_code", "prefecture", "verified_products", "breweries"])
    for code, prefecture in enumerate(PREFECTURES, 1):
        rows = [r for r in records if r["prefecture_code"] == code]
        writer.writerow([f"{code:02d}", prefecture, len(rows), len({r["_brewery_key"] for r in rows})])
    return buffer.getvalue()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="生成物を変更せず、出典JSONとの一致を確認")
    args = parser.parse_args()
    records = load_records(ROOT / "data/sake-catalog/regions")
    outputs = {
        ROOT / "data/sake_catalog_prefecture_order.sql": make_sql(records),
        ROOT / "data/sake-catalog/sources.csv": make_sources(records),
        ROOT / "data/sake-catalog/prefecture-counts.csv": make_counts(records),
    }
    for path, contents in outputs.items():
        if args.check:
            if not path.exists() or path.read_text(encoding="utf-8") != contents:
                raise ValueError(f"生成物が古いか未作成です: {path.relative_to(ROOT)}")
        else:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(contents, encoding="utf-8")
    print(f"{'検証' if args.check else '生成'}完了: {len(records)}商品 / "
          f"{len({r['prefecture_code'] for r in records})}都道府県 / "
          f"{len({r['_brewery_key'] for r in records})}酒蔵")


if __name__ == "__main__":
    try:
        main()
    except (ValueError, KeyError, OSError) as error:
        print(f"エラー: {error}", file=sys.stderr)
        sys.exit(1)
