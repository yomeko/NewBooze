-- 既存DBに一度だけ実行する。既存会員は認証済みとして引き継ぐ。
ALTER TABLE users
  ADD COLUMN email_verified TINYINT(1) NOT NULL DEFAULT 1,
  ADD COLUMN verification_token_hash VARCHAR(64) DEFAULT NULL,
  ADD COLUMN verification_expires_at DATETIME DEFAULT NULL,
  ADD COLUMN verification_sent_at DATETIME DEFAULT NULL;
