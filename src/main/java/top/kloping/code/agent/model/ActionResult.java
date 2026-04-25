package top.kloping.code.agent.model;

import lombok.Data;

import java.util.List;

/**
 * 行动Agent标准化输出结果。
 */
@Data
public class ActionResult {

    /**
     * 是否需要发送消息，默认true。
     */
    private Boolean send = true;

    /**
     * 发送内容步骤列表。
     */
    private List<ActionContent> content;

    /**
     * 单步发送动作，支持at/text/sleep字段。
     */
    @Data
    public static class ActionContent {

        /**
         * 需要@的用户QQ号列表。
         */
        private List<Long> at;

        /**
         * 当前步骤发送的文本内容。
         */
        private String text;

        /**
         * 当前步骤执行后的延迟时长，如1s。
         */
        private String sleep;
    }
}
