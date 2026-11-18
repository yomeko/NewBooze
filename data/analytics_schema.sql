-- 既存の接続先DBへ適用する追加SQL。DB選択は接続引数で指定し、既存データを変更・削除しない。
-- 公式リンクは銘柄と遷移時点の正のタグへ各1件。PVは日本時間の日付・クエリーを除いたパスごとに保存する。
-- 会員ID、IPアドレス、Cookie、検索語、参照元は保存しない。
CREATE TABLE IF NOT EXISTS analytics_sake_clicks (
  sake_id BIGINT UNSIGNED NOT NULL PRIMARY KEY,
  click_count BIGINT UNSIGNED NOT NULL DEFAULT 0,
  CONSTRAINT fk_analytics_sake_clicks_sake FOREIGN KEY (sake_id) REFERENCES sake(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS analytics_tag_clicks (
  tag_id BIGINT UNSIGNED NOT NULL,
  sake_id BIGINT UNSIGNED NOT NULL,
  click_count BIGINT UNSIGNED NOT NULL DEFAULT 0,
  PRIMARY KEY (tag_id, sake_id),
  KEY idx_analytics_tag_clicks_sake (sake_id),
  CONSTRAINT fk_analytics_tag_clicks_tag FOREIGN KEY (tag_id) REFERENCES tags(id) ON DELETE CASCADE,
  CONSTRAINT fk_analytics_tag_clicks_sake FOREIGN KEY (sake_id) REFERENCES sake(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS analytics_page_views (
  view_date DATE NOT NULL,
  page_path VARCHAR(255) NOT NULL,
  view_count BIGINT UNSIGNED NOT NULL DEFAULT 0,
  PRIMARY KEY (view_date, page_path)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
