-- ============================================================
-- NEWBOOZE 好み診断機能 初期データ投入用SQL
--
-- 【使い方】
--   phpMyAdmin（XAMPP）で newbooze データベースを選択した状態で
--   「SQL」タブからこのファイルの内容をそのまま実行してください。
--
-- 【前提】
--   newbooze.sql（DDL）を実行済みで、tags / diagnosis_questions /
--   diagnosis_choices / choice_tags が空の状態であること。
--   （既にデータが入っている場合は重複INSERTになるため、
--    先にTRUNCATEするか、実行前に中身を確認してください）
--
-- 【タグ名について】
--   ここで登録するタグ名（フルーティー／旨口／辛口／軽快／濃醇／酸味／甘口）は、
--   現状 SakeCatalogService（暫定のインメモリ地酒データ）で使われているタグ名と
--   一致させています。将来、濵田担当の実データ（sake_tags）を投入する際も、
--   同じタグ名の体系に合わせるか、DiagnosisService側の突き合わせロジックを
--   見直す必要があります。
-- ============================================================

-- --- 1. タグマスタ ---
INSERT INTO tags (name, category) VALUES
 ('フルーティー', '香り'),
 ('旨口',       '味わい'),
 ('辛口',       '味わい'),
 ('軽快',       '口当たり'),
 ('濃醇',       '口当たり'),
 ('酸味',       '味わい'),
 ('甘口',       '味わい');

-- --- 2. 診断設問 ---
INSERT INTO diagnosis_questions (question_text, sort_order) VALUES
 ('まず、気分に近い一杯は？', 1),
 ('口当たりの好みは？',       2),
 ('合わせたい場面は？',       3),
 ('甘さの印象は？',           4);

-- --- 3. 診断選択肢 ---
-- Q1: まず、気分に近い一杯は？
INSERT INTO diagnosis_choices (question_id, choice_text)
SELECT id, '果物のように華やかな香り' FROM diagnosis_questions WHERE question_text = 'まず、気分に近い一杯は？';
INSERT INTO diagnosis_choices (question_id, choice_text)
SELECT id, '米のうまみをしっかり感じたい' FROM diagnosis_questions WHERE question_text = 'まず、気分に近い一杯は？';
INSERT INTO diagnosis_choices (question_id, choice_text)
SELECT id, 'すっきりシャープに飲みたい' FROM diagnosis_questions WHERE question_text = 'まず、気分に近い一杯は？';

-- Q2: 口当たりの好みは？
INSERT INTO diagnosis_choices (question_id, choice_text)
SELECT id, '軽やかでさらり' FROM diagnosis_questions WHERE question_text = '口当たりの好みは？';
INSERT INTO diagnosis_choices (question_id, choice_text)
SELECT id, 'ふくよかで飲みごたえあり' FROM diagnosis_questions WHERE question_text = '口当たりの好みは？';
INSERT INTO diagnosis_choices (question_id, choice_text)
SELECT id, 'きゅっと爽やかな酸味' FROM diagnosis_questions WHERE question_text = '口当たりの好みは？';

-- Q3: 合わせたい場面は？
INSERT INTO diagnosis_choices (question_id, choice_text)
SELECT id, '乾杯・プレゼント' FROM diagnosis_questions WHERE question_text = '合わせたい場面は？';
INSERT INTO diagnosis_choices (question_id, choice_text)
SELECT id, '食事とゆっくり' FROM diagnosis_questions WHERE question_text = '合わせたい場面は？';
INSERT INTO diagnosis_choices (question_id, choice_text)
SELECT id, '暑い日に冷やして' FROM diagnosis_questions WHERE question_text = '合わせたい場面は？';

-- Q4: 甘さの印象は？
INSERT INTO diagnosis_choices (question_id, choice_text)
SELECT id, 'やさしい甘みが好き' FROM diagnosis_questions WHERE question_text = '甘さの印象は？';
INSERT INTO diagnosis_choices (question_id, choice_text)
SELECT id, '甘すぎないバランス派' FROM diagnosis_questions WHERE question_text = '甘さの印象は？';
INSERT INTO diagnosis_choices (question_id, choice_text)
SELECT id, 'キレのある辛口派' FROM diagnosis_questions WHERE question_text = '甘さの印象は？';

-- --- 4. 選択肢タグ（choice_tags）---
-- weight: その選択肢を選んだ際に、対応タグへ加算する重み

