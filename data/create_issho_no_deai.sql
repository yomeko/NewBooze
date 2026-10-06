-- 一升の出会い：既存DBを残して、新しいDBを作成する。
-- 同名DBが存在する場合はエラーで停止する。mysql --force は使用しない。
-- 既存DBに対するDROP/ALTER/UPDATEは行わない。
SET NAMES utf8mb4;
SET time_zone = '+09:00';

CREATE DATABASE `issho_no_deai`
  CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;
USE `issho_no_deai`;

SET FOREIGN_KEY_CHECKS = 1;

-- ---------- Master tables ----------

CREATE TABLE `users` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `name` VARCHAR(50) NOT NULL,
  `email` VARCHAR(255) NOT NULL,
  `password_hash` VARCHAR(255) NOT NULL,
  `admin` TINYINT(1) NOT NULL DEFAULT 0,
  `temporary_password` TINYINT(1) NOT NULL DEFAULT 0,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_users_email` (`email`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `breweries` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `name` VARCHAR(100) NOT NULL,
  `prefecture` VARCHAR(50) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_breweries_name` (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `sake_types` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `name` VARCHAR(50) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_sake_types_name` (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `tags` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `name` VARCHAR(50) NOT NULL,
  `category` VARCHAR(30) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tags_name` (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `diagnosis_questions` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `question_text` VARCHAR(255) NOT NULL,
  `sort_order` INT NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_diagnosis_questions_text` (`question_text`),
  UNIQUE KEY `uk_diagnosis_questions_sort` (`sort_order`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------- Tables depending on master tables ----------

CREATE TABLE `sake` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `brewery_id` BIGINT UNSIGNED DEFAULT NULL,
  `sake_type_id` BIGINT UNSIGNED NOT NULL,
  `name` VARCHAR(100) NOT NULL,
  `region` VARCHAR(50) DEFAULT NULL,
  `abv` DECIMAL(4,1) DEFAULT NULL COMMENT 'アルコール度数(%)',
  `price` INT UNSIGNED DEFAULT NULL COMMENT '価格(円)',
  `description` TEXT DEFAULT NULL,
  `image_url` VARCHAR(255) DEFAULT NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_sake_name` (`name`),
  KEY `idx_sake_brewery` (`brewery_id`),
  KEY `idx_sake_type` (`sake_type_id`),
  CONSTRAINT `fk_sake_brewery`
    FOREIGN KEY (`brewery_id`) REFERENCES `breweries` (`id`) ON DELETE SET NULL,
  CONSTRAINT `fk_sake_type`
    FOREIGN KEY (`sake_type_id`) REFERENCES `sake_types` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS sake_page_details (
  sake_id BIGINT UNSIGNED NOT NULL PRIMARY KEY,
  detail_json LONGTEXT NOT NULL,
  CONSTRAINT fk_sake_page_details FOREIGN KEY (sake_id) REFERENCES sake(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `diagnosis_choices` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `question_id` BIGINT UNSIGNED NOT NULL,
  `choice_text` VARCHAR(255) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_diagnosis_choice` (`question_id`, `choice_text`),
  CONSTRAINT `fk_diagnosis_choice_question`
    FOREIGN KEY (`question_id`) REFERENCES `diagnosis_questions` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `diagnosis_sessions` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `user_id` BIGINT UNSIGNED NOT NULL,
  `taken_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_diagnosis_sessions_user` (`user_id`),
  CONSTRAINT `fk_diagnosis_session_user`
    FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `drink_posts` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `user_id` BIGINT UNSIGNED NOT NULL,
  `sake_name` VARCHAR(100) NOT NULL,
  `comment` VARCHAR(500) DEFAULT NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_drink_posts_user` (`user_id`),
  CONSTRAINT `fk_drink_post_user`
    FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `user_profile_images` (
  `user_id` BIGINT UNSIGNED NOT NULL,
  `image_data` MEDIUMBLOB NOT NULL,
  `content_type` VARCHAR(50) NOT NULL,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `position_x` INT NOT NULL DEFAULT 50,
  `position_y` INT NOT NULL DEFAULT 50,
  `zoom` INT NOT NULL DEFAULT 100,
  PRIMARY KEY (`user_id`),
  CONSTRAINT `fk_user_profile_image_user`
    FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------- Junction and activity tables ----------

CREATE TABLE `choice_tags` (
  `choice_id` BIGINT UNSIGNED NOT NULL,
  `tag_id` BIGINT UNSIGNED NOT NULL,
  `weight` TINYINT UNSIGNED NOT NULL DEFAULT 1,
  PRIMARY KEY (`choice_id`, `tag_id`),
  KEY `idx_choice_tags_tag` (`tag_id`),
  CONSTRAINT `fk_choice_tags_choice`
    FOREIGN KEY (`choice_id`) REFERENCES `diagnosis_choices` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_choice_tags_tag`
    FOREIGN KEY (`tag_id`) REFERENCES `tags` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `diagnosis_answers` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `session_id` BIGINT UNSIGNED NOT NULL,
  `question_id` BIGINT UNSIGNED NOT NULL,
  `choice_id` BIGINT UNSIGNED NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_diagnosis_answer_session_question` (`session_id`, `question_id`),
  KEY `idx_diagnosis_answers_question` (`question_id`),
  KEY `idx_diagnosis_answers_choice` (`choice_id`),
  CONSTRAINT `fk_diagnosis_answer_session`
    FOREIGN KEY (`session_id`) REFERENCES `diagnosis_sessions` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_diagnosis_answer_question`
    FOREIGN KEY (`question_id`) REFERENCES `diagnosis_questions` (`id`),
  CONSTRAINT `fk_diagnosis_answer_choice`
    FOREIGN KEY (`choice_id`) REFERENCES `diagnosis_choices` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `drink_post_likes` (
  `user_id` BIGINT UNSIGNED NOT NULL,
  `post_id` BIGINT UNSIGNED NOT NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`user_id`, `post_id`),
  KEY `idx_drink_post_likes_post` (`post_id`),
  CONSTRAINT `fk_drink_post_like_user`
    FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_drink_post_like_post`
    FOREIGN KEY (`post_id`) REFERENCES `drink_posts` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `drink_post_reports` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `reporter_id` BIGINT UNSIGNED NOT NULL,
  `post_id` BIGINT UNSIGNED NOT NULL,
  `reason` VARCHAR(30) NOT NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_drink_post_reporter` (`reporter_id`, `post_id`),
  KEY `idx_drink_post_reports_post` (`post_id`),
  CONSTRAINT `fk_drink_post_report_user`
    FOREIGN KEY (`reporter_id`) REFERENCES `users` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_drink_post_report_post`
    FOREIGN KEY (`post_id`) REFERENCES `drink_posts` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `favorites` (
  `user_id` BIGINT UNSIGNED NOT NULL,
  `sake_id` BIGINT UNSIGNED NOT NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`user_id`, `sake_id`),
  KEY `idx_favorites_sake` (`sake_id`),
  CONSTRAINT `fk_favorite_user`
    FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_favorite_sake`
    FOREIGN KEY (`sake_id`) REFERENCES `sake` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `sake_tags` (
  `sake_id` BIGINT UNSIGNED NOT NULL,
  `tag_id` BIGINT UNSIGNED NOT NULL,
  `score` TINYINT UNSIGNED NOT NULL DEFAULT 3,
  PRIMARY KEY (`sake_id`, `tag_id`),
  KEY `idx_sake_tags_tag` (`tag_id`),
  CONSTRAINT `fk_sake_tags_sake`
    FOREIGN KEY (`sake_id`) REFERENCES `sake` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_sake_tags_tag`
    FOREIGN KEY (`tag_id`) REFERENCES `tags` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `user_preferences` (
  `user_id` BIGINT UNSIGNED NOT NULL,
  `tag_id` BIGINT UNSIGNED NOT NULL,
  `score` INT NOT NULL DEFAULT 0,
  PRIMARY KEY (`user_id`, `tag_id`),
  KEY `idx_user_preferences_tag` (`tag_id`),
  CONSTRAINT `fk_user_preference_user`
    FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_user_preference_tag`
    FOREIGN KEY (`tag_id`) REFERENCES `tags` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE sake_reviews (
  user_id BIGINT UNSIGNED NOT NULL,
  sake_id BIGINT UNSIGNED NOT NULL,
  published BOOLEAN NOT NULL DEFAULT FALSE,
  rating TINYINT UNSIGNED NOT NULL,
  comment VARCHAR(500) NOT NULL DEFAULT '',
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (user_id, sake_id),
  KEY idx_reviews_public (sake_id, published, updated_at, user_id),
  CONSTRAINT fk_sake_reviews_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
  CONSTRAINT fk_sake_reviews_sake FOREIGN KEY (sake_id) REFERENCES sake(id) ON DELETE CASCADE,
  CONSTRAINT chk_sake_reviews_rating CHECK (rating BETWEEN 1 AND 5)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------- Initial master and diagnosis data ----------

START TRANSACTION;

INSERT INTO `sake_types` (`id`, `name`) VALUES
  (1, '純米大吟醸'), (2, '生酒'), (3, '本醸造'), (4, '吟醸'),
  (5, '特別純米'), (6, '特別本醸造'), (7, '純米酒');

INSERT INTO `tags` (`id`, `name`, `category`) VALUES
  (1, 'フルーティー', '香り'), (2, '旨口', '味わい'), (3, '辛口', '味わい'),
  (4, '軽快', '口当たり'), (5, '濃醇', '口当たり'), (6, '酸味', '味わい'), (7, '甘口', '味わい');

INSERT INTO `diagnosis_questions` (`id`, `question_text`, `sort_order`) VALUES
  (1, 'まず、気分に近い一杯は？', 1),
  (2, '口当たりの好みは？', 2),
  (3, '合わせたい場面は？', 3),
  (4, '甘さの印象は？', 4);

INSERT INTO `diagnosis_choices` (`id`, `question_id`, `choice_text`) VALUES
  (1, 1, '果物のように華やかな香り'),
  (2, 1, '米のうまみをしっかり感じたい'),
  (3, 1, 'すっきりシャープに飲みたい'),
  (4, 2, '軽やかでさらり'),
  (5, 2, 'ふくよかで飲みごたえあり'),
  (6, 2, 'きゅっと爽やかな酸味'),
  (7, 3, '乾杯・プレゼント'),
  (8, 3, '食事とゆっくり'),
  (9, 3, '暑い日に冷やして'),
  (10, 4, 'やさしい甘みが好き'),
  (11, 4, '甘すぎないバランス派'),
  (12, 4, 'キレのある辛口派');

INSERT INTO `choice_tags` (`choice_id`, `tag_id`, `weight`) VALUES
  (1,1,5), (2,2,5), (3,3,5), (4,4,5), (5,5,5), (6,6,5),
  (7,1,3), (7,7,2), (8,2,3), (8,3,2), (9,4,3), (9,6,3),
  (10,7,5), (11,2,3), (11,6,2), (12,3,5);

-- ---------- Sake catalog demo data ----------

INSERT INTO `sake` (`id`, `sake_type_id`, `name`, `region`, `abv`, `price`, `description`) VALUES
  (1, 1, '獺祭 純米大吟醸45', '山口県', 16.0, 2180, '華やかな香りと、透明感のあるやわらかな甘みが特徴です。'),
  (2, 2, '新政 No.6', '秋田県', 13.0, 2500, '爽やかな酸味とみずみずしい口当たりを楽しめる生酒です。'),
  (3, 3, '十四代 本丸', '山形県', 15.0, 3200, 'やさしい旨みと上品な甘みが広がる、なめらかな味わいです。'),
  (4, 4, '黒龍 いっちょらい', '福井県', 15.0, 1650, 'すっきりとした切れ味と、穏やかな吟醸香のバランスが魅力です。'),
  (5, 5, '而今 特別純米', '三重県', 16.0, 2800, '果実感のある香りに、米のふくらみと心地よい余韻が続きます。'),
  (6, 5, '田酒 特別純米', '青森県', 16.0, 1900, '米の旨みをしっかり感じられる、落ち着いた食中酒です。'),
  (7, 4, '出羽桜 桜花吟醸', '山形県', 15.0, 1500, '華やかな香りと軽やかな飲み口で、日本酒入門にもおすすめです。'),
  (8, 6, '八海山 特別本醸造', '新潟県', 15.5, 1400, '淡麗でキレが良く、料理に寄り添うすっきりした味わいです。'),
  (9, 7, '風の森 秋津穂', '奈良県', 17.0, 1600, '微発泡感と鮮やかな酸味を持つ、瑞々しい純米酒です。'),
  (10, 5, '鍋島 特別純米', '佐賀県', 15.0, 1800, 'ジューシーな甘みとほどよい酸味を備えた、親しみやすい一本です。');

INSERT INTO `sake_tags` (`sake_id`, `tag_id`, `score`) VALUES
  (1,1,5), (1,7,4), (1,4,3),
  (2,6,5), (2,1,4), (2,4,4),
  (3,7,5), (3,2,4), (3,1,3),
  (4,3,4), (4,4,5), (4,1,2),
  (5,1,4), (5,2,4), (5,6,3),
  (6,2,5), (6,3,3), (6,5,4),
  (7,1,5), (7,4,4), (7,7,3),
  (8,3,5), (8,4,4), (8,2,2),
  (9,6,5), (9,1,3), (9,4,4),
  (10,7,4), (10,6,4), (10,2,3);

COMMIT;

-- No sample users or activity data are inserted. The application creates them.
