package top.kloping.code.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import java.time.LocalDateTime;
import java.io.Serializable;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

/**
 * <p>
 * 
 * </p>
 *
 * @author kloping
 * @since 2026-04-25
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("message_record")
public class MessageRecord implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String messageId;

    private String sceneType;

    private Long conversationId;

    private Long senderId;

    private String senderName;

    private String rawText;

    private String normalizedText;

    private Boolean mentionedBot;

    private Boolean replyToBot;

    private Boolean imageMessage;

    /**
     * 图片元数据JSON，包含源链接与本地持久化路径
     */
    private String imageMetadata;

    private Boolean quoteMessage;

    /**
     * 是否已完成向量化处理
     */
    private Boolean vectorized;

    private LocalDateTime receivedAt;

    private LocalDateTime createdAt;


}
