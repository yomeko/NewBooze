#!/usr/bin/env python3
"""公式確認済みJSONから、MySQL 8/MariaDB 10.11用のカタログ・空欄補完SQLを生成する。"""

import argparse
import csv
import datetime
import io
import json
from decimal import Decimal
from pathlib import Path
import re
import sys
import unicodedata
import zipfile
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


def sql_key(column, brewery=False, precise=False):
    expression = f"REGEXP_REPLACE({column}, '[[:space:]　]+', '')"
    if brewery:
        # MySQLのREPLACEは照合順序に依存せず置換するため全角・略字も明示する。
        for word in (*CORPORATE_WORDS, "（株）", "（有）", "(株）", "（株)", "(有）", "（有)", "㈱", "㈲", "㍿"):
            expression = f"REPLACE({expression}, {sql_string(word)}, '')"
    if precise:
        return f"(LOWER({expression}) COLLATE utf8mb4_bin)"
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
            row = {key: value for key, value in original.items() if not key.startswith("_")}
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


def checked_url(value, label, https=False):
    if not isinstance(value, str):
        raise ValueError(f"{label}: URLは文字列が必要です")
    url = urlsplit(value)
    if url.scheme not in ({"https"} if https else {"http", "https"}) or not url.hostname or url.username or url.password:
        raise ValueError(f"{label}: URLが不正です: {value}")
    if any(ord(char) < 32 for char in value):
        raise ValueError(f"{label}: URLに制御文字があります")
    sql_string(value)
    return value


def apply_enrichment(records, directory):
    """独立した属性出典を照合し、検証済みの値だけを内部メタデータへ載せる。"""
    indexed = {row["_name_key"]: row for row in records}
    seen = set()
    for path in sorted(directory.glob("*.json")):
        entries = json.loads(path.read_text(encoding="utf-8"))
        if not isinstance(entries, list):
            raise ValueError(f"属性JSONは配列が必要です: {path}")
        for position, entry in enumerate(entries, 1):
            label = f"{path.name}:{position}"
            key = normalized(entry["name"])
            row = indexed.get(key)
            if row is None or entry["prefecture_code"] != row["prefecture_code"] or brewery_key(entry["brewery"]) != row["_brewery_key"]:
                raise ValueError(f"{label}: 既存商品・県・酒蔵と一致しません: {entry['name']}")
            if key in seen:
                raise ValueError(f"{label}: 属性の対象商品が重複しています: {entry['name']}")
            seen.add(key)
            values = dict(entry)
            datetime.date.fromisoformat(values["checked_on"])
            for field in ("abv_checked_on", "price_checked_on", "image_checked_on"):
                values[field] = values.get(field) or values["checked_on"]
                datetime.date.fromisoformat(values[field])
            for field in ("abv", "abv_text", "abv_source_url", "price", "volume_ml", "price_source_url",
                          "image_url", "image_source_url", "image_http_status", "image_content_type"):
                values.setdefault(field, None)
            if values["abv"] is not None:
                if type(values["abv"]) not in (int, float):
                    raise ValueError(f"{label}: 度数は数値が必要です")
                number = Decimal(str(values["abv"]))
                if not number.is_finite() or not 0 < number < 100 or number != number.quantize(Decimal("0.1")):
                    raise ValueError(f"{label}: 度数は0超100未満・小数1桁以内が必要です")
            if values["abv"] is not None or values["abv_text"]:
                checked_url(values["abv_source_url"], label + ":abv_source_url")
            if values["abv_text"] is not None:
                if not isinstance(values["abv_text"], str) or len(values["abv_text"]) > 120:
                    raise ValueError(f"{label}: abv_textは120文字以内の文字列が必要です")
                sql_string(values["abv_text"])
                if values["abv"] is not None and re.search(r"以上|未満|以下|超|約|およそ|[〜～~]|\d\s*[-–—]\s*\d", values["abv_text"]):
                    raise ValueError(f"{label}: 範囲・概数表記を単一の数値度数にはできません")
            if values["price"] is not None:
                if type(values["price"]) is not int or not 0 < values["price"] <= 4294967295:
                    raise ValueError(f"{label}: 価格は正の整数が必要です")
                if type(values["volume_ml"]) is not int or values["volume_ml"] <= 0 or values.get("price_tax_included") is not True:
                    raise ValueError(f"{label}: 価格には容量と税込確認が必要です")
                checked_url(values["price_source_url"], label + ":price_source_url")
            if values["image_url"] is not None:
                checked_url(values["image_url"], label + ":image_url", https=True)
                checked_url(values["image_source_url"], label + ":image_source_url")
                if len(values["image_url"]) > 255 or values["image_http_status"] != 200:
                    raise ValueError(f"{label}: 画像は255文字以内・HTTP200確認済みが必要です")
                if not isinstance(values["image_content_type"], str) or not values["image_content_type"].lower().startswith("image/"):
                    raise ValueError(f"{label}: 画像Content-Typeの確認が必要です")
                if re.search(r"no[-_]?image|placeholder|spacer|/logo[/.\-_]", values["image_url"], re.IGNORECASE):
                    raise ValueError(f"{label}: 商品写真ではない代替画像候補です")
            if not any(values.get(field) is not None for field in ("abv", "abv_text", "price", "image_url")):
                raise ValueError(f"{label}: 確認済みの属性がありません")
            values["notes"] = values.get("notes", "")
            if not isinstance(values["notes"], str):
                raise ValueError(f"{label}: notesは文字列が必要です")
            sql_string(values["notes"])
            values["source_file"] = path.name
            row["_attributes"] = values
    return records


def sql_scalar(value):
    if value is None:
        return "NULL"
    return sql_string(value) if isinstance(value, str) else str(value)


def fallback_page_ids():
    path = ROOT / "src/main/resources/data/sake-pages.json"
    if not path.exists():
        return []
    pages = json.loads(path.read_text(encoding="utf-8"))
    ids = [page["id"] for page in pages]
    if any(type(value) is not int or value <= 0 for value in ids):
        raise ValueError("同梱の紹介JSONに不正なIDがあります")
    return sorted(set(ids))


def portable_key_normalization(table):
    """幅・カナの濁点を一時キーで正規化。DB固有の日本語照合順序は使わない。"""
    characters = set(chr(code) for start, end in ((0xFF01, 0xFF5F), (0xFF61, 0xFFA0), (0x3040, 0x3100))
                     for code in range(start, end))
    characters.update("神㈱㈲㍿")
    replacements = [(char, unicodedata.normalize("NFKD", char).lower()) for char in sorted(characters)]
    replacements = [(old, new) for old, new in replacements if old != new]

    def literal(value):
        # 全角の引用符・バックスラッシュも、sql_modeに依存しない16進文字列で扱う。
        return "CONVERT(X'" + value.encode("utf-8").hex() + "' USING utf8mb4)"

    statements = []
    # 巨大な入れ子や候補の直積ごとの計算を避け、各キーを小さな段階に分けて一度ずつ処理。
    for offset in range(0, len(replacements), 20):
        assignments = []
        for column in ("name_key", "brewery_key"):
            expression = column
            for old, new in replacements[offset:offset + 20]:
                expression = f"REPLACE({expression}, {literal(old)}, {literal(new)})"
            assignments.append(f"{column} = {expression}")
        statements.append(f"UPDATE {table} SET " + ", ".join(assignments) + ";")
    statements.append(f"UPDATE {table} SET name_key = {sql_key('name_key', precise=True)}, "
                      f"brewery_key = {sql_key('brewery_key', True, precise=True)};")
    return statements


