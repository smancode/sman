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
     * 从响应中提取 JSON（参考 bank-core-analysis-agent 的实现）
     * <p>
     * 支持多种格式：
     * 1. ```json代码块
     * 2. 纯JSON（以{开头，以}结尾）
     * 3. 文本中的JSON片段
     */
    private String extractJsonFromResponse(String response) {
        if (response == null || response.trim().isEmpty()) {
            return null;
        }

        String trimmedResponse = response.trim();

        // 策略1: 提取```json代码块
        String jsonStart = "```json";
        String jsonEnd = "```";

        int startIndex = trimmedResponse.indexOf(jsonStart);
        if (startIndex != -1) {
            startIndex += jsonStart.length();
            // 从 startIndex 开始找第一个 {，然后智能匹配对应的 }
            int firstBrace = trimmedResponse.indexOf('{', startIndex);
            if (firstBrace != -1) {
                int depth = 0;
                boolean inString = false;
                boolean escape = false;

                for (int i = firstBrace; i < trimmedResponse.length(); i++) {
                    char c = trimmedResponse.charAt(i);

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
                                String jsonContent = trimmedResponse.substring(firstBrace, i + 1).trim();
                                if (isValidJsonStructure(jsonContent)) {
                                    return jsonContent;
                                }
                            }
                        }
                    }
                }
            }
        }

        // 策略2: 检查是否为纯JSON格式
        if (trimmedResponse.startsWith("{") && trimmedResponse.endsWith("}")) {
            if (isValidJsonStructure(trimmedResponse)) {
                return trimmedResponse;
            }
        }

        // 策略3: 查找文本中的JSON片段（智能匹配大括号）
        int braceStart = trimmedResponse.indexOf('{');
        if (braceStart >= 0) {
            int depth = 0;
            boolean inString = false;
            boolean escape = false;

            for (int i = braceStart; i < trimmedResponse.length(); i++) {
                char c = trimmedResponse.charAt(i);

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
                            String jsonCandidate = trimmedResponse.substring(braceStart, i + 1);
                            if (isValidJsonStructure(jsonCandidate)) {
                                return jsonCandidate;
                            }
                        }
                    }
                }
            }
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
