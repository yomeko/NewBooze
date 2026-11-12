-- 接続先DBを確認してから適用する。既存の会員・銘柄・記録は変更しない。
CREATE TABLE IF NOT EXISTS `outbound_clicks` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `user_id` BIGINT UNSIGNED DEFAULT NULL,
  `logged_in` TINYINT(1) NOT NULL,
  `sake_id` BIGINT UNSIGNED DEFAULT NULL,
  `target_name` VARCHAR(100) NOT NULL,
  `source` VARCHAR(16) NOT NULL,
  `link_kind` VARCHAR(16) NOT NULL,
  `destination_url` VARCHAR(1000) NOT NULL,
  `clicked_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_outbound_clicks_user_time` (`user_id`, `clicked_at`),
  KEY `idx_outbound_clicks_time` (`clicked_at`),
  KEY `idx_outbound_clicks_sake` (`sake_id`),
  CONSTRAINT `fk_outbound_clicks_user`
    FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE SET NULL,
  CONSTRAINT `fk_outbound_clicks_sake`
    FOREIGN KEY (`sake_id`) REFERENCES `sake` (`id`) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
