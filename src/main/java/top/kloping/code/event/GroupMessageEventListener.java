package top.kloping.code.event;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.mamoe.mirai.event.EventHandler;
import net.mamoe.mirai.event.SimpleListenerHost;
import net.mamoe.mirai.event.events.GroupMessageEvent;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import top.kloping.code.config.BotProperties;
import top.kloping.code.entity.MessageRecord;
import top.kloping.code.enums.MessageSceneType;
import top.kloping.code.service.MessageRecordService;
import top.kloping.code.service.MessageVectorizationService;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 群消息事件监听器。
 * 用于接收 GroupMessageEvent、完成消息入库，并在达到阈值时触发向量化流程。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GroupMessageEventListener extends SimpleListenerHost {

    private final BotProperties botProperties;
    private final MessageRecordService messageRecordService;
    private final MessageVectorizationService messageVectorizationService;

    /**
     * 处理群消息事件。
     *
     * @param event 群消息事件，不能为空
     */
    @EventHandler
    public void onGroupMessage(GroupMessageEvent event) {
        long groupId = event.getGroup().getId();
        if (!shouldHandleGroup(groupId)) {
            return;
        }

        MessageRecord record = buildMessageRecord(event);
        if (record.getContent().isBlank()) {
            return;
        }

        try {
            messageRecordService.save(record);
            messageVectorizationService.tryVectorizeConversation(record.getSceneType(), record.getConversationId());
        } catch (DuplicateKeyException ex) {
            log.debug("群消息重复投递，已忽略, groupId={}, messageId={}", groupId, record.getMessageId());
        }
    }

    private boolean shouldHandleGroup(long groupId) {
        List<Long> blacklistedGroups = botProperties.getGroup().getBlacklistedGroups();
        if (blacklistedGroups != null && blacklistedGroups.contains(groupId)) {
            return false;
        }
        List<Long> allowedGroups = botProperties.getGroup().getAllowedGroups();
        return allowedGroups == null || allowedGroups.isEmpty() || allowedGroups.contains(groupId);
    }

    private MessageRecord buildMessageRecord(GroupMessageEvent event) {
        LocalDateTime now = LocalDateTime.now();
        MessageRecord record = new MessageRecord();
        record.setSceneType(MessageSceneType.GROUP.getCode());
        record.setConversationId(String.valueOf(event.getGroup().getId()));
        record.setConversationName(event.getGroup().getName());
        record.setMessageId(buildMessageId(event));
        record.setSenderId(event.getSender().getId());
        record.setSenderName(event.getSenderName());
        record.setContent(normalizeContent(event.getMessage().contentToString()));
        record.setMessageTime(LocalDateTime.ofInstant(Instant.ofEpochSecond(event.getTime()), ZoneId.systemDefault()));
        record.setVectorized(Boolean.FALSE);
        record.setCreatedAt(now);
        record.setUpdatedAt(now);
        return record;
    }

    private String buildMessageId(GroupMessageEvent event) {
        int[] ids = event.getSource().getIds();
        if (ids != null && ids.length > 0) {
            return Arrays.stream(ids)
                    .mapToObj(String::valueOf)
                    .collect(Collectors.joining("-"));
        }
        return event.getSender().getId()
                + "-"
                + event.getTime()
                + "-"
                + Integer.toUnsignedString(event.getMessage().contentToString().hashCode());
    }

    private String normalizeContent(String content) {
        if (content == null) {
            return "";
        }
        return content.replace("\r\n", "\n")
                .replace('\r', '\n')
                .trim();
    }
}
