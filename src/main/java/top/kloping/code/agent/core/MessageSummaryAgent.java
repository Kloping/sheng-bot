package top.kloping.code.agent.core;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.TypeReference;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.stereotype.Service;
import top.kloping.code.entity.MessageRecord;
import top.kloping.code.service.IMessageRecordService;
import org.springframework.util.MimeType;
import org.springframework.util.MimeTypeUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.time.Duration;
import java.time.LocalDateTime;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 群消息自动摘要 Agent，负责按触发条件汇总未向量化消息并回写向量化标记。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class MessageSummaryAgent {

    private static final String GROUP_SCENE_TYPE = "GROUP";

    private final IMessageRecordService messageRecordService;
    private final ChatClient chatClient;

    @Value("${sheng.agent.summary.enabled:true}")
    private boolean summaryEnabled;

    @Value("${sheng.agent.summary.auto-trigger-interval-minutes:15}")
    private long autoTriggerIntervalMinutes;

    @Value("${sheng.agent.summary.auto-trigger-pending-threshold:25}")
    private int autoTriggerPendingThreshold;

    @Value("${sheng.message.image-storage.base-directory:./data/message-images}")
    private String imageStorageBaseDirectory;

    /**
     * 根据最新入库消息尝试触发自动摘要。
     *
     * @param currentRecord 当前消息记录，非空
     */
    public void triggerAutoSummary(MessageRecord currentRecord) {
        if (!summaryEnabled || currentRecord == null || currentRecord.getId() == null || currentRecord.getConversationId() == null) {
            return;
        }

        TriggerEvaluation evaluation = evaluateTrigger(currentRecord);
        if (!evaluation.triggered()) {
            return;
        }

        List<MessageRecord> pendingRecords = loadPendingRecordsBeforeCurrent(currentRecord);
        if (pendingRecords.isEmpty()) {
            // 触发条件满足但无可处理消息时直接返回，避免生成空摘要。
            return;
        }

        String summary = summarizeWithLlm(pendingRecords);
        if (summary == null || summary.isBlank()) {
            return;
        }

        markRecordsVectorized(pendingRecords);
        log.info("自动摘要完成, reason={}, conversationId={}, currentMessageId={}, summarizedCount={}, summary={}",
                evaluation.reason(), currentRecord.getConversationId(), currentRecord.getMessageId(), pendingRecords.size(), summary);
    }

    /**
     * 评估是否满足自动摘要触发条件。
     *
     * @param currentRecord 当前消息
     * @return 触发评估结果
     */
    private TriggerEvaluation evaluateTrigger(MessageRecord currentRecord) {
        MessageRecord previousRecord = loadPreviousRecord(currentRecord);
        if (previousRecord != null && isIntervalExceeded(previousRecord.getReceivedAt(), currentRecord.getReceivedAt())) {
            return new TriggerEvaluation(true, "interval_exceeded");
        }

        long pendingCountIncludingCurrent = countPendingRecordsIncludingCurrent(currentRecord);
        if (pendingCountIncludingCurrent >= Math.max(1, autoTriggerPendingThreshold)) {
            return new TriggerEvaluation(true, "pending_threshold_reached");
        }

        return new TriggerEvaluation(false, "not_triggered");
    }

    /**
     * 查询当前消息之前的上一条消息。
     *
     * @param currentRecord 当前消息
     * @return 上一条消息；若不存在则返回null
     */
    private MessageRecord loadPreviousRecord(MessageRecord currentRecord) {
        List<MessageRecord> records = messageRecordService.list(new LambdaQueryWrapper<MessageRecord>()
                .eq(MessageRecord::getSceneType, GROUP_SCENE_TYPE)
                .eq(MessageRecord::getConversationId, currentRecord.getConversationId())
                .lt(MessageRecord::getId, currentRecord.getId())
                .orderByDesc(MessageRecord::getId)
                .last("LIMIT 1"));
        if (records == null || records.isEmpty()) {
            return null;
        }
        return records.get(0);
    }

    /**
     * 判断当前消息与上一条消息间隔是否超过阈值。
     *
     * @param previousReceivedAt 上一条消息接收时间
     * @param currentReceivedAt 当前消息接收时间
     * @return true-超过阈值；false-未超过阈值
     */
    private boolean isIntervalExceeded(LocalDateTime previousReceivedAt, LocalDateTime currentReceivedAt) {
        if (previousReceivedAt == null || currentReceivedAt == null) {
            return false;
        }

        long configuredInterval = Math.max(1L, autoTriggerIntervalMinutes);
        long minutes = Duration.between(previousReceivedAt, currentReceivedAt).toMinutes();
        return minutes > configuredInterval;
    }

    /**
     * 统计到当前消息为止的未向量化消息数量（包含当前消息）。
     *
     * @param currentRecord 当前消息
     * @return 未向量化数量
     */
    private long countPendingRecordsIncludingCurrent(MessageRecord currentRecord) {
        return messageRecordService.count(new LambdaQueryWrapper<MessageRecord>()
                .eq(MessageRecord::getSceneType, GROUP_SCENE_TYPE)
                .eq(MessageRecord::getConversationId, currentRecord.getConversationId())
                .le(MessageRecord::getId, currentRecord.getId())
                // 兼容历史脏数据：null 也按“未向量化”处理。
                .and(wrapper -> wrapper.eq(MessageRecord::getVectorized, false).or().isNull(MessageRecord::getVectorized)));
    }

    /**
     * 加载当前消息之前的未向量化消息列表（不含当前消息）。
     *
     * @param currentRecord 当前消息
     * @return 待摘要消息列表，按时间升序
     */
    private List<MessageRecord> loadPendingRecordsBeforeCurrent(MessageRecord currentRecord) {
        List<MessageRecord> records = messageRecordService.list(new LambdaQueryWrapper<MessageRecord>()
                .eq(MessageRecord::getSceneType, GROUP_SCENE_TYPE)
                .eq(MessageRecord::getConversationId, currentRecord.getConversationId())
                .lt(MessageRecord::getId, currentRecord.getId())
                .and(wrapper -> wrapper.eq(MessageRecord::getVectorized, false).or().isNull(MessageRecord::getVectorized))
                .orderByAsc(MessageRecord::getReceivedAt)
                .orderByAsc(MessageRecord::getId));

        if (records == null || records.isEmpty()) {
            return Collections.emptyList();
        }
        return records;
    }

    /**
     * 调用多模态模型执行消息摘要。
     *
     * @param records 待摘要消息列表
     * @return 摘要文本；失败或空结果返回空字符串
     */
    private String summarizeWithLlm(List<MessageRecord> records) {
        List<Message> aiMessageQueue = buildAiMessageQueue(records);

        try {
            String summary = chatClient.prompt(new Prompt(aiMessageQueue))
                    .call()
                    .content();
            if (summary == null || summary.isBlank()) {
                return "";
            }
            return summary.trim();
        } catch (Exception ex) {
            log.warn("调用自动摘要Agent失败", ex);
            return "";
        }
    }

    /**
     * 构建按时间顺序输入模型的消息队列。
     *
     * @param records 消息列表
     * @return AI消息队列
     */
    private List<Message> buildAiMessageQueue(List<MessageRecord> records) {
        List<Message> queue = new ArrayList<>();
        queue.add(new SystemMessage("你是群聊消息整理助手。接下来会收到按时间顺序排列的群聊记录，请结合文本与图片内容给出客观总结。"));

        int index = 1;
        for (MessageRecord record : records) {
            queue.add(buildOrderedUserMessage(record, index));
            index++;
        }

        queue.add(new UserMessage("请基于以上消息输出总结：\n1) 2-6条要点\n2) 若存在明确任务或行动项，单独列出\n3) 禁止编造未出现的信息"));
        return queue;
    }

    /**
     * 构建单条按顺序入队的用户消息。
     *
     * @param record 消息记录
     * @param index 队列内顺序序号
     * @return 用户消息
     */
    private UserMessage buildOrderedUserMessage(MessageRecord record, int index) {
        String speaker = (record.getSenderName() == null || record.getSenderName().isBlank())
                ? String.valueOf(record.getSenderId())
                : record.getSenderName();
        String messageText = resolveRecordText(record);
        List<ImageInput> imageInputs = collectImageInputs(record);
        boolean emojiImageMessage = isEmojiImageMessage(record, messageText, imageInputs);

        String prefix = index + ". " + speaker + ": ";
        // 图片表情按普通文本消息处理，避免在多模态分支中被误判为需要图像理解。
        if (imageInputs.isEmpty() || emojiImageMessage) {
            String fallbackText = (messageText == null || messageText.isBlank())
                    ? (emojiImageMessage ? "[图片表情]" : "[空消息]")
                    : messageText;
            return new UserMessage(prefix + fallbackText);
        }

        List<Media> mediaList = new ArrayList<>();
        for (ImageInput imageInput : imageInputs) {
            Media media = buildMediaInput(imageInput);
            if (media != null) {
                mediaList.add(media);
            }
        }

        String fallbackText = (messageText == null || messageText.isBlank()) ? "[图片消息]" : messageText;
        if (mediaList.isEmpty()) {
            return new UserMessage(prefix + fallbackText + "\n图片引用: " + String.join(", ", collectImageReferences(imageInputs)));
        }

        return UserMessage.builder()
                .text(prefix + fallbackText)
                .media(mediaList)
                .build();
    }

    /**
     * 提取消息文本内容。
     *
     * @param record 消息记录
     * @return 优先原始文本，其次归一化文本
     */
    private String resolveRecordText(MessageRecord record) {
        if (record.getRawText() != null && !record.getRawText().isBlank()) {
            return record.getRawText();
        }
        if (record.getNormalizedText() != null && !record.getNormalizedText().isBlank()) {
            return record.getNormalizedText();
        }
        return "";
    }

    /**
     * 收集单条消息中的图片输入信息并去重。
     *
     * @param record 消息记录
     * @return 去重后的图片输入列表
     */
    private List<ImageInput> collectImageInputs(MessageRecord record) {
        if (record.getImageMetadata() == null || record.getImageMetadata().isBlank()) {
            return Collections.emptyList();
        }

        LinkedHashSet<String> deduplicatedKeys = new LinkedHashSet<>();
        List<ImageInput> imageInputs = new ArrayList<>();
        try {
            List<Map<String, String>> metadataList = JSON.parseObject(
                    record.getImageMetadata(),
                    new TypeReference<>() {
                    }
            );
            if (metadataList == null || metadataList.isEmpty()) {
                return Collections.emptyList();
            }
            for (Map<String, String> metadata : metadataList) {
                if (metadata == null) {
                    continue;
                }
                String localPath = normalizeImageField(metadata.get("localPath"));
                String url = normalizeImageField(metadata.get("url"));
                if (localPath == null && url == null) {
                    continue;
                }
                // 优先使用 localPath 作为去重主键，保证同一文件仅入队一次。
                String deduplicationKey = localPath != null ? "local:" + localPath : "url:" + url;
                if (deduplicatedKeys.add(deduplicationKey)) {
                    imageInputs.add(new ImageInput(url, localPath));
                }
            }
        } catch (Exception ex) {
            log.warn("解析图片元数据失败, messageRecordId={}", record.getId(), ex);
            return Collections.emptyList();
        }

        return imageInputs;
    }

    /**
     * 规范化图片字段值。
     *
     * @param value 字段值
     * @return 规范化后的非空字符串；空白返回null
     */
    private String normalizeImageField(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    /**
     * 构建多模态图片媒体输入，优先使用本地文件，失败时回退URL。
     *
     * @param imageInput 图片输入信息
     * @return 构建成功返回Media，失败返回null
     */
    private Media buildMediaInput(ImageInput imageInput) {
        if (imageInput == null) {
            return null;
        }

        String localPath = imageInput.localPath();
        String url = imageInput.url();
        MimeType mimeType = resolveMimeType(localPath != null ? localPath : url);

        if (localPath != null) {
            try {
                Path resolvedLocalPath = resolveLocalImagePath(localPath);
                if (resolvedLocalPath != null && Files.exists(resolvedLocalPath) && Files.isRegularFile(resolvedLocalPath)) {
                    return new Media(mimeType, new FileSystemResource(resolvedLocalPath));
                }
                log.warn("本地图片不存在或不可读, localPath={}", localPath);
            } catch (Exception ex) {
                log.warn("构建本地图片媒体输入失败, localPath={}", localPath, ex);
            }
        }

        if (url != null) {
            try {
                return new Media(mimeType, URI.create(url));
            } catch (Exception ex) {
                log.warn("构建图片URL媒体输入失败, url={}", url, ex);
            }
        }

        return null;
    }

    /**
     * 将图片相对路径解析为可读取的本地绝对路径。
     *
     * @param localPath 图片本地路径（相对或绝对）
     * @return 解析后的绝对路径；越权路径返回null
     */
    private Path resolveLocalImagePath(String localPath) {
        if (localPath == null || localPath.isBlank()) {
            return null;
        }

        Path inputPath = Paths.get(localPath).normalize();
        if (inputPath.isAbsolute()) {
            return inputPath;
        }

        Path baseDirectory = Paths.get(imageStorageBaseDirectory).toAbsolutePath().normalize();
        Path resolvedPath = baseDirectory.resolve(inputPath).normalize();
        // 防止通过"../"构造越权访问路径。
        if (!resolvedPath.startsWith(baseDirectory)) {
            log.warn("检测到非法图片路径，已忽略, localPath={}", localPath);
            return null;
        }
        return resolvedPath;
    }

    /**
     * 收集图片引用信息用于降级文本展示。
     *
     * @param imageInputs 图片输入列表
     * @return 图片引用列表
     */
    private List<String> collectImageReferences(List<ImageInput> imageInputs) {
        if (imageInputs == null || imageInputs.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> references = new ArrayList<>();
        for (ImageInput imageInput : imageInputs) {
            if (imageInput == null) {
                continue;
            }
            if (imageInput.localPath() != null) {
                references.add("local:" + imageInput.localPath());
                continue;
            }
            if (imageInput.url() != null) {
                references.add(imageInput.url());
            }
        }
        return references;
    }

    /**
     * 判断图片消息是否属于应按文本处理的“图片表情”。
     *
     * @param record 消息记录
     * @param messageText 消息文本
     * @param imageInputs 图片输入列表
     * @return true-按文本处理；false-按多模态图片处理
     */
    private boolean isEmojiImageMessage(MessageRecord record, String messageText, List<ImageInput> imageInputs) {
        if (!Boolean.TRUE.equals(record.getImageMessage()) || imageInputs.isEmpty()) {
            return false;
        }
        if (messageText == null || messageText.isBlank()) {
            return false;
        }

        // 使用消息文本特征做轻量判定，命中“表情/贴纸”时按普通文本消息处理。
        String normalizedText = messageText.toLowerCase(Locale.ROOT);
        return normalizedText.contains("表情")
                || normalizedText.contains("贴纸")
                || normalizedText.contains("emoji")
                || normalizedText.contains("sticker");
    }

    /**
     * 根据URL推断图片MIME类型。
     *
     * @param imageReference 图片引用（本地路径或URL）
     * @return MIME类型
     */
    private MimeType resolveMimeType(String imageReference) {
        if (imageReference == null) {
            return MimeTypeUtils.IMAGE_JPEG;
        }
        String normalizedReference = imageReference.toLowerCase(Locale.ROOT);
        if (normalizedReference.contains(".png")) {
            return MimeTypeUtils.IMAGE_PNG;
        }
        if (normalizedReference.contains(".gif")) {
            return MimeTypeUtils.parseMimeType("image/gif");
        }
        if (normalizedReference.contains(".webp")) {
            return MimeTypeUtils.parseMimeType("image/webp");
        }
        if (normalizedReference.contains(".bmp")) {
            return MimeTypeUtils.parseMimeType("image/bmp");
        }
        if (normalizedReference.contains(".jpeg") || normalizedReference.contains(".jpg")) {
            return MimeTypeUtils.IMAGE_JPEG;
        }
        return MimeTypeUtils.IMAGE_JPEG;
    }

    /**
     * 将已参与摘要的消息标记为已向量化。
     *
     * @param records 已摘要消息列表
     */
    private void markRecordsVectorized(List<MessageRecord> records) {
        if (records.isEmpty()) {
            return;
        }

        List<Long> recordIds = new ArrayList<>();
        for (MessageRecord record : records) {
            if (record.getId() != null) {
                recordIds.add(record.getId());
            }
        }

        if (recordIds.isEmpty()) {
            return;
        }

        messageRecordService.update(new LambdaUpdateWrapper<MessageRecord>()
                .in(MessageRecord::getId, recordIds)
                .set(MessageRecord::getVectorized, true));
    }

    /**
     * 自动触发评估结果。
     *
     * @param triggered 是否触发
     * @param reason 触发原因
     */
    private record TriggerEvaluation(boolean triggered, String reason) {
    }

    /**
     * 图片输入信息，包含URL与本地路径。
     *
     * @param url 图片网络地址
     * @param localPath 图片本地路径
     */
    private record ImageInput(String url, String localPath) {
    }
}
