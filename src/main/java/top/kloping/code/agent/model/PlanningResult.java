package top.kloping.code.agent.model;

import com.alibaba.fastjson.annotation.JSONField;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 规划Agent响应结果
 *
 * @author kloping
 */
@Data
public class PlanningResult {

    @JSONField(name = "should_retrieve")
    private Boolean shouldRetrieve;

    @JSONField(name = "query_ids")
    private List<Long> queryIds;

    private List<ToolPlan> tools;

    @Data
    public static class ToolPlan {
        @JSONField(name = "tool_name")
        private String toolName;

        private String purpose;

        @JSONField(name = "required_params")
        private Map<String, Object> requiredParams;

        @JSONField(name = "fallback_tool")
        private String fallbackTool;
    }
}
