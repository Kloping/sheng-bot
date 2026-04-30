ALTER TABLE `message_record`
    ADD COLUMN `image_md5_list` VARCHAR(1024) DEFAULT NULL AFTER `content`;
