package top.kloping.code.agent.core;

import com.alibaba.fastjson.JSON;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;
import top.kloping.code.agent.model.PlanningResult;
import top.kloping.code.entity.MessageRecord;

import java.util.List;
import java.util.Map;

/**
 * 群内机器人规划 Agent
 * 职责：负责群内回复的逻辑规划；重点：只规划不行动。
 *
 * @author kloping
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class GroupPlanningAgent {

    private final ChatClient chatClient;

    private static final String SYSTEM_PROMPT = """
            你是一个群内机器人规划 Agent，负责群内回复的逻辑规划；重点：只规划不行动。
            
            你的任务是：
            1. 分析用户输入的消息列表，判断是否需要从知识库或历史记忆中检索相关内容以辅助回复。
            2. 根据当前拥有的工具列表，规划需要调用哪些工具、传递什么参数来完成用户的意图，并提供每个工具的使用目的和备选工具。
            
            请严格以以下JSON格式输出，不要包含任何其他说明文字或Markdown标记，确保能被解析为合法JSON：
            {
              "should_retrieve": true,
              "query_ids": [1,2,3],
              "tools": [
                {
                  "tool_name": "...",
                  "purpose": "用于...",
                  "required_params": {"param1": "value_from_message"},
                  "fallback_tool": "..."
                }
              ]
            }
            
            其中 query_ids 为需要作为检索Query的消息ID列表。
            """;

    /**
     * 根据用户输入消息和可用工具进行逻辑规划。
     *
     * @param inputMessages 用户输入的相关消息列表 非空
     * @param availableTools 当前可用工具映射，key-工具名，value-工具描述
     * @return 规划结果，解析失败则返回null
     *
     * TODO: 待后续确定具体的触发时机与组装逻辑
     */
    public PlanningResult plan(List<MessageRecord> inputMessages, Map<String, String> availableTools) {
        if (inputMessages == null || inputMessages.isEmpty()) {
            return null;
        }
        log.info("准备规划：{} 相关问题", inputMessages.getLast());
        StringBuilder userPromptBuilder = new StringBuilder();
        userPromptBuilder.append("用户输入以下相关内容:\n");
        for (MessageRecord msg : inputMessages) {
            userPromptBuilder.append("ID: ").append(msg.getId())
                    .append(", 内容: ").append(msg.getNormalizedText()).append("\n");
        }

        userPromptBuilder.append("\n当前拥有以下相关工具:\n");
        if (availableTools != null) {
            int index = 1;
            // 将“工具名: 工具描述”逐条写入提示词，帮助模型准确规划调用链路
            for (Map.Entry<String, String> entry : availableTools.entrySet()) {
                userPromptBuilder.append(index++).append(". ").append(entry.getKey());
                if (entry.getValue() != null && !entry.getValue().isBlank()) {
                    userPromptBuilder.append(": ").append(entry.getValue());
                }
                userPromptBuilder.append("\n");
            }
        }

        userPromptBuilder.append("\n请进行规划。");

        try {
            log.info("规划提示词：{}", userPromptBuilder);
            String responseStr = chatClient.prompt(new Prompt(List.of(
                    new SystemMessage(SYSTEM_PROMPT),
                    new UserMessage(userPromptBuilder.toString())
            ))).call().content();

            if (responseStr != null) {
                // 简单清理可能包含的 markdown 标签
                responseStr = responseStr.replaceAll("(?i)^```json", "")
                        .replaceAll("```$", "")
                        .trim();
                return JSON.parseObject(responseStr, PlanningResult.class);
            }
        } catch (Exception e) {
            log.error("调用规划Agent失败", e);
        }
        return null;
    }
}
