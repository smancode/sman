package com.smancode.smanagent.tools.code;

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
 * 代码修改工具
 * <p>
 * 应用代码修改，在 IDE 中执行。
 * 执行模式：intellij（IDE 执行）
 */
@Component
public class ApplyChangeTool extends AbstractTool implements Tool {

    private static final Logger logger = LoggerFactory.getLogger(ApplyChangeTool.class);

    @Override
    public String getName() {
        return "apply_change";
    }

    @Override
    public String getDescription() {
        return "应用代码修改";
    }

    @Override
    public Map<String, ParameterDef> getParameters() {
        Map<String, ParameterDef> params = new HashMap<>();
        params.put("relativePath", new ParameterDef("relativePath", String.class, true, "文件相对路径"));
        params.put("searchContent", new ParameterDef("searchContent", String.class, true, "搜索内容"));
        params.put("replaceContent", new ParameterDef("replaceContent", String.class, true, "替换内容"));
        params.put("description", new ParameterDef("description", String.class, false, "修改描述"));
        params.put("mode", new ParameterDef("mode", String.class, false, "执行模式：local/intellij", "intellij"));
        return params;
    }

    @Override
    public ToolResult execute(String projectKey, Map<String, Object> params) {
        long startTime = System.currentTimeMillis();

        try {
            // 获取参数
            String relativePath = (String) params.get("relativePath");
            if (relativePath == null || relativePath.trim().isEmpty()) {
                return ToolResult.failure("缺少 relativePath 参数");
            }

            String searchContent = (String) params.get("searchContent");
            if (searchContent == null || searchContent.trim().isEmpty()) {
                return ToolResult.failure("缺少 searchContent 参数");
            }

            String replaceContent = (String) params.get("replaceContent");
            if (replaceContent == null || replaceContent.trim().isEmpty()) {
                return ToolResult.failure("缺少 replaceContent 参数");
            }

            String description = getOptString(params, "description", "");

            logger.info("执行代码修改: projectKey={}, relativePath={}, description={}",
                projectKey, relativePath, description);

            // 此工具需要通过 WebSocket 转发到 IDE 执行
            // 返回占位结果
            String displayContent = String.format(
                "工具需要在 IDE 中执行\n" +
                "参数：relativePath=%s\n" +
                "     searchContent=%s\n" +
                "     replaceContent=%s\n" +
                "     description=%s",
                relativePath,
                searchContent.substring(0, Math.min(50, searchContent.length())) + "...",
                replaceContent.substring(0, Math.min(50, replaceContent.length())) + "...",
                description
            );

            long duration = System.currentTimeMillis() - startTime;

            ToolResult toolResult = ToolResult.success(null, "代码修改（IDE 执行）", displayContent);
            toolResult.setExecutionTimeMs(duration);
            return toolResult;

        } catch (Exception e) {
            logger.error("代码修改失败", e);
            long duration = System.currentTimeMillis() - startTime;
            ToolResult toolResult = ToolResult.failure("修改失败: " + e.getMessage());
            toolResult.setExecutionTimeMs(duration);
            return toolResult;
        }
    }
}
