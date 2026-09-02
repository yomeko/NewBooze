-- マイページ（飲酒投稿・DM）追加用。既存のnewbooze DBに一度だけ実行する。
CREATE TABLE IF NOT EXISTS `drink_posts` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `user_id` bigint(20) NOT NULL,
  `sake_name` varchar(100) NOT NULL,
  `comment` varchar(500) DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT current_timestamp(),
  PRIMARY KEY (`id`),
  KEY `idx_drink_posts_user` (`user_id`),
  CONSTRAINT `drink_posts_ibfk_1` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE IF NOT EXISTS `direct_messages` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `sender_id` bigint(20) NOT NULL,
  `recipient_id` bigint(20) NOT NULL,
  `body` varchar(1000) NOT NULL,
  `created_at` datetime NOT NULL DEFAULT current_timestamp(),
  PRIMARY KEY (`id`),
  KEY `idx_dm_sender` (`sender_id`),
  KEY `idx_dm_recipient` (`recipient_id`),
  CONSTRAINT `direct_messages_ibfk_1` FOREIGN KEY (`sender_id`) REFERENCES `users` (`id`) ON DELETE CASCADE,
  CONSTRAINT `direct_messages_ibfk_2` FOREIGN KEY (`recipient_id`) REFERENCES `users` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE IF NOT EXISTS `drink_post_likes` (
  `user_id` bigint(20) NOT NULL,
  `post_id` bigint(20) NOT NULL,
  `created_at` datetime NOT NULL DEFAULT current_timestamp(),
  PRIMARY KEY (`user_id`, `post_id`),
  KEY `idx_post_likes_post` (`post_id`),
  CONSTRAINT `drink_post_likes_user_fk` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE,
  CONSTRAINT `drink_post_likes_post_fk` FOREIGN KEY (`post_id`) REFERENCES `drink_posts` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE IF NOT EXISTS `drink_post_reports` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `reporter_id` bigint(20) NOT NULL,
  `post_id` bigint(20) NOT NULL,
  `reason` varchar(30) NOT NULL,
  `created_at` datetime NOT NULL DEFAULT current_timestamp(),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_post_reporter` (`reporter_id`, `post_id`),
  KEY `idx_post_reports_post` (`post_id`),
  CONSTRAINT `drink_post_reports_user_fk` FOREIGN KEY (`reporter_id`) REFERENCES `users` (`id`) ON DELETE CASCADE,
  CONSTRAINT `drink_post_reports_post_fk` FOREIGN KEY (`post_id`) REFERENCES `drink_posts` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE IF NOT EXISTS `user_profile_images` (
  `user_id` bigint(20) NOT NULL,
  `image_data` mediumblob NOT NULL,
  `content_type` varchar(50) NOT NULL,
  `position_x` int NOT NULL DEFAULT 50,
  `position_y` int NOT NULL DEFAULT 50,
  `zoom` int NOT NULL DEFAULT 100,
  `updated_at` datetime NOT NULL DEFAULT current_timestamp() ON UPDATE current_timestamp(),
  PRIMARY KEY (`user_id`),
  CONSTRAINT `user_profile_images_ibfk_1` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

ALTER TABLE `user_profile_images`
  ADD COLUMN IF NOT EXISTS `position_x` int NOT NULL DEFAULT 50,
  ADD COLUMN IF NOT EXISTS `position_y` int NOT NULL DEFAULT 50;

ALTER TABLE `user_profile_images`
  ADD COLUMN IF NOT EXISTS `zoom` int NOT NULL DEFAULT 100;
