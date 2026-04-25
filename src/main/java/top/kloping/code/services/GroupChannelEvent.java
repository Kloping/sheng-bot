package top.kloping.code.services;

import com.alibaba.fastjson.JSON;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.mamoe.mirai.event.GlobalEventChannel;
import net.mamoe.mirai.event.ListenerHost;
import net.mamoe.mirai.event.events.GroupMessageEvent;
import net.mamoe.mirai.message.data.Image;
import net.mamoe.mirai.message.data.MessageSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import top.kloping.code.config.BotProperties;
import top.kloping.code.entity.MessageRecord;
import top.kloping.code.service.IMessageRecordService;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 群消息事件监听器，负责解析群消息并持久化消息记录。
 *
 * <br/><strong>Created at 10:11<strong/>
 *
 * @author github kloping
 * @since 2026/4/25
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class GroupChannelEvent implements ListenerHost {

    private final IMessageRecordService messageRecordService;
    private final BotProperties botProperties;

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    @Value("${sheng.message.image-storage.enabled:true}")
    private boolean imageStorageEnabled;

    @Value("${sheng.message.image-storage.base-directory:./data/message-images}")
    private String imageStorageBaseDirectory;

    @Value("${sheng.message.image-storage.connect-timeout-seconds:10}")
    private int connectTimeoutSeconds;

    @Value("${sheng.message.image-storage.read-timeout-seconds:30}")
    private int readTimeoutSeconds;

    /**
     * 处理群消息并保存消息记录。
     *
     * @param event 群消息事件，非空
     */
    @net.mamoe.mirai.event.EventHandler
    public void onGroupMsg(GroupMessageEvent event) {
        Long groupId = event.getGroup().getId();
        // 配置了允许群聊白名单时，仅处理白名单内群消息
        if (!isAllowedGroup(groupId)) {
            return;
        }

        MessageSource messageSource = event.getMessage().get(MessageSource.Key);
        if (messageSource == null) {
            return;
        }
        Integer messageId = messageSource.getIds()[0];

        boolean mentionedBot = false;
        boolean imageMessage = false;
        boolean quoteMessage = false;
        boolean replyToBot = false;
        StringBuilder plainText = new StringBuilder();
        Map<String, Map<String, String>> imageMetadataByMd5 = new LinkedHashMap<>();

        for (net.mamoe.mirai.message.data.SingleMessage m : event.getMessage()) {
            if (m instanceof net.mamoe.mirai.message.data.At) {
                if (((net.mamoe.mirai.message.data.At) m).getTarget() == event.getBot().getId()) {
                    mentionedBot = true;
                }
            } else if (m instanceof Image image) {
                imageMessage = true;
                // 开启图片落盘时，按图片内容MD5去重保存并收集可入库的路径信息
                collectImageMetadata(image, imageMetadataByMd5);
            } else if (m instanceof net.mamoe.mirai.message.data.QuoteReply) {
                quoteMessage = true;
                if (((net.mamoe.mirai.message.data.QuoteReply) m).getSource().getFromId() == event.getBot().getId()) {
                    replyToBot = true;
                }
            } else if (m instanceof net.mamoe.mirai.message.data.PlainText plainText1) {
                plainText.append(plainText1.getContent());
            }
        }

        String senderName = event.getSender().getNameCard();
        if (senderName == null || senderName.isEmpty()) {
            senderName = event.getSender().getNick();
        }

        List<Map<String, String>> imageMetadataList = new ArrayList<>(imageMetadataByMd5.values());
        String imageMetadataJson = imageMetadataList.isEmpty() ? null : JSON.toJSONString(imageMetadataList);

        MessageRecord record = new MessageRecord()
                .setMessageId(String.valueOf(messageId))
                .setSceneType("GROUP")
                .setConversationId(groupId)
                .setSenderId(event.getSender().getId())
                .setSenderName(senderName)
                .setRawText(event.getMessage().contentToString())
                .setNormalizedText(plainText.toString().trim())
                .setMentionedBot(mentionedBot)
                .setReplyToBot(replyToBot)
                .setImageMessage(imageMessage)
                .setImageMetadata(imageMetadataJson)
                .setQuoteMessage(quoteMessage)
                .setVectorized(false)
                .setReceivedAt(LocalDateTime.ofEpochSecond(event.getTime(), 0, ZoneOffset.ofHours(8)))
                .setCreatedAt(LocalDateTime.now());

        saveMessageRecord(record);
    }

    /**
     * 持久化消息记录。
     *
     * @param record 消息记录，非空
     */
    private void saveMessageRecord(MessageRecord record) {
        try {
            messageRecordService.save(record);
        } catch (DuplicateKeyException ex) {
            // 同一消息重复投递时按幂等处理，避免唯一约束异常中断事件流程
            log.debug("消息记录已存在，忽略重复写入, sceneType={}, conversationId={}, messageId={}",
                    record.getSceneType(), record.getConversationId(), record.getMessageId());
        }
    }

    /**
     * 判断当前群消息是否在允许处理的群聊范围内。
     *
     * @param groupId 群号，非空
     * @return true-允许处理；false-忽略该群消息
     */
    private boolean isAllowedGroup(Long groupId) {
        List<Long> allowedGroups = botProperties.getGroup().getAllowedGroups();
        if (allowedGroups == null || allowedGroups.isEmpty()) {
            return true;
        }
        return allowedGroups.contains(groupId);
    }

    /**
     * 收集图片元数据并按MD5去重缓存。
     *
     * @param image 图片对象，非空
     * @param imageMetadataByMd5 按MD5索引的图片元数据容器，非空
     */
    private void collectImageMetadata(Image image, Map<String, Map<String, String>> imageMetadataByMd5) {
        if (!imageStorageEnabled) {
            return;
        }
        try {
            String imageUrl = Image.queryUrl(image);
            Map<String, String> imageMetadata = persistImageByMd5(imageUrl);
            if (imageMetadata != null) {
                imageMetadataByMd5.putIfAbsent(imageMetadata.get("md5"), imageMetadata);
            }
        } catch (Exception ex) {
            log.warn("保存群消息图片失败, image={}", image, ex);
        }
    }

    /**
     * 下载图片并按文件内容MD5命名保存到配置目录。
     *
     * @param imageUrl 图片网络地址
     * @return 图片元数据，包含url、md5、localPath（相对路径），失败时返回null
     */
    private Map<String, String> persistImageByMd5(String imageUrl) {
        if (imageUrl == null || imageUrl.isBlank()) {
            return null;
        }
        byte[] imageBytes = downloadImage(imageUrl);
        if (imageBytes == null || imageBytes.length == 0) {
            return null;
        }

        String md5 = md5Hex(imageBytes);
        Path baseDirectory = Paths.get(imageStorageBaseDirectory).toAbsolutePath().normalize();
        Path imagePath = baseDirectory.resolve(md5);
        // localPath 仅保存相对于配置目录的路径，避免写入部署机绝对路径
        Path relativeImagePath = baseDirectory.relativize(imagePath);

        try {
            Files.createDirectories(baseDirectory);
            // 文件名直接使用内容MD5，天然保证跨消息去重
            if (Files.notExists(imagePath)) {
                Files.write(imagePath, imageBytes);
            }
            return Map.of(
                    "url", imageUrl,
                    "md5", md5,
                    "localPath", relativeImagePath.toString()
            );
        } catch (IOException ex) {
            log.warn("写入图片文件失败, path={}", imagePath, ex);
            return null;
        }
    }

    /**
     * 下载图片二进制数据。
     *
     * @param imageUrl 图片网络地址
     * @return 图片字节数组，失败时返回null
     */
    private byte[] downloadImage(String imageUrl) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) URI.create(imageUrl).toURL().openConnection();
            connection.setConnectTimeout(connectTimeoutSeconds * 1000);
            connection.setReadTimeout(readTimeoutSeconds * 1000);
            connection.setRequestMethod("GET");
            connection.connect();

            if (connection.getResponseCode() / 100 != 2) {
                log.warn("下载图片返回非2xx状态码, url={}, code={}", imageUrl, connection.getResponseCode());
                return null;
            }

            try (InputStream inputStream = connection.getInputStream()) {
                return inputStream.readAllBytes();
            }
        } catch (Exception ex) {
            log.warn("下载图片失败, url={}", imageUrl, ex);
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /**
     * 计算字节数组MD5并输出小写16进制字符串。
     *
     * @param data 二进制数据，非空
     * @return 小写MD5字符串
     */
    private String md5Hex(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            byte[] hashBytes = digest.digest(data);
            char[] chars = new char[hashBytes.length * 2];
            for (int i = 0; i < hashBytes.length; i++) {
                int value = hashBytes[i] & 0xFF;
                chars[i * 2] = HEX[value >>> 4];
                chars[i * 2 + 1] = HEX[value & 0x0F];
            }
            return new String(chars);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("当前JDK不支持MD5算法", ex);
        }
    }
}
