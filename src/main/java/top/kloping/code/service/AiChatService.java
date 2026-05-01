package top.kloping.code.service;

/**
 * AI 对话服务接口。
 * 当群用户 @机器人时，加载近期上下文和向量检索的长期记忆，调用 AI 生成回复。
 */
public interface AiChatService {

    /**
     * 处理群消息中的 @机器人 触发，生成 AI 回复并发送。
     *
     * @param sceneType      会话场景类型，不能为空
     * @param conversationId 会话ID，不能为空
     * @param userMessage    用户消息文本（去除 @部分后的内容），允许为空
     * @param groupId        群ID，用于发送回复
     * @return AI 生成的回复内容，生成失败时返回 null
     */
    String handleAtBotChat(String sceneType, String conversationId, String userMessage, long groupId);
}