def identity_statements():
    """商品名・酒蔵・県が一意に対応する既存IDだけを照合する。"""
    statements = [
        "\n-- MySQL/MariaDB共通の一時キー。濁点・アクセント・ひらがな/カタカナを区別する。",
        "CREATE TEMPORARY TABLE tmp_sake_catalog_existing_keys (",
        "  id BIGINT UNSIGNED NOT NULL PRIMARY KEY, name_key VARCHAR(400) NOT NULL,",
        "  brewery_key VARCHAR(400) NOT NULL, region VARCHAR(50) NULL, brewery_prefecture VARCHAR(50) NULL,",
        "  KEY idx_existing_name (name_key(191))",
        ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;",
        "INSERT INTO tmp_sake_catalog_existing_keys (id, name_key, brewery_key, region, brewery_prefecture)",
        "SELECT s.id, LOWER(s.name), LOWER(b.name), s.region, b.prefecture",
        "FROM sake s JOIN breweries b ON b.id = s.brewery_id WHERE @sake_catalog_lock_acquired = 1;",
        "CREATE TEMPORARY TABLE tmp_sake_catalog_source_keys (",
        "  seq INT NOT NULL, name_key VARCHAR(800) NOT NULL, brewery_key VARCHAR(400) NOT NULL,",
        "  prefecture VARCHAR(50) NOT NULL, KEY idx_source_name (name_key(191))",
        ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;",
        "INSERT INTO tmp_sake_catalog_source_keys (seq, name_key, brewery_key, prefecture)",
        "SELECT a.seq, LOWER(a.alias_key), LOWER(src.brewery), src.prefecture",
        "FROM tmp_sake_catalog_aliases a JOIN tmp_sake_catalog_source src ON src.seq = a.seq",
        "WHERE @sake_catalog_lock_acquired = 1;",
    ]
    statements += portable_key_normalization("tmp_sake_catalog_existing_keys")
    statements += portable_key_normalization("tmp_sake_catalog_source_keys")
    statements += [
        "\n-- 別名は一意の商品のみ対応させ、酒蔵・地域が違う既存行は変更しない。",
        "CREATE TEMPORARY TABLE tmp_sake_catalog_matches AS",
        "SELECT seq, id FROM (",
        "  SELECT pairs.*, COUNT(*) OVER (PARTITION BY seq) AS source_matches,",
        "         COUNT(*) OVER (PARTITION BY id) AS target_matches FROM (",
        "    SELECT DISTINCT src.seq, existing.id FROM tmp_sake_catalog_source_keys src",
        "    JOIN tmp_sake_catalog_existing_keys existing",
        "      ON existing.name_key = src.name_key AND existing.brewery_key = src.brewery_key",
        "    WHERE @sake_catalog_lock_acquired = 1 AND existing.region = src.prefecture",
        "      AND (existing.brewery_prefecture IS NULL OR existing.brewery_prefecture = src.prefecture)",
        "  ) pairs",
        ") ranked WHERE source_matches = 1 AND target_matches = 1;",
    ]
    return statements


def attribute_statements():
    """単一の商品と酒蔵に対応した既存行の、空欄だけを補完する。"""
    statements = [
        "UPDATE sake s JOIN tmp_sake_catalog_matches m ON m.id = s.id",
        "JOIN tmp_sake_catalog_source src ON src.seq = m.seq",
        "SET s.abv = COALESCE(s.abv, src.abv), s.price = COALESCE(s.price, src.price),",
        "    s.image_url = CASE WHEN src.image_url IS NOT NULL AND (s.image_url IS NULL OR s.image_url = '')",
        "                       THEN src.image_url ELSE s.image_url END",
        "WHERE @sake_catalog_lock_acquired = 1 AND (",
        "  (s.abv IS NULL AND src.abv IS NOT NULL) OR (s.price IS NULL AND src.price IS NOT NULL) OR",
        "  ((s.image_url IS NULL OR s.image_url = '') AND src.image_url IS NOT NULL));",
        "SET @sake_catalog_updated_rows = ROW_COUNT();",
    ]
    return statements


def price_statements():
    """価格だけの差分では、既存の数値属性や商品行を変更しない。"""
    return [
        "UPDATE sake s JOIN tmp_sake_catalog_matches m ON m.id = s.id",
        "JOIN tmp_sake_catalog_source src ON src.seq = m.seq",
        "SET s.price = src.price",
        "WHERE @sake_catalog_lock_acquired = 1 AND s.price IS NULL AND src.price IS NOT NULL;",
        "SET @sake_catalog_updated_rows = ROW_COUNT();",
    ]


def price_detail_statements():
    """容量と税込価格の空欄だけを補完し、紹介・範囲度数・手入力を保持する。"""
    source = ROOT / "src/main/resources/data/sake-pages.json"
    pages = json.loads(source.read_text(encoding="utf-8")) if source.exists() else []
    statements = [
        "\n-- 容量は公式に確認した実容量だけ。720ml以外を720mlへ換算しない。",
        "CREATE TEMPORARY TABLE tmp_sake_catalog_price_fallback (",
        "  id BIGINT UNSIGNED NOT NULL PRIMARY KEY, name VARCHAR(100) COLLATE utf8mb4_bin NOT NULL,",
        "  detail_json LONGTEXT NOT NULL",
        ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;",
    ]
    if pages:
        values = []
        for page in pages:
            if type(page.get("id")) is not int or page["id"] <= 0 or not isinstance(page.get("name"), str):
                raise ValueError("同梱紹介JSONのID・商品名が不正です")
            serialized = json.dumps(page, ensure_ascii=False, separators=(",", ":"))
            literal = "CONVERT(X'" + serialized.encode("utf-8").hex() + "' USING utf8mb4)"
            values.append(f"  ({page['id']}, {sql_string(page['name'])}, {literal})")
        statements += ["INSERT INTO tmp_sake_catalog_price_fallback (id, name, detail_json) VALUES",
                       ",\n".join(values) + ";"]
    statements += [
        "CREATE TEMPORARY TABLE tmp_sake_catalog_price_pages AS",
        "SELECT s.id, s.name, s.price AS current_price, src.price AS verified_price, src.volume_ml,",
        "  d.sake_id IS NULL AS is_new,",
        "  CASE WHEN d.sake_id IS NOT NULL THEN COALESCE(JSON_VALID(d.detail_json), FALSE) ELSE TRUE END AS valid_json,",
        "  CASE WHEN d.sake_id IS NOT NULL THEN CASE WHEN JSON_VALID(d.detail_json) THEN d.detail_json ELSE '{}' END",
        "       WHEN f.id IS NOT NULL THEN f.detail_json",
        "       ELSE JSON_OBJECT('id', s.id, 'name', s.name, 'brewery', src.brewery, 'prices', JSON_ARRAY(),",
        "         'officialUrl', CASE WHEN src.price_source_url LIKE 'https://%' THEN src.price_source_url ELSE '' END) END AS detail_json,",
        "  FALSE AS fillable",
        "FROM tmp_sake_catalog_matches m JOIN sake s ON s.id = m.id",
        "JOIN tmp_sake_catalog_source src ON src.seq = m.seq",
        "LEFT JOIN sake_page_details d ON d.sake_id = s.id",
        "LEFT JOIN tmp_sake_catalog_price_fallback f ON f.id = s.id AND f.name = s.name COLLATE utf8mb4_bin",
        "WHERE @sake_catalog_lock_acquired = 1 AND src.price IS NOT NULL AND src.volume_ml IS NOT NULL;",
        "UPDATE tmp_sake_catalog_price_pages SET fillable = COALESCE(current_price = verified_price",
        "  AND valid_json AND JSON_TYPE(detail_json) = 'OBJECT'",
        "  AND JSON_TYPE(JSON_EXTRACT(detail_json, '$.id')) = 'INTEGER'",
        "  AND JSON_UNQUOTE(JSON_EXTRACT(detail_json, '$.id')) = CAST(id AS CHAR)",
        "  AND JSON_TYPE(JSON_EXTRACT(detail_json, '$.name')) = 'STRING'",
        "  AND JSON_UNQUOTE(JSON_EXTRACT(detail_json, '$.name')) COLLATE utf8mb4_bin = name COLLATE utf8mb4_bin",
        "  AND (JSON_EXTRACT(detail_json, '$.prices') IS NULL",
        "       OR JSON_TYPE(JSON_EXTRACT(detail_json, '$.prices')) = 'NULL'",
        "       OR (JSON_TYPE(JSON_EXTRACT(detail_json, '$.prices')) = 'ARRAY'",
        "           AND JSON_LENGTH(JSON_EXTRACT(detail_json, '$.prices')) = 0)), FALSE);",
        "UPDATE sake_page_details d JOIN tmp_sake_catalog_price_pages p ON p.id = d.sake_id",
        "JOIN sake s ON s.id = p.id AND s.price = p.verified_price",
        "SET d.detail_json = JSON_SET(d.detail_json, '$.prices',",
        "  JSON_ARRAY(JSON_OBJECT('volume', CONCAT(p.volume_ml, 'ml'), 'yen', p.verified_price)))",
        "WHERE @sake_catalog_lock_acquired = 1 AND p.fillable = TRUE AND p.is_new = FALSE",
        "  AND d.detail_json COLLATE utf8mb4_bin = p.detail_json COLLATE utf8mb4_bin;",
        "SET @sake_catalog_price_detail_rows = ROW_COUNT();",
        "INSERT INTO sake_page_details (sake_id, detail_json)",
        "SELECT p.id, JSON_SET(p.detail_json, '$.prices',",
        "  JSON_ARRAY(JSON_OBJECT('volume', CONCAT(p.volume_ml, 'ml'), 'yen', p.verified_price)))",
        "FROM tmp_sake_catalog_price_pages p JOIN sake s ON s.id = p.id AND s.price = p.verified_price",
        "WHERE @sake_catalog_lock_acquired = 1 AND p.fillable = TRUE AND p.is_new = TRUE;",
        "SET @sake_catalog_inserted_price_detail_rows = ROW_COUNT();",
        "SET @sake_catalog_price_detail_rows = @sake_catalog_price_detail_rows + @sake_catalog_inserted_price_detail_rows;",
    ]
    return statements


