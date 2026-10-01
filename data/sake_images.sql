-- 商品写真URLの登録（MySQL / MariaDB）
-- 公式商品ページ・画像URL確認日: 2026-10-01
-- 出典・未登録理由: data/SAKE_IMAGES.md
-- phpMyAdminでアプリの接続先DB（newbooze または issho_no_deai）を
-- 選択し、このファイルを実行してください。確認済みの7銘柄を更新します。
-- 残る3銘柄はNULLのままなので、既存URLは変わりません。
-- 例: SET @sake_image_1 = '/images/sake/dassai-45.jpg';
-- ローカル写真は src/main/resources/static/images/sake/ に配置します。
-- 外部の写真を使う場合は、利用できる画像のHTTPS URLを指定してください。
-- URLはimage_urlカラムの上限である255文字以内にしてください。
-- 未指定（NULL / 空文字）の銘柄と、ID・名前が一致しない行は更新しません。
-- 写真ファイルの配置・ダウンロードはこのSQLでは行いません。
-- 同じURLで再実行できます。既存URLの上書き前に変更前一覧を確認してください。

SET @sake_image_1 = 'https://dassai.com/files/dassai45.jpg'; -- 獺祭 純米大吟醸45
SET @sake_image_2 = NULL; -- 新政 No.6
SET @sake_image_3 = NULL; -- 十四代 本丸
SET @sake_image_4 = 'https://images.microcms-assets.io/assets/13f2c790a2684060bddbf97b3cad8e72/97247b5b47ec46e883b290c0aec3fd58/4GR_kok_icr_syuku.png'; -- 黒龍 いっちょらい
SET @sake_image_5 = 'https://kiyashow.com/asset/img/sake/jikon_25.webp'; -- 而今 特別純米
SET @sake_image_6 = NULL; -- 田酒 特別純米
SET @sake_image_7 = 'https://www.dewazakura.co.jp/item/img/oka-2-main.png'; -- 出羽桜 桜花吟醸
SET @sake_image_8 = 'https://www.hakkaisan.co.jp/wp-content/uploads/2024/05/tokubetu-honjyozou_720ml_-347x800.jpg'; -- 八海山 特別本醸造
SET @sake_image_9 = 'https://yucho-sake.jp/assets/img/product/kazenomori/Kaze_no_Mori_Akitsuho_657.png'; -- 風の森 秋津穂
SET @sake_image_10 = 'https://nabeshima.biz/sake/28.jpg'; -- 鍋島 特別純米

-- 変更前の登録内容
SELECT id, name, image_url FROM sake WHERE id BETWEEN 1 AND 10 ORDER BY id;

START TRANSACTION;

UPDATE sake AS s
JOIN (
  SELECT 1 AS id, '獺祭 純米大吟醸45' AS name, @sake_image_1 AS image_url
  UNION ALL SELECT 2, '新政 No.6', @sake_image_2
  UNION ALL SELECT 3, '十四代 本丸', @sake_image_3
  UNION ALL SELECT 4, '黒龍 いっちょらい', @sake_image_4
  UNION ALL SELECT 5, '而今 特別純米', @sake_image_5
  UNION ALL SELECT 6, '田酒 特別純米', @sake_image_6
  UNION ALL SELECT 7, '出羽桜 桜花吟醸', @sake_image_7
  UNION ALL SELECT 8, '八海山 特別本醸造', @sake_image_8
  UNION ALL SELECT 9, '風の森 秋津穂', @sake_image_9
  UNION ALL SELECT 10, '鍋島 特別純米', @sake_image_10
 ) AS photos ON photos.id = s.id AND photos.name = s.name
SET s.image_url = TRIM(photos.image_url)
WHERE photos.image_url IS NOT NULL
  AND CHAR_LENGTH(TRIM(photos.image_url)) BETWEEN 1 AND 255
  AND (TRIM(photos.image_url) LIKE 'https://%'
       OR (TRIM(photos.image_url) LIKE '/images/sake/%'
           AND TRIM(photos.image_url) NOT LIKE '%..%'));

SELECT ROW_COUNT() AS updated_rows;
COMMIT;

-- 登録結果（写真の読み込みはブラウザで確認してください）
SELECT id, name, image_url FROM sake WHERE id BETWEEN 1 AND 10 ORDER BY id;
