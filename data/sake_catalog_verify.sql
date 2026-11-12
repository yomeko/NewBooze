-- 読み取り専用。phpMyAdminで対象アプリDBを選択して実行。再構築・削除・更新は行わない。
SELECT DATABASE() AS target_database, VERSION() AS server_version, @@foreign_key_checks AS foreign_key_checks;
-- 再構築直後の期待値: total_rows=2296, first_id=1, last_id=2296。
SELECT COUNT(*) AS total_rows, MIN(id) AS first_id, MAX(id) AS last_id FROM sake;
-- 確認済み属性の期待値: abv=1521, price=919, image_url=1914。未確認値はNULL。
SELECT COUNT(abv) AS abv_rows, COUNT(price) AS price_rows,
  COUNT(NULLIF(image_url, '')) AS image_url_rows FROM sake;
-- 再構築直後の期待値: detail_rows=2296。
SELECT COUNT(*) AS detail_rows FROM sake_page_details;
-- id, brewery_id, sake_type_id, name, region, abv, price, image_url, description, created_atの順。
SHOW COLUMNS FROM sake;
-- 先頭は北海道の商品。
SELECT id, name, region, abv, price, image_url FROM sake ORDER BY id LIMIT 10;
