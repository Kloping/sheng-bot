package top.kloping.code;


/**
 * 小生AI Agent 启动主类
 * <br/><strong>Created at 16:40<strong/>
 *
 * @author github kloping
 * @since 2026/04/12
 */
@org.springframework.boot.autoconfigure.SpringBootApplication
public class ShengBotApplication {
    public static void main(String[] args) {
        org.springframework.context.ConfigurableApplicationContext context = org.springframework.boot.SpringApplication.run(ShengBotApplication.class, args);
        // 全局注册
        String[] names = context.getBeanNamesForType(net.mamoe.mirai.event.ListenerHost.class);
        for (String name : names) {
            net.mamoe.mirai.event.ListenerHost listenerHost = context.getBean(name, net.mamoe.mirai.event.ListenerHost.class);
            net.mamoe.mirai.event.GlobalEventChannel.INSTANCE.registerListenerHost(listenerHost);
        }
    }

}