def abv_text_statements():
    """公式原文を空欄へ併合し、紹介・価格・リンクと既存の原文を保持する。"""
    source = ROOT / "src/main/resources/data/sake-pages.json"
    pages = json.loads(source.read_text(encoding="utf-8")) if source.exists() else []
    statements = [
        "\n-- 同梱紹介JSONはIDと商品名が一致する場合だけ丸ごと保持する。",
        "CREATE TEMPORARY TABLE tmp_sake_catalog_abv_fallback (",
        "  id BIGINT UNSIGNED NOT NULL PRIMARY KEY, name VARCHAR(100) COLLATE utf8mb4_bin NOT NULL,",
        "  detail_json LONGTEXT NOT NULL",
        ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;",
    ]
    if pages:
        statements += ["INSERT INTO tmp_sake_catalog_abv_fallback (id, name, detail_json) VALUES"]
        values = []
        for page in pages:
            if type(page.get("id")) is not int or page["id"] <= 0 or not isinstance(page.get("name"), str):
                raise ValueError("同梱紹介JSONのID・商品名が不正です")
            serialized = json.dumps(page, ensure_ascii=False, separators=(",", ":"))
            literal = "CONVERT(X'" + serialized.encode("utf-8").hex() + "' USING utf8mb4)"
            values.append(f"  ({page['id']}, {sql_string(page['name'])}, {literal})")
        statements += [",\n".join(values) + ";"]
    statements += [
        "\n-- 不正JSONは関数評価前に安全な空objectへ置き換え、元の行は変更しない。",
        "CREATE TEMPORARY TABLE tmp_sake_catalog_abv_pages AS",
        "SELECT s.id, s.name, src.abv_text, d.sake_id IS NULL AS is_new,",
        "  CASE WHEN d.sake_id IS NOT NULL THEN COALESCE(JSON_VALID(d.detail_json), FALSE) ELSE TRUE END AS valid_json,",
        "  CASE WHEN d.sake_id IS NOT NULL THEN CASE WHEN JSON_VALID(d.detail_json) THEN d.detail_json ELSE '{}' END",
        "       WHEN f.id IS NOT NULL THEN f.detail_json",
        "       ELSE JSON_OBJECT('id', s.id, 'name', s.name, 'brewery', src.brewery, 'introduction', '',",
        "         'prices', CASE WHEN src.price IS NOT NULL AND src.volume_ml IS NOT NULL AND s.price = src.price",
        "           THEN JSON_ARRAY(JSON_OBJECT('volume', CONCAT(src.volume_ml, 'ml'), 'yen', src.price)) ELSE JSON_ARRAY() END,",
        "         'officialUrl', CASE WHEN src.source_url LIKE 'https://%' THEN src.source_url ELSE '' END) END AS detail_json, FALSE AS fillable",
        "FROM tmp_sake_catalog_matches m JOIN sake s ON s.id = m.id",
        "JOIN tmp_sake_catalog_source src ON src.seq = m.seq",
        "LEFT JOIN sake_page_details d ON d.sake_id = s.id",
        "LEFT JOIN tmp_sake_catalog_abv_fallback f ON f.id = s.id AND f.name = s.name COLLATE utf8mb4_bin",
        "WHERE @sake_catalog_lock_acquired = 1 AND src.abv IS NULL AND src.abv_text IS NOT NULL AND src.abv_text <> '';",
        "UPDATE tmp_sake_catalog_abv_pages SET fillable = COALESCE(valid_json AND JSON_TYPE(detail_json) = 'OBJECT'",
        "  AND JSON_TYPE(JSON_EXTRACT(detail_json, '$.id')) = 'INTEGER'",
        "  AND JSON_UNQUOTE(JSON_EXTRACT(detail_json, '$.id')) = CAST(id AS CHAR)",
        "  AND JSON_TYPE(JSON_EXTRACT(detail_json, '$.name')) = 'STRING'",
        "  AND JSON_UNQUOTE(JSON_EXTRACT(detail_json, '$.name')) COLLATE utf8mb4_bin = name COLLATE utf8mb4_bin",
        "  AND (JSON_EXTRACT(detail_json, '$.abvText') IS NULL",
        "       OR JSON_TYPE(JSON_EXTRACT(detail_json, '$.abvText')) = 'NULL'",
        "       OR (JSON_TYPE(JSON_EXTRACT(detail_json, '$.abvText')) = 'STRING'",
        "           AND TRIM(JSON_UNQUOTE(JSON_EXTRACT(detail_json, '$.abvText'))) = '')), FALSE);",
        "UPDATE sake_page_details d JOIN tmp_sake_catalog_abv_pages p ON p.id = d.sake_id",
        "SET d.detail_json = JSON_SET(d.detail_json, '$.abvText', p.abv_text)",
        "WHERE @sake_catalog_lock_acquired = 1 AND p.fillable = TRUE AND p.is_new = FALSE",
        "  AND d.detail_json COLLATE utf8mb4_bin = p.detail_json COLLATE utf8mb4_bin;",
        "SET @sake_catalog_abv_text_rows = ROW_COUNT();",
        "INSERT INTO sake_page_details (sake_id, detail_json)",
        "SELECT id, JSON_SET(detail_json, '$.abvText', abv_text) FROM tmp_sake_catalog_abv_pages",
        "WHERE @sake_catalog_lock_acquired = 1 AND fillable = TRUE AND is_new = TRUE;",
        "SET @sake_catalog_abv_text_rows = @sake_catalog_abv_text_rows + ROW_COUNT();",
    ]
    return statements


