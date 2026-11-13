-- 読み取り専用。phpMyAdminで現在のアプリDBを選択して実行する。
-- NULL/0の数値価格を一覧化する。詳細JSONに価格がある場合は別列に表示する。
-- 出典CSVのカタログ連番と、このSQLで返す実DBのidは別物。
SELECT DATABASE() AS target_database, COUNT(*) AS total_products,
  COALESCE(SUM(price IS NULL), 0) AS null_price_rows, COALESCE(SUM(price = 0), 0) AS zero_price_rows
FROM sake;
SELECT s.id, s.name, s.region, b.name AS brewery, s.price,
  CASE WHEN JSON_VALID(d.detail_json) THEN JSON_EXTRACT(d.detail_json, '$.prices') ELSE NULL END AS saved_capacity_prices,
  CASE WHEN JSON_VALID(d.detail_json) THEN JSON_UNQUOTE(JSON_EXTRACT(d.detail_json, '$.officialUrl')) ELSE NULL END AS official_url
FROM sake s LEFT JOIN breweries b ON b.id = s.brewery_id
LEFT JOIN sake_page_details d ON d.sake_id = s.id
WHERE s.price IS NULL OR s.price = 0
ORDER BY CASE WHEN FIELD(s.region, '北海道', '青森県', '岩手県', '宮城県', '秋田県', '山形県', '福島県', '茨城県', '栃木県', '群馬県', '埼玉県', '千葉県', '東京都', '神奈川県', '新潟県', '富山県', '石川県', '福井県', '山梨県', '長野県', '岐阜県', '静岡県', '愛知県', '三重県', '滋賀県', '京都府', '大阪府', '兵庫県', '奈良県', '和歌山県', '鳥取県', '島根県', '岡山県', '広島県', '山口県', '徳島県', '香川県', '愛媛県', '高知県', '福岡県', '佐賀県', '長崎県', '熊本県', '大分県', '宮崎県', '鹿児島県', '沖縄県') = 0 THEN 48 ELSE FIELD(s.region, '北海道', '青森県', '岩手県', '宮城県', '秋田県', '山形県', '福島県', '茨城県', '栃木県', '群馬県', '埼玉県', '千葉県', '東京都', '神奈川県', '新潟県', '富山県', '石川県', '福井県', '山梨県', '長野県', '岐阜県', '静岡県', '愛知県', '三重県', '滋賀県', '京都府', '大阪府', '兵庫県', '奈良県', '和歌山県', '鳥取県', '島根県', '岡山県', '広島県', '山口県', '徳島県', '香川県', '愛媛県', '高知県', '福岡県', '佐賀県', '長崎県', '熊本県', '大分県', '宮崎県', '鹿児島県', '沖縄県') END, s.id;
