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

        Object data = result.getData();
        logger.info("【ResultSummarizer数据】toolName={}, data类型={}, data值={}",
                toolName,
                data != null ? data.getClass().getSimpleName() : "null",
                data);

        if (data == null) {
            logger.warn("【ResultSummarizer空数据】toolName={}, data为null，返回默认消息", toolName);
            return "执行完成，无返回数据";
        }

        String dataStr = String.valueOf(data);
        logger.info("【ResultSummarizer数据字符串】toolName={}, data长度={}", toolName, dataStr.length());

        // 根据数据大小决定压缩策略
        int dataSize = dataStr.length();

        if (dataSize < 500) {
            // 小数据：直接返回（添加路径信息）
            String summary = enrichWithPath(result, dataStr);
            logger.info("【ResultSummarizer小数据】toolName={}, summary长度={}", toolName, summary.length());
            return summary;
        } else if (dataSize < 5000) {
            // 中等数据：简单压缩
            String compressed = simpleCompress(toolName, dataStr, result);
            String summary = enrichWithPath(result, compressed);
            logger.info("【ResultSummarizer中等数据】toolName={}, 压缩后长度={}", toolName, summary.length());
            return summary;
        } else {
            // 大数据：LLM 智能压缩
            logger.info("【ResultSummarizer大数据】toolName={}, 使用LLM压缩", toolName);
            return llmCompress(toolName, dataStr, parentSession, result);
        }
    }

    /**
     * 为摘要添加文件路径信息
     */
    private String enrichWithPath(ToolResult result, String content) {
        // 如果有相对路径，在摘要开头添加
        if (result.getRelativePath() != null && !result.getRelativePath().isEmpty()) {
            StringBuilder sb = new StringBuilder();
            sb.append("路径: ").append(result.getRelativePath()).append("\n");
            sb.append(content);
            return sb.toString();
        }

        // 如果有相关文件列表，在摘要开头添加
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
            case "semantic_search" -> {
                // 提取关键信息：文件路径 + 相关性分数
                StringBuilder sb = new StringBuilder();
                String[] lines = data.split("\n");
                int count = 0;
                for (String line : lines) {
                    if (line.contains("filePath:") || line.contains("score:")) {
                        sb.append(line.trim()).append("\n");
                        count++;
                        if (count >= 20) break;  // 最多保留 20 条
                    }
                }
                yield sb.toString();
            }
            case "grep_file" -> {
                // 提取匹配行和上下文
                StringBuilder sb = new StringBuilder();
                String[] lines = data.split("\n");
                int count = 0;
                for (String line : lines) {
                    if (line.trim().isEmpty()) continue;
                    sb.append(line.trim()).append("\n");
                    count++;
                    if (count >= 30) break;  // 最多保留 30 行
                }
                yield sb.toString();
            }
            case "read_file" -> {
                // 提取类/方法签名和关键逻辑
                StringBuilder sb = new StringBuilder();

                // 新增：优先使用 relativePath
                logger.info("【read_file压缩】relativePath={}", result.getRelativePath());
                if (result.getRelativePath() != null && !result.getRelativePath().isEmpty()) {
                    sb.append("路径: ").append(result.getRelativePath()).append("\n");
                }

                String[] lines = data.split("\n");
                boolean inComment = false;

                for (String line : lines) {
                    String trimmed = line.trim();

                    // 跳过注释
                    if (trimmed.startsWith("/*") || trimmed.startsWith("*")) {
                        inComment = true;
                        continue;
                    }
                    if (inComment && trimmed.endsWith("*/")) {
                        inComment = false;
                        continue;
                    }
                    if (inComment) continue;
                    if (trimmed.startsWith("//")) continue;

                    // 保留关键行
                    if (trimmed.startsWith("package ") ||
                        trimmed.startsWith("import ") ||
                        trimmed.startsWith("public class ") ||
                        trimmed.startsWith("private class ") ||
                        trimmed.startsWith("public ") ||
                        trimmed.startsWith("private ") ||
                        trimmed.startsWith("protected ")) {
                        sb.append(line).append("\n");
                    }

                    // 限制大小
                    if (sb.length() > 2000) {
                        sb.append("\n... (内容已压缩，完整内容在子会话中)");
                        break;
                    }
                }
                yield sb.toString();
            }
            case "call_chain" -> {
                // 保留调用链路径
                StringBuilder sb = new StringBuilder();
                String[] lines = data.split("\n");
                int depth = 0;
                for (String line : lines) {
                    if (line.contains("->")) {
                        sb.append(line.trim()).append("\n");
                        depth++;
                        if (depth >= 10) break;  // 最多 10 层
                    }
                }
                yield sb.length() > 0 ? sb.toString() : data.substring(0, 500);
            }
            case "apply_change" -> {
                // 只保留成功状态，详细说明由 LLM 生成
                if (data.contains("✅") || data.contains("成功")) {
                    yield "✅ 修改已应用";
                } else {
                    yield "❌ 修改失败";
                }
            }
            default -> {
                // 默认：截取前 1000 字符
                yield data.length() > 1000 ? data.substring(0, 1000) + "\n... (已压缩)" : data;
            }
        };
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
        return dataStr.length() > 500;  // 超过 500 字符需要压缩
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
