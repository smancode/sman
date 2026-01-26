package com.smancode.smanagent.tools.consult;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smancode.smanagent.common.service_discovery.api.ServiceRegistry;
import com.smancode.smanagent.config.KnowledgeGraphDiscoveryConfig;
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
 * 通过 HTTP 调用 Knowledge Graph 服务，获取业务知识和代码入口。
 * <p>
 * 架构设计：
 * - Agent 只负责工具编排（ReAct 循环）
 * - 向量搜索、重排等复杂功能由 Knowledge Graph 服务承担
 * - 支持云原生扩展（cloud-server 架构）
 * - 支持多项目（不同 projectKey 对应不同的 Knowledge Graph 服务实例）
 * <p>
 * 参数：
 * - query: 咨询问题（必填）
 * <p>
 * 执行模式：local（后端执行）
 */
@Component
public class ExpertConsultTool extends AbstractTool implements Tool {

    private static final Logger logger = LoggerFactory.getLogger(ExpertConsultTool.class);

    // ==================== 常量 ====================

    private static final String SERVICE_UNAVAILABLE_MESSAGE =
        "⚠️ expert_consult 服务当前不可用。\n\n" +
        "可能原因：\n" +
        "1. Knowledge 服务未启动或已崩溃\n" +
        "2. 网络连接问题\n" +
        "3. 服务正在重启中\n\n" +
        "建议操作：\n" +
        "- 请稍后重试（建议等待 5-10 秒）\n" +
        "- 检查 Knowledge 服务状态\n" +
        "- 联系管理员检查服务运行情况\n\n" +
        "提示：很多复杂需求需要 expert_consult 才能准确分析，请确保服务可用后再试。";

    private static final String ERROR_MISSING_QUERY = "缺少 query 参数";
    private static final String ERROR_PROJECT_NOT_CONFIGURED = "Knowledge Graph 项目未配置: ";
    private static final String ERROR_SERVICE_RETURN_ERROR = "Knowledge Graph 服务返回错误: ";
    private static final String ERROR_CONSULT_FAILED = "专家咨询失败: ";
    private static final String DISPLAY_NO_KNOWLEDGE = "未找到相关知识";
    private static final String DISPLAY_TITLE = "专家咨询结果";
    private static final String DISPLAY_TITLE_UNAVAILABLE = "expert_consult 不可用";
    private static final String TRACE_ID_PARAM = "traceId";

    // ==================== 依赖注入 ====================

    @Autowired
    private KnowledgeGraphDiscoveryConfig knowledgeGraphDiscoveryConfig;

    @Autowired
    private ServiceRegistry serviceRegistry;

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    // 包级可见，用于测试
    KnowledgeGraphDiscoveryConfig getKnowledgeGraphDiscoveryConfig() {
        return knowledgeGraphDiscoveryConfig;
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
                return ToolResult.failure(ERROR_MISSING_QUERY);
            }

            // ==================== 检查 expert_consult 是否可用 ====================
            if (!serviceRegistry.isExpertConsultAvailable(projectKey)) {
                logger.warn("expert_consult 不可用: projectKey={}, traceId={}, 将返回明确提示给前端",
                    projectKey, traceId);
                return withDuration(
                    ToolResult.success(SERVICE_UNAVAILABLE_MESSAGE, DISPLAY_TITLE_UNAVAILABLE, SERVICE_UNAVAILABLE_MESSAGE),
                    System.currentTimeMillis() - startTime
                );
            }

            // ==================== 获取服务 URL ====================
            String knowledgeGraphServiceUrl = getServiceUrl(projectKey, traceId);
            if (knowledgeGraphServiceUrl == null) {
                return withDuration(
                    ToolResult.failure(ERROR_PROJECT_NOT_CONFIGURED + projectKey),
                    System.currentTimeMillis() - startTime
                );
            }

            logger.info("执行专家咨询: projectKey={}, query={}, url={}, traceId={}",
                    projectKey, query, knowledgeGraphServiceUrl, traceId);

            // ==================== 调用 Knowledge Graph 服务 ====================
            ResponseEntity<String> response = callKnowledgeGraphService(knowledgeGraphServiceUrl, projectKey, query, traceId);
            long duration = System.currentTimeMillis() - startTime;

            if (!response.getStatusCode().is2xxSuccessful()) {
                logger.warn("Knowledge Graph 服务返回错误: status={}, traceId={}", response.getStatusCode(), traceId);
                return withDuration(ToolResult.failure(ERROR_SERVICE_RETURN_ERROR + response.getStatusCode()), duration);
            }

            String responseBody = response.getBody();
            if (responseBody == null || responseBody.isEmpty()) {
                return withDuration(ToolResult.success(DISPLAY_NO_KNOWLEDGE, DISPLAY_TITLE, null), duration);
            }

            return parseResponse(responseBody, duration, traceId);

        } catch (Exception e) {
            logger.error("专家咨询失败: traceId={}, {}", traceId, StackTraceUtils.formatStackTrace(e));
            return withDuration(ToolResult.failure(ERROR_CONSULT_FAILED + e.getMessage()), System.currentTimeMillis() - startTime);
        }
    }

    // ==================== 私有辅助方法 ====================

    /**
     * 获取 Knowledge Graph 服务 URL
     */
    private String getServiceUrl(String projectKey, String traceId) {
        try {
            return knowledgeGraphDiscoveryConfig.getKnowledgeGraphServiceUrl(projectKey);
        } catch (IllegalArgumentException e) {
            logger.error("Knowledge Graph 项目未配置: projectKey={}, traceId={}", projectKey, traceId);
            return null;
        }
    }

    /**
     * 调用 Knowledge Graph 服务
     */
    private ResponseEntity<String> callKnowledgeGraphService(
            String url, String projectKey, String query, String traceId) {
        Map<String, String> requestBody = new HashMap<>();
        requestBody.put("projectKey", projectKey);
        requestBody.put("query", query);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        String fullUrl = buildUrlWithTraceId(url, traceId);
        HttpEntity<Map<String, String>> request = new HttpEntity<>(requestBody, headers);

        return restTemplate.postForEntity(fullUrl, request, String.class);
    }

    /**
     * 构建 URL（带 traceId）
     */
    private String buildUrlWithTraceId(String baseUrl, String traceId) {
        if (traceId != null && !traceId.isEmpty()) {
            return baseUrl + "?" + TRACE_ID_PARAM + "=" + traceId;
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
                logger.warn("Knowledge Graph 服务返回失败: error={}, traceId={}", error, traceId);
                return withDuration(ToolResult.failure(error), duration);
            }

            JsonNode dataNode = jsonNode.path("data");
            String displayContent = dataNode.isObject() && dataNode.has("summary")
                    ? buildDisplayContent(dataNode)
                    : responseBody;

            return withDuration(ToolResult.success(responseBody, DISPLAY_TITLE, displayContent), duration);

        } catch (Exception e) {
            logger.debug("JSON 解析失败，返回原始响应: {}", e.getMessage());
            return withDuration(ToolResult.success(responseBody, DISPLAY_TITLE, responseBody), duration);
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
