package com.smancode.smanagent.tools.read;

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
 * 读取文件工具
 * <p>
 * 读取文件内容。
 * 执行模式：intellij（IDE 执行）
 */
@Component
public class ReadFileTool extends AbstractTool implements Tool {

    private static final Logger logger = LoggerFactory.getLogger(ReadFileTool.class);

    @Override
    public String getName() {
        return "read_file";
    }

    @Override
    public String getDescription() {
        return "读取文件内容（支持行范围）";
    }

    @Override
    public Map<String, ParameterDef> getParameters() {
        Map<String, ParameterDef> params = new HashMap<>();
        params.put("relativePath", new ParameterDef("relativePath", String.class, true, "文件相对路径"));
        params.put("startLine", new ParameterDef("startLine", Integer.class, false, "开始行号（默认 1）", 1));
        params.put("endLine", new ParameterDef("endLine", Integer.class, false, "结束行号（默认 100）", 100));
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

            int startLine = getOptInt(params, "startLine", 1);
            int endLine = getOptInt(params, "endLine", 100);

            logger.info("执行读取文件: projectKey={}, relativePath={}, startLine={}, endLine={}",
                projectKey, relativePath, startLine, endLine);

            // 注意：这个工具需要在 IDE 中执行
            String displayContent = String.format(
                "工具需要在 IDE 中执行\n" +
                "参数：relativePath=%s, startLine=%d, endLine=%d",
                relativePath, startLine, endLine
            );

            long duration = System.currentTimeMillis() - startTime;

            ToolResult toolResult = ToolResult.success(null, "读取文件（IDE 执行）", displayContent);
            toolResult.setExecutionTimeMs(duration);
            return toolResult;

        } catch (Exception e) {
            logger.error("读取文件失败", e);
            long duration = System.currentTimeMillis() - startTime;
            ToolResult toolResult = ToolResult.failure("读取失败: " + e.getMessage());
            toolResult.setExecutionTimeMs(duration);
            return toolResult;
        }
    }
}
