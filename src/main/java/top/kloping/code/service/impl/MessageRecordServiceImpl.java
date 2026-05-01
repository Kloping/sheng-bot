package top.kloping.code.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;
import top.kloping.code.entity.MessageRecord;
import top.kloping.code.mapper.MessageRecordMapper;
import top.kloping.code.service.MessageRecordService;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/**
 * 消息记录服务实现类。
 * 负责消息记录的批量查询和向量化状态更新。
 */
@Service
public class MessageRecordServiceImpl extends ServiceImpl<MessageRecordMapper, MessageRecord> implements MessageRecordService {

    /**
     * 统计指定会话中尚未向量化的消息数量。
     *
     * @param sceneType      会话场景类型，不能为空
     * @param conversationId 会话ID，不能为空
     * @return 未向量化消息数量
     */
    @Override
    public long countUnvectorized(String sceneType, String conversationId) {
        return count(Wrappers.<MessageRecord>lambdaQuery()
                .eq(MessageRecord::getSceneType, sceneType)
                .eq(MessageRecord::getConversationId, conversationId)
                .eq(MessageRecord::getVectorized, Boolean.FALSE));
    }

    /**
     * 按消息时间正序读取指定会话全部未向量化消息。
     *
     * @param sceneType      会话场景类型，不能为空
     * @param conversationId 会话ID，不能为空
     * @return 未向量化消息列表
     */
    @Override
    public List<MessageRecord> listAllUnvectorized(String sceneType, String conversationId) {
        return list(Wrappers.<MessageRecord>lambdaQuery()
                .eq(MessageRecord::getSceneType, sceneType)
                .eq(MessageRecord::getConversationId, conversationId)
                .eq(MessageRecord::getVectorized, Boolean.FALSE)
                .orderByAsc(MessageRecord::getMessageTime)
                .orderByAsc(MessageRecord::getId));
    }

    /**
     * 按消息时间正序读取最早的一批未向量化消息。
     *
     * @param sceneType      会话场景类型，不能为空
     * @param conversationId 会话ID，不能为空
     * @param limit          读取上限，必须大于0
     * @return 未向量化消息列表
     */
    @Override
    public List<MessageRecord> listOldestUnvectorizedBatch(String sceneType, String conversationId, int limit) {
        int safeLimit = Math.max(1, limit);
        return list(Wrappers.<MessageRecord>lambdaQuery()
                .eq(MessageRecord::getSceneType, sceneType)
                .eq(MessageRecord::getConversationId, conversationId)
                .eq(MessageRecord::getVectorized, Boolean.FALSE)
                .orderByAsc(MessageRecord::getMessageTime)
                .orderByAsc(MessageRecord::getId)
                // 每次只提取一个固定批次，避免一次性加载过多消息到内存中。
                .last("LIMIT " + safeLimit));
    }

    /**
     * 将指定消息批量标记为已向量化。
     *
     * @param ids          需要标记的消息主键集合，可为空
     * @param vectorizedAt 向量化完成时间，为空时使用当前时间
     * @return 更新是否成功
     */
    @Override
    public boolean markVectorized(Collection<Long> ids, LocalDateTime vectorizedAt) {
        if (ids == null || ids.isEmpty()) {
            return true;
        }
        LocalDateTime actualVectorizedAt = vectorizedAt == null ? LocalDateTime.now() : vectorizedAt;
        return update(Wrappers.<MessageRecord>lambdaUpdate()
                .in(MessageRecord::getId, ids)
                .set(MessageRecord::getVectorized, Boolean.TRUE)
                .set(MessageRecord::getVectorizedAt, actualVectorizedAt)
                .set(MessageRecord::getUpdatedAt, actualVectorizedAt));
    }

    /**
     * 按消息时间正序读取指定会话最近N条消息（含已/未向量化），用作 AI 对话上下文。
     *
     * @param sceneType      会话场景类型，不能为空
     * @param conversationId 会话ID，不能为空
     * @param limit          读取上限，必须大于0
     * @return 最近N条消息列表，按时间正序排列
     */
    @Override
    public List<MessageRecord> listRecentMessages(String sceneType, String conversationId, int limit) {
        int safeLimit = Math.max(1, limit);
        // 先按时间倒序取最近N条，再反转为正序，以便 AI 按时间线阅读。
        List<MessageRecord> descList = list(Wrappers.<MessageRecord>lambdaQuery()
                .eq(MessageRecord::getSceneType, sceneType)
                .eq(MessageRecord::getConversationId, conversationId)
                .orderByDesc(MessageRecord::getMessageTime)
                .orderByDesc(MessageRecord::getId)
                .last("LIMIT " + safeLimit));
        if (descList.size() <= 1) {
            return descList;
        }
        java.util.Collections.reverse(descList);
        return descList;
    }
}
