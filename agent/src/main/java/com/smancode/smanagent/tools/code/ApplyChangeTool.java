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

    /**
     * 获取工具名称
     *
     * @return 工具名称
     */
    @Override
    public String getName() {
        return "apply_change";
    }

    /**
     * 获取工具描述
     *
     * @return 工具描述信息，说明支持替换和创建两种模式
     */
    @Override
    public String getDescription() {
        return "应用代码修改（替换现有内容或创建新文件）";
    }

    /**
     * 获取工具参数定义
     * <p>
     * 支持两种模式：
     * 1. replace 模式：需要 searchContent 和 newContent
     * 2. create 模式：只需要 newContent
     *
     * @return 参数定义 Map，包含 relativePath、mode、newContent、searchContent、description
     */
    @Override
    public Map<String, ParameterDef> getParameters() {
        Map<String, ParameterDef> params = new HashMap<>();
        params.put("relativePath", new ParameterDef("relativePath", String.class, true, "文件相对路径"));
        params.put("mode", new ParameterDef("mode", String.class, false, "修改模式：replace(替换)/create(新建)", "replace"));
        params.put("newContent", new ParameterDef("newContent", String.class, true, "新内容"));
        params.put("searchContent", new ParameterDef("searchContent", String.class, true, "搜索内容（replace 模式必须）"));
        params.put("description", new ParameterDef("description", String.class, false, "修改描述"));
        return params;
    }

    /**
     * 执行代码修改操作
     * <p>
     * 支持两种模式：
     * 1. replace 模式：替换现有内容（需要 searchContent）
     * 2. create 模式：创建新文件（只需要 newContent）
     * <p>
     * 注意：此工具需要在 IDE 中执行，实际修改由 IDE 完成
     *
     * @param projectKey 项目标识
     * @param params     参数 Map，支持：relativePath、mode、newContent、searchContent、description
     * @return 工具执行结果
     */
    @Override
    public ToolResult execute(String projectKey, Map<String, Object> params) {
        long startTime = System.currentTimeMillis();

        try {
            // 获取参数
            String relativePath = (String) params.get("relativePath");
            if (relativePath == null || relativePath.trim().isEmpty()) {
                return ToolResult.failure("缺少 relativePath 参数");
            }

            String newContent = (String) params.get("newContent");
            if (newContent == null || newContent.trim().isEmpty()) {
                return ToolResult.failure("缺少 newContent 参数");
            }

            String mode = getOptString(params, "mode", "replace");
            String searchContent = (String) params.get("searchContent");
            String description = getOptString(params, "description", "");

            // replace 模式必须提供 searchContent
            if ("replace".equalsIgnoreCase(mode) && (searchContent == null || searchContent.trim().isEmpty())) {
                return ToolResult.failure("replace 模式缺少 searchContent 参数");
            }

            logger.info("执行代码修改: projectKey={}, relativePath={}, mode={}, description={}",
                projectKey, relativePath, mode, description);

            // 此工具需要通过 WebSocket 转发到 IDE 执行
            // 返回占位结果
            String displayContent = String.format(
                "工具需要在 IDE 中执行\n" +
                "参数：relativePath=%s\n" +
                "     mode=%s\n" +
                "     searchContent=%s\n" +
                "     newContent=%s\n" +
                "     description=%s",
                relativePath,
                mode,
                searchContent != null ? searchContent.substring(0, Math.min(50, searchContent.length())) + "..." : "(null)",
                newContent.substring(0, Math.min(50, newContent.length())) + "...",
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
