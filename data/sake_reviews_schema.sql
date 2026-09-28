-- Existing product/user data is retained. Run once before starting the updated app.
CREATE TABLE IF NOT EXISTS sake_reviews (
  user_id BIGINT UNSIGNED NOT NULL,
  sake_id BIGINT UNSIGNED NOT NULL,
  published BOOLEAN NOT NULL DEFAULT FALSE,
  rating TINYINT UNSIGNED NOT NULL,
  comment VARCHAR(500) NOT NULL DEFAULT '',
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (user_id, sake_id),
  CONSTRAINT fk_sake_reviews_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
  CONSTRAINT fk_sake_reviews_sake FOREIGN KEY (sake_id) REFERENCES sake(id) ON DELETE CASCADE,
  CONSTRAINT chk_sake_reviews_rating CHECK (rating BETWEEN 1 AND 5)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
