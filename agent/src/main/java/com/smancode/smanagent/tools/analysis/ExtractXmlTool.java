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
 * 从代码中提取特定 XML 标签内容（用于结构化输出解析）。
 * 执行模式：local（后端执行）
 */
@Component
public class ExtractXmlTool extends AbstractTool implements Tool {

    private static final Logger logger = LoggerFactory.getLogger(ExtractXmlTool.class);

    @Override
    public String getName() {
        return "extract_xml";
    }

    @Override
    public String getDescription() {
        return "从文本中提取 XML 标签内容";
    }

    @Override
    public Map<String, ParameterDef> getParameters() {
        Map<String, ParameterDef> params = new HashMap<>();
        params.put("text", new ParameterDef("text", String.class, true, "包含 XML 的文本"));
        params.put("tagName", new ParameterDef("tagName", String.class, true, "要提取的标签名"));
        params.put("mode", new ParameterDef("mode", String.class, false, "执行模式：local/intellij", "local"));
        return params;
    }

    @Override
    public ToolResult execute(String projectKey, Map<String, Object> params) {
        long startTime = System.currentTimeMillis();

        try {
            // 获取参数
            String text = (String) params.get("text");
            if (text == null || text.trim().isEmpty()) {
                return ToolResult.failure("缺少 text 参数");
            }

            String tagName = (String) params.get("tagName");
            if (tagName == null || tagName.trim().isEmpty()) {
                return ToolResult.failure("缺少 tagName 参数");
            }

            logger.info("执行 XML 提取: projectKey={}, tagName={}", projectKey, tagName);

            // 执行 XML 提取
            String extractedContent = extractXmlTag(text, tagName);

            String displayContent;
            String displayTitle;

            if (extractedContent != null) {
                displayTitle = String.format("XML 提取：找到 <%s> 标签", tagName);
                displayContent = String.format(
                    "标签名: %s\n" +
                    "内容:\n%s",
                    tagName,
                    extractedContent
                );
            } else {
                displayTitle = String.format("XML 提取：未找到 <%s> 标签", tagName);
                displayContent = String.format(
                    "标签名: %s\n" +
                    "结果: 未找到该标签",
                    tagName
                );
            }

            long duration = System.currentTimeMillis() - startTime;

            ToolResult toolResult = ToolResult.success(extractedContent, displayTitle, displayContent);
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

    @Override
    public ExecutionMode getExecutionMode(Map<String, Object> params) {
        // extract_xml 固定为 local 模式
        return ExecutionMode.LOCAL;
    }

    /**
     * 从文本中提取指定 XML 标签的内容
     *
     * @param text    包含 XML 的文本
     * @param tagName 标签名
     * @return 标签内容，如果未找到则返回 null
     */
    private String extractXmlTag(String text, String tagName) {
        // 构建开始和结束标签
        String startTag = "<" + tagName + ">";
        String endTag = "</" + tagName + ">";

        // 查找开始标签位置
        int startIndex = text.indexOf(startTag);
        if (startIndex == -1) {
            return null;
        }

        // 查找结束标签位置
        int endIndex = text.indexOf(endTag, startIndex);
        if (endIndex == -1) {
            return null;
        }

        // 提取内容
        int contentStart = startIndex + startTag.length();
        return text.substring(contentStart, endIndex).trim();
    }
}
