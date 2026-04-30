package top.kloping.code.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 消息存储与向量化配置类。
 * 用于统一管理消息拼接窗口和向量化内容长度阈值。
 */
@Data
@Component
@ConfigurationProperties(prefix = "sheng.message")
public class MessageStorageProperties {

    /**
     * 同一发送人在同一会话内允许自动拼接的时间窗口，单位为分钟。
     */
    private long mergeWindowMinutes = 3L;

    /**
     * 触发一次向量化的格式化对话内容长度阈值。
     * 单位为字符数，基于格式化后的展示文本计算。
     */
    private int vectorizationContentLengthThreshold = 320;
}