-- 果物のように華やかな香り -> フルーティー:5
INSERT INTO choice_tags (choice_id, tag_id, weight)
SELECT c.id, t.id, 5 FROM diagnosis_choices c, tags t
WHERE c.choice_text = '果物のように華やかな香り' AND t.name = 'フルーティー';

-- 米のうまみをしっかり感じたい -> 旨口:5
INSERT INTO choice_tags (choice_id, tag_id, weight)
SELECT c.id, t.id, 5 FROM diagnosis_choices c, tags t
WHERE c.choice_text = '米のうまみをしっかり感じたい' AND t.name = '旨口';

-- すっきりシャープに飲みたい -> 辛口:5
INSERT INTO choice_tags (choice_id, tag_id, weight)
SELECT c.id, t.id, 5 FROM diagnosis_choices c, tags t
WHERE c.choice_text = 'すっきりシャープに飲みたい' AND t.name = '辛口';

-- 軽やかでさらり -> 軽快:5
INSERT INTO choice_tags (choice_id, tag_id, weight)
SELECT c.id, t.id, 5 FROM diagnosis_choices c, tags t
WHERE c.choice_text = '軽やかでさらり' AND t.name = '軽快';

-- ふくよかで飲みごたえあり -> 濃醇:5
INSERT INTO choice_tags (choice_id, tag_id, weight)
SELECT c.id, t.id, 5 FROM diagnosis_choices c, tags t
WHERE c.choice_text = 'ふくよかで飲みごたえあり' AND t.name = '濃醇';

-- きゅっと爽やかな酸味 -> 酸味:5
INSERT INTO choice_tags (choice_id, tag_id, weight)
SELECT c.id, t.id, 5 FROM diagnosis_choices c, tags t
WHERE c.choice_text = 'きゅっと爽やかな酸味' AND t.name = '酸味';

-- 乾杯・プレゼント -> フルーティー:3, 甘口:2
INSERT INTO choice_tags (choice_id, tag_id, weight)
SELECT c.id, t.id, 3 FROM diagnosis_choices c, tags t
WHERE c.choice_text = '乾杯・プレゼント' AND t.name = 'フルーティー';
INSERT INTO choice_tags (choice_id, tag_id, weight)
SELECT c.id, t.id, 2 FROM diagnosis_choices c, tags t
WHERE c.choice_text = '乾杯・プレゼント' AND t.name = '甘口';

-- 食事とゆっくり -> 旨口:3, 辛口:2
INSERT INTO choice_tags (choice_id, tag_id, weight)
SELECT c.id, t.id, 3 FROM diagnosis_choices c, tags t
WHERE c.choice_text = '食事とゆっくり' AND t.name = '旨口';
INSERT INTO choice_tags (choice_id, tag_id, weight)
SELECT c.id, t.id, 2 FROM diagnosis_choices c, tags t
WHERE c.choice_text = '食事とゆっくり' AND t.name = '辛口';

-- 暑い日に冷やして -> 軽快:3, 酸味:3
INSERT INTO choice_tags (choice_id, tag_id, weight)
SELECT c.id, t.id, 3 FROM diagnosis_choices c, tags t
WHERE c.choice_text = '暑い日に冷やして' AND t.name = '軽快';
INSERT INTO choice_tags (choice_id, tag_id, weight)
SELECT c.id, t.id, 3 FROM diagnosis_choices c, tags t
WHERE c.choice_text = '暑い日に冷やして' AND t.name = '酸味';

-- やさしい甘みが好き -> 甘口:5
INSERT INTO choice_tags (choice_id, tag_id, weight)
SELECT c.id, t.id, 5 FROM diagnosis_choices c, tags t
WHERE c.choice_text = 'やさしい甘みが好き' AND t.name = '甘口';

-- 甘すぎないバランス派 -> 旨口:3, 酸味:2
INSERT INTO choice_tags (choice_id, tag_id, weight)
SELECT c.id, t.id, 3 FROM diagnosis_choices c, tags t
WHERE c.choice_text = '甘すぎないバランス派' AND t.name = '旨口';
INSERT INTO choice_tags (choice_id, tag_id, weight)
SELECT c.id, t.id, 2 FROM diagnosis_choices c, tags t
WHERE c.choice_text = '甘すぎないバランス派' AND t.name = '酸味';

-- キレのある辛口派 -> 辛口:5
INSERT INTO choice_tags (choice_id, tag_id, weight)
SELECT c.id, t.id, 5 FROM diagnosis_choices c, tags t
WHERE c.choice_text = 'キレのある辛口派' AND t.name = '辛口';
