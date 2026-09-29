-- 一升の出会い：不足している酒蔵マスタだけを作成する。
-- phpMyAdminのSQLタブ、またはMySQLクライアントから実行する。
-- 既存テーブル・データは変更しない。既存定義の修正や酒蔵データの復元は行わない。
-- idのUNSIGNEDは現行のsake.brewery_idと一致させている。
USE `newbooze`;

CREATE TABLE IF NOT EXISTS `breweries` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `name` VARCHAR(100) NOT NULL,
  `prefecture` VARCHAR(50) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_breweries_name` (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

SHOW CREATE TABLE `breweries`;
