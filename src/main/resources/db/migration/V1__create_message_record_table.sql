CREATE TABLE IF NOT EXISTS `message_record` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `scene_type` VARCHAR(32) NOT NULL,
    `conversation_id` VARCHAR(64) NOT NULL,
    `conversation_name` VARCHAR(255) DEFAULT NULL,
    `message_id` VARCHAR(128) NOT NULL,
    `sender_id` BIGINT NOT NULL,
    `sender_name` VARCHAR(255) NOT NULL,
    `content` TEXT NOT NULL,
    `message_time` DATETIME NOT NULL,
    `vectorized` TINYINT(1) NOT NULL DEFAULT 0,
    `vectorized_at` DATETIME DEFAULT NULL,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_message_record_scene_conversation_message_id` (`scene_type`, `conversation_id`, `message_id`),
    KEY `idx_message_record_scene_conversation_time` (`scene_type`, `conversation_id`, `message_time`),
    KEY `idx_message_record_scene_conversation_vectorized` (`scene_type`, `conversation_id`, `vectorized`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