def source_staging_statements(records, tables):
    lines = []
    lines += [f"DROP TEMPORARY TABLE IF EXISTS tmp_sake_catalog_{table};" for table in tables]
    lines += [
        "CREATE TEMPORARY TABLE tmp_sake_catalog_source (",
        "  seq INT NOT NULL PRIMARY KEY, prefecture_code TINYINT UNSIGNED NOT NULL,",
        "  prefecture VARCHAR(50) NOT NULL, brewery VARCHAR(100) NOT NULL,",
        "  brewery_key VARCHAR(200) NOT NULL, name VARCHAR(100) NOT NULL,",
        "  name_key VARCHAR(200) NOT NULL UNIQUE, sake_type VARCHAR(50) NOT NULL,",
        "  source_url TEXT NOT NULL, checked_on DATE NOT NULL,",
        "  abv DECIMAL(4,1) NULL, abv_text VARCHAR(120) NULL, price INT UNSIGNED NULL, volume_ml INT UNSIGNED NULL,",
        "  image_url VARCHAR(255) NULL, price_source_url TEXT NULL",
        ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;",
        "CREATE TEMPORARY TABLE tmp_sake_catalog_aliases (",
        "  seq INT NOT NULL, alias_key VARCHAR(200) COLLATE utf8mb4_bin NOT NULL, PRIMARY KEY (seq, alias_key)",
        ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;",
    ]
    for code in sorted({r["prefecture_code"] for r in records}):
        subset = [r for r in records if r["prefecture_code"] == code]
        lines += [f"\n-- {code:02d} {PREFECTURES[code - 1]} / {len(subset)}商品",
                  "INSERT INTO tmp_sake_catalog_source",
                  "  (seq, prefecture_code, prefecture, brewery, brewery_key, name, name_key, sake_type, source_url, checked_on, abv, abv_text, price, volume_ml, image_url, price_source_url) VALUES"]
        values = []
        for row in subset:
            strings = [row["prefecture"], row["_canonical_brewery"], row["_brewery_key"], row["name"],
                       row["_name_key"], row["sake_type"], row["source_url"], row["checked_on"]]
            attributes = row.get("_attributes", {})
            fields = [attributes.get(key) for key in ("abv", "abv_text", "price", "volume_ml", "image_url", "price_source_url")]
            values.append(f"  ({row['_seq']}, {code}, " + ", ".join(map(sql_string, strings)) + ", "
                          + ", ".join(map(sql_scalar, fields)) + ")")
        lines.append(",\n".join(values) + ";")
    # 日本語の厳密な照合を保ちつつ、神/神のようなNFKC互換漢字は公式の生表記でも照合する。
    aliases = [(r["_seq"], key) for r in records
               for key in sorted({key for name in [r["name"], *r.get("aliases", [])]
                                  for key in (normalized(name), re.sub(r"\s+", "", name))})]
    lines += ["\nINSERT INTO tmp_sake_catalog_aliases (seq, alias_key) VALUES",
              ",\n".join(f"  ({seq}, {sql_string(key)})" for seq, key in aliases) + ";",
              ]
    return lines


def catalog_master_statements():
    return [
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
    ]


def make_sql(records, attributes_only=False, rebuild=False, abv_text_only=False, prices_only=False):
    if rebuild:
        if attributes_only or abv_text_only or prices_only:
            raise ValueError("再構築と補完専用は同時に指定できません")
        return make_rebuild_sql(records)
    if sum((attributes_only, abv_text_only, prices_only)) > 1:
        raise ValueError("補完専用モードは同時に指定できません")
    only_existing = attributes_only or abv_text_only or prices_only
    if not records:
        return "-- 確認済み属性なし。永続テーブルを変更しない。\nSELECT 'no_verified_attributes' AS status;\n"
    lines = [
        "-- 一升の出会い：確認済みの容量・税込価格を補完。商品行の追加・削除・再採番はしない。" if prices_only else
        "-- 一升の出会い：公式の度数範囲をdetail_json.abvTextへ補完。商品・数値属性・列構成は変更しない。" if abv_text_only else
        "-- 一升の出会い：確認済み属性の空欄補完。商品そのものは追加しない。" if attributes_only else
        "-- 一升の出会い：公式確認済み日本酒を都道府県コード01→47順で追加し、確認済み属性を補完。",
        "-- MySQL 8.0 / MariaDB 10.11用。対象DBを選択し、同じ接続でファイル全体を実行する。",
        "-- NULLのpriceと空のpricesだけ補完。既存の数値・紹介・範囲度数・購入先・入力済み価格配列は保持。" if prices_only else
        "-- sakeの各値は変更しない。詳細JSONの紹介等と入力済みabvTextを保持し、空のabvTextだけ追記。" if abv_text_only else
        "-- 既存属性は空欄だけ補完。詳細JSONの紹介等を保持し、空のpricesとabvTextだけ追記。",
        "-- USE/CREATE DATABASE/DELETEは行わない。mysql --forceは禁止。",
        "-- 先に data/sake-catalog/README.md を読み、接続先・バックアップを確認する。",
        "-- 出典・確認日: data/sake-catalog/sources.csv / attribute-sources.csv",
        f"-- 収録商品数: {len(records)}。容量違いは統合。日本全国の全商品の網羅ではない。",
        "SET NAMES utf8mb4 COLLATE utf8mb4_unicode_ci;",
        "SELECT DATABASE() AS target_database;",
    ]
    tables = (("source", "aliases", "existing_keys", "source_keys", "matches")
              + (() if abv_text_only else ("price_fallback", "price_pages"))
              + (() if prices_only else ("abv_fallback", "abv_pages"))
              + (() if only_existing else ("candidates", "brewery_ids", "type_ids", "inserted")))
    lines += source_staging_statements(records, tables)
    lines += [
              "\n-- 同一DBに対するこのSQLの並行実行を直列化する。取得失敗時は永続テーブルを変更しない。",
              "SET @sake_catalog_lock_name = CONCAT('sake_catalog_', LEFT(SHA2(DATABASE(), 256), 48));",
              "SET @sake_catalog_lock_acquired = GET_LOCK(@sake_catalog_lock_name, 30);",
              "START TRANSACTION;",
              "SET @sake_catalog_inserted_rows = 0;",
              "SET @sake_catalog_price_detail_rows = 0;",
              "SET @sake_catalog_inserted_price_detail_rows = 0;",
              "SET @sake_catalog_abv_text_rows = 0;",
              ]
    if not only_existing:
        lines += [
              "CREATE TEMPORARY TABLE tmp_sake_catalog_candidates AS",
              "SELECT src.* FROM tmp_sake_catalog_source src",
              "WHERE NOT EXISTS (",
              "  SELECT 1 FROM tmp_sake_catalog_aliases a JOIN sake s",
              f"    ON {sql_key('s.name')} = a.alias_key WHERE a.seq = src.seq",
              ");",
              ]
        lines += catalog_master_statements()
        lines += [
              "\n-- ORDER BY seqで北海道から都道府県コード順に採番。IDの固定値は使わない。",
              "-- 未確認の価格・度数・紹介文・画像はNULL。根拠のない診断タグは追加しない。",
              "INSERT INTO sake (brewery_id, sake_type_id, name, region, abv, price, description, image_url)",
              "SELECT b.brewery_id, t.sake_type_id, c.name, c.prefecture, c.abv, c.price, NULL, c.image_url",
              "FROM tmp_sake_catalog_candidates c",
              "JOIN tmp_sake_catalog_brewery_ids b ON b.brewery_key = c.brewery_key AND b.prefecture = c.prefecture",
              "JOIN tmp_sake_catalog_type_ids t ON t.sake_type = c.sake_type",
              "WHERE @sake_catalog_lock_acquired = 1 ORDER BY c.seq;",
              "SET @sake_catalog_inserted_rows = ROW_COUNT();",
              "CREATE TEMPORARY TABLE tmp_sake_catalog_inserted AS",
              "SELECT c.seq, s.id FROM tmp_sake_catalog_candidates c",
              f"JOIN sake s ON {sql_key('s.name')} = c.name_key",
              "WHERE @sake_catalog_lock_acquired = 1;",
              ]
    lines += identity_statements()
    if prices_only:
        lines += price_statements()
    elif not abv_text_only:
        lines += attribute_statements()
    else:
        lines += ["SET @sake_catalog_updated_rows = 0;"]
    if not abv_text_only:
        lines += price_detail_statements()
    if not prices_only:
        lines += abv_text_statements()
    lines += [
              "COMMIT;",
              "\nSELECT CASE WHEN @sake_catalog_lock_acquired = 1 THEN 'completed'",
              "            ELSE 'not_applied_lock_unavailable' END AS status,",
              "       @sake_catalog_inserted_rows AS inserted_products,",
              "       @sake_catalog_updated_rows AS filled_existing_products,",
              "       @sake_catalog_inserted_price_detail_rows AS inserted_price_details,",
              "       @sake_catalog_price_detail_rows AS filled_price_details,",
              "       @sake_catalog_abv_text_rows AS filled_abv_text_details;",
              "SELECT COUNT(*) AS source_products, COUNT(src.abv) AS verified_abv,",
              "       SUM(src.abv IS NULL AND src.abv_text IS NOT NULL) AS verified_abv_text,",
              "       COUNT(src.price) AS verified_prices, COUNT(src.image_url) AS verified_image_urls,",
              "       COUNT(m.id) AS unambiguously_matched_products",
              "FROM tmp_sake_catalog_source src LEFT JOIN tmp_sake_catalog_matches m ON m.seq = src.seq;",
              ]
    if not only_existing:
        lines += [
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
              ]
    else:
        lines += [
            "SELECT src.prefecture_code, src.name, src.brewery, 'product_identity_not_unique_or_mismatched' AS reason",
            "FROM tmp_sake_catalog_source src LEFT JOIN tmp_sake_catalog_matches m ON m.seq = src.seq",
            "WHERE m.id IS NULL ORDER BY src.seq;",
        ]
    if not prices_only:
        lines += [
              "SELECT s.id, s.name, src.abv_text AS official_abv_text,",
              "       'existing_text_or_invalid_or_mismatched_detail' AS reason",
              "FROM tmp_sake_catalog_source src JOIN tmp_sake_catalog_matches m ON m.seq = src.seq",
              "JOIN sake s ON s.id = m.id JOIN sake_page_details d ON d.sake_id = s.id",
              "JOIN tmp_sake_catalog_abv_pages p ON p.id = s.id",
              "WHERE src.abv IS NULL AND src.abv_text IS NOT NULL AND src.abv_text <> ''",
              "  AND COALESCE(p.valid_json AND JSON_TYPE(p.detail_json) = 'OBJECT'",
              "    AND JSON_TYPE(JSON_EXTRACT(p.detail_json, '$.id')) = 'INTEGER'",
              "    AND JSON_UNQUOTE(JSON_EXTRACT(p.detail_json, '$.id')) = CAST(s.id AS CHAR)",
              "    AND JSON_TYPE(JSON_EXTRACT(p.detail_json, '$.name')) = 'STRING'",
              "    AND JSON_UNQUOTE(JSON_EXTRACT(p.detail_json, '$.name')) COLLATE utf8mb4_bin = s.name COLLATE utf8mb4_bin",
              "    AND CASE WHEN JSON_VALID(d.detail_json) THEN",
              "    COALESCE(JSON_UNQUOTE(JSON_EXTRACT(d.detail_json, '$.abvText')) COLLATE utf8mb4_bin = src.abv_text COLLATE utf8mb4_bin, FALSE)",
              "    ELSE FALSE END, FALSE) = FALSE ORDER BY src.seq;",
        ]
    if not abv_text_only:
        lines += [
            "SELECT s.id, s.name, src.volume_ml AS verified_volume_ml, src.price AS verified_tax_included_yen,",
            "       'existing_price_or_invalid_or_mismatched_or_nonempty_detail' AS reason",
            "FROM tmp_sake_catalog_source src JOIN tmp_sake_catalog_matches m ON m.seq = src.seq",
            "JOIN sake s ON s.id = m.id LEFT JOIN sake_page_details d ON d.sake_id = s.id",
            "JOIN tmp_sake_catalog_price_pages p ON p.id = s.id",
            "WHERE src.price IS NOT NULL AND src.volume_ml IS NOT NULL",
            "  AND COALESCE(s.price = src.price AND p.valid_json AND JSON_TYPE(p.detail_json) = 'OBJECT'",
            "    AND JSON_TYPE(JSON_EXTRACT(p.detail_json, '$.id')) = 'INTEGER'",
            "    AND JSON_UNQUOTE(JSON_EXTRACT(p.detail_json, '$.id')) = CAST(s.id AS CHAR)",
            "    AND JSON_TYPE(JSON_EXTRACT(p.detail_json, '$.name')) = 'STRING'",
            "    AND JSON_UNQUOTE(JSON_EXTRACT(p.detail_json, '$.name')) COLLATE utf8mb4_bin = s.name COLLATE utf8mb4_bin",
            "    AND CASE WHEN JSON_VALID(COALESCE(d.detail_json, p.detail_json)) THEN",
            "      JSON_TYPE(JSON_EXTRACT(COALESCE(d.detail_json, p.detail_json), '$.prices')) = 'ARRAY'",
            "      AND JSON_CONTAINS(COALESCE(d.detail_json, p.detail_json), JSON_OBJECT('volume', CONCAT(src.volume_ml, 'ml'), 'yen', src.price), '$.prices')",
            "      ELSE FALSE END, FALSE) = FALSE ORDER BY src.seq;",
        ]
    lines += [
              "SELECT RELEASE_LOCK(@sake_catalog_lock_name) AS released_catalog_lock;",
              *[f"DROP TEMPORARY TABLE tmp_sake_catalog_{table};" for table in reversed(tables)],
              "",
              ]
    return "\n".join(lines)


