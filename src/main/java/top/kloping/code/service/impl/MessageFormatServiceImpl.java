package top.kloping.code.service.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import top.kloping.code.config.MessageStorageProperties;
import top.kloping.code.entity.MessageRecord;
import top.kloping.code.service.MessageFormatService;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 消息格式化服务实现类。
 * 负责把原始消息记录转换为带时间、用户和自动拼接效果的文本内容。
 */
@Service
@RequiredArgsConstructor
public class MessageFormatServiceImpl implements MessageFormatService {

    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final MessageStorageProperties messageStorageProperties;

    /**
     * 将消息列表格式化为可读文本。
     * 同一发送人在配置时间窗口内的连续消息会自动拼接。
     *
     * @param records 原始消息记录列表，允许为空
     * @return 格式化后的文本内容
     */
    @Override
    public String formatConversationMessages(List<MessageRecord> records) {
        if (records == null || records.isEmpty()) {
            return "";
        }

        StringBuilder result = new StringBuilder();
        StringBuilder currentBlock = new StringBuilder();
        MessageRecord previousRecord = null;
        String currentHeader = "";
        String currentPadding = "";

        for (MessageRecord record : records) {
            String normalizedContent = normalizeRawContent(record == null ? null : record.getContent());
            if (normalizedContent.isBlank()) {
                continue;
            }

            // 只有同一发送人且时间差未超过配置窗口时，才会把消息合并到同一段落。
            if (previousRecord != null && canMerge(previousRecord, record)) {
                currentBlock.append(System.lineSeparator())
                        .append(currentPadding)
                        .append(applyContinuationPadding(normalizedContent, currentPadding));
            } else {
                appendBlock(result, currentBlock);
                currentHeader = buildHeader(record);
                currentPadding = "\t\t";
                currentBlock = new StringBuilder(currentHeader)
                        .append(applyContinuationPadding(normalizedContent, currentPadding));
            }
            previousRecord = record;
        }

        appendBlock(result, currentBlock);
        return result.toString();
    }

    private boolean canMerge(MessageRecord previousRecord, MessageRecord currentRecord) {
        if (previousRecord == null || currentRecord == null) {
            return false;
        }
        if (previousRecord.getSenderId() == null || currentRecord.getSenderId() == null) {
            return false;
        }
        if (!previousRecord.getSenderId().equals(currentRecord.getSenderId())) {
            return false;
        }
        LocalDateTime previousTime = previousRecord.getMessageTime();
        LocalDateTime currentTime = currentRecord.getMessageTime();
        if (previousTime == null || currentTime == null) {
            return false;
        }
        long secondsBetween = ChronoUnit.SECONDS.between(previousTime, currentTime);
        long allowedSeconds = Math.max(0L, messageStorageProperties.getMergeWindowMinutes()) * 60L;
        return secondsBetween >= 0 && secondsBetween <= allowedSeconds;
    }

    private void appendBlock(StringBuilder result, StringBuilder currentBlock) {
        if (currentBlock == null || currentBlock.length() == 0) {
            return;
        }
        if (result.length() > 0) {
            result.append(System.lineSeparator());
        }
        result.append(currentBlock);
    }

    private String buildHeader(MessageRecord record) {
        LocalDateTime messageTime = record != null && record.getMessageTime() != null ? record.getMessageTime() : LocalDateTime.now();
        Long senderId = record != null ? record.getSenderId() : null;
        String senderName = record != null ? record.getSenderName() : null;
        return DATE_TIME_FORMATTER.format(messageTime)
                + " "
                + safeSenderName(senderName)
                + "("
                + (senderId == null ? 0L : senderId)
                + "): ";
    }

    private String safeSenderName(String senderName) {
        if (senderName == null || senderName.isBlank()) {
            return "unknown";
        }
        return senderName.trim();
    }

    private String normalizeRawContent(String content) {
        if (content == null) {
            return "";
        }
        return content.replace("\r\n", "\n")
                .replace('\r', '\n')
                .trim();
    }

    private String applyContinuationPadding(String content, String padding) {
        return content.replace("\n", System.lineSeparator() + padding);
    }
}
