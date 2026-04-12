package top.kloping.code.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 机器人配置属性
 */
@Data
@Component
@ConfigurationProperties(prefix = "bot")
public class BotProperties {
    
    /**
     * 机器人QQ号
     */
    private Long account;
    
    /**
     * 是否启用机器人
     */
    private boolean enabled = true;
    
    /**
     * 群聊配置
     */
    private GroupConfig group = new GroupConfig();
    
    /**
     * 私聊配置
     */
    private PrivateConfig private_ = new PrivateConfig();
    
    /**
     * AI人格设定
     */
    private PersonalityConfig personality = new PersonalityConfig();
    
    @Data
    public static class GroupConfig {
        /**
         * 允许响应的群列表（为空则响应所有群）
         */
        private List<Long> allowedGroups = new ArrayList<>();

        /**
         * 黑名单群列表（在黑名单内则禁用全部机器人功能）
         */
        private List<Long> blacklistedGroups = new ArrayList<>();
        
        /**
         * 是否响应所有消息
         */
        private boolean respondAll = false;
    }
    
    @Data
    public static class PrivateConfig {
        /**
         * 是否响应私聊
         */
        private boolean enabled = true;
        
        /**
         * 允许私聊的用户列表（为空则允许所有）
         */
        private List<Long> allowedUsers = new ArrayList<>();
    }
    

    @Data
    public static class PersonalityConfig {
        /**
         * 机器人名称
         */
        private String name = "智能助手";
        
        /**
         * 人格描述
         */
        private String description = "你是一个友好、乐于助人的QQ机器人助手。";
    }
}
