-- 既存会員・パスワードを保持したまま、再設定リンク用の列だけを追加する。
-- 接続先とバックアップを確認し、未適用のDBに一度だけ実行する。
-- USEは指定しない。DB初期化や既存データの削除は行わない。
ALTER TABLE users
    ADD COLUMN password_reset_hash VARCHAR(64) NULL,
    ADD COLUMN password_reset_expires_at DATETIME NULL,
    ADD COLUMN password_reset_sent_at DATETIME NULL,
    ADD UNIQUE KEY uk_users_password_reset_hash (password_reset_hash);
