package ai.smancode.sman.agent.claude;

import ai.smancode.sman.agent.ast.SpoonAstService;
import ai.smancode.sman.agent.callchain.CallChainService;
import ai.smancode.sman.agent.config.ProjectConfigService;
import ai.smancode.sman.agent.models.CallChainModels.CallChainRequest;
import ai.smancode.sman.agent.models.CallChainModels.CallChainResult;
import ai.smancode.sman.agent.models.SpoonModels.ClassInfo;
import ai.smancode.sman.agent.models.VectorModels.SearchResult;
import ai.smancode.sman.agent.models.VectorModels.SemanticSearchRequest;
import ai.smancode.sman.agent.vector.VectorSearchService;
import ai.smancode.sman.agent.websocket.AgentWebSocketHandler;
import ai.smancode.sman.agent.websocket.ToolForwardingService;
import ai.smancode.sman.agent.websocket.ToolForwardingService.WebSocketSessionManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * HTTP Tool 执行器
 *
 * 功能：
 * - 执行 Claude Code 调用的工具
 * - 路由到相应的服务（vector_search, call_chain, grep_file, read_file, apply_change）
 * - 统一的错误处理和响应格式
 *
 * 支持的工具：
 * - semantic_search: 向量语义搜索（本地处理，BGE-M3）
 * - call_chain: 调用链分析（转发 IDE）
 * - grep_file: 文件内搜索（转发 IDE，不指定文件则为全项目搜索）
 * - read_file: 读取文件（转发 IDE）
 * - apply_change: 应用代码修改（转发 IDE）
 *
 * @author SiliconMan Team
 * @since 1.0.0
 */
