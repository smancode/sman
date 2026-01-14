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
 * 文件查找工具
 * <p>
 * 按文件名正则搜索文件。
 * 执行模式：intellij（IDE 执行）
 */
@Component
public class FindFileTool extends AbstractTool implements Tool {

    private static final Logger logger = LoggerFactory.getLogger(FindFileTool.class);

    @Override
    public String getName() {
        return "find_file";
    }

    @Override
    public String getDescription() {
        return "按文件名正则搜索文件";
    }

    @Override
    public Map<String, ParameterDef> getParameters() {
        Map<String, ParameterDef> params = new HashMap<>();
        params.put("filePattern", new ParameterDef("filePattern", String.class, true, "文件名正则"));
        params.put("searchPath", new ParameterDef("searchPath", String.class, false, "搜索路径（可选）"));
        params.put("mode", new ParameterDef("mode", String.class, false, "执行模式：local/intellij", "intellij"));
        return params;
    }

    @Override
    public ToolResult execute(String projectKey, Map<String, Object> params) {
        long startTime = System.currentTimeMillis();

        try {
            // 获取参数
            String filePattern = (String) params.get("filePattern");
            if (filePattern == null || filePattern.trim().isEmpty()) {
                return ToolResult.failure("缺少 filePattern 参数");
            }

            String searchPath = getOptString(params, "searchPath", null);

            logger.info("执行文件查找: projectKey={}, filePattern={}, searchPath={}",
                projectKey, filePattern, searchPath);

            // 此工具需要通过 WebSocket 转发到 IDE 执行
            // 返回占位结果
            String displayContent = String.format(
                "工具需要在 IDE 中执行\n" +
                "参数：filePattern=%s, searchPath=%s",
                filePattern, searchPath
            );

            long duration = System.currentTimeMillis() - startTime;

            ToolResult toolResult = ToolResult.success(null, "文件查找（IDE 执行）", displayContent);
            toolResult.setExecutionTimeMs(duration);
            return toolResult;

        } catch (Exception e) {
            logger.error("文件查找失败", e);
            long duration = System.currentTimeMillis() - startTime;
            ToolResult toolResult = ToolResult.failure("查找失败: " + e.getMessage());
            toolResult.setExecutionTimeMs(duration);
            return toolResult;
        }
    }
}
