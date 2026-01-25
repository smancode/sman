package com.smancode.smanagent.tools.consult;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smancode.smanagent.config.KnowledgeProperties;
import com.smancode.smanagent.tools.AbstractTool;
import com.smancode.smanagent.tools.ParameterDef;
import com.smancode.smanagent.tools.Tool;
import com.smancode.smanagent.tools.ToolResult;
import com.smancode.smanagent.util.StackTraceUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
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

    // 包级可见，用于测试
    KnowledgeProperties getKnowledgeProperties() {
        return knowledgeProperties;
    }

    RestTemplate getRestTemplate() {
        return restTemplate;
    }

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
        String traceId = MDC.get("traceId");

        try {
            String query = (String) params.get("query");
            if (query == null || query.trim().isEmpty()) {
                return ToolResult.failure("缺少 query 参数");
            }

            logger.info("执行专家咨询: projectKey={}, query={}, url={}, traceId={}",
                    projectKey, query, knowledgeProperties.getSearchUrl(), traceId);

            Map<String, String> requestBody = new HashMap<>();
            requestBody.put("projectKey", projectKey);
            requestBody.put("query", query);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            String url = buildUrlWithTraceId(knowledgeProperties.getSearchUrl(), traceId);
            HttpEntity<Map<String, String>> request = new HttpEntity<>(requestBody, headers);

            ResponseEntity<String> response = restTemplate.postForEntity(url, request, String.class);
            long duration = System.currentTimeMillis() - startTime;

            if (!response.getStatusCode().is2xxSuccessful()) {
                logger.warn("Knowledge 服务返回错误: status={}, traceId={}", response.getStatusCode(), traceId);
                return withDuration(ToolResult.failure("Knowledge 服务返回错误: " + response.getStatusCode()), duration);
            }

            String responseBody = response.getBody();
            if (responseBody == null || responseBody.isEmpty()) {
                return withDuration(ToolResult.success("未找到相关知识", "专家咨询结果", null), duration);
            }

            return parseResponse(responseBody, duration, traceId);

        } catch (Exception e) {
            logger.error("专家咨询失败: traceId={}, {}", traceId, StackTraceUtils.formatStackTrace(e));
            long duration = System.currentTimeMillis() - startTime;
            return withDuration(ToolResult.failure("专家咨询失败: " + e.getMessage()), duration);
        }
    }

    private String buildUrlWithTraceId(String baseUrl, String traceId) {
        if (traceId != null && !traceId.isEmpty()) {
            return baseUrl + "?traceId=" + traceId;
        }
        return baseUrl;
    }

    private ToolResult withDuration(ToolResult result, long duration) {
        result.setExecutionTimeMs(duration);
        return result;
    }

    private ToolResult parseResponse(String responseBody, long duration, String traceId) {
        try {
            JsonNode jsonNode = objectMapper.readTree(responseBody);
            JsonNode successNode = jsonNode.path("success");

            if (!successNode.isBoolean() || !successNode.asBoolean()) {
                String error = jsonNode.has("error") ? jsonNode.get("error").asText() : "未知错误";
                logger.warn("Knowledge 服务返回失败: error={}, traceId={}", error, traceId);
                return withDuration(ToolResult.failure(error), duration);
            }

            JsonNode dataNode = jsonNode.path("data");
            String displayContent = dataNode.isObject() && dataNode.has("summary")
                    ? buildDisplayContent(dataNode)
                    : responseBody;

            return withDuration(ToolResult.success(responseBody, "专家咨询结果", displayContent), duration);

        } catch (Exception e) {
            logger.debug("JSON 解析失败，返回原始响应: {}", e.getMessage());
            return withDuration(ToolResult.success(responseBody, "专家咨询结果", responseBody), duration);
        }
    }

    private String buildDisplayContent(JsonNode dataNode) {
        StringBuilder content = new StringBuilder();
        content.append(dataNode.get("summary").asText()).append("\n\n");

        appendEntities(content, dataNode);
        appendRules(content, dataNode);
        appendBusinessFlow(content, dataNode);
        appendCodeLocation(content, dataNode);
        appendConfidence(content, dataNode);

        return content.toString();
    }

    private void appendEntities(StringBuilder content, JsonNode dataNode) {
        JsonNode entities = dataNode.path("entities");
        if (!entities.isArray() || entities.isEmpty()) {
            return;
        }

        content.append("## 关联业务实体\n");
        for (JsonNode entity : entities) {
            String name = entity.path("name").asText("");
            String sourceText = entity.path("sourceText").asText("");

            content.append("- ").append(name);
            if (!sourceText.isEmpty()) {
                content.append(" (").append(sourceText).append(")");
            }
            content.append("\n");

            appendCodeNodes(content, entity);
        }
        content.append("\n");
    }

    private void appendCodeNodes(StringBuilder content, JsonNode entity) {
        JsonNode nodeDetail = entity.path("nodeDetail");
        JsonNode codeNodes = nodeDetail.path("codeNodes");

        if (!codeNodes.isArray() || codeNodes.isEmpty()) {
            return;
        }

        content.append("  代码位置: ");
        boolean first = true;
        for (JsonNode codeNode : codeNodes) {
            if (!first) {
                content.append(", ");
            }
            content.append(codeNode.asText());
            first = false;
        }
        content.append("\n");
    }

    private void appendRules(StringBuilder content, JsonNode dataNode) {
        JsonNode rules = dataNode.path("rules");
        if (!rules.isArray() || rules.isEmpty()) {
            return;
        }

        content.append("## 相关业务规则\n");
        for (JsonNode rule : rules) {
            String ruleDesc = rule.path("description").asText();
            if (ruleDesc.isEmpty()) {
                ruleDesc = rule.path("name").asText(rule.toString());
            }
            content.append("- ").append(ruleDesc).append("\n");
        }
        content.append("\n");
    }

    private void appendBusinessFlow(StringBuilder content, JsonNode dataNode) {
        JsonNode flow = dataNode.path("businessFlow");
        if (!flow.isArray() || flow.isEmpty()) {
            return;
        }

        content.append("## 业务流程\n");
        for (JsonNode step : flow) {
            String stepDesc = step.path("description").asText();
            if (stepDesc.isEmpty()) {
                stepDesc = step.path("name").asText(step.toString());
            }
            content.append("- ").append(stepDesc).append("\n");
        }
        content.append("\n");
    }

    private void appendCodeLocation(StringBuilder content, JsonNode dataNode) {
        JsonNode codeLocation = dataNode.path("codeLocation");
        if (!codeLocation.isObject()) {
            return;
        }

        content.append("## 代码位置\n");
        appendIfPresent(content, codeLocation, "className", "类");
        appendIfPresent(content, codeLocation, "methodName", "方法");
        appendIfPresent(content, codeLocation, "filePath", "文件");
        content.append("\n");
    }

    private void appendIfPresent(StringBuilder content, JsonNode node, String field, String label) {
        if (node.has(field)) {
            content.append("- ").append(label).append(": ").append(node.get(field).asText()).append("\n");
        }
    }

    private void appendConfidence(StringBuilder content, JsonNode dataNode) {
        if (dataNode.has("confidence")) {
            double confidence = dataNode.get("confidence").asDouble();
            content.append("置信度: ").append(String.format("%.0f%%", confidence * 100)).append("\n");
        }
    }

    @Override
    public ExecutionMode getExecutionMode(Map<String, Object> params) {
        return ExecutionMode.LOCAL;
    }
}
