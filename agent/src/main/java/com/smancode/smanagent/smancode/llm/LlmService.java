package com.smancode.smanagent.smancode.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * LLM 调用服务
 * <p>
 * 封装与大语言模型的交互。
 */
@Service
public class LlmService {

    private static final Logger logger = LoggerFactory.getLogger(LlmService.class);

    @Value("${llm.endpoint}")
    private String llmEndpoint;

    @Value("${llm.api-key}")
    private String apiKey;

    @Value("${llm.model}")
    private String model;

    @Value("${llm.max-tokens:8192}")
    private int maxTokens;

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 调用 LLM（使用系统提示词）
     *
     * @param systemPrompt 系统提示词
     * @param userPrompt    用户提示词
     * @return LLM 响应文本
     */
    public String call(String systemPrompt, String userPrompt) {
        try {
            logger.debug("调用 LLM: systemPrompt={}, userPrompt={}",
                truncate(systemPrompt, 100), truncate(userPrompt, 100));

            // 构建请求体
            Map<String, Object> requestBody = buildRequestBody(systemPrompt, userPrompt);

            // 设置请求头
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            if (apiKey != null && !apiKey.isEmpty()) {
                headers.setBearerAuth(apiKey);
            }

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);

            // 发送请求
            ResponseEntity<String> response = restTemplate.exchange(
                llmEndpoint,
                HttpMethod.POST,
                entity,
                String.class
            );

            // 解析响应
            return extractContent(response.getBody());

        } catch (Exception e) {
            logger.error("LLM 调用失败", e);
            throw new RuntimeException("LLM 调用失败: " + e.getMessage(), e);
        }
    }

    /**
     * 调用 LLM（仅用户提示词）
     *
     * @param userPrompt 用户提示词
     * @return LLM 响应文本
     */
    public String call(String userPrompt) {
        return call(null, userPrompt);
    }

    /**
     * 构建 LLM 请求体
     */
    private Map<String, Object> buildRequestBody(String systemPrompt, String userPrompt) {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("model", model);
        body.put("max_tokens", maxTokens);

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
     */
    private String extractContent(String responseBody) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);

            // 尝试 OpenAI 格式
            JsonNode contentNode = root.path("choices").get(0).path("message").path("content");
            if (!contentNode.isMissingNode()) {
                return contentNode.asText();
            }

            // 尝试直接格式
            contentNode = root.path("content");
            if (!contentNode.isMissingNode()) {
                return contentNode.asText();
            }

            logger.warn("无法从响应中提取内容: {}", responseBody);
            return responseBody;

        } catch (Exception e) {
            logger.error("解析 LLM 响应失败", e);
            return responseBody;
        }
    }

    /**
     * 截断字符串（用于日志）
     */
    private String truncate(String str, int maxLen) {
        if (str == null) return null;
        return str.length() > maxLen ? str.substring(0, maxLen) + "..." : str;
    }
}
