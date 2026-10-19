-- 既存DBに一度だけ適用する。既存ユーザーのログインは継続して許可する。
-- DEFAULT 1は既存会員を利用可能な状態で保持するため。新規登録はServiceが0を明示する。
-- 生のトークンは保存せずハッシュを保持し、認証後はNULLに戻してリンクを使い捨てにする。
ALTER TABLE users
  ADD COLUMN email_verified TINYINT(1) NOT NULL DEFAULT 1,
  ADD COLUMN email_verification_hash VARCHAR(64) NULL UNIQUE,
  ADD COLUMN email_verification_expires_at DATETIME NULL,
  ADD COLUMN email_verification_sent_at DATETIME NULL;
