ALTER TABLE message_record
    DROP INDEX uk_message_record_message_id,
    ADD UNIQUE KEY uk_message_record_scene_conversation_message_id (scene_type, conversation_id, message_id);
