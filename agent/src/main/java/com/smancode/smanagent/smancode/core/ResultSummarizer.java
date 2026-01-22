package com.smancode.smanagent.smancode.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smancode.smanagent.model.session.Session;
import com.smancode.smanagent.smancode.llm.LlmService;
import com.smancode.smanagent.tools.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 结果摘要生成器
 * <p>
 * 将工具执行结果压缩为简洁的摘要，防止 Token 爆炸
 */
@Component
public class ResultSummarizer {

    private static final Logger logger = LoggerFactory.getLogger(ResultSummarizer.class);
    private static final int SMALL_DATA_THRESHOLD = 500;
    private static final int LARGE_DATA_THRESHOLD = 5000;
    private static final int COMPRESSION_THRESHOLD = 500;

    @Autowired
    private LlmService llmService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 生成工具结果摘要
     *
     * @param toolName      工具名称
     * @param result        完整工具结果
     * @param parentSession 父会话（用于获取用户问题上下文）
     * @return 摘要文本
     */
    public String summarize(String toolName, ToolResult result, Session parentSession) {
        logger.info("【ResultSummarizer开始】toolName={}, success={}", toolName, result.isSuccess());

        if (!result.isSuccess()) {
            String errorSummary = "执行失败: " + (result.getError() != null ? result.getError() : "未知错误");
            logger.info("【ResultSummarizer失败】toolName={}, summary={}", toolName, errorSummary);
            return errorSummary;
        }

        if ("expert_consult".equals(toolName) && result.getDisplayContent() != null) {
            String displayContent = result.getDisplayContent();
            logger.info("【ResultSummarizer expert_consult】使用 displayContent, 长度={}", displayContent.length());
            return displayContent;
        }

        Object data = result.getData();
        if (data == null) {
            logger.warn("【ResultSummarizer空数据】toolName={}, data为null，返回默认消息", toolName);
            return "执行完成，无返回数据";
        }

        String dataStr = String.valueOf(data);
        logger.info("【ResultSummarizer数据字符串】toolName={}, data长度={}", toolName, dataStr.length());

        return compressBySize(toolName, dataStr, result, parentSession);
    }

    private String compressBySize(String toolName, String dataStr, ToolResult result, Session parentSession) {
        int dataSize = dataStr.length();

        if (dataSize < SMALL_DATA_THRESHOLD) {
            String summary = enrichWithPath(result, dataStr);
            logger.info("【ResultSummarizer小数据】toolName={}, summary长度={}", toolName, summary.length());
            return summary;
        }

        if (dataSize < LARGE_DATA_THRESHOLD) {
            String compressed = simpleCompress(toolName, dataStr, result);
            String summary = enrichWithPath(result, compressed);
            logger.info("【ResultSummarizer中等数据】toolName={}, 压缩后长度={}", toolName, summary.length());
            return summary;
        }

        logger.info("【ResultSummarizer大数据】toolName={}, 使用LLM压缩", toolName);
        return llmCompress(toolName, dataStr, parentSession, result);
    }

    /**
     * 为摘要添加文件路径信息
     */
    private String enrichWithPath(ToolResult result, String content) {
        if (result.getRelativePath() != null && !result.getRelativePath().isEmpty()) {
            return "路径: " + result.getRelativePath() + "\n" + content;
        }

        if (result.getRelatedFilePaths() != null && !result.getRelatedFilePaths().isEmpty()) {
            StringBuilder sb = new StringBuilder();
            sb.append("找到文件: ").append(result.getRelatedFilePaths().size()).append(" 个\n");
            result.getRelatedFilePaths().forEach(path -> sb.append("  - ").append(path).append("\n"));
            sb.append("\n").append(content);
            return sb.toString();
        }

        return content;
    }

    /**
     * 简单压缩（针对中等数据）
     */
    private String simpleCompress(String toolName, String data, ToolResult result) {
        return switch (toolName) {
            case "semantic_search" -> extractLinesContaining(data, 20, "filePath:", "score:");
            case "grep_file" -> extractNonEmptyLines(data, 30);
            case "read_file" -> prefixWithPath(result, data);
            case "call_chain" -> extractCallChain(data);
            case "apply_change" -> extractApplyChangeStatus(data);
            default -> truncateData(data, 1000);
        };
    }

    private String extractLinesContaining(String data, int maxLines, String... markers) {
        StringBuilder sb = new StringBuilder();
        String[] lines = data.split("\n");
        int count = 0;

        for (String line : lines) {
            if (containsAny(line, markers)) {
                sb.append(line.trim()).append("\n");
                if (++count >= maxLines) break;
            }
        }
        return sb.toString();
    }

