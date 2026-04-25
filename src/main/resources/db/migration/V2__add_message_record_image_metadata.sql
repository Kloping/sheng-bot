ALTER TABLE message_record
    ADD COLUMN image_metadata JSON NULL COMMENT '图片元数据JSON，包含url、md5、localPath';