def make_rebuild_sql(records):
    """MariaDB匿名blockで、FKを維持してsakeの全行を入れ直す。"""
    if not records:
        raise ValueError("再構築対象の商品がありません")
    total = len(records)
    columns = ("id", "brewery_id", "sake_type_id", "name", "region", "abv", "price", "image_url", "description", "created_at")
    children = ("favorites", "sake_tags", "sake_reviews", "sake_page_details")
    child_names = ", ".join(map(sql_string, children))
    table_names = ", ".join(map(sql_string, ("sake", "breweries", "sake_types", *children)))
    tables = ("source", "aliases", "candidates", "brewery_ids", "type_ids")
    cleanup = [f"DROP TEMPORARY TABLE IF EXISTS tmp_sake_catalog_{table};" for table in reversed(tables)]
    body = [
        "DECLARE v_lock_held BOOLEAN DEFAULT FALSE;",
        "DECLARE v_transaction_active BOOLEAN DEFAULT FALSE;",
        "DECLARE v_count BIGINT DEFAULT 0;",
        "DECLARE v_old_products BIGINT DEFAULT 0;",
        "DECLARE EXIT HANDLER FOR SQLEXCEPTION",
        "BEGIN",
        "  SET @sake_catalog_rebuild_status = 'failed';",
        "  IF v_transaction_active THEN ROLLBACK; END IF;",
        *["  " + line for line in cleanup],
        "  IF v_lock_held THEN DO RELEASE_LOCK(@sake_catalog_lock_name); END IF;",
        "  SET @sake_catalog_lock_acquired = 0;",
        "  RESIGNAL;",
        "END;",
        "IF DATABASE() IS NULL THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Select the application database first'; END IF;",
        "IF @@foreign_key_checks <> 1 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Rebuild requires FOREIGN_KEY_CHECKS=1'; END IF;",
        "SET @sake_catalog_lock_name = CONCAT('sake_catalog_', LEFT(SHA2(DATABASE(), 256), 48));",
        "SET @sake_catalog_lock_acquired = GET_LOCK(@sake_catalog_lock_name, 30);",
        "IF COALESCE(@sake_catalog_lock_acquired, 0) <> 1 THEN",
        "  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Catalog lock unavailable: nothing rebuilt';",
        "END IF;",
        "SET v_lock_held = TRUE;",
        "\n-- 親表はDROPせず、既存の参照外部キーを維持する。欠落時のみ作成。",
        "CREATE TABLE IF NOT EXISTS sake (",
        "  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT, brewery_id BIGINT UNSIGNED DEFAULT NULL,",
        "  sake_type_id BIGINT UNSIGNED NOT NULL, name VARCHAR(100) NOT NULL, region VARCHAR(50) DEFAULT NULL,",
        "  abv DECIMAL(4,1) DEFAULT NULL COMMENT 'アルコール度数(%)',",
        "  price INT UNSIGNED DEFAULT NULL COMMENT '価格(円)', image_url VARCHAR(255) DEFAULT NULL,",
        "  description TEXT DEFAULT NULL, created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,",
        "  PRIMARY KEY (id), UNIQUE KEY uk_sake_name (name), KEY idx_sake_brewery (brewery_id), KEY idx_sake_type (sake_type_id),",
        "  CONSTRAINT fk_sake_brewery FOREIGN KEY (brewery_id) REFERENCES breweries(id) ON DELETE SET NULL,",
        "  CONSTRAINT fk_sake_type FOREIGN KEY (sake_type_id) REFERENCES sake_types(id)",
        ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;",
        "CREATE TABLE IF NOT EXISTS sake_page_details (",
        "  sake_id BIGINT UNSIGNED NOT NULL PRIMARY KEY, detail_json LONGTEXT NOT NULL,",
        "  CONSTRAINT fk_sake_page_details FOREIGN KEY (sake_id) REFERENCES sake(id) ON DELETE CASCADE",
        ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;",
        "SELECT COUNT(*) INTO v_count FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE()",
        f"  AND TABLE_NAME IN ({table_names}) AND ENGINE = 'InnoDB';",
        "IF v_count <> 7 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Expected application tables must exist and use InnoDB'; END IF;",
        "SELECT COUNT(*) INTO v_count FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sake';",
        "IF v_count <> 10 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Unexpected sake columns: no data deleted'; END IF;",
        "SELECT COUNT(*) INTO v_count FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sake'",
        "  AND COLUMN_NAME IN (" + ", ".join(map(sql_string, columns)) + ");",
        "IF v_count <> 10 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Required sake columns are missing: no data deleted'; END IF;",
        "SELECT COUNT(*) INTO v_count FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sake'",
        "  AND COLUMN_NAME IN ('id', 'brewery_id', 'sake_type_id') AND DATA_TYPE = 'bigint' AND COLUMN_TYPE LIKE '%unsigned%';",
        "IF v_count <> 3 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Unexpected sake FK column types: no data deleted'; END IF;",
        "SELECT COUNT(*) INTO v_count FROM information_schema.REFERENTIAL_CONSTRAINTS",
        "WHERE UNIQUE_CONSTRAINT_SCHEMA = DATABASE() AND REFERENCED_TABLE_NAME = 'sake'",
        f"  AND (CONSTRAINT_SCHEMA <> DATABASE() OR TABLE_NAME NOT IN ({child_names}) OR DELETE_RULE <> 'CASCADE');",
        "IF v_count <> 0 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Unexpected dependent table or non-CASCADE FK: no data deleted'; END IF;",
        "SELECT COUNT(DISTINCT k.TABLE_NAME) INTO v_count FROM information_schema.KEY_COLUMN_USAGE k",
        "JOIN information_schema.REFERENTIAL_CONSTRAINTS r ON r.CONSTRAINT_SCHEMA = k.CONSTRAINT_SCHEMA",
        "  AND r.CONSTRAINT_NAME = k.CONSTRAINT_NAME AND r.TABLE_NAME = k.TABLE_NAME",
        "WHERE k.TABLE_SCHEMA = DATABASE() AND k.REFERENCED_TABLE_SCHEMA = DATABASE()",
        "  AND k.REFERENCED_TABLE_NAME = 'sake' AND k.REFERENCED_COLUMN_NAME = 'id' AND k.COLUMN_NAME = 'sake_id'",
        f"  AND k.TABLE_NAME IN ({child_names}) AND r.DELETE_RULE = 'CASCADE';",
        "IF v_count <> 4 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Expected four sake CASCADE relations are missing'; END IF;",
    ]
    for child in children:
        body += [
            f"SELECT COUNT(*) INTO v_count FROM {child} c LEFT JOIN sake s ON s.id = c.sake_id WHERE s.id IS NULL;",
            f"IF v_count <> 0 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Orphan rows in {child}: no data deleted'; END IF;",
        ]
    body += [
        "\n-- 列定義・順序のDDLは削除前。削除後の暗黙COMMITを避ける。",
        "ALTER TABLE sake",
        "  MODIFY COLUMN id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT FIRST,",
        "  MODIFY COLUMN brewery_id BIGINT UNSIGNED DEFAULT NULL AFTER id,",
        "  MODIFY COLUMN sake_type_id BIGINT UNSIGNED NOT NULL AFTER brewery_id,",
        "  MODIFY COLUMN name VARCHAR(100) NOT NULL AFTER sake_type_id,",
        "  MODIFY COLUMN region VARCHAR(50) DEFAULT NULL AFTER name,",
        "  MODIFY COLUMN abv DECIMAL(4,1) DEFAULT NULL COMMENT 'アルコール度数(%)' AFTER region,",
        "  MODIFY COLUMN price INT UNSIGNED DEFAULT NULL COMMENT '価格(円)' AFTER abv,",
        "  MODIFY COLUMN image_url VARCHAR(255) DEFAULT NULL AFTER price,",
        "  MODIFY COLUMN description TEXT DEFAULT NULL AFTER image_url,",
        "  MODIFY COLUMN created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP AFTER description;",
    ]
    body += source_staging_statements(records, tables)
    body += [
        f"IF (SELECT COUNT(*) FROM tmp_sake_catalog_source) <> {total} THEN",
        "  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Incomplete source catalog: no data deleted';",
        "END IF;",
        "START TRANSACTION;",
        "SET v_transaction_active = TRUE;",
        "CREATE TEMPORARY TABLE tmp_sake_catalog_candidates AS SELECT * FROM tmp_sake_catalog_source;",
    ]
    body += catalog_master_statements()
    body += [
        "SELECT COUNT(*) INTO v_count FROM tmp_sake_catalog_candidates c",
        "JOIN tmp_sake_catalog_brewery_ids b ON b.brewery_key = c.brewery_key AND b.prefecture = c.prefecture",
        "JOIN tmp_sake_catalog_type_ids t ON t.sake_type = c.sake_type;",
        f"IF v_count <> {total} THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Brewery/type conflict: original sake data preserved'; END IF;",
        "SELECT COUNT(*) INTO v_old_products FROM sake;",
        "DELETE FROM sake;",
        "INSERT INTO sake (id, brewery_id, sake_type_id, name, region, abv, price, image_url, description, created_at)",
        "SELECT c.seq, b.brewery_id, t.sake_type_id, c.name, c.prefecture, c.abv, c.price, c.image_url, NULL, CURRENT_TIMESTAMP",
        "FROM tmp_sake_catalog_candidates c",
        "JOIN tmp_sake_catalog_brewery_ids b ON b.brewery_key = c.brewery_key AND b.prefecture = c.prefecture",
        "JOIN tmp_sake_catalog_type_ids t ON t.sake_type = c.sake_type ORDER BY c.seq;",
        f"IF (SELECT COUNT(*) FROM sake) <> {total} OR (SELECT MIN(id) FROM sake) <> 1 OR (SELECT MAX(id) FROM sake) <> {total} THEN",
        "  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Rebuild row count/ID validation failed';",
        "END IF;",
        "\n-- 新しいIDに古い同梱紹介を結び付けないよう、全新商品の詳細を作成する。",
    ]
    for with_price in (True, False):
        prices = "JSON_ARRAY(JSON_OBJECT('volume', CONCAT(src.volume_ml, 'ml'), 'yen', src.price))" if with_price else "JSON_ARRAY()"
        body += [
            "INSERT INTO sake_page_details (sake_id, detail_json)",
            "SELECT s.id, JSON_OBJECT('id', s.id, 'name', s.name, 'brewery', src.brewery, 'introduction', '',",
            f"  'prices', {prices}, 'abvText', CASE WHEN src.abv IS NULL THEN src.abv_text ELSE NULL END,",
            "  'officialUrl', CASE WHEN src.price_source_url LIKE 'https://%' THEN src.price_source_url",
            "                      WHEN src.source_url LIKE 'https://%' THEN src.source_url ELSE '' END)",
            "FROM sake s JOIN tmp_sake_catalog_candidates src ON src.seq = s.id",
            "WHERE src.price IS " + ("NOT NULL;" if with_price else "NULL;"),
        ]
    body += [
        f"IF (SELECT COUNT(*) FROM sake_page_details) <> {total} THEN",
        "  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Rebuild detail count validation failed';",
        "END IF;",
        "COMMIT;",
        "SET v_transaction_active = FALSE;",
        "-- 明示IDの再投入成功後にだけ、次の自動採番をmax(id)+1に揃える。",
        "ALTER TABLE sake AUTO_INCREMENT = 1;",
        *cleanup,
        "DO RELEASE_LOCK(@sake_catalog_lock_name);",
        "SET v_lock_held = FALSE;",
        "SET @sake_catalog_lock_acquired = 0;",
        "SET @sake_catalog_removed_products = v_old_products;",
        "SET @sake_catalog_rebuild_status = 'rebuilt';",
    ]
    sql = "\n".join([
        "-- 危険: sakeの全商品と、連鎖するお気に入り・レビュー・タグ付け・詳細を削除して再登録する。",
        "-- MariaDB 10.11専用（BEGIN NOT ATOMIC）。phpMyAdminで対象アプリDBを選択して全体をインポート。",
        "-- 実行前に対象DBをバックアップし、アプリの登録・投稿操作を停止する。",
        "-- 会員・酒蔵/酒種/タグの既存マスタ・投稿・診断履歴は削除しない。IDは1から振り直す。",
        "-- FOREIGN_KEY_CHECKSは無効化しない。再投入の例外時はDELETEと連鎖削除をROLLBACKする。",
        "-- 列順補正と採番DDLはtransactionの外側。mysql/mariadb --forceは禁止。",
        "SET NAMES utf8mb4 COLLATE utf8mb4_unicode_ci;",
        "SET time_zone = '+09:00';",
        "SET @sake_catalog_rebuild_status = 'not_started';",
        "SET @sake_catalog_removed_products = NULL;",
        "SELECT DATABASE() AS target_database;",
        "DELIMITER $$",
        "BEGIN NOT ATOMIC",
        *["  " + line for line in body],
        "END$$",
        "DELIMITER ;",
        "-- phpMyAdminが未読の複数結果を残さないよう、結果表示は匿名blockの外側で個別に行う。",
        "SELECT @sake_catalog_rebuild_status AS status, DATABASE() AS target_database,",
        "  @sake_catalog_removed_products AS removed_products,",
        f"  IF(@sake_catalog_rebuild_status = 'rebuilt', {total}, NULL) AS inserted_products,",
        "  IF(@sake_catalog_rebuild_status = 'rebuilt', 1, NULL) AS first_id,",
        f"  IF(@sake_catalog_rebuild_status = 'rebuilt', {total}, NULL) AS last_id;",
        "SHOW COLUMNS FROM sake;",
        "",
    ])
    return "\n".join(line.rstrip() for line in sql.split("\n"))


