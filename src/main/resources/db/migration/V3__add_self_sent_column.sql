ALTER TABLE `message_record`
    ADD COLUMN `self_sent` TINYINT(1) NOT NULL DEFAULT 0 AFTER `image_md5_list`;
