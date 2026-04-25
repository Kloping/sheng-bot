package top.kloping.code.agent;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import top.kloping.code.agent.core.GroupPlanningAgent;
import top.kloping.code.agent.core.MessageSummaryAgent;
import top.kloping.code.agent.model.PlanningResult;
import top.kloping.code.entity.MessageRecord;
import top.kloping.code.service.IMessageRecordService;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Agent 门面服务，负责对外统一暴露 Agent 能力入口。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentFacade {

    private static final String GROUP_SCENE_TYPE = "GROUP";
    private static final int PLANNING_MIN_MESSAGE_COUNT = 12;

    private final MessageSummaryAgent messageSummaryAgent;
    private final GroupPlanningAgent groupPlanningAgent;
    private final IMessageRecordService messageRecordService;

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
        PlanningResult planningResult = groupPlanningAgent.plan(inputMessages, availableTools);
        // 规划失败或无有效结果时，不进入后续行动阶段
        log.info("规划结果：{}", planningResult);
        if (planningResult == null) {
            return;
        }
        // TODO: 行动Agent尚未开发，后续在此根据planningResult触发实际执行
    }

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
}
