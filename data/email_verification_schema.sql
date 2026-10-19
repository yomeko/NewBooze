-- 既存DBに一度だけ適用する。既存ユーザーのログインは継続して許可する。
ALTER TABLE users
  ADD COLUMN email_verified TINYINT(1) NOT NULL DEFAULT 1,
  ADD COLUMN email_verification_hash VARCHAR(64) NULL UNIQUE,
  ADD COLUMN email_verification_expires_at DATETIME NULL,
  ADD COLUMN email_verification_sent_at DATETIME NULL;
