-- MariaDB: preserves private reviews created before public posting was available.
ALTER TABLE sake_reviews ADD COLUMN IF NOT EXISTS published BOOLEAN NOT NULL DEFAULT FALSE;
CREATE INDEX IF NOT EXISTS idx_reviews_public ON sake_reviews (sake_id, published, updated_at, user_id);
