package top.kloping.code.agent;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import top.kloping.code.agent.core.MessageSummaryAgent;
import top.kloping.code.entity.MessageRecord;

/**
 * Agent 门面服务，负责对外统一暴露 Agent 能力入口。
 */
@Service
@RequiredArgsConstructor
public class AgentFacade {

    private final MessageSummaryAgent messageSummaryAgent;

    /**
     * 根据最新接收消息触发自动摘要流程。
     *
     * @param currentRecord 当前刚入库的消息记录，非空
     */
    public void triggerAutoSummary(MessageRecord currentRecord) {
        messageSummaryAgent.triggerAutoSummary(currentRecord);
    }
}