@Component
public class HttpToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(HttpToolExecutor.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private VectorSearchService vectorSearchService;

    @Autowired
    private SpoonAstService spoonAstService;

    @Autowired
    private CallChainService callChainService;

    @Autowired
    private ProjectConfigService projectConfigService;

    @Autowired
    private ToolForwardingService toolForwardingService;

    @Autowired
    private WebSocketSessionManager webSocketSessionManager;

    @Autowired
    private AgentWebSocketHandler agentWebSocketHandler;

    /**
     * 执行工具
     *
     * @param toolName 工具名称
     * @param params   工具参数
     * @param projectKey 项目标识符（多项目支持）
     * @param sessionId 会话ID（多轮对话支持）
     * @param webSocketSessionId WebSocket Session ID（用于转发工具调用给 IDE Plugin）
     * @return 执行结果
     */
    public ClaudeCodeToolModels.ToolExecutionResponse execute(String toolName, Map<String, Object> params, String projectKey, String sessionId, String webSocketSessionId) {
        log.info("🔧 执行工具: tool={}, projectKey={}, sessionId={}, webSocketSessionId={}, params={}",
                toolName, projectKey, sessionId, webSocketSessionId, params);

        // 将 projectKey、sessionId 和 webSocketSessionId 添加到 params 中（供具体方法使用）
        if (params == null) {
            params = new HashMap<>();
        }

        // 获取 webSocketSessionId
        // Claude Code 会将 webSocketSessionId 放在 params 中（通过 XML 标签传递）
        String actualWebSocketSessionId = webSocketSessionId;  // HTTP 请求体外层参数（通常为 null）
        if (params.containsKey("webSocketSessionId") && params.get("webSocketSessionId") != null) {
            actualWebSocketSessionId = (String) params.get("webSocketSessionId");  // 从 params 中获取
        }

        // 如果需要转发给 IDE Plugin，webSocketSessionId 必须存在
        if (toolForwardingService.shouldForwardToIde(toolName)) {
            if (actualWebSocketSessionId == null || actualWebSocketSessionId.isEmpty()) {
                log.error("❌ 工具 {} 需要 webSocketSessionId，但参数为空", toolName);
                return ClaudeCodeToolModels.ToolExecutionResponse.failure(
                    "webSocketSessionId 参数缺失，无法转发工具调用给 IDE Plugin");
            }
        }

        // 优先使用 params 中的 projectKey（兼容 Claude Code 在 params 中传递的情况）
        String actualProjectKey = projectKey;
        if (params.containsKey("projectKey") && params.get("projectKey") != null) {
            actualProjectKey = (String) params.get("projectKey");
            log.info("📋 使用 params 中的 projectKey: {} (外层 projectKey={})", actualProjectKey, projectKey);
        } else if (actualProjectKey != null) {
            // 如果 params 中没有，使用参数传入的
            params.put("projectKey", actualProjectKey);
            log.info("📋 使用外层 projectKey: {} (已注入到 params)", actualProjectKey);
        }

        String actualSessionId = sessionId;
        if (params.containsKey("sessionId") && params.get("sessionId") != null) {
            actualSessionId = (String) params.get("sessionId");
            log.info("📋 使用 params 中的 sessionId: {} (外层 sessionId={})", actualSessionId, sessionId);
        } else if (actualSessionId != null) {
            // 如果 params 中没有，使用参数传入的
            params.put("sessionId", actualSessionId);
            log.info("📋 使用外层 sessionId: {} (已注入到 params)", actualSessionId);
        }

        // 🆕 注入 projectPath（如果提供了 projectKey）
        if (actualProjectKey != null && !params.containsKey("projectPath")) {
            try {
                String projectPath = projectConfigService.getProjectPath(actualProjectKey);
                params.put("projectPath", projectPath);
                log.info("✅ 已注入 projectPath: {} for projectKey={}", projectPath, actualProjectKey);
            } catch (IllegalArgumentException e) {
                log.warn("⚠️ 无法获取 projectPath: {} (projectKey={})", e.getMessage(), actualProjectKey);
                // 不中断执行，让具体工具方法处理缺失的 projectKey
            }
        }

        // 🆕 判断是否需要转发给 IDE Plugin
        if (toolForwardingService.shouldForwardToIde(toolName)) {
            return forwardToIdePlugin(toolName, params, actualWebSocketSessionId);
        }

        try {
            ClaudeCodeToolModels.ToolExecutionResponse response;
            switch (toolName) {
                case "semantic_search":
                    response = executeVectorSearch(params);
                    log.info("🎯 semantic_search 执行结果: success={}, resultCount={}\n   完整结果: {}",
                            response.isSuccess(),
                            response.isSuccess() ? response.getResult().get("count") : "N/A",
                            response.getResult());
                    return response;

                default:
                    return ClaudeCodeToolModels.ToolExecutionResponse.failure(
                            "未知的工具: " + toolName);
            }

        } catch (Exception e) {
            log.error("❌ 工具执行异常: tool={}, error={}", toolName, e.getMessage(), e);
            return ClaudeCodeToolModels.ToolExecutionResponse.failure(
                    "执行失败: " + e.getMessage());
        }
    }

    /**
     * 执行语义搜索
     */
    private ClaudeCodeToolModels.ToolExecutionResponse executeVectorSearch(Map<String, Object> params) {
        // 提取参数（全部必须）
        String projectKey = (String) params.get("projectKey");
        String recallQuery = (String) params.get("recallQuery");
        String rerankQuery = (String) params.get("rerankQuery");
        Integer recallTopK = params.get("recallTopK") != null ?
                ((Number) params.get("recallTopK")).intValue() : null;
        Integer rerankTopN = params.get("rerankTopN") != null ?
                ((Number) params.get("rerankTopN")).intValue() : null;
        Boolean enableReranker = params.get("enableReranker") != null ?
                (Boolean) params.get("enableReranker") : null;

        // 参数校验
        if (projectKey == null || projectKey.isEmpty()) {
            return ClaudeCodeToolModels.ToolExecutionResponse.failure("缺少 projectKey 参数");
        }
        if (recallQuery == null || recallQuery.isEmpty()) {
            return ClaudeCodeToolModels.ToolExecutionResponse.failure("缺少 recallQuery 参数");
        }
        if (rerankQuery == null || rerankQuery.isEmpty()) {
            return ClaudeCodeToolModels.ToolExecutionResponse.failure("缺少 rerankQuery 参数");
        }
        if (recallTopK == null) {
            return ClaudeCodeToolModels.ToolExecutionResponse.failure("缺少 recallTopK 参数");
        }
        if (rerankTopN == null) {
            return ClaudeCodeToolModels.ToolExecutionResponse.failure("缺少 rerankTopN 参数");
        }
        if (enableReranker == null) {
            return ClaudeCodeToolModels.ToolExecutionResponse.failure("缺少 enableReranker 参数");
        }

        // 执行搜索
        SemanticSearchRequest request = new SemanticSearchRequest();
        request.setProjectKey(projectKey);
        request.setRecallQuery(recallQuery);
        request.setRerankQuery(rerankQuery);
        request.setRecallTopK(recallTopK);
        request.setRerankTopN(rerankTopN);
        request.setEnableReranker(enableReranker);

        List<SearchResult> results = vectorSearchService.semanticSearch(request);

        // 构建响应
        Map<String, Object> resultMap = new HashMap<>();
        resultMap.put("recallQuery", recallQuery);
        resultMap.put("rerankQuery", rerankQuery);
        resultMap.put("recallTopK", recallTopK);
        resultMap.put("rerankTopN", rerankTopN);
        resultMap.put("count", results.size());
        resultMap.put("results", results);

        return ClaudeCodeToolModels.ToolExecutionResponse.success(resultMap);
    }

    /**
     * 转发工具调用给 IDE Plugin
     *
     * @param toolName 工具名称
     * @param params 工具参数
     * @param webSocketSessionId WebSocket Session ID
     * @return 工具执行结果
     */
    private ClaudeCodeToolModels.ToolExecutionResponse forwardToIdePlugin(
            String toolName, Map<String, Object> params, String webSocketSessionId) {

        log.info("🔄 转发工具给 IDE Plugin: tool={}, webSocketSessionId={}", toolName, webSocketSessionId);

        // 测试模式：如果 webSocketSessionId 为 test-*，返回模拟结果
        if (webSocketSessionId != null && webSocketSessionId.startsWith("test-")) {
            log.info("🧪 检测到测试模式，返回模拟结果: tool={}", toolName);
            return createMockResponse(toolName, params);
        }

        // 验证 webSocketSessionId
        if (webSocketSessionId == null || webSocketSessionId.isEmpty()) {
            log.error("❌ webSocketSessionId 为空，无法转发工具调用");
            return ClaudeCodeToolModels.ToolExecutionResponse.failure(
                    "webSocketSessionId 参数缺失，无法转发工具调用给 IDE Plugin（提示：使用 test-websocket-xxx 进行测试，或建立真实的 WebSocket 连接）");
        }

        try {
            // 调用 ToolForwardingService 转发工具
            JsonNode result = toolForwardingService.forwardToolCall(
                    webSocketSessionId, toolName, params, webSocketSessionManager);

            // 🔥 打印 IDE 返回的原始内容
            log.info("📨 收到 IDE 返回结果: tool={}, result={}", toolName, result.toPrettyString());

            // 解析结果
            boolean success = result.has("success") && result.get("success").asBoolean();
            if (success) {
                // 🔥 直接透传 result 字段给 Claude Code，不做任何转换
                // IDE 返回什么格式（String 或 Map），Claude Code 就收到什么格式
                if (result.has("result")) {
                    JsonNode resultNode = result.get("result");

                    // 根据结果类型决定如何包装
                    Map<String, Object> resultMap = new HashMap<>();
                    if (resultNode.isTextual()) {
                        // IDE 返回的是字符串，直接传递
                        resultMap.put("content", resultNode.asText());
                    } else if (resultNode.isObject() || resultNode.isArray()) {
                        // IDE 返回的是对象或数组，转换为 Map
                        resultMap = objectMapper.convertValue(resultNode, Map.class);
                    } else {
                        // 其他类型，转为字符串
                        resultMap.put("value", resultNode.toString());
                    }

                    return ClaudeCodeToolModels.ToolExecutionResponse.success(resultMap);
                } else {
                    // 没有 result 字段，返回空 success
                    return ClaudeCodeToolModels.ToolExecutionResponse.success(new HashMap<>());
                }
            } else {
                // 提取 error 字段
                String error = result.has("error") ? result.get("error").asText() : "未知错误";
                return ClaudeCodeToolModels.ToolExecutionResponse.failure(error);
            }

        } catch (Exception e) {
            log.error("❌ 转发工具调用失败: tool={}, error={}", toolName, e.getMessage(), e);
            return ClaudeCodeToolModels.ToolExecutionResponse.failure(
                    "转发工具调用失败: " + e.getMessage());
        }
    }

    /**
     * 创建模拟响应（用于测试模式）
     *
     * @param toolName 工具名称
     * @param params 工具参数
     * @return 模拟的工具执行结果
     */
    private ClaudeCodeToolModels.ToolExecutionResponse createMockResponse(
            String toolName, Map<String, Object> params) {

        return switch (toolName) {
            case "call_chain" -> {
                String method = (String) params.get("method");
                String direction = (String) params.getOrDefault("direction", "both");
                int depth = (Integer) params.getOrDefault("depth", 2);

                Map<String, Object> resultMap = new HashMap<>();
                resultMap.put("method", method);
                resultMap.put("direction", direction);
                resultMap.put("depth", depth);

                // 模拟调用链结果
                StringBuilder sb = new StringBuilder();
                sb.append("## 调用链分析: ").append(method).append("\n\n");
                sb.append("**分析方向**: ").append(direction).append("\n");
                sb.append("**分析深度**: ").append(depth).append("\n\n");
                sb.append("**[测试模式]** 此为模拟数据，实际使用时请建立真实的 WebSocket 连接\n\n");

                if ("callers".equals(direction) || "both".equals(direction)) {
                    sb.append("### 🔼 调用者（谁调用了这个方法）\n\n");
                    sb.append("- `CallerClass1.callerMethod1()` → `path/to/CallerClass1.java`\n");
                    sb.append("- `CallerClass2.callerMethod2()` → `path/to/CallerClass2.java`\n");
                }

                if ("callees".equals(direction) || "both".equals(direction)) {
                    sb.append("### 🔽 被调用者（这个方法调用了谁）\n\n");
                    sb.append("- `CalleeClass1.calleeMethod1()`\n");
                    sb.append("- `CalleeClass2.calleeMethod2()`\n");
                }

                resultMap.put("result", sb.toString());
                resultMap.put("_mock", true);

                yield ClaudeCodeToolModels.ToolExecutionResponse.success(resultMap);
            }

            case "grep_file" -> {
                String relativePath = (String) params.get("relativePath");
                String pattern = (String) params.get("pattern");

                Map<String, Object> resultMap = new HashMap<>();
                resultMap.put("relativePath", relativePath);
                resultMap.put("pattern", pattern);
                resultMap.put("matches", List.of(
                    Map.of("line", 10, "content", "public void " + pattern + " {"),
                    Map.of("line", 25, "content", "return " + pattern + ";")
                ));
                resultMap.put("_mock", true);

                yield ClaudeCodeToolModels.ToolExecutionResponse.success(resultMap);
            }

            case "read_file" -> {
                String relativePath = (String) params.get("relativePath");

                Map<String, Object> resultMap = new HashMap<>();
                resultMap.put("relativePath", relativePath);
                resultMap.put("content", "// Mock file content for: " + relativePath + "\n" +
                        "public class Example {\n" +
                        "    // File content here...\n" +
                        "}");
                resultMap.put("_mock", true);

                yield ClaudeCodeToolModels.ToolExecutionResponse.success(resultMap);
            }

            case "apply_change" -> {
                String relativePath = (String) params.get("relativePath");
                String description = (String) params.getOrDefault("description", "代码修改");

                Map<String, Object> resultMap = new HashMap<>();
                resultMap.put("relativePath", relativePath);
                resultMap.put("description", description);
                resultMap.put("success", true);
                resultMap.put("message", "测试模式：模拟应用代码修改成功");
                resultMap.put("_mock", true);

                yield ClaudeCodeToolModels.ToolExecutionResponse.success(resultMap);
            }

            default -> ClaudeCodeToolModels.ToolExecutionResponse.failure(
                    "未知工具: " + toolName);
        };
    }
}
