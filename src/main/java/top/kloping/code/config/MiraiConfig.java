package top.kloping.code.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.mamoe.mirai.Bot;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import top.mrxiaom.overflow.BotBuilder;

/**
 * Mirai机器人配置类
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class MiraiConfig {

    private final BotProperties botProperties;

    @Value("${bot.connection.type:ws}")
    private String connectionType;

    @Value("${bot.connection.ip:127.0.0.1}")
    private String ip;

    @Value("${bot.connection.port:3001}")
    private int port;

    @Value("${bot.connection.token:}")
    private String token;

    @Value("${bot.connection.heart:60}")
    private int heartbeatSeconds;

    @Value("${bot.connection.retry-times:3}")
    private int retryTimes;

    @Value("${bot.connection.retry-wait-mills:7000}")
    private long retryWaitMills;

    @Bean
    @ConditionalOnProperty(prefix = "bot", name = "enabled", havingValue = "true")
    public Bot miraiBot() {
        log.info("正在创建Overflow机器人实例...");
        log.info("连接方式: {}", connectionType);

        BotBuilder builder;
        if (connectionType.equalsIgnoreCase("ws")) {
            // 主动WebSocket连接
            builder = BotBuilder.positive("ws://" + ip + ":" + port + "/")
                    .retryTimes(retryTimes)
                    .retryWaitMills(retryWaitMills)
                    .retryRestMills(-1);
            log.info("主动WebSocket连接地址: {}", ip);
        } else {
            // 被动连接方式
            builder = BotBuilder.reversed(port);
            log.info("被动连接端口: {}", port);
        }

        builder.overrideLogger(log);
        builder.token(token);
        builder.heartbeatCheckSeconds(heartbeatSeconds);

        Bot bot = builder.connect();

        if (bot == null) {
            log.error("Overflow机器人实例创建失败");
            throw new RuntimeException("无法创建Bot实例");
        }

        log.info("Overflow机器人实例创建完成，账号: {}", bot.getId());
        return bot;
    }

    @Bean
    @ConditionalOnProperty(prefix = "bot", name = "enabled", havingValue = "true")
    public CommandLineRunner botInitRunner(Bot bot) {
        return args -> {
            log.info("Overflow机器人已连接，账号: {}", bot.getId());
        };
    }
}