def make_verify_sql(records):
    """登録後の状態だけを確認し、永続データや接続設定を変更しない。"""
    expected = {field: sum(row.get("_attributes", {}).get(field) is not None for row in records)
                for field in ("abv", "price", "image_url")}
    ranges = sum(row.get("_attributes", {}).get("abv") is None
                 and bool(row.get("_attributes", {}).get("abv_text")) for row in records)
    return "\n".join([
        "-- 読み取り専用。phpMyAdminで対象アプリDBを選択して実行。再構築・削除・更新は行わない。",
        "SELECT DATABASE() AS target_database, VERSION() AS server_version, @@foreign_key_checks AS foreign_key_checks;",
        f"-- 再構築直後の期待値: total_rows={len(records)}, first_id=1, last_id={len(records)}。",
        "SELECT COUNT(*) AS total_rows, MIN(id) AS first_id, MAX(id) AS last_id FROM sake;",
        f"-- 確認済み属性の期待値: abv={expected['abv']}, price={expected['price']}, image_url={expected['image_url']}。未確認値はNULL。",
        "SELECT COUNT(abv) AS abv_rows, COUNT(price) AS price_rows,",
        "  COUNT(NULLIF(image_url, '')) AS image_url_rows FROM sake;",
        f"-- 価格補完後の未確認価格の期待値: missing_price_rows={len(records) - expected['price']}。",
        "SELECT COUNT(*) AS missing_price_rows FROM sake WHERE price IS NULL OR price = 0;",
        f"-- 再構築直後の期待値: detail_rows={len(records)}。",
        "SELECT COUNT(*) AS detail_rows FROM sake_page_details;",
        f"-- 範囲表記追加後の期待値: abv_text_rows={ranges}。単一の数値へ換算しない。",
        "SELECT SUM(CASE WHEN JSON_VALID(detail_json) THEN",
        "  JSON_TYPE(JSON_EXTRACT(detail_json, '$.abvText')) = 'STRING'",
        "  AND NULLIF(TRIM(JSON_UNQUOTE(JSON_EXTRACT(detail_json, '$.abvText'))), '') IS NOT NULL",
        "  ELSE FALSE END) AS abv_text_rows FROM sake_page_details;",
        "SELECT s.id, s.name, JSON_UNQUOTE(JSON_EXTRACT(d.detail_json, '$.abvText')) AS official_abv_text",
        "FROM sake s JOIN sake_page_details d ON d.sake_id = s.id",
        "WHERE CASE WHEN JSON_VALID(d.detail_json) THEN",
        "  JSON_TYPE(JSON_EXTRACT(d.detail_json, '$.abvText')) = 'STRING'",
        "  AND NULLIF(TRIM(JSON_UNQUOTE(JSON_EXTRACT(d.detail_json, '$.abvText'))), '') IS NOT NULL",
        "  ELSE FALSE END ORDER BY s.id;",
        "-- id, brewery_id, sake_type_id, name, region, abv, price, image_url, description, created_atの順。",
        "SHOW COLUMNS FROM sake;",
        "-- 先頭は北海道の商品。",
        "SELECT id, name, region, abv, price, image_url FROM sake ORDER BY id LIMIT 10;",
        "",
    ])


