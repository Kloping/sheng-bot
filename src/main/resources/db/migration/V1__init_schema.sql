CREATE TABLE IF NOT EXISTS message_record
(
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    message_id      VARCHAR(64)  NOT NULL,
    scene_type      VARCHAR(16)  NOT NULL,
    conversation_id BIGINT       NOT NULL,
    sender_id       BIGINT       NOT NULL,
    sender_name     VARCHAR(128) NOT NULL DEFAULT '',
    raw_text        TEXT         NULL,
    normalized_text TEXT         NULL,
    mentioned_bot   TINYINT(1)   NOT NULL DEFAULT 0,
    reply_to_bot    TINYINT(1)   NOT NULL DEFAULT 0,
    image_message   TINYINT(1)   NOT NULL DEFAULT 0,
    quote_message   TINYINT(1)   NOT NULL DEFAULT 0,
    received_at     DATETIME     NOT NULL,
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_message_record_message_id (message_id),
    KEY idx_message_record_conversation_id (conversation_id),
    KEY idx_message_record_sender_id (sender_id),
    KEY idx_message_record_received_at (received_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;
