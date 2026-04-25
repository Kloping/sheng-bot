package top.kloping.code.agent.core;

import com.alibaba.fastjson.JSON;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;
import top.kloping.code.agent.model.ActionResult;
import top.kloping.code.agent.model.PlanningResult;
import top.kloping.code.entity.MessageRecord;
import top.kloping.core.ai.McpBean;

import java.util.List;

/**
 * 群内机器人行动 Agent。
 * 职责：根据规划结果、检索记忆和最新消息生成标准化发送动作。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class GroupActionAgent {
    private final McpBean mcpBean;
    private final ChatClient chatClient;

    private static final String SYSTEM_PROMPT = """
            你是一个群内机器人行动 Agent。
            
            你会收到：
            1. 规划结果（含工具调用用途）
            2. 检索到的记忆
            3. 最新聊天消息
            
            你的任务：仅输出可执行的发送动作JSON，严格符合以下结构，禁止输出任何多余文本：
            {
              "send": true,
              "content": [
                {
                  "at": [123456789],
                  "text": "消息内容"
                },
                {
                  "sleep": "1s"
                },
                {
                  "text": "下一条消息"
                }
              ]
            }
            
            约束：
            1. send 默认必须为 true；仅在明确不应回复时才设置为 false。
            2. content 为发送步骤数组；每个对象只能包含当前步骤所需字段。
            3. at 字段仅放数字QQ号数组。
            4. sleep 字段使用时长字符串，如 "1s"、"500ms"。
            5. 输出必须是合法JSON，禁止Markdown代码块。
            """;

    /**
     * 基于规划上下文生成行动输出。
     *
     * @param planningResult    规划结果，非空
     * @param retrievedMemories 检索到的记忆文本列表
     * @param latestMessages    最新消息列表，按时间升序
     * @return 标准化行动结果，解析失败时返回null
     */
    public ActionResult act(PlanningResult planningResult,
                            List<String> retrievedMemories,
                            List<MessageRecord> latestMessages) {
        if (planningResult == null) {
            return null;
        }

        StringBuilder userPromptBuilder = new StringBuilder();
        userPromptBuilder.append("规划结果:\n")
                .append(JSON.toJSONString(planningResult))
                .append("\n\n");

        userPromptBuilder.append("检索记忆结果:\n");
        if (retrievedMemories == null || retrievedMemories.isEmpty()) {
            userPromptBuilder.append("- 无检索结果\n\n");
        } else {
            for (int i = 0; i < retrievedMemories.size(); i++) {
                userPromptBuilder.append(i + 1).append(". ").append(retrievedMemories.get(i)).append("\n");
            }
            userPromptBuilder.append("\n");
        }

        userPromptBuilder.append("最新消息(按时间顺序):\n");
        if (latestMessages != null) {
            for (MessageRecord latestMessage : latestMessages) {
                userPromptBuilder.append("{qqid:")
                        .append(latestMessage.getSenderId())
                        .append(", 事件:")
                        .append(latestMessage.getSceneType())
                        .append(", 内容:")
                        .append(resolveMessageContent(latestMessage))
                        .append("}\n");
            }
        }

        try {
            String response = chatClient.prompt(new Prompt(List.of(
                    new SystemMessage(SYSTEM_PROMPT),
                    new UserMessage(userPromptBuilder.toString())
            ))).toolCallbacks(mcpBean.getToolCallbacks()).call().content();
            if (response == null || response.isBlank()) {
                return null;
            }

            // 兼容模型误输出Markdown代码块，清理后再解析JSON
            String normalizedResponse = response.replaceAll("(?i)^```json", "")
                    .replaceAll("```$", "")
                    .trim();
            return JSON.parseObject(normalizedResponse, ActionResult.class);
        } catch (Exception ex) {
            log.error("调用行动Agent失败", ex);
            return null;
        }
    }

    /**
     * 提取消息内容，优先原始文本，回退归一化文本。
     *
     * @param messageRecord 消息记录，非空
     * @return 消息文本
     */
    private String resolveMessageContent(MessageRecord messageRecord) {
        if (messageRecord.getRawText() != null && !messageRecord.getRawText().isBlank()) {
            return messageRecord.getRawText();
        }
        if (messageRecord.getNormalizedText() != null && !messageRecord.getNormalizedText().isBlank()) {
            return messageRecord.getNormalizedText();
        }
        return "[空消息]";
    }
}
