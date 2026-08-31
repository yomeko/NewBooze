-- 既存のnewbooze DBに一度だけ適用する。
ALTER TABLE users
  ADD COLUMN temporary_password TINYINT(1) NOT NULL DEFAULT 0 AFTER password_hash;
