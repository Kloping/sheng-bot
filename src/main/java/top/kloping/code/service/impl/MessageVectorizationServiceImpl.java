package top.kloping.code.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;
import top.kloping.code.config.MessageStorageProperties;
import top.kloping.code.entity.MessageRecord;
import top.kloping.code.service.MessageFormatService;
import top.kloping.code.service.MessageRecordService;
import top.kloping.code.service.MessageVectorizationService;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 消息向量化服务实现类。
 * 负责在会话消息达到阈值后，将格式化文本写入 Milvus 并回写数据库状态。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MessageVectorizationServiceImpl implements MessageVectorizationService {

    private final MessageRecordService messageRecordService;
    private final MessageFormatService messageFormatService;
    private final MessageStorageProperties messageStorageProperties;
    private final VectorStore vectorStore;
    private final ConcurrentMap<String, Object> conversationLocks = new ConcurrentHashMap<>();

    /**
     * 尝试对指定会话执行批量向量化。
     *
     * @param sceneType      会话场景类型，不能为空
     * @param conversationId 会话ID，不能为空
     */
    @Override
    public void tryVectorizeConversation(String sceneType, String conversationId) {
        String lockKey = sceneType + ":" + conversationId;
        Object lock = conversationLocks.computeIfAbsent(lockKey, key -> new Object());
        synchronized (lock) {
            doVectorizeInBatch(sceneType, conversationId);
        }
    }

    private void doVectorizeInBatch(String sceneType, String conversationId) {
        int contentLengthThreshold = Math.max(1, messageStorageProperties.getVectorizationContentLengthThreshold());
        List<MessageRecord> pendingRecords = messageRecordService.listAllUnvectorized(sceneType, conversationId);
        if (pendingRecords.isEmpty()) {
            return;
        }

        int startIndex = 0;
        while (startIndex < pendingRecords.size()) {
            List<MessageRecord> currentSegment = collectSegmentByContentLength(pendingRecords, startIndex, contentLengthThreshold);
            if (currentSegment.isEmpty()) {
                return;
            }

            String formattedContent = messageFormatService.formatConversationMessages(currentSegment);
            int effectiveLength = messageFormatService.effectiveContentLength(formattedContent);
            // 仅当剩余消息整体还未达到可切段阈值时，才继续等待后续消息进入同一批次。
            if (effectiveLength < contentLengthThreshold
                    && startIndex + currentSegment.size() >= pendingRecords.size()) {
                return;
            }

            LocalDateTime vectorizedAt = LocalDateTime.now();
            List<Long> recordIds = currentSegment.stream()
                    .map(MessageRecord::getId)
                    .filter(Objects::nonNull)
                    .toList();

            // 即便分段消息被清洗后为空，也要完成状态回写，避免同一批数据反复重试。
            if (formattedContent.isBlank()) {
                messageRecordService.markVectorized(recordIds, vectorizedAt);
                startIndex += currentSegment.size();
                continue;
            }

            vectorStore.add(List.of(buildDocument(sceneType, conversationId, currentSegment, formattedContent)));
            messageRecordService.markVectorized(recordIds, vectorizedAt);
            log.info("消息批量向量化完成, sceneType={}, conversationId={}, messageCount={}, effectiveLength={}", sceneType, conversationId, currentSegment.size(), effectiveLength);
            startIndex += currentSegment.size();
        }
    }

    /**
     * 从指定起点开始累积消息，直到格式化内容长度接近或达到阈值。
     *
     * @param pendingRecords          未向量化消息列表，按时间正序排列
     * @param startIndex              起始下标，必须在列表范围内
     * @param contentLengthThreshold  目标内容长度阈值，必须大于0
     * @return 本次应入向量库的消息分段
     */
    private List<MessageRecord> collectSegmentByContentLength(List<MessageRecord> pendingRecords, int startIndex, int contentLengthThreshold) {
        List<MessageRecord> segment = new ArrayList<>();
        for (int index = startIndex; index < pendingRecords.size(); index++) {
            segment.add(pendingRecords.get(index));
            String formattedContent = messageFormatService.formatConversationMessages(segment);
            int effectiveLength = messageFormatService.effectiveContentLength(formattedContent);
            // 当累计内容首次跨过阈值时，对比跨过前后两段，选取更接近目标字数的一段。
            if (effectiveLength >= contentLengthThreshold) {
                if (segment.size() == 1) {
                    return new ArrayList<>(segment);
                }
                List<MessageRecord> previousSegment = new ArrayList<>(segment.subList(0, segment.size() - 1));
                String previousFormattedContent = messageFormatService.formatConversationMessages(previousSegment);
                int previousEffectiveLength = messageFormatService.effectiveContentLength(previousFormattedContent);
                int previousDistance = Math.abs(previousEffectiveLength - contentLengthThreshold);
                int currentDistance = Math.abs(effectiveLength - contentLengthThreshold);
                if (previousDistance <= currentDistance) {
                    return previousSegment;
                }
                return new ArrayList<>(segment);
            }
        }
        return segment;
    }

    private Document buildDocument(String sceneType, String conversationId, List<MessageRecord> batch, String formattedContent) {
        MessageRecord firstRecord = batch.get(0);
        MessageRecord lastRecord = batch.get(batch.size() - 1);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("sceneType", sceneType);
        metadata.put("conversationId", conversationId);
        metadata.put("conversationName", firstRecord.getConversationName());
        metadata.put("messageCount", batch.size());
        metadata.put("startRecordId", firstRecord.getId());
        metadata.put("endRecordId", lastRecord.getId());
        metadata.put("startMessageTime", firstRecord.getMessageTime() == null ? null : firstRecord.getMessageTime().toString());
        metadata.put("endMessageTime", lastRecord.getMessageTime() == null ? null : lastRecord.getMessageTime().toString());
        metadata.put("vectorizedAt", LocalDateTime.now().toString());
        return new Document(formattedContent, metadata);
    }
}
