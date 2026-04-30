package top.kloping.code.service;

import com.baomidou.mybatisplus.extension.service.IService;
import top.kloping.code.entity.MessageRecord;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/**
 * 消息记录服务接口。
 * 用于提供消息记录查询、批量读取以及向量化状态更新能力。
 */
public interface MessageRecordService extends IService<MessageRecord> {

    /**
     * 统计指定会话中尚未向量化的消息数量。
     *
     * @param sceneType      会话场景类型，不能为空
     * @param conversationId 会话ID，不能为空
     * @return 未向量化消息数量
     */
    long countUnvectorized(String sceneType, String conversationId);

    /**
     * 按消息时间正序读取指定会话全部未向量化消息。
     *
     * @param sceneType      会话场景类型，不能为空
     * @param conversationId 会话ID，不能为空
     * @return 未向量化消息列表
     */
    List<MessageRecord> listAllUnvectorized(String sceneType, String conversationId);

    /**
     * 按消息时间正序读取最早的一批未向量化消息。
     *
     * @param sceneType      会话场景类型，不能为空
     * @param conversationId 会话ID，不能为空
     * @param limit          读取上限，必须大于0
     * @return 未向量化消息列表
     */
    List<MessageRecord> listOldestUnvectorizedBatch(String sceneType, String conversationId, int limit);

    /**
     * 将指定消息批量标记为已向量化。
     *
     * @param ids          需要标记的消息主键集合，可为空
     * @param vectorizedAt 向量化完成时间，为空时使用当前时间
     * @return 更新是否成功
     */
    boolean markVectorized(Collection<Long> ids, LocalDateTime vectorizedAt);
}
