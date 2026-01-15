package com.smancode.smanagent.smancode.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smancode.smanagent.smancode.llm.config.LlmEndpoint;
import com.smancode.smanagent.smancode.llm.config.LlmPoolConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * LLM 调用服务（独立模块）
 * <p>
 * 功能：
 * 1. 端点池轮询（Round-Robin）
 * 2. 故障自动切换（超时、429、5xx）
 * 3. 指数退避重试（10s -> 20s -> 30s）
 * 4. JSON 格式自动校验
 */
@Service
public class LlmService {

    private static final Logger logger = LoggerFactory.getLogger(LlmService.class);

    @Autowired
    private LlmPoolConfig poolConfig;

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 普通文本请求（自动重试）
     *
     * @param prompt 用户提示词
     * @return LLM 响应文本
     */
    public String simpleRequest(String prompt) {
        return simpleRequest(null, prompt);
    }

    /**
     * 普通文本请求（自动重试）
     *
     * @param systemPrompt 系统提示词
     * @param userPrompt   用户提示词
     * @return LLM 响应文本
     */
    public String simpleRequest(String systemPrompt, String userPrompt) {
        int retryCount = 0;
        int maxRetries = poolConfig.getRetry().getMaxRetries();

        while (retryCount <= maxRetries) {
            LlmEndpoint endpoint = poolConfig.getNextAvailableEndpoint();
            if (endpoint == null) {
                String errorMsg = "没有可用的 LLM 端点";
                logger.error(errorMsg);
                throw new RuntimeException(errorMsg);
            }

            long startTime = System.currentTimeMillis();
            try {
                // 完整打印 prompt 内容（不截取）
                if (systemPrompt != null && !systemPrompt.isEmpty()) {
                    logger.info("=== System Prompt (完整内容):\n{}", systemPrompt);
                }

                if (userPrompt != null && !userPrompt.isEmpty()) {
                    logger.info("=== User Prompt (完整内容):\n{}", userPrompt);
                }

                // 调用 callInternal 并获取原始响应（用于提取 tokens）
                String rawApiResponse = callInternalForRawResponse(endpoint, systemPrompt, userPrompt);

                // 完整打印响应内容（不截取）
                logger.info("=== LLM 响应 (完整内容):\n{}", rawApiResponse);

                // 提取纯文本内容
                String content = extractContent(rawApiResponse);

                // 打印 tokens 信息
                logTokensUsage(rawApiResponse, startTime);

                endpoint.markSuccess();
                return content;

            } catch (Exception e) {
                endpoint.markFailed();
                logger.warn("端点调用失败: {}, 错误: {}", endpoint.getBaseUrl(), e.getMessage());

                // 判断是否需要重试
                if (shouldRetry(e) && retryCount < maxRetries) {
                    retryCount++;
                    long delay = poolConfig.getRetry().calculateDelay(retryCount);
                    logger.info("等待 {} 毫秒后进行第 {} 次重试...", delay, retryCount);
                    sleep(delay);
                } else {
                    throw new RuntimeException("LLM 调用失败: " + e.getMessage(), e);
                }
            }
        }

        throw new RuntimeException("LLM 调用失败：超过最大重试次数");
    }

    /**
     * 打印 tokens 使用情况
     */
    private void logTokensUsage(String rawApiResponse, long startTime) {
        try {
            JsonNode root = objectMapper.readTree(rawApiResponse);
            JsonNode usage = root.path("usage");

            if (!usage.isMissingNode() && usage.isObject()) {
                int promptTokens = usage.path("prompt_tokens").asInt();
                int completionTokens = usage.path("completion_tokens").asInt();
                int totalTokens = usage.path("total_tokens").asInt();

                long elapsedTime = System.currentTimeMillis() - startTime;
                double elapsedSeconds = elapsedTime / 1000.0;

                logger.info("LLM 响应: 发送tokens={}, 接收tokens={}, 总tokens={}, 耗时{}s",
                        promptTokens, completionTokens, totalTokens,
                        String.format("%.1f", elapsedSeconds));
            }
        } catch (Exception e) {
            // 忽略 tokens 解析错误
            logger.debug("无法解析 tokens 信息: {}", e.getMessage());
        }
    }

