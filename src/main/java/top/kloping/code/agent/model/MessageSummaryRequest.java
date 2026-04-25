package top.kloping.code.agent.model;

import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 消息摘要请求参数。
 */
@Data
@Accessors(chain = true)
public class MessageSummaryRequest {

    /**
     * 会话ID（群号）。
     */
    private Long conversationId;

    /**
     * 期望摘要的消息条数。
     */
    private Integer messageCount;
}
