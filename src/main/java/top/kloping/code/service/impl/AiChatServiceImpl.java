package top.kloping.code.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeType;
import org.springframework.util.MimeTypeUtils;
import top.kloping.code.config.BotProperties;
import top.kloping.code.entity.MessageRecord;
import top.kloping.code.service.AiChatService;
import top.kloping.code.service.MessageFormatService;
import top.kloping.code.service.MessageRecordService;
import top.kloping.core.ai.McpBean;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * AI 对话服务实现类。
 * 当群用户 @机器人时，加载近期上下文和向量检索的长期记忆，调用 AI 生成回复。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiChatServiceImpl implements AiChatService {

    private final ChatClient chatClient;
    private final VectorStore vectorStore;
    private final MessageRecordService messageRecordService;
    private final MessageFormatService messageFormatService;
    private final BotProperties botProperties;
    private final McpBean mcpBean;

    /**
     * 近期上下文消息条数。
     */
    private static final int RECENT_CONTEXT_LIMIT = 10;

    /**
     * 向量检索返回的最近似文档条数（长久记忆）。
     */
    private static final int LONG_TERM_MEMORY_TOP_K = 3;

    /**
     * 图片本地存储目录，需与 GroupMessageEventListener 中保持一致。
     */
    private static final Path IMAGE_STORAGE_DIR = Paths.get("data", "message-images");

    /**
     * 每次对话最多携带的图片数量，避免请求体过大。
     */
    private static final int MAX_IMAGE_MEDIA = 6;

    /**
     * 匹配 [图片:md5] 格式的占位符。
     */
    private static final Pattern IMAGE_PLACEHOLDER_PATTERN = Pattern.compile("\\[图片:([0-9a-f]+)]");

    /**
     * 自主判断不需要回复时 AI 输出的标记。
     */
    private static final String NO_REPLY_MARKER = "[NO_REPLY]";

    /**
     * 处理群消息中的 @机器人 触发，生成 AI 回复。
     *
     * @param sceneType      会话场景类型，不能为空
     * @param conversationId 会话ID，不能为空
     * @param userMessage    用户消息文本（去除 @部分后的内容），允许为空
     * @param groupId        群ID，用于发送回复
     * @return AI 生成的回复内容，生成失败时返回 null
     */
    @Override
    public String handleAtBotChat(String sceneType, String conversationId, String userMessage, long groupId) {
        try {
            // 1. 加载近期上下文（未向量化 + 已向量化的最近消息）
            String recentContext = buildRecentContext(sceneType, conversationId);

            // 2. 从向量库检索最相近的2条作为长久记忆
            String longTermMemory = buildLongTermMemory(userMessage, conversationId);

            // 3. 构建 AI 提示词（其中包含按时间顺序拼接的 [图片:md5] 占位符）。
            String systemPrompt = buildSystemPrompt();
            String userPrompt = buildUserPrompt(recentContext, longTermMemory, userMessage);

            // 4. 基于最终的 userPrompt 按出现顺序解析 [图片:md5] 占位符，解析为多模态图片资源。
            List<String> allImageMd5 = collectImageMd5FromText(userPrompt);
            List<ImageMedia> imageMediaList = resolveImageMedia(allImageMd5);

            // 5. 调用 AI 生成回复（文本 + 图片多模态）。
            log.info("触发AI对话(@机器人), groupId={}, userMessage={}, imageCount={}",
                    groupId, userMessage, imageMediaList.size());
            String reply = chatClient.prompt()
                    .toolCallbacks(mcpBean.getToolCallbacks())
                    .system(systemPrompt)
                    .user(userSpec -> {
                        userSpec.text(userPrompt);
                        for (ImageMedia media : imageMediaList) {
                            userSpec.media(media.mimeType(), media.resource());
                        }
                    })
                    .call()
                    .content();

            log.info("AI对话生成完成, groupId={}, replyLength={}", groupId, reply == null ? 0 : reply.length());
            return reply;
        } catch (Exception ex) {
            log.error("AI对话生成失败, groupId={}", groupId, ex);
            return null;
        }
    }

    /**
     * 加载近期上下文，格式化为可读文本。
     *
     * @param sceneType      会话场景类型
     * @param conversationId 会话ID
     * @return 格式化后的近期上下文文本
     */
    private String buildRecentContext(String sceneType, String conversationId) {
        List<MessageRecord> recentMessages = messageRecordService.listRecentMessages(sceneType, conversationId, RECENT_CONTEXT_LIMIT);
        if (recentMessages.isEmpty()) {
            return "";
        }
        return messageFormatService.formatConversationMessages(recentMessages);
    }

    /**
     * 从向量库检索与用户消息最相近的文档作为长久记忆。
     *
     * @param userMessage    用户消息文本
     * @param conversationId 会话ID，用于过滤同会话的记忆
     * @return 长久记忆文本，无结果时返回空字符串
     */
    private String buildLongTermMemory(String userMessage, String conversationId) {
        if (userMessage == null || userMessage.isBlank()) {
            return "";
        }
        try {
            SearchRequest searchRequest = SearchRequest.builder()
                    .query(userMessage)
                    .topK(LONG_TERM_MEMORY_TOP_K)
                    .filterExpression("conversationId == '" + conversationId + "'")
                    .build();
            List<Document> results = vectorStore.similaritySearch(searchRequest);
            if (results.isEmpty()) {
                return "";
            }
            return results.stream()
                    .map(Document::getText)
                    .collect(Collectors.joining("\n---\n"));
        } catch (Exception ex) {
            log.warn("向量检索长期记忆失败, conversationId={}", conversationId, ex);
            return "";
        }
    }

    /**
     * 构建 AI 系统提示词，包含人格设定和任务指引。
     *
     * @return 系统提示词
     */
    private String buildSystemPrompt() {
        BotProperties.PersonalityConfig personality = botProperties.getPersonality();
        String name = personality.getName();
        String description = personality.getDescription();
        return description + "\n"
                + "你的名字是「" + name + "」。\n"
                + "你正在一个QQ群聊中，用户通过 @你 来向你提问或寻求帮助。\n"
                + "请根据提供的近期对话上下文和长期记忆来理解用户的需求，\n"
                + "帮助用户解决问题或提供情绪价值。\n"
                + "回复要简洁自然，适合聊天环境，不要过长。";
    }

    /**
     * 构建用户提示词，将上下文、长期记忆和用户消息组合。
     *
     * @param recentContext  近期上下文
     * @param longTermMemory 长期记忆
     * @param userMessage    用户消息
     * @return 组合后的用户提示词
     */
    private String buildUserPrompt(String recentContext, String longTermMemory, String userMessage) {
        StringBuilder sb = new StringBuilder();
        if (!longTermMemory.isBlank()) {
            sb.append("【长期记忆（历史相似对话）】\n").append(longTermMemory).append("\n\n");
        }
        if (!recentContext.isBlank()) {
            sb.append("【近期对话上下文】\n").append(recentContext).append("\n\n");
        }
        sb.append("【用户消息】\n").append(userMessage == null ? "" : userMessage);
        return sb.toString();
    }

    /**
     * 处理活跃对话中的自主判断回复。
     * 当机器人之前被 @并回复后，在后续消息中自主判断是否需要继续回复。
     * AI 回复含 [NO_REPLY] 标记时视为不需要回复，返回 null。
     *
     * @param sceneType            会话场景类型，不能为空
     * @param conversationId       会话ID，不能为空
     * @param latestMessageContent 最新消息内容，用于向量检索查询
     * @param groupId              群ID
     * @return AI 生成的回复内容，不需要回复时返回 null
     */
    @Override
    public String handleAutonomousChat(String sceneType, String conversationId, String latestMessageContent, long groupId) {
        try {
            // 1. 加载近期上下文（最新消息已入库，会包含在内）
            String recentContext = buildRecentContext(sceneType, conversationId);

            // 2. 从向量库检索长久记忆（用最新消息内容作为查询）
            String longTermMemory = buildLongTermMemory(latestMessageContent, conversationId);

            // 3. 构建自主判断提示词
            String systemPrompt = buildAutonomousSystemPrompt();
            String userPrompt = buildAutonomousUserPrompt(recentContext, longTermMemory);

            // 4. 收集图片
            List<String> allImageMd5 = collectImageMd5FromText(userPrompt);
            List<ImageMedia> imageMediaList = resolveImageMedia(allImageMd5);

            // 5. 调用 AI 自主判断
            log.info("触发AI自主判断, groupId={}, latestMessage={}, imageCount={}",
                    groupId, latestMessageContent, imageMediaList.size());
            String reply = chatClient.prompt()
                    .toolCallbacks(mcpBean.getToolCallbacks())
                    .system(systemPrompt)
                    .user(userSpec -> {
                        userSpec.text(userPrompt);
                        for (ImageMedia media : imageMediaList) {
                            userSpec.media(media.mimeType(), media.resource());
                        }
                    })
                    .call()
                    .content();
            log.debug("AI自主判断回复完成, groupId={}, replyLength={}, out={}", groupId, reply == null ? 0 : reply.length(), reply);
            // 6. 检查是否为"不回复"标记
            if (reply != null && reply.contains(NO_REPLY_MARKER)) {
                log.debug("AI自主判断不需要回复, groupId={}", groupId);
                return null;
            }

            log.info("AI自主判断回复完成, groupId={}, replyLength={}", groupId, reply == null ? 0 : reply.length());
            assert reply != null;
            reply = reply.replaceFirst("需要回复:", "");
            return reply;
        } catch (Exception ex) {
            log.error("AI自主判断回复失败, groupId={}", groupId, ex);
            return null;
        }
    }

    /**
     * 构建自主判断模式下的系统提示词，指导 AI 判断是否需要回复。
     *
     * @return 自主判断系统提示词
     */
    private String buildAutonomousSystemPrompt() {
        BotProperties.PersonalityConfig personality = botProperties.getPersonality();
        String name = personality.getName();
        String description = personality.getDescription();
        return description + "\n"
                + "你的名字是「" + name + "」。\n"
                + "你正在一个QQ群聊中，之前被 @并已回复，现在对话仍在继续。\n"
                + "请根据近期对话上下文和长期记忆，判断你是否需要继续回复,是否用户继续追问了：\n"
                + "- 如果对话与你无关，或你已经充分回答，不需要追加回复，请回复 [NO_REPLY]\n"
                + "- 如果你有必要补充、纠正或提供新的有价值信息，请直接回复内容\n"
                + "- 不要过度插话，只在确实有必要时才回复\n"
                + "回复要简洁自然，适合聊天环境，不要过长。";
    }

    /**
     * 构建自主判断模式下的用户提示词，不含单独的用户消息段（最新消息已在近期上下文中）。
     *
     * @param recentContext  近期上下文
     * @param longTermMemory 长期记忆
     * @return 自主判断用户提示词
     */
    private String buildAutonomousUserPrompt(String recentContext, String longTermMemory) {
        StringBuilder sb = new StringBuilder();
        if (!longTermMemory.isBlank()) {
            sb.append("【长期记忆（历史相似对话）】\n").append(longTermMemory).append("\n\n");
        }
        if (!recentContext.isBlank()) {
            sb.append("【近期对话上下文】\n").append(recentContext).append("\n\n");
        }
        sb.append("请判断是否需要回复上述对话。不需要回复则输出 [NO_REPLY]。");
        return sb.toString();
    }

    /**
     * 从文本中解析 [图片:md5] 占位符，收集所有 MD5。
     *
     * @param text 含占位符的文本，允许为空
     * @return 去重后的 MD5 列表
     */
    private List<String> collectImageMd5FromText(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        Matcher matcher = IMAGE_PLACEHOLDER_PATTERN.matcher(text);
        Set<String> md5Set = new LinkedHashSet<>();
        while (matcher.find()) {
            String md5 = matcher.group(1);
            if (md5 != null && !md5.isBlank()) {
                md5Set.add(md5);
            }
        }
        return new ArrayList<>(md5Set);
    }

    /**
     * 将 MD5 列表解析为可用于多模态对话的图片资源集合。
     *
     * @param imageMd5List 图片 MD5 列表
     * @return 图片资源集合，数量不超过 MAX_IMAGE_MEDIA
     */
    private List<ImageMedia> resolveImageMedia(List<String> imageMd5List) {
        List<ImageMedia> result = new ArrayList<>();
        if (imageMd5List == null || imageMd5List.isEmpty()) {
            return result;
        }
        for (String md5 : imageMd5List) {
            if (md5 == null || md5.isBlank()) {
                continue;
            }
            Path file = findImageFile(md5);
            if (file == null) {
                continue;
            }
            MimeType mimeType = resolveImageMimeType(file);
            Resource resource = new FileSystemResource(file.toFile());
            result.add(new ImageMedia(mimeType, resource));
            if (result.size() >= MAX_IMAGE_MEDIA) {
                break;
            }
        }
        return result;
    }

    /**
     * 根据 MD5 在本地图片目录中查找对应文件。
     *
     * @param md5 图片内容 MD5
     * @return 找到的文件路径，未找到时返回 null
     */
    private Path findImageFile(String md5) {
        try {
            Path dir = IMAGE_STORAGE_DIR.toAbsolutePath().normalize();
            String[] exts = new String[]{".jpg", ".jpeg", ".png", ".gif", ".webp"};
            for (String ext : exts) {
                Path candidate = dir.resolve(md5 + ext);
                if (Files.exists(candidate)) {
                    return candidate;
                }
            }
        } catch (Exception ex) {
            log.warn("查找本地图片文件失败, md5={}", md5, ex);
        }
        return null;
    }

    /**
     * 根据文件扩展名推断图片 MimeType。
     *
     * @param file 本地图片文件
     * @return 对应的 MimeType
     */
    private MimeType resolveImageMimeType(Path file) {
        String name = file.getFileName().toString().toLowerCase();
        if (name.endsWith(".png")) {
            return MimeTypeUtils.IMAGE_PNG;
        }
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) {
            return MimeTypeUtils.IMAGE_JPEG;
        }
        if (name.endsWith(".gif")) {
            return MimeTypeUtils.IMAGE_GIF;
        }
        if (name.endsWith(".webp")) {
            return MimeTypeUtils.parseMimeType("image/webp");
        }
        return MimeTypeUtils.IMAGE_JPEG;
    }

    /**
     * 封装图片多模态输入所需的 MimeType 与 Resource。
     */
    private record ImageMedia(MimeType mimeType, Resource resource) {
    }
}
