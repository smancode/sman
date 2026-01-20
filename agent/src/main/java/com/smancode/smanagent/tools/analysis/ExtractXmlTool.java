package com.smancode.smanagent.tools.analysis;

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
 * XML 提取工具
 * <p>
 * 从 XML 文件中提取特定标签内容。
 * 执行模式：intellij（IDE 执行）
 */
@Component
public class ExtractXmlTool extends AbstractTool implements Tool {

    private static final Logger logger = LoggerFactory.getLogger(ExtractXmlTool.class);

    /**
     * 获取工具名称
     *
     * @return 工具唯一标识名称
     */
    @Override
    public String getName() {
        return "extract_xml";
    }

    /**
     * 获取工具描述
     *
     * @return 工具功能描述
     */
    @Override
    public String getDescription() {
        return "从 XML 文件中提取标签内容";
    }

    /**
     * 获取工具参数定义
     *
     * @return 参数定义映射表，包含 tagPattern、relativePath、mode 等参数
     */
    @Override
    public Map<String, ParameterDef> getParameters() {
        Map<String, ParameterDef> params = new HashMap<>();
        params.put("tagPattern", new ParameterDef("tagPattern", String.class, true, "标签模式，如 bean.*id=\"paymentService\""));
        params.put("relativePath", new ParameterDef("relativePath", String.class, true, "文件相对路径"));
        params.put("mode", new ParameterDef("mode", String.class, false, "执行模式：local/intellij", "intellij"));
        return params;
    }

    /**
     * 执行 XML 提取操作
     * <p>
     * 从指定的 XML 文件中提取符合标签模式的内容。
     * 此工具需要通过 WebSocket 转发到 IDE 执行。
     *
     * @param projectKey 项目标识
     * @param params     参数映射表，包含：
     *                   - tagPattern: 标签模式（必需）
     *                   - relativePath: 文件相对路径（必需）
     *                   - mode: 执行模式（可选，默认 intellij）
     * @return 工具执行结果，包含提取的 XML 内容或错误信息
     */
    @Override
    public ToolResult execute(String projectKey, Map<String, Object> params) {
        long startTime = System.currentTimeMillis();

        try {
            // 获取参数
            String tagPattern = (String) params.get("tagPattern");
            if (tagPattern == null || tagPattern.trim().isEmpty()) {
                return ToolResult.failure("缺少 tagPattern 参数");
            }

            String relativePath = (String) params.get("relativePath");
            if (relativePath == null || relativePath.trim().isEmpty()) {
                return ToolResult.failure("缺少 relativePath 参数");
            }

            logger.info("执行 XML 提取: projectKey={}, tagPattern={}, relativePath={}",
                projectKey, tagPattern, relativePath);

            // 此工具需要通过 WebSocket 转发到 IDE 执行
            // 返回占位结果
            String displayContent = String.format(
                "工具需要在 IDE 中执行\n" +
                "参数：tagPattern=%s, relativePath=%s",
                tagPattern, relativePath
            );

            long duration = System.currentTimeMillis() - startTime;

            ToolResult toolResult = ToolResult.success(null, "XML 提取（IDE 执行）", displayContent);
            toolResult.setExecutionTimeMs(duration);
            return toolResult;

        } catch (Exception e) {
            logger.error("XML 提取失败", e);
            long duration = System.currentTimeMillis() - startTime;
            ToolResult toolResult = ToolResult.failure("提取失败: " + e.getMessage());
            toolResult.setExecutionTimeMs(duration);
            return toolResult;
        }
    }
}
