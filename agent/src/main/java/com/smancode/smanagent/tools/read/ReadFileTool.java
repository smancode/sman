package com.smancode.smanagent.tools.read;

import com.smancode.smanagent.tools.AbstractTool;
import com.smancode.smanagent.tools.ParameterDef;
import com.smancode.smanagent.tools.Tool;
import com.smancode.smanagent.tools.ToolResult;
import com.smancode.smanagent.util.StackTraceUtils;
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

    /**
     * 获取工具名称
     *
     * @return 工具名称
     */
    @Override
    public String getName() {
        return "read_file";
    }

    /**
     * 获取工具描述
     *
     * @return 工具描述信息
     */
    @Override
    public String getDescription() {
        return "Read file content. By default reads lines 1-300. To read entire file, set endLine to a large number (e.g., 999999).";
    }

    /**
     * 获取工具参数定义
     *
     * @return 参数定义 Map，包含 simpleName、relativePath、startLine、endLine、mode 等参数
     */
    @Override
    public Map<String, ParameterDef> getParameters() {
        Map<String, ParameterDef> params = new HashMap<>();
        params.put("simpleName", new ParameterDef("simpleName", String.class, false, "Class name (system will auto-find the file, recommended)"));
        params.put("relativePath", new ParameterDef("relativePath", String.class, false, "File relative path"));
        params.put("startLine", new ParameterDef("startLine", Integer.class, false, "Start line number (default: 1, use 1 for entire file)", 1));
        params.put("endLine", new ParameterDef("endLine", Integer.class, false, "End line number (default: 300, set to 999999 for entire file)", 300));
        params.put("mode", new ParameterDef("mode", String.class, false, "Execution mode: local/intellij", "intellij"));
        return params;
    }

    /**
     * 执行读取文件操作
     * <p>
     * 支持两种模式：
     * 1. simpleName 模式：通过类名自动查找文件（推荐）
     * 2. relativePath 模式：通过相对路径直接指定文件
     * <p>
     * 注意：此工具需要在 IDE 中执行，实际读取由 IDE 完成
     *
     * @param projectKey 项目标识
     * @param params     参数 Map，支持：simpleName、relativePath、startLine、endLine、mode
     * @return 工具执行结果
     */
    @Override
    public ToolResult execute(String projectKey, Map<String, Object> params) {
        logger.info("ReadFileTool.execute called: projectKey={}, params={}", projectKey, params);
        long startTime = System.currentTimeMillis();
        try {
            // 优先使用 simpleName
            String simpleName = (String) params.get("simpleName");
            String relativePath = (String) params.get("relativePath");

            // 如果提供了 simpleName，自动查找文件
            if (simpleName != null && !simpleName.trim().isEmpty()) {
                // 解析行号参数
                int startLine = getOptInt(params, "startLine", 1);
                int endLine = getOptInt(params, "endLine", 300);

                logger.info("使用 simpleName 模式: simpleName={}, startLine={}, endLine={}",
                    simpleName, startLine, endLine);

                // 返回需要 IDE 执行的提示（包含完整参数）
                String displayContent = String.format(
                    "工具需要在 IDE 中执行\n" +
                    "参数：simpleName=%s, startLine=%d, endLine=%d",
                    simpleName, startLine, endLine
                );

                long duration = System.currentTimeMillis() - startTime;
                ToolResult toolResult = ToolResult.success(null, "读取文件（IDE 执行）", displayContent);
                toolResult.setExecutionTimeMs(duration);
                return toolResult;
            }

            // 使用 relativePath 模式
            if (relativePath == null || relativePath.trim().isEmpty()) {
                return ToolResult.failure("缺少 relativePath 参数（或使用 simpleName 参数）");
            }

            int startLine = getOptInt(params, "startLine", 1);
            int endLine = getOptInt(params, "endLine", 300);

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
            logger.error("读取文件失败: {}", StackTraceUtils.formatStackTrace(e));
            long duration = System.currentTimeMillis() - startTime;
            ToolResult toolResult = ToolResult.failure("读取失败: " + e.getMessage());
            toolResult.setExecutionTimeMs(duration);
            return toolResult;
        }
    }
}
