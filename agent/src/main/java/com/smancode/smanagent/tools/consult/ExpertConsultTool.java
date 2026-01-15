package com.smancode.smanagent.tools.consult;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smancode.smanagent.config.KnowledgeProperties;
import com.smancode.smanagent.tools.AbstractTool;
import com.smancode.smanagent.tools.ParameterDef;
import com.smancode.smanagent.tools.Tool;
import com.smancode.smanagent.tools.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;

/**
 * 专家咨询工具
 * <p>
 * 通过 HTTP 调用 Knowledge 服务，获取业务知识和代码入口。
 * <p>
 * 架构设计：
 * - Agent 只负责工具编排（ReAct 循环）
 * - 向量搜索、重排等复杂功能由 Knowledge 服务承担
 * - 支持云原生扩展（cloud-server 架构）
 * <p>
 * 参数：
 * - query: 咨询问题（必填）
 * <p>
 * 执行模式：local（后端执行）
 */
@Component
public class ExpertConsultTool extends AbstractTool implements Tool {

    private static final Logger logger = LoggerFactory.getLogger(ExpertConsultTool.class);

    @Autowired
    private KnowledgeProperties knowledgeProperties;

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public String getName() {
        return "expert_consult";
    }

    @Override
    public String getDescription() {
        return "专家咨询工具：查询业务知识、规则、流程，并定位相关代码入口（委托给 Knowledge 服务）";
    }

    @Override
    public Map<String, ParameterDef> getParameters() {
        Map<String, ParameterDef> params = new HashMap<>();
        params.put("query", new ParameterDef("query", String.class, true, "咨询问题"));
        return params;
    }

    @Override
    public ToolResult execute(String projectKey, Map<String, Object> params) {
        long startTime = System.currentTimeMillis();

        try {
            // 获取参数
            String query = (String) params.get("query");
            if (query == null || query.trim().isEmpty()) {
                return ToolResult.failure("缺少 query 参数");
            }

            logger.info("执行专家咨询: projectKey={}, query={}, url={}",
                    projectKey, query, knowledgeProperties.getSearchUrl());

            // 构建请求体
            Map<String, String> requestBody = new HashMap<>();
            requestBody.put("projectKey", projectKey);
            requestBody.put("query", query);

            // 设置请求头
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            HttpEntity<Map<String, String>> request = new HttpEntity<>(requestBody, headers);

            // 调用 Knowledge 服务
            ResponseEntity<String> response = restTemplate.postForEntity(
                    knowledgeProperties.getSearchUrl(),
                    request,
                    String.class
            );

            long duration = System.currentTimeMillis() - startTime;

            if (!response.getStatusCode().is2xxSuccessful()) {
                logger.warn("Knowledge 服务返回错误: status={}", response.getStatusCode());
                ToolResult toolResult = ToolResult.failure("Knowledge 服务返回错误: " + response.getStatusCode());
                toolResult.setExecutionTimeMs(duration);
                return toolResult;
            }

            // 解析响应
            String responseBody = response.getBody();
            if (responseBody == null || responseBody.isEmpty()) {
                ToolResult toolResult = ToolResult.success("未找到相关知识", "专家咨询结果", null);
                toolResult.setExecutionTimeMs(duration);
                return toolResult;
            }

            // 尝试解析 JSON 响应
            try {
                JsonNode jsonNode = objectMapper.readTree(responseBody);
                JsonNode resultNode = jsonNode.path("result");

                if (resultNode.isObject() && resultNode.has("content")) {
                    String content = resultNode.get("content").asText();
                    ToolResult toolResult = ToolResult.success(content, "专家咨询结果", responseBody);
                    toolResult.setExecutionTimeMs(duration);
                    return toolResult;
                }

                // 如果没有 content 字段，直接返回整个响应
                ToolResult toolResult = ToolResult.success(responseBody, "专家咨询结果", responseBody);
                toolResult.setExecutionTimeMs(duration);
                return toolResult;

            } catch (Exception e) {
                // JSON 解析失败，直接返回原始响应
                logger.debug("JSON 解析失败，返回原始响应: {}", e.getMessage());
                ToolResult toolResult = ToolResult.success(responseBody, "专家咨询结果", responseBody);
                toolResult.setExecutionTimeMs(duration);
                return toolResult;
            }

        } catch (Exception e) {
            logger.error("专家咨询失败", e);
            long duration = System.currentTimeMillis() - startTime;
            ToolResult toolResult = ToolResult.failure("专家咨询失败: " + e.getMessage());
            toolResult.setExecutionTimeMs(duration);
            return toolResult;
        }
    }

    @Override
    public ExecutionMode getExecutionMode(Map<String, Object> params) {
        return ExecutionMode.LOCAL;
    }
}
