package top.kloping.code.agent;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.mamoe.mirai.Bot;
import net.mamoe.mirai.message.data.At;
import net.mamoe.mirai.message.data.MessageChainBuilder;
import net.mamoe.mirai.message.data.PlainText;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import top.kloping.code.agent.core.GroupActionAgent;
import top.kloping.code.agent.core.GroupPlanningAgent;
import top.kloping.code.agent.core.MessageSummaryAgent;
import top.kloping.code.agent.model.ActionResult;
import top.kloping.code.agent.model.PlanningResult;
import top.kloping.code.entity.MessageRecord;
import top.kloping.code.service.IMessageRecordService;

import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Agent 门面服务，负责对外统一暴露 Agent 能力入口。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentFacade {

    private static final String GROUP_SCENE_TYPE = "GROUP";
    private static final int PLANNING_MIN_MESSAGE_COUNT = 12;
    private static final int ACTION_MIN_MESSAGE_COUNT = 12;
    private static final int MEMORY_RETRIEVE_TOP_K = 1;

    private final MessageSummaryAgent messageSummaryAgent;
    private final GroupActionAgent groupActionAgent;
    private final GroupPlanningAgent groupPlanningAgent;
    private final IMessageRecordService messageRecordService;
    private final VectorStore vectorStore;
    private final Bot bot;

    private final Lock planningLock = new ReentrantLock();

    /**
     * 根据最新接收消息触发自动摘要流程。
     *
     * @param currentRecord 当前刚入库的消息记录，非空
     */
    public void triggerAutoSummary(MessageRecord currentRecord) {
        messageSummaryAgent.triggerAutoSummary(currentRecord);
        if (Boolean.TRUE.equals(currentRecord.getMentionedBot())) {
            List<MessageRecord> planningMessages = loadPlanningMessages(currentRecord);
            if (planningMessages.isEmpty()) {
                return;
            }
            triggerPlanning(planningMessages, toolLists);
        }
    }

    @Autowired
    @Qualifier("toolLists")
    private Map<String, String> toolLists;

    /**
     * 根据输入消息触发回复规划流程。
     *
     * @param inputMessages  待规划的相关消息列表，非空
     * @param availableTools 可用工具映射，key-工具名，value-工具描述
     */
    public void triggerPlanning(List<MessageRecord> inputMessages, Map<String, String> availableTools) {
        planningLock.lock();
        ActionResult actionResult;
        Long conversationId;
        try {
            PlanningResult planningResult = groupPlanningAgent.plan(inputMessages, availableTools);
            log.info("规划结果：{}", planningResult);
            // 规划失败或无有效结果时，不进入后续行动阶段
            if (planningResult == null) {
                return;
            }

            List<String> retrievedMemories = retrievePlannedMemories(planningResult, inputMessages);
            conversationId = resolveConversationId(inputMessages);
            List<MessageRecord> latestMessages = loadActionMessages(conversationId);

            actionResult = groupActionAgent.act(planningResult, retrievedMemories, latestMessages);
            if (actionResult == null) {
                return;
            }
            log.info("行动Agent输出：{}", actionResult);
        } finally {
            planningLock.unlock();
        }
        // 消息发送交由虚拟线程异步执行，不阻塞规划锁
        ActionResult finalActionResult = actionResult;
        Long finalConversationId = conversationId;
        executorService.submit(() -> executeAction(finalActionResult, finalConversationId));
    }

    public static ExecutorService executorService = Executors.newSingleThreadExecutor();

    /**
     * 加载用于规划的消息上下文。
     *
     * @param currentRecord 当前消息记录，非空
     * @return 规划上下文消息列表，按时间升序
     */
    private List<MessageRecord> loadPlanningMessages(MessageRecord currentRecord) {
        if (currentRecord == null || currentRecord.getConversationId() == null) {
            return Collections.emptyList();
        }

        List<MessageRecord> pendingRecords = messageRecordService.list(new LambdaQueryWrapper<MessageRecord>()
                .eq(MessageRecord::getSceneType, GROUP_SCENE_TYPE)
                .eq(MessageRecord::getConversationId, currentRecord.getConversationId())
                .and(wrapper -> wrapper.eq(MessageRecord::getVectorized, false).or().isNull(MessageRecord::getVectorized))
                .orderByAsc(MessageRecord::getReceivedAt)
                .orderByAsc(MessageRecord::getId));

        // 优先使用会话内全部未向量化消息，满足阈值时可保留完整待处理上下文
        if (pendingRecords != null && pendingRecords.size() >= PLANNING_MIN_MESSAGE_COUNT) {
            return pendingRecords;
        }

        List<MessageRecord> latestRecordsDesc = messageRecordService.list(new LambdaQueryWrapper<MessageRecord>()
                .eq(MessageRecord::getSceneType, GROUP_SCENE_TYPE)
                .eq(MessageRecord::getConversationId, currentRecord.getConversationId())
                .orderByDesc(MessageRecord::getReceivedAt)
                .orderByDesc(MessageRecord::getId)
                .last("LIMIT " + PLANNING_MIN_MESSAGE_COUNT));

        if (latestRecordsDesc == null || latestRecordsDesc.isEmpty()) {
            return Collections.emptyList();
        }

        // 降序查询后反转为升序，便于LLM按真实对话时间线理解上下文
        Collections.reverse(latestRecordsDesc);
        return latestRecordsDesc;
    }

    /**
     * 按规划结果执行记忆检索。
     *
     * @param planningResult 规划结果，非空
     * @param inputMessages  当前规划输入消息
     * @return 检索到的记忆文本列表
     */
    private List<String> retrievePlannedMemories(PlanningResult planningResult, List<MessageRecord> inputMessages) {
        if (!Boolean.TRUE.equals(planningResult.getShouldRetrieve())
                || planningResult.getQueryIds() == null
                || planningResult.getQueryIds().isEmpty()) {
            return Collections.emptyList();
        }

        Long conversationId = resolveConversationId(inputMessages);

        Map<Long, MessageRecord> recordById = new HashMap<>();
        if (inputMessages != null) {
            for (MessageRecord inputMessage : inputMessages) {
                if (inputMessage != null && inputMessage.getId() != null) {
                    recordById.put(inputMessage.getId(), inputMessage);
                }
            }
        }

        for (Long queryId : planningResult.getQueryIds()) {
            if (queryId == null) {
                continue;
            }

            MessageRecord queryRecord = recordById.get(queryId);
            if (queryRecord == null) {
                queryRecord = messageRecordService.getById(queryId);
            }
            if (queryRecord == null) {
                continue;
            }
            // 限制仅检索当前会话消息，避免模型给出越界queryId导致跨会话干扰
            if (conversationId != null && !conversationId.equals(queryRecord.getConversationId())) {
                continue;
            }

            // 按“id 事件 内容”格式组装检索词，提升语义召回相关率
            String retrievalQuery = buildRetrievalQuery(queryRecord);
            List<Document> matchedDocuments = searchTopOneMemory(retrievalQuery);
            if (matchedDocuments.isEmpty()) {
                continue;
            }

            Document firstDocument = matchedDocuments.get(0);
            List<String> retrievedMemories = new ArrayList<>();
            retrievedMemories.add(buildMemoryText(queryRecord, firstDocument));
            // 单次规划最多取1条记忆，避免上下文被历史信息淹没
            return retrievedMemories;
        }
        return Collections.emptyList();
    }

    /**
     * 执行向量检索并返回Top1记忆。
     *
     * @param retrievalQuery 检索词
     * @return 匹配文档列表
     */
    private List<Document> searchTopOneMemory(String retrievalQuery) {
        try {
            return vectorStore.similaritySearch(SearchRequest.builder()
                    .query(retrievalQuery)
                    .topK(MEMORY_RETRIEVE_TOP_K)
                    .build());
        } catch (Exception ex) {
            log.warn("检索记忆失败, query={}", retrievalQuery, ex);
            return Collections.emptyList();
        }
    }

    /**
     * 构建向量检索词。
     *
     * @param messageRecord 消息记录，非空
     * @return 结构化检索词
     */
    private String buildRetrievalQuery(MessageRecord messageRecord) {
        return "id:" + messageRecord.getSenderId()
                + "\n事件:" + messageRecord.getSceneType()
                + "\n内容:" + resolveMessageContent(messageRecord);
    }

    /**
     * 构建可传递给行动Agent的记忆文本。
     *
     * @param queryRecord 检索触发消息
     * @param document    命中的记忆文档
     * @return 格式化记忆文本
     */
    private String buildMemoryText(MessageRecord queryRecord, Document document) {
        StringBuilder memoryTextBuilder = new StringBuilder();
        memoryTextBuilder.append("检索依据=")
                .append(buildRetrievalQuery(queryRecord))
                .append("; 记忆内容=")
                .append(document.getText());
        if (!document.getMetadata().isEmpty()) {
            memoryTextBuilder.append("; 元数据=").append(document.getMetadata());
        }
        return memoryTextBuilder.toString();
    }

    /**
     * 加载行动阶段的消息上下文。
     * 优先获取会话内未向量化消息；不足12条时取最新12条（无论是否向量化）。
     *
     * @param conversationId 会话ID
     * @return 行动上下文消息列表，按时间升序
     */
    private List<MessageRecord> loadActionMessages(Long conversationId) {
        if (conversationId == null) {
            return Collections.emptyList();
        }

        List<MessageRecord> pendingRecords = messageRecordService.list(new LambdaQueryWrapper<MessageRecord>()
                .eq(MessageRecord::getSceneType, GROUP_SCENE_TYPE)
                .eq(MessageRecord::getConversationId, conversationId)
                .and(wrapper -> wrapper.eq(MessageRecord::getVectorized, false).or().isNull(MessageRecord::getVectorized))
                .orderByAsc(MessageRecord::getReceivedAt)
                .orderByAsc(MessageRecord::getId));

        // 未向量化消息达到阈值时直接使用，保留完整待处理上下文
        if (pendingRecords != null && pendingRecords.size() >= ACTION_MIN_MESSAGE_COUNT) {
            return pendingRecords;
        }

        // 未向量化消息不足12条，回退取最新12条（含已向量化）
        List<MessageRecord> latestRecordsDesc = messageRecordService.list(new LambdaQueryWrapper<MessageRecord>()
                .eq(MessageRecord::getSceneType, GROUP_SCENE_TYPE)
                .eq(MessageRecord::getConversationId, conversationId)
                .orderByDesc(MessageRecord::getReceivedAt)
                .orderByDesc(MessageRecord::getId)
                .last("LIMIT " + ACTION_MIN_MESSAGE_COUNT));

        if (latestRecordsDesc == null || latestRecordsDesc.isEmpty()) {
            return Collections.emptyList();
        }
        // 降序查询后反转为升序，便于行动Agent按真实对话时间线理解上下文
        Collections.reverse(latestRecordsDesc);
        return latestRecordsDesc;
    }

    /**
     * 从输入消息中解析会话ID。
     *
     * @param inputMessages 输入消息列表
     * @return 会话ID，不存在时返回null
     */
    private Long resolveConversationId(List<MessageRecord> inputMessages) {
        if (inputMessages == null || inputMessages.isEmpty()) {
            return null;
        }
        MessageRecord latestMessage = inputMessages.get(inputMessages.size() - 1);
        return latestMessage.getConversationId();
    }

    /**
     * 提取消息内容，优先原始文本，回退归一化文本。
     *
     * @param messageRecord 消息记录，非空
     * @return 消息文本
     */
    private String resolveMessageContent(MessageRecord messageRecord) {
        if (messageRecord == null) {
            return "[空消息]";
        }
        if (messageRecord.getRawText() != null && !messageRecord.getRawText().isBlank()) {
            return messageRecord.getRawText();
        }
        if (messageRecord.getNormalizedText() != null && !messageRecord.getNormalizedText().isBlank()) {
            return messageRecord.getNormalizedText();
        }
        return "[空消息]";
    }

    private static final Pattern SLEEP_PATTERN = Pattern.compile("^(\\d+)(ms|s|m)$");

    /**
     * 执行行动结果，按步骤向群发送消息。
     *
     * @param actionResult   行动结果，非空
     * @param conversationId 会话ID（群号），非空
     */
    private void executeAction(ActionResult actionResult, Long conversationId) {
        if (actionResult == null || !Boolean.TRUE.equals(actionResult.getSend())
                || actionResult.getContent() == null || actionResult.getContent().isEmpty()
                || conversationId == null) {
            return;
        }

        net.mamoe.mirai.contact.Group group = bot.getGroup(conversationId);
        if (group == null) {
            log.warn("无法获取群对象, groupId={}", conversationId);
            return;
        }

        for (ActionResult.ActionContent step : actionResult.getContent()) {
            if (step == null) {
                continue;
            }

            // 延迟步骤：解析时长后休眠，不发送消息
            if (step.getSleep() != null && !step.getSleep().isBlank()) {
                long sleepMillis = parseSleepMillis(step.getSleep());
                if (sleepMillis > 0) {
                    try {
                        Thread.sleep(sleepMillis);
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        log.warn("行动步骤延迟被中断, sleep={}", step.getSleep());
                    }
                }
                continue;
            }

            // 发送步骤：组装At + PlainText消息链并发送
            if (step.getText() != null && !step.getText().isBlank()) {
                MessageChainBuilder chainBuilder = new MessageChainBuilder();
                if (step.getAt() != null && !step.getAt().isEmpty()) {
                    for (Long targetId : step.getAt()) {
                        chainBuilder.add(new At(targetId));
                    }
                }
                chainBuilder.add(new PlainText(step.getText()));
                try {
                    group.sendMessage(chainBuilder.build());
                    log.info("已发送群消息, groupId={}, text={}", conversationId, step.getText());
                } catch (Exception ex) {
                    log.error("发送群消息失败, groupId={}", conversationId, ex);
                }
            }
        }
    }

    /**
     * 解析延迟时长字符串为毫秒数。
     *
     * @param sleepStr 延迟时长字符串，如1s、500ms、2m
     * @return 毫秒数；解析失败返回0
     */
    private long parseSleepMillis(String sleepStr) {
        if (sleepStr == null || sleepStr.isBlank()) {
            return 0;
        }
        Matcher matcher = SLEEP_PATTERN.matcher(sleepStr.trim().toLowerCase());
        if (!matcher.matches()) {
            return 0;
        }
        long value = Long.parseLong(matcher.group(1));
        String unit = matcher.group(2);
        return switch (unit) {
            case "ms" -> value;
            case "s" -> value * 1000;
            case "m" -> value * 60 * 1000;
            default -> 0;
        };
    }
}