    private boolean containsAny(String line, String[] markers) {
        for (String marker : markers) {
            if (line.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    private String extractNonEmptyLines(String data, int maxLines) {
        StringBuilder sb = new StringBuilder();
        String[] lines = data.split("\n");
        int count = 0;

        for (String line : lines) {
            if (!line.trim().isEmpty()) {
                sb.append(line.trim()).append("\n");
                if (++count >= maxLines) break;
            }
        }
        return sb.toString();
    }

    private String prefixWithPath(ToolResult result, String data) {
        if (result.getRelativePath() != null && !result.getRelativePath().isEmpty()) {
            return result.getRelativePath() + "\n" + data;
        }
        return data;
    }

    private String extractCallChain(String data) {
        StringBuilder sb = new StringBuilder();
        String[] lines = data.split("\n");
        int depth = 0;
        final int maxDepth = 10;

        for (String line : lines) {
            if (line.contains("->")) {
                sb.append(line.trim()).append("\n");
                if (++depth >= maxDepth) break;
            }
        }
        return sb.length() > 0 ? sb.toString() : data.substring(0, 500);
    }

    private String extractApplyChangeStatus(String data) {
        if (data.contains("✅") || data.contains("成功")) {
            return "✅ 修改已应用";
        }
        return "❌ 修改失败";
    }

    private String truncateData(String data, int maxLength) {
        if (data.length() > maxLength) {
            return data.substring(0, maxLength) + "\n... (已压缩)";
        }
        return data;
    }

    /**
     * LLM 智能压缩（针对大数据）
     */
    private String llmCompress(String toolName, String data, Session parentSession, ToolResult result) {
        try {
            // 构建压缩提示词
            String prompt = buildCompressionPrompt(toolName, data, parentSession);

            // 调用 LLM 生成摘要
            String response = llmService.simpleRequest(prompt);

            // 解析响应
            JsonNode json = objectMapper.readTree(response);
            String summary = json.path("summary").asText("");

            if (!summary.isEmpty()) {
                return enrichWithPath(result, summary);
            }

            // 降级到简单压缩
            return simpleCompress(toolName, data, result);

        } catch (Exception e) {
            logger.warn("LLM 压缩失败，降级到简单压缩: toolName={}", toolName, e);
            return simpleCompress(toolName, data, result);
        }
    }

    /**
     * 构建压缩提示词
     */
    private String buildCompressionPrompt(String toolName, String data, Session parentSession) {
        StringBuilder prompt = new StringBuilder();

        prompt.append("你是代码分析助手。请将以下工具执行结果压缩为简洁的摘要。\n\n");

        prompt.append("## 工具类型\n");
        prompt.append(toolName).append("\n\n");

        prompt.append("## 用户原始问题\n");
        if (parentSession != null) {
            var latestUser = parentSession.getLatestUserMessage();
            if (latestUser != null && !latestUser.getParts().isEmpty()) {
                var firstPart = latestUser.getParts().get(0);
                if (firstPart instanceof com.smancode.smanagent.model.part.TextPart) {
                    prompt.append(((com.smancode.smanagent.model.part.TextPart) firstPart).getText()).append("\n\n");
                }
            }
        }

        prompt.append("## 工具输出\n");
        prompt.append(data).append("\n\n");

        prompt.append("## 要求\n");
        prompt.append("请生成一个简洁的摘要（1-5句话），保留关键信息，去除冗余内容。\n");
        prompt.append("重点关注：\n");
        prompt.append("- 核心发现\n");
        prompt.append("- 关键数据（文件路径、类名、方法名等）\n");
        prompt.append("- 与用户问题的相关性\n\n");

        prompt.append("请以 JSON 格式返回：\n");
        prompt.append("{\n");
        prompt.append("  \"summary\": \"你的摘要\"\n");
        prompt.append("}");

        return prompt.toString();
    }

    /**
     * 检查是否需要压缩
     */
    public boolean needsCompression(ToolResult result) {
        if (result.getData() == null) {
            return false;
        }

        String dataStr = String.valueOf(result.getData());
        return dataStr.length() > COMPRESSION_THRESHOLD;
    }

    /**
     * 计算压缩率
     */
    public double getCompressionRatio(String original, String compressed) {
        if (original == null || original.isEmpty()) {
            return 0;
        }
        return (double) compressed.length() / original.length();
    }
}
