package top.kloping.code.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import top.kloping.core.ai.McpBean;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AI配置类
 */
@Configuration
public class AiConfig {

    @Bean(name = "toolLists")
    public Map<String, String> toolLists(McpBean mcpBean) {
        Map<String, String> toolMap = new LinkedHashMap<>();
        // name -> description
        for (ToolCallback toolCallback : mcpBean.getToolCallbacks()) {
            ToolDefinition toolDefinition = toolCallback.getToolDefinition();
            toolMap.put(toolDefinition.name(), toolDefinition.description());
        }
        return toolMap;
    }

    @Bean
    public ChatClient chatClient(org.springframework.ai.chat.model.ChatModel chatModel) {
        return ChatClient.builder(chatModel).build();
    }
}