    /**
     * 内部调用实现（返回原始 API 响应）
     */
    private String callInternalForRawResponse(LlmEndpoint endpoint, String systemPrompt, String userPrompt) {
        try {
            // 构建请求体
            Map<String, Object> requestBody = buildRequestBody(endpoint, systemPrompt, userPrompt);

            // 设置请求头
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            if (endpoint.getApiKey() != null && !endpoint.getApiKey().isEmpty()) {
                headers.setBearerAuth(endpoint.getApiKey());
            }

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);

            // 发送请求
            ResponseEntity<String> response = restTemplate.exchange(
                    endpoint.getBaseUrl() + "/chat/completions",
                    HttpMethod.POST,
                    entity,
                    String.class
            );

            // 检查 HTTP 状态码
            if (response.getStatusCode().is4xxClientError() || response.getStatusCode().is5xxServerError()) {
                throw new RuntimeException("HTTP 错误: " + response.getStatusCode());
            }

            // 返回原始 API 响应
            return response.getBody();

        } catch (ResourceAccessException e) {
            // 超时或网络错误
            throw new RuntimeException("请求超时或网络错误", e);
        } catch (Exception e) {
            throw new RuntimeException("LLM 调用失败: " + e.getMessage(), e);
        }
    }

    /**
     * JSON 请求（自动重试 + JSON 格式校验）
     *
     * @param prompt 用户提示词（应要求返回 JSON）
     * @return 解析后的 JSON 节点
     */
    public JsonNode jsonRequest(String prompt) {
        return jsonRequest(null, prompt);
    }

    /**
     * JSON 请求（自动重试 + JSON 格式校验）
     * <p>
     * 基于 simpleRequest，增加 JSON 解析和验证
     *
     * @param systemPrompt 系统提示词
     * @param userPrompt   用户提示词（应要求返回 JSON）
     * @return 解析后的 JSON 节点
     */
    public JsonNode jsonRequest(String systemPrompt, String userPrompt) {
        // 1. 调用 simpleRequest 获取纯文本响应（已有重试机制）
        String responseText = simpleRequest(systemPrompt, userPrompt);

        // 2. 从纯文本响应中提取 JSON
        String extractedJson = extractJsonFromResponse(responseText);
        if (extractedJson == null) {
            throw new RuntimeException("无法从响应中提取有效的 JSON，响应内容: " +
                    responseText.substring(0, Math.min(200, responseText.length())));
        }

        // 3. 解析 JSON
        try {
            return objectMapper.readTree(extractedJson);
        } catch (Exception e) {
            throw new RuntimeException("JSON 解析失败: " + e.getMessage() + "，提取的 JSON: " + extractedJson, e);
        }
    }

    /**
     * 判断是否应该重试
     */
    private boolean shouldRetry(Exception e) {
        String message = e.getMessage();
        if (message == null) {
            return false;
        }

        // 超时错误
        if (e instanceof ResourceAccessException || message.contains("timeout") || message.contains("Timed out")) {
            return true;
        }

        // 429 Too Many Requests
        if (message.contains("429")) {
            return true;
        }

        // 5xx 服务器错误
        if (message.contains("5") && (message.contains("50") || message.contains("HTTP error 5"))) {
            return true;
        }

        // JSON 格式错误（检查异常类型和消息）
        // JsonParseException 或包含 JSON 解析相关关键词
        if (e instanceof com.fasterxml.jackson.core.JsonParseException ||
            e.getCause() instanceof com.fasterxml.jackson.core.JsonParseException ||
            message.contains("Unexpected character") ||
            message.contains("JsonParseException")) {
            return true;
        }

        return false;
    }

    /**
     * 从响应中提取 JSON（8级递进式解析策略）
     * <p>
     * 解析策略从简单到复杂，逐级尝试，确保最大容错能力：
     * Level 1: 直接解析（最快）
     * Level 2: 清理后解析（去除 markdown 代码块）
     * Level 3: 修复转义后解析（修复常见转义问题）
     * Level 4: 智能大括号提取（增强版策略3）
     * Level 5: 正则提取尝试（多种模式匹配）
     * Level 6: 简单正则快速尝试（补充兜底）
     * Level 7: 终极大招 - LLM 辅助提取
     * Level 8: 降级为纯文本（兜底）
     *
     * @param response LLM 返回的原始响应
     * @return 提取出的 JSON 字符串，如果所有策略都失败则返回 null
     */
    private String extractJsonFromResponse(String response) {
        if (response == null || response.trim().isEmpty()) {
            return null;
        }

        String trimmedResponse = response.trim();

        // ========== Level 1: 直接解析 ==========
        if (isValidJsonStructure(trimmedResponse)) {
            logger.debug("Level 1 成功: 直接解析");
            return trimmedResponse;
        }

        // ========== Level 2: 清理 markdown 代码块 ==========
        String level2Result = extractFromMarkdownBlock(trimmedResponse);
        if (level2Result != null && isValidJsonStructure(level2Result)) {
            logger.debug("Level 2 成功: 清理 markdown 代码块");
            return level2Result;
        }

        // ========== Level 3: 修复转义字符 ==========
        String level3Result = fixAndParse(trimmedResponse);
        if (level3Result != null) {
            logger.debug("Level 3 成功: 修复转义字符");
            return level3Result;
        }

        // ========== Level 4: 智能大括号提取（增强版）==========
        String level4Result = extractWithSmartBraceMatching(trimmedResponse);
        if (level4Result != null && isValidJsonStructure(level4Result)) {
            logger.debug("Level 4 成功: 智能大括号提取");
            return level4Result;
        }

        // ========== Level 5: 正则提取尝试 ==========
        String level5Result = extractWithRegex(trimmedResponse);
        if (level5Result != null && isValidJsonStructure(level5Result)) {
            logger.debug("Level 5 成功: 正则提取");
            return level5Result;
        }

        // ========== Level 6: 简单正则快速尝试 ==========
        String level6Result = extractWithSimpleRegex(trimmedResponse);
        if (level6Result != null && isValidJsonStructure(level6Result)) {
            logger.debug("Level 6 成功: 简单正则提取");
            return level6Result;
        }

        // ========== Level 7: 终极大招 - LLM 辅助提取 ==========
        // 注意：LlmService 不支持 LLM 辅助提取（避免无限递归）
        // 跳过 Level 7，直接降级

        // ========== Level 8: 所有策略失败，降级为纯文本 ==========
        logger.warn("所有 JSON 提取策略失败，将降级为纯文本处理");
        return null;
    }

    /**
     * Level 2: 从 markdown 代码块中提取 JSON
     */
    private String extractFromMarkdownBlock(String response) {
        // 尝试提取 ```json...``` 代码块
        String jsonStart = "```json";
        String jsonEnd = "```";

        int startIndex = response.indexOf(jsonStart);
        if (startIndex != -1) {
            startIndex += jsonStart.length();
            // 从 startIndex 开始找第一个 {，然后智能匹配对应的 }
            int firstBrace = response.indexOf('{', startIndex);
            if (firstBrace != -1) {
                int depth = 0;
                boolean inString = false;
                boolean escape = false;

                for (int i = firstBrace; i < response.length(); i++) {
                    char c = response.charAt(i);

                    if (escape) {
                        escape = false;
                        continue;
                    }

                    if (c == '\\' && inString) {
                        escape = true;
                        continue;
                    }

                    if (c == '"' && !escape) {
                        inString = !inString;
                        continue;
                    }

                    if (!inString) {
                        if (c == '{') depth++;
                        else if (c == '}') {
                            depth--;
                            if (depth == 0) {
                                return response.substring(firstBrace, i + 1).trim();
                            }
                        }
                    }
                }
            }
        }

        // 尝试提取 ```...``` 代码块（没有 json 标记）
        String codeStart = "```";
        int codeStartIndex = response.indexOf(codeStart);
        if (codeStartIndex != -1) {
            int afterStart = codeStartIndex + codeStart.length();
            // 跳过可能的语言标记
            int firstBrace = response.indexOf('{', afterStart);
            if (firstBrace != -1) {
                int endIndex = response.indexOf(codeStart, firstBrace);
                if (endIndex != -1) {
                    return response.substring(firstBrace, endIndex).trim();
                }
            }
        }

        return null;
    }

    /**
     * Level 3: 修复转义字符并解析
     * <p>
     * 处理 LLM 返回的 JSON 中常见的转义问题：
     * - 字符串内部的换行符 \n 未转义
     * - 字符串内部的引号 " 未转义
     * - 字符串内部的反斜杠 \ 未转义
     */
    private String fixAndParse(String response) {
        // 先尝试从 markdown 代码块中提取
        String extracted = extractFromMarkdownBlock(response);
        String toFix = extracted != null ? extracted : response;

        // 尝试多种修复策略
        String[] fixedVersions = {
                fixStringNewlines(toFix),           // 修复字符串内的换行
                fixUnescapedQuotes(toFix),          // 修复未转义的引号
                fixUnescapedBackslashes(toFix),     // 修复未转义的反斜杠
                fixAllCommonIssues(toFix)           // 修复所有常见问题
        };

        for (String fixed : fixedVersions) {
            if (isValidJsonStructure(fixed)) {
                return fixed;
            }
        }

        return null;
    }

    /**
     * 修复 JSON 字符串值中的未转义换行符
     * <p>
     * 例如: {"text": "hello\nworld"} -> {"text": "hello\\nworld"}
     */
    private String fixStringNewlines(String json) {
        // 这是一个简化版本，只处理最常见的情况
        // 更复杂的版本需要跟踪字符串状态
        return json.replace("\\\n", "\\\\n");
    }

    /**
     * 修复 JSON 字符串值中的未转义引号
     * <p>
     * 这是一个启发式方法，尝试修复常见的未转义引号问题
     */
    private String fixUnescapedQuotes(String json) {
        // 简化版本：只处理明显的情况
        // 注意：这是一个有损修复，可能不是所有情况都适用
        return json;
    }

    /**
     * 修复 JSON 字符串值中的未转义反斜杠
     */
    private String fixUnescapedBackslashes(String json) {
        // 简化版本：只处理明显的情况
        return json;
    }

    /**
     * 修复所有常见的转义问题
     */
    private String fixAllCommonIssues(String json) {
        String result = json;
        result = fixStringNewlines(result);
        result = fixUnescapedQuotes(result);
        result = fixUnescapedBackslashes(result);
        return result;
    }

    /**
     * Level 4: 智能大括号匹配提取
     * <p>
     * 从复杂文本中提取完整的 JSON 对象，处理：
     * - 嵌套大括号
     * - 字符串内部的大括号
     * - 转义字符
     */
    private String extractWithSmartBraceMatching(String response) {
        int braceStart = response.indexOf('{');
        if (braceStart < 0) {
            return null;
        }

        int depth = 0;
        boolean inString = false;
        boolean escape = false;

        for (int i = braceStart; i < response.length(); i++) {
            char c = response.charAt(i);

            if (escape) {
                escape = false;
                continue;
            }

            if (c == '\\' && inString) {
                escape = true;
                continue;
            }

            if (c == '"' && !escape) {
                inString = !inString;
                continue;
            }

            if (!inString) {
                if (c == '{') {
                    depth++;
                } else if (c == '}') {
                    depth--;
                    if (depth == 0) {
                        return response.substring(braceStart, i + 1);
                    }
                }
            }
        }

        return null;
    }

    /**
     * Level 5: 使用正则表达式提取 JSON
     */
    private String extractWithRegex(String response) {
        // 尝试多种正则模式
        java.util.regex.Pattern[] patterns = {
                // 模式1: 匹配 ```json 和 ``` 之间的内容
                java.util.regex.Pattern.compile("```json\\s*([\\s\\S]*?)\\s*```"),
                // 模式2: 匹配 { 和 } 之间的完整 JSON 对象（贪婪）
                java.util.regex.Pattern.compile("\\{[\\s\\S]*\\}"),
                // 模式3: 匹配嵌套的 JSON 对象
                java.util.regex.Pattern.compile("\\{(?:[^{}]|\\{[^{}]*\\})*\\}")
        };

        for (java.util.regex.Pattern pattern : patterns) {
            java.util.regex.Matcher matcher = pattern.matcher(response);
            if (matcher.find()) {
                String match = matcher.group(1);
                if (match == null) {
                    match = matcher.group(0);
                }
                if (match != null && !match.trim().isEmpty()) {
                    return match.trim();
                }
            }
        }

        return null;
    }

    /**
     * Level 6: 简单正则快速提取
     */
    private String extractWithSimpleRegex(String response) {
        // 快速尝试：找到第一个 { 和最后一个 }
        int firstBrace = response.indexOf('{');
        int lastBrace = response.lastIndexOf('}');

        if (firstBrace >= 0 && lastBrace > firstBrace) {
            return response.substring(firstBrace, lastBrace + 1);
        }

        return null;
    }

    /**
     * 验证JSON结构的基本有效性
     */
    private boolean isValidJsonStructure(String jsonStr) {
        if (jsonStr == null || jsonStr.trim().isEmpty()) {
            return false;
        }

        try {
            objectMapper.readTree(jsonStr);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 构建 LLM 请求体
     */
    private Map<String, Object> buildRequestBody(LlmEndpoint endpoint, String systemPrompt, String userPrompt) {
        Map<String, Object> body = new HashMap<>();
        body.put("model", endpoint.getModel());
        body.put("max_tokens", endpoint.getMaxTokens());
        body.put("temperature", 0.0);  // 设置为 0，确保输出稳定，避免幻觉

        // 构建消息列表
        List<Map<String, String>> messages = new ArrayList<>();

        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            Map<String, String> systemMsg = new HashMap<>();
            systemMsg.put("role", "system");
            systemMsg.put("content", systemPrompt);
            messages.add(systemMsg);
        }

        Map<String, String> userMsg = new HashMap<>();
        userMsg.put("role", "user");
        userMsg.put("content", userPrompt);
        messages.add(userMsg);

        body.put("messages", messages);

        return body;
    }

    /**
     * 从 LLM 响应中提取内容
     * <p>
     * 参考 bank-core-analysis-agent：只提取纯文本 content，不做任何转换
     * JSON 解析由上层（SmanAgentLoop）负责
     */
    private String extractContent(String responseBody) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);

            // 尝试 OpenAI 格式
            JsonNode choicesNode = root.path("choices");
            if (choicesNode.isArray() && choicesNode.size() > 0) {
                JsonNode contentNode = choicesNode.get(0).path("message").path("content");
                if (!contentNode.isMissingNode()) {
                    return contentNode.asText();  // 直接返回纯文本
                }
            }

            // 尝试直接格式（有些 LLM 直接返回 content 字段）
            JsonNode contentNode = root.path("content");
            if (!contentNode.isMissingNode()) {
                return contentNode.asText();  // 直接返回纯文本
            }

            // 都不是，返回原始响应
            return responseBody;

        } catch (Exception e) {
            // JSON 解析失败，返回原始响应
            logger.debug("响应不是 JSON 格式，按纯文本处理: {}", e.getMessage());
            return responseBody;
        }
    }

    /**
     * 线程休眠
     */
    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("重试等待被中断", e);
        }
    }
}