def make_missing_prices_sql():
    """実DBの数値価格未登録行を表示する。データや接続設定は変更しない。"""
    regions = ", ".join(map(sql_string, PREFECTURES))
    region_order = f"FIELD(s.region, {regions})"
    return "\n".join([
        "-- 読み取り専用。phpMyAdminで現在のアプリDBを選択して実行する。",
        "-- NULL/0の数値価格を一覧化する。詳細JSONに価格がある場合は別列に表示する。",
        "-- 出典CSVのカタログ連番と、このSQLで返す実DBのidは別物。",
        "SELECT DATABASE() AS target_database, COUNT(*) AS total_products,",
        "  COALESCE(SUM(price IS NULL), 0) AS null_price_rows, COALESCE(SUM(price = 0), 0) AS zero_price_rows",
        "FROM sake;",
        "SELECT s.id, s.name, s.region, b.name AS brewery, s.price,",
        "  CASE WHEN JSON_VALID(d.detail_json) THEN JSON_EXTRACT(d.detail_json, '$.prices') ELSE NULL END AS saved_capacity_prices,",
        "  CASE WHEN JSON_VALID(d.detail_json) THEN JSON_UNQUOTE(JSON_EXTRACT(d.detail_json, '$.officialUrl')) ELSE NULL END AS official_url",
        "FROM sake s LEFT JOIN breweries b ON b.id = s.brewery_id",
        "LEFT JOIN sake_page_details d ON d.sake_id = s.id",
        "WHERE s.price IS NULL OR s.price = 0",
        f"ORDER BY CASE WHEN {region_order} = 0 THEN 48 ELSE {region_order} END, s.id;",
        "",
    ])


