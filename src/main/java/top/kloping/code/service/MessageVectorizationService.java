package top.kloping.code.service;

/**
 * 消息向量化服务接口。
 * 用于在消息数量达到阈值时，将消息批量写入 Milvus 并回写向量化状态。
 */
public interface MessageVectorizationService {

    /**
     * 尝试对指定会话执行批量向量化。
     *
     * @param sceneType      会话场景类型，不能为空
     * @param conversationId 会话ID，不能为空
     */
    void tryVectorizeConversation(String sceneType, String conversationId);
}
