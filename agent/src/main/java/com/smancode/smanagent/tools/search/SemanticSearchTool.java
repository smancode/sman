package com.smancode.smanagent.tools.search;

import com.smancode.smanagent.tools.AbstractTool;
import com.smancode.smanagent.tools.ParameterDef;
import com.smancode.smanagent.tools.Tool;
import com.smancode.smanagent.tools.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 语义搜索工具
 * <p>
 * 根据语义相似性搜索代码片段。
 * 执行模式：local（后端执行）
 */
@Component
public class SemanticSearchTool extends AbstractTool implements Tool {

    private static final Logger logger = LoggerFactory.getLogger(SemanticSearchTool.class);

    @Override
    public String getName() {
        return "semantic_search";
    }

    @Override
    public String getDescription() {
        return "根据语义相似性搜索代码片段";
    }

    @Override
    public Map<String, ParameterDef> getParameters() {
        Map<String, ParameterDef> params = new HashMap<>();
        params.put("query", new ParameterDef("query", String.class, true, "搜索查询"));
        params.put("topK", new ParameterDef("topK", Integer.class, false, "返回结果数量（默认 10）", 10));
        params.put("mode", new ParameterDef("mode", String.class, false, "执行模式：local/intellij", "local"));
        return params;
    }

    @Override
    public ToolResult execute(String projectKey, Map<String, Object> params) {
        long startTime = System.currentTimeMillis();

        try {
            // 获取参数
            String query = (String) params.get("query");
            if (query == null || query.trim().isEmpty()) {
                return ToolResult.failure("缺少 query 参数");
            }

            int topK = getOptInt(params, "topK", 10);
            if (topK <= 0) {
                return ToolResult.failure("topK 必须大于 0");
            }

            logger.info("执行语义搜索: projectKey={}, query={}, topK={}", projectKey, query, topK);

            // TODO: 调用实际的语义搜索服务
            // 目前返回占位结果
            String displayContent = String.format(
                "语义搜索功能尚未完全实现\n" +
                "参数：projectKey=%s, query=%s, topK=%d",
                projectKey, query, topK
            );

            String displayTitle = "语义搜索（待实现）";

            long duration = System.currentTimeMillis() - startTime;

            ToolResult toolResult = ToolResult.success(null, displayTitle, displayContent);
            toolResult.setExecutionTimeMs(duration);
            return toolResult;

        } catch (Exception e) {
            logger.error("语义搜索失败", e);
            long duration = System.currentTimeMillis() - startTime;
            ToolResult toolResult = ToolResult.failure("搜索失败: " + e.getMessage());
            toolResult.setExecutionTimeMs(duration);
            return toolResult;
        }
    }

    @Override
    public ExecutionMode getExecutionMode(Map<String, Object> params) {
        // semantic_search 固定为 local 模式
        return ExecutionMode.LOCAL;
    }
}
