package top.kloping.code.event;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.mamoe.mirai.event.EventHandler;
import net.mamoe.mirai.event.SimpleListenerHost;
import net.mamoe.mirai.event.events.GroupMessageEvent;
import net.mamoe.mirai.event.events.GroupMessageSyncEvent;
import net.mamoe.mirai.message.data.At;
import net.mamoe.mirai.message.data.FlashImage;
import net.mamoe.mirai.message.data.Image;
import net.mamoe.mirai.message.data.MessageChain;
import net.mamoe.mirai.message.data.SingleMessage;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import top.kloping.code.config.BotProperties;
import top.kloping.code.entity.MessageRecord;
import top.kloping.code.enums.MessageSceneType;
import top.kloping.code.service.AiChatService;
import top.kloping.code.service.MessageRecordService;
import top.kloping.code.service.MessageVectorizationService;

import kotlin.coroutines.CoroutineContext;
import kotlinx.coroutines.CoroutineExceptionHandler;

import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
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
    private final AiChatService aiChatService;

    /**
     * 覆写 SimpleListenerHost 异常处理，避免协程异常导致监听器崩溃。
     *
     * @param context 协程上下文
     * @param exception 异常对象
     */
    @Override
    public void handleException(CoroutineContext context, Throwable exception) {
        log.error("群消息事件监听器异常", exception);
    }

    /**
     * 处理群消息事件（他人发送）。
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

        // 检测 @机器人，触发 AI 对话
        if (isAtBot(event.getMessage())) {
            String userMessage = extractUserMessage(event.getMessage());
            String sceneType = MessageSceneType.GROUP.getCode();
            String conversationId = String.valueOf(groupId);
            String reply = aiChatService.handleAtBotChat(sceneType, conversationId, userMessage, groupId);
            if (reply != null && !reply.isBlank()) {
                event.getGroup().sendMessage(reply);
            }
        }
    }

    /**
     * 判断消息链中是否包含 @机器人。
     *
     * @param chain 消息链，不能为空
     * @return 是否 @了机器人
     */
    private boolean isAtBot(MessageChain chain) {
        Long botAccount = botProperties.getAccount();
        if (botAccount == null) {
            return false;
        }
        for (SingleMessage element : chain) {
            if (element instanceof At at) {
                if (at.getTarget() == botAccount) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 提取用户消息文本，去除 @机器人 部分。
     *
     * @param chain 消息链，不能为空
     * @return 去除 @后的用户消息文本
     */
    private String extractUserMessage(MessageChain chain) {
        Long botAccount = botProperties.getAccount();
        StringBuilder sb = new StringBuilder();
        for (SingleMessage element : chain) {
            // 跳过闪照
            if (element instanceof FlashImage) {
                continue;
            }
            // 跳过 @机器人
            if (element instanceof At at && botAccount != null && at.getTarget() == botAccount) {
                continue;
            }
            // 图片替换为 [图片:md5] 格式
            if (element instanceof Image image) {
                String md5 = downloadAndComputeMd5(image);
                if (md5 != null && !md5.isEmpty()) {
                    sb.append("[图片:").append(md5).append("]");
                } else {
                    sb.append("[图片]");
                }
            } else {
                sb.append(element.contentToString());
            }
        }
        return sb.toString().trim();
    }

    /**
     * 处理机器人自身发送的群消息同步事件，标记 selfSent=true 以便 AI 区分。
     *
     * @param event 群消息同步事件，不能为空
     */
    @EventHandler
    public void onGroupMessageSync(GroupMessageSyncEvent event) {
        long groupId = event.getGroup().getId();
        if (!shouldHandleGroup(groupId)) {
            return;
        }

        MessageRecord record = buildSelfSentMessageRecord(event);
        if (record.getContent().isBlank()) {
            return;
        }

        try {
            messageRecordService.save(record);
            messageVectorizationService.tryVectorizeConversation(record.getSceneType(), record.getConversationId());
        } catch (DuplicateKeyException ex) {
            log.debug("机器人消息重复投递，已忽略, groupId={}, messageId={}", groupId, record.getMessageId());
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

    private static final Path IMAGE_STORAGE_DIR = Paths.get("data", "message-images");

    private MessageRecord buildMessageRecord(GroupMessageEvent event) {
        LocalDateTime now = LocalDateTime.now();
        MessageRecord record = new MessageRecord();
        record.setSceneType(MessageSceneType.GROUP.getCode());
        record.setConversationId(String.valueOf(event.getGroup().getId()));
        record.setConversationName(event.getGroup().getName());
        record.setMessageId(buildMessageId(event));
        record.setSenderId(event.getSender().getId());
        record.setSenderName(event.getSenderName());

        // 提取图片信息并下载保存，content 中图片替换为 [图片:md5] 格式。
        List<String> md5List = new ArrayList<>();
        String processedContent = processImages(event.getMessage(), md5List);
        record.setContent(normalizeContent(processedContent));
        if (!md5List.isEmpty()) {
            record.setImageMd5List(String.join(",", md5List));
        }

        record.setMessageTime(LocalDateTime.ofInstant(Instant.ofEpochSecond(event.getTime()), ZoneId.systemDefault()));
        record.setSelfSent(Boolean.FALSE);
        record.setVectorized(Boolean.FALSE);
        record.setCreatedAt(now);
        record.setUpdatedAt(now);
        return record;
    }

    /**
     * 构建机器人自身发送消息的 MessageRecord，selfSent 标记为 true。
     *
     * @param event 群消息同步事件，不能为空
     * @return 消息记录实体
     */
    private MessageRecord buildSelfSentMessageRecord(GroupMessageSyncEvent event) {
        LocalDateTime now = LocalDateTime.now();
        MessageRecord record = new MessageRecord();
        record.setSceneType(MessageSceneType.GROUP.getCode());
        record.setConversationId(String.valueOf(event.getGroup().getId()));
        record.setConversationName(event.getGroup().getName());
        record.setMessageId(buildSyncMessageId(event));
        record.setSenderId(event.getBot().getId());
        record.setSenderName(event.getBot().getNick());

        // 提取图片信息并下载保存，content 中图片替换为 [图片:md5] 格式。
        List<String> md5List = new ArrayList<>();
        String processedContent = processImages(event.getMessage(), md5List);
        record.setContent(normalizeContent(processedContent));
        if (!md5List.isEmpty()) {
            record.setImageMd5List(String.join(",", md5List));
        }

        record.setMessageTime(LocalDateTime.ofInstant(Instant.ofEpochSecond(event.getTime()), ZoneId.systemDefault()));
        record.setSelfSent(Boolean.TRUE);
        record.setVectorized(Boolean.FALSE);
        record.setCreatedAt(now);
        record.setUpdatedAt(now);
        return record;
    }

    /**
     * 遍历消息链提取 Image 元素（跳过 FlashImage），下载图片到本地并收集 MD5。
     * contentToString 中的图片占位会被替换为 [图片:md5] 格式。
     *
     * @param chain  消息链，不能为空
     * @param md5List 用于收集图片 MD5 的列表，不能为空
     * @return 处理后的文本内容
     */
    private String processImages(MessageChain chain, List<String> md5List) {
        StringBuilder contentBuilder = new StringBuilder();
        for (SingleMessage element : chain) {
            // 跳过闪照，只处理普通图片。
            if (element instanceof FlashImage) {
                continue;
            }
            if (element instanceof Image image) {
                // 先下载图片再对文件内容算 MD5，兼容 NapCat 等新协议的 URL 格式 imageId。
                String md5 = downloadAndComputeMd5(image);
                if (md5 != null && !md5.isEmpty()) {
                    md5List.add(md5);
                    contentBuilder.append("[图片:").append(md5).append("]");
                } else {
                    // 下载失败时用 imageId 的 hash 作为兜底标识。
                    String fallbackId = Integer.toHexString(image.getImageId().hashCode());
                    md5List.add(fallbackId);
                    contentBuilder.append("[图片:").append(fallbackId).append("]");
                }
            } else {
                contentBuilder.append(element.contentToString());
            }
        }
        return contentBuilder.toString();
    }

    /**
     * 通过 Image.queryUrl 下载图片到本地 ./data/message-images 目录，
     * 同时对下载内容计算 MD5 作为文件名，兼容 NapCat 等新协议的 URL 格式 imageId。
     *
     * @param image Mirai 图片对象，不能为空
     * @return 图片内容 MD5 十六进制字符串，下载失败时返回 null
     */
    private String downloadAndComputeMd5(Image image) {
        try {
            String url = Image.queryUrl(image);
            if (url == null || url.isBlank()) {
                log.warn("图片查询直链为空");
                return null;
            }
            Path dir = IMAGE_STORAGE_DIR.toAbsolutePath().normalize();
            Files.createDirectories(dir);
            Path tempTarget = dir.resolve("tmp_" + System.nanoTime());
            MessageDigest md5Digest = MessageDigest.getInstance("MD5");
            try (InputStream in = URI.create(url).toURL().openStream();
                 DigestInputStream dis = new DigestInputStream(in, md5Digest)) {
                Files.copy(dis, tempTarget, StandardCopyOption.REPLACE_EXISTING);
            }
            byte[] md5Bytes = md5Digest.digest();
            String md5 = hexFormat(md5Bytes);
            // 检查是否已存在同 MD5 文件（去重）。
            for (String ext : List.of(".jpg", ".png", ".gif", ".webp")) {
                Path existing = dir.resolve(md5 + ext);
                if (Files.exists(existing)) {
                    Files.deleteIfExists(tempTarget);
                    log.debug("图片已存在, md5={}", md5);
                    return md5;
                }
            }
            // 从临时文件推断扩展名并重命名。
            String ext = guessExtension(tempTarget);
            Path finalTarget = dir.resolve(md5 + ext);
            Files.move(tempTarget, finalTarget, StandardCopyOption.REPLACE_EXISTING);
            log.debug("图片下载完成, md5={}, path={}", md5, finalTarget);
            return md5;
        } catch (Exception ex) {
            log.warn("图片下载失败", ex);
            return null;
        }
    }

    /**
     * 根据文件头部魔数推断图片扩展名，默认 .jpg。
     *
     * @param file 本地图片文件路径
     * @return 扩展名，如 ".jpg"、".png"、".gif"、".webp"
     */
    private String guessExtension(Path file) {
        try {
            byte[] header = Files.readAllBytes(file);
            if (header.length < 4) {
                return ".jpg";
            }
            // PNG: 89 50 4E 47
            if (header[0] == (byte) 0x89 && header[1] == 0x50 && header[2] == 0x4E && header[3] == 0x47) {
                return ".png";
            }
            // GIF: 47 49 46 38
            if (header[0] == 0x47 && header[1] == 0x49 && header[2] == 0x46 && header[3] == 0x38) {
                return ".gif";
            }
            // WebP: 52 49 46 46 ... 57 45 42 50
            if (header.length >= 12
                    && header[0] == 0x52 && header[1] == 0x49 && header[2] == 0x46 && header[3] == 0x46
                    && header[8] == 0x57 && header[9] == 0x45 && header[10] == 0x42 && header[11] == 0x50) {
                return ".webp";
            }
        } catch (Exception ignored) {
        }
        return ".jpg";
    }

    /**
     * 将字节数组转换为小写十六进制字符串。
     *
     * @param bytes 字节数组，可为空
     * @return 小写十六进制字符串
     */
    private String hexFormat(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b & 0xff));
        }
        return sb.toString();
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

    /**
     * 为 GroupMessageSyncEvent 构建消息唯一ID。
     * 使用 bot ID + 时间戳 + 内容哈希，确保与普通消息 ID 不冲突。
     *
     * @param event 群消息同步事件，不能为空
     * @return 消息唯一ID
     */
    private String buildSyncMessageId(GroupMessageSyncEvent event) {
        int[] ids = event.getSource().getIds();
        if (ids != null && ids.length > 0) {
            return "sync-" + Arrays.stream(ids)
                    .mapToObj(String::valueOf)
                    .collect(Collectors.joining("-"));
        }
        return "sync-"
                + event.getBot().getId()
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
