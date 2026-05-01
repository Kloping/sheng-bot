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

    /**
     * 消息中包含的图片MD5列表，多个MD5以逗号分隔。
     * 用于从本地 ./data/message-images 目录读取对应图片供 AI 大模型理解。
     */
    @TableField("image_md5_list")
    private String imageMd5List;

    /**
     * 是否为机器人自身发送的消息。
     * 用于 AI 区分消息来源，机器人自身消息在格式化时会带 [bot] 标记。
     */
    @TableField("self_sent")
    private Boolean selfSent;

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