def make_price_sources(records, missing=False):
    """出典上の価格未確認一覧、または確認済み容量・税込価格の表示一覧。"""
    buffer = io.StringIO(newline="")
    common = ["catalog_sequence", "prefecture_code", "prefecture", "brewery", "name"]
    fields = (common + ["source_url", "reason", "record_checked_on", "notes"] if missing else
              common + ["volume_ml", "price", "price_display", "price_source_url", "price_checked_on", "notes"])
    writer = csv.DictWriter(buffer, fields, lineterminator="\n")
    writer.writeheader()
    for row in records:
        attributes = row.get("_attributes", {})
        price = attributes.get("price")
        if (price is None) != missing:
            continue
        result = {key: row[key] for key in ("prefecture", "brewery", "name")}
        result.update(catalog_sequence=row["_seq"], prefecture_code=f"{row['prefecture_code']:02d}",
                      notes=attributes.get("notes", row.get("source_note", "")))
        if missing:
            result.update(source_url=row["source_url"],
                          reason="今回の記録では容量と税込価格を確定できていない",
                          record_checked_on=attributes.get("checked_on", row["checked_on"]))
        else:
            volume = attributes["volume_ml"]
            result.update(volume_ml=volume, price=price, price_display=f"{volume}ml {price:,}円（税込）",
                          price_source_url=attributes["price_source_url"],
                          price_checked_on=attributes["price_checked_on"])
        writer.writerow(result)
    return buffer.getvalue()


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


def make_attribute_sources(records):
    buffer = io.StringIO(newline="")
    fields = ["prefecture_code", "prefecture", "brewery", "name", "abv", "abv_text", "abv_source_url",
              "price", "volume_ml", "price_tax_included", "price_source_url", "image_url", "image_source_url",
              "image_http_status", "image_content_type", "abv_checked_on", "price_checked_on", "image_checked_on",
              "checked_on", "notes", "source_file"]
    writer = csv.DictWriter(buffer, fields, lineterminator="\n")
    writer.writeheader()
    for row in records:
        attributes = row.get("_attributes")
        if attributes:
            writer.writerow({**{key: attributes.get(key, "") for key in fields},
                             "name": row["name"], "brewery": row["brewery"], "prefecture": row["prefecture"],
                             "prefecture_code": f"{row['prefecture_code']:02d}"})
    return buffer.getvalue()


def make_attribute_counts(records):
    buffer = io.StringIO(newline="")
    writer = csv.writer(buffer, lineterminator="\n")
    writer.writerow(["prefecture_code", "prefecture", "products", "abv", "abv_text_only", "price", "image_url", "all_three"])
    for code, prefecture in enumerate(PREFECTURES, 1):
        rows = [row.get("_attributes", {}) for row in records if row["prefecture_code"] == code]
        counts = [sum(row.get(field) is not None for row in rows) for field in ("abv", "price", "image_url")]
        ranges = sum(row.get("abv") is None and bool(row.get("abv_text")) for row in rows)
        complete = sum(all(row.get(field) is not None for field in ("abv", "price", "image_url")) for row in rows)
        writer.writerow([f"{code:02d}", prefecture, len(rows), counts[0], ranges, counts[1], counts[2], complete])
    return buffer.getvalue()


def make_import_zip(sql, filename, checked_on):
    buffer = io.BytesIO()
    date = datetime.date.fromisoformat(checked_on)
    entry = zipfile.ZipInfo(filename, date_time=(date.year, date.month, date.day, 0, 0, 0))
    entry.compress_type = zipfile.ZIP_DEFLATED
    entry.external_attr = 0o100644 << 16
    with zipfile.ZipFile(buffer, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        archive.writestr(entry, sql.encode("utf-8"))
    return buffer.getvalue()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="生成物を変更せず、出典JSONとの一致を確認")
    args = parser.parse_args()
    records = apply_enrichment(load_records(ROOT / "data/sake-catalog/regions"), ROOT / "data/sake-catalog/enrichment")
    attribute_records = [row for row in records if any(row.get("_attributes", {}).get(field) is not None
                                                     for field in ("abv", "abv_text", "price", "image_url"))]
    range_records = [row for row in records if row.get("_attributes", {}).get("abv") is None
                     and row.get("_attributes", {}).get("abv_text")]
    price_records = [row for row in records if row.get("_attributes", {}).get("price") is not None]
    catalog_sql = make_sql(records)
    attributes_sql = make_sql(attribute_records, attributes_only=True)
    rebuild_sql = make_sql(records, rebuild=True)
    abv_text_sql = make_sql(range_records, abv_text_only=True)
    prices_sql = make_sql(price_records, prices_only=True)
    checked_on = max([row["checked_on"] for row in records]
                     + [row["_attributes"][field] for row in records if row.get("_attributes")
                        for field in ("checked_on", "abv_checked_on", "price_checked_on", "image_checked_on")])
    outputs = {
        ROOT / "data/sake_catalog_prefecture_order.sql": catalog_sql,
        ROOT / "data/sake_catalog_attributes.sql": attributes_sql,
        ROOT / "data/sake_catalog_rebuild.sql": rebuild_sql,
        ROOT / "data/sake_catalog_verify.sql": make_verify_sql(records),
        ROOT / "data/sake_catalog_abv_text.sql": abv_text_sql,
        ROOT / "data/sake_catalog_prices.sql": prices_sql,
        ROOT / "data/sake_catalog_missing_prices.sql": make_missing_prices_sql(),
        ROOT / "data/sake-catalog/sources.csv": make_sources(records),
        ROOT / "data/sake-catalog/prefecture-counts.csv": make_counts(records),
        ROOT / "data/sake-catalog/attribute-sources.csv": make_attribute_sources(records),
        ROOT / "data/sake-catalog/attribute-counts.csv": make_attribute_counts(records),
        ROOT / "data/sake-catalog/missing-prices.csv": make_price_sources(records, missing=True),
        ROOT / "data/sake-catalog/verified-prices.csv": make_price_sources(records),
        ROOT / f"data/sake-catalog/import/sake_import_{len(records)}.sql.zip":
            make_import_zip(catalog_sql, f"sake_import_{len(records)}.sql", checked_on),
        ROOT / f"data/sake-catalog/import/sake_attributes_{len(records)}.sql.zip":
            make_import_zip(attributes_sql, f"sake_attributes_{len(records)}.sql", checked_on),
        ROOT / f"data/sake-catalog/import/sake_abv_text_{len(range_records)}.sql.zip":
            make_import_zip(abv_text_sql, f"sake_abv_text_{len(range_records)}.sql", checked_on),
        ROOT / "data/sake-catalog/import/sake_prices.sql.zip":
            make_import_zip(prices_sql, "sake_prices.sql", checked_on),
        ROOT / f"data/sake-catalog/import/sake_rebuild_{len(records)}.sql.zip":
            make_import_zip(rebuild_sql, f"sake_rebuild_{len(records)}.sql", checked_on),
    }
    for path, contents in outputs.items():
        payload = contents if isinstance(contents, bytes) else contents.encode("utf-8")
        if args.check:
            if not path.exists() or path.read_bytes() != payload:
                raise ValueError(f"生成物が古いか未作成です: {path.relative_to(ROOT)}")
        else:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(payload)
    print(f"{'検証' if args.check else '生成'}完了: {len(records)}商品 / "
          f"{len({r['prefecture_code'] for r in records})}都道府県 / "
          f"{len({r['_brewery_key'] for r in records})}酒蔵")
    print("属性確認: " + " / ".join(f"{field}={sum(row.get('_attributes', {}).get(field) is not None for row in records)}"
                                   for field in ("abv", "price", "image_url")))


if __name__ == "__main__":
    try:
        main()
    except (ValueError, KeyError, OSError) as error:
        print(f"エラー: {error}", file=sys.stderr)
        sys.exit(1)
