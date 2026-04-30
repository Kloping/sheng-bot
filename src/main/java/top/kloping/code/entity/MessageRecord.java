package top.kloping.code.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 消息记录实体。
 * 用于保存群消息的原始文本、发送者信息、时间戳以及向量化状态。
 */
@Data
@TableName("message_record")
public class MessageRecord {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("scene_type")
    private String sceneType;

    @TableField("conversation_id")
    private String conversationId;

    @TableField("conversation_name")
    private String conversationName;

    @TableField("message_id")
    private String messageId;

    @TableField("sender_id")
    private Long senderId;

    @TableField("sender_name")
    private String senderName;

    @TableField("content")
    private String content;

    @TableField("message_time")
    private LocalDateTime messageTime;

    @TableField("vectorized")
    private Boolean vectorized;

    @TableField("vectorized_at")
    private LocalDateTime vectorizedAt;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
