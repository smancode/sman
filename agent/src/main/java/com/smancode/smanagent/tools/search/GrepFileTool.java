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
 * 正则搜索工具
 * <p>
 * 使用正则表达式搜索文件内容。
 * 执行模式：intellij（IDE 执行）
 */
@Component
public class GrepFileTool extends AbstractTool implements Tool {

    private static final Logger logger = LoggerFactory.getLogger(GrepFileTool.class);

    @Override
    public String getName() {
        return "grep_file";
    }

    @Override
    public String getDescription() {
        return "使用正则表达式搜索文件内容";
    }

    @Override
    public Map<String, ParameterDef> getParameters() {
        Map<String, ParameterDef> params = new HashMap<>();
        params.put("pattern", new ParameterDef("pattern", String.class, true, "正则表达式"));
        params.put("filePattern", new ParameterDef("filePattern", String.class, false, "文件名正则（可选）"));
        params.put("searchPath", new ParameterDef("searchPath", String.class, false, "搜索路径（可选）"));
        params.put("mode", new ParameterDef("mode", String.class, false, "执行模式：local/intellij", "intellij"));
        return params;
    }

    @Override
    public ToolResult execute(String projectKey, Map<String, Object> params) {
        long startTime = System.currentTimeMillis();

        try {
            // 获取参数
            String pattern = (String) params.get("pattern");
            if (pattern == null || pattern.trim().isEmpty()) {
                return ToolResult.failure("缺少 pattern 参数");
            }

            String filePattern = getOptString(params, "filePattern", null);
            String searchPath = getOptString(params, "searchPath", null);

            logger.info("执行正则搜索: projectKey={}, pattern={}, filePattern={}, searchPath={}",
                projectKey, pattern, filePattern, searchPath);

            // 注意：这个工具需要在 IDE 中执行
            // 当 mode=intellij 时，会通过 WebSocket 转发到 IDE 执行
            // 这里返回一个占位结果
            String displayContent = String.format(
                "工具需要在 IDE 中执行\n" +
                "参数：pattern=%s, filePattern=%s, searchPath=%s",
                pattern, filePattern, searchPath
            );

            long duration = System.currentTimeMillis() - startTime;

            ToolResult toolResult = ToolResult.success(null, "正则搜索（IDE 执行）", displayContent);
            toolResult.setExecutionTimeMs(duration);
            return toolResult;

        } catch (Exception e) {
            logger.error("正则搜索失败", e);
            long duration = System.currentTimeMillis() - startTime;
            ToolResult toolResult = ToolResult.failure("搜索失败: " + e.getMessage());
            toolResult.setExecutionTimeMs(duration);
            return toolResult;
        }
    }
}
