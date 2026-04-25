ALTER TABLE message_record
    ADD COLUMN vectorized TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否已完成向量化处理';
