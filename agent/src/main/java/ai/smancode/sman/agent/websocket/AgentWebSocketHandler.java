package ai.smancode.sman.agent.websocket;

import ai.smancode.sman.agent.claude.ClaudeCodeProcessPool;
import ai.smancode.sman.agent.claude.ClaudeCodeWorker;
import ai.smancode.sman.agent.config.ProjectConfigService;
import ai.smancode.sman.agent.fallback.FallbackDetector;
import ai.smancode.sman.agent.fallback.FallbackOrchestrator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

/**
 * WebSocket Agent 协议处理器
 *
 * 支持 AGENT_CHAT/AGENT_RESPONSE 协议，用于 Claude Code 多轮对话和 Agent 模式
 * 同时实现 WebSocketSessionManager 接口，用于工具转发
 *
 * @author SiliconMan Team
 */
@Component
public class AgentWebSocketHandler extends TextWebSocketHandler
        implements ToolForwardingService.WebSocketSessionManager {

    private static final Logger log = LoggerFactory.getLogger(AgentWebSocketHandler.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private ClaudeCodeProcessPool processPool;

    @Autowired
    private ProjectConfigService projectConfigService;

    @Autowired
    private FallbackDetector fallbackDetector;

    @Autowired
    private FallbackOrchestrator fallbackOrchestrator;

    @Autowired
    private ToolForwardingService toolForwardingService;

    /**
     * WebSocket 消息处理专用线程池
     */
    @Autowired
    @Qualifier("webSocketExecutor")
    private Executor webSocketExecutor;

    @Value("${claude-code.http-api.endpoint:/api/claude-code/tools/execute}")
    private String httpApiEndpoint;

    @Value("${server.port:8080}")
    private int serverPort;

    // 存储所有活跃的 WebSocket 会话
    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();

    // 存储会话元数据
    private final Map<String, SessionMetadata> sessionMetadataMap = new ConcurrentHashMap<>();

    // 存储活跃的 Worker 实例 (workerId -> Worker)
    private final Map<String, ClaudeCodeWorker> activeWorkers = new ConcurrentHashMap<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String sessionId = extractSessionId(session);

        log.info("========================================");
        log.info("🔌 WebSocket Agent 连接建立");
        log.info("  WebSocket sessionId: {}", session.getId());
        log.info("  用户 sessionId: {}", sessionId);
        log.info("  URI: {}", session.getUri());
        log.info("========================================");

        sessions.put(session.getId(), session);

        // 提取连接参数
        String projectKey = extractQueryParam(session, "projectKey");
        String projectPath = extractQueryParam(session, "projectPath");
        String mode = extractQueryParam(session, "mode", "agent");

        // 验证必需参数
        if (sessionId == null || sessionId.isEmpty()) {
            session.close(new CloseStatus(
                CloseStatus.NOT_ACCEPTABLE.getCode(),
                "INVALID_SESSION: sessionId 参数缺失"
            ));
            return;
        }

        if (projectKey == null || projectKey.isEmpty()) {
            session.close(new CloseStatus(
                CloseStatus.NOT_ACCEPTABLE.getCode(),
                "INVALID_PARAMS: projectKey 参数缺失"
            ));
            return;
        }

        // 验证 sessionId 格式（UUID）
        if (!isValidUuid(sessionId)) {
            session.close(new CloseStatus(
                CloseStatus.NOT_ACCEPTABLE.getCode(),
                "INVALID_SESSION: sessionId 格式无效，必须是 UUID"
            ));
            return;
        }

        // 保存会话元数据
        SessionMetadata metadata = new SessionMetadata();
        metadata.setWebSocketSessionId(session.getId());
        metadata.setUserSessionId(sessionId);
        metadata.setProjectKey(projectKey);
        metadata.setProjectPath(projectPath);
        metadata.setMode(mode);
        metadata.setConnectedAt(System.currentTimeMillis());

        sessionMetadataMap.put(session.getId(), metadata);

        // 如果未提供 projectPath，尝试从配置中查询
        if ((projectPath == null || projectPath.isEmpty()) && projectConfigService.hasProject(projectKey)) {
            projectPath = projectConfigService.getProjectPath(projectKey);
            metadata.setProjectPath(projectPath);
            log.info("📋 从配置中查询到 projectPath: {}", projectPath);
        }

        // 发送连接成功消息
        sendConnectedMessage(session, metadata);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        String payload = message.getPayload();

        log.debug("📨 收到消息: {}", payload);

        try {
            JsonNode json = objectMapper.readTree(payload);
            String messageType = json.has("type") ? json.get("type").asText() : null;
            JsonNode data = json.has("data") ? json.get("data") : null;

            if (messageType == null) {
                sendError(session, "INVALID_MESSAGE", "消息类型缺失");
                return;
            }

            switch (messageType) {
                case "AGENT_CHAT":
                    handleAgentChat(session, data);
                    break;

                case "TOOL_RESULT":
                    handleToolResult(session, data);
                    break;

                case "PING":
                    handlePing(session, data);
                    break;

                case "STOP":
                    handleStop(session, data);
                    break;

                default:
                    sendError(session, "UNKNOWN_MESSAGE_TYPE",
                        "未知的消息类型: " + messageType);
            }

        } catch (Exception e) {
            log.error("❌ 处理消息失败", e);
            sendError(session, "MESSAGE_PARSE_ERROR", e.getMessage());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        String webSocketSessionId = session.getId();

        if (webSocketSessionId == null) {
            log.warn("⚠️  WebSocket Session ID 为空，跳过清理");
            return;
        }

        log.info("🔌 WebSocket Agent 连接关闭: sessionId={}, status={}", webSocketSessionId, status);

        // 获取会话元数据（在移除之前）
        SessionMetadata metadata = sessionMetadataMap.get(webSocketSessionId);

        // 清理 Worker 资源（如果有）
        if (metadata != null) {
            String workerId = metadata.getWorkerId();
            if (workerId != null && !workerId.isEmpty()) {
                ClaudeCodeWorker worker = activeWorkers.get(workerId);
                if (worker != null) {
                    log.info("🧹 连接关闭，清理 Worker {}...", workerId);

                    try {
                        // 终止进程
                        Process process = worker.getProcess();
                        if (process != null && process.isAlive()) {
                            process.destroyForcibly();
                            log.info("✅ Worker {} 进程已终止", workerId);
                        }

                        // 从活跃 Map 中移除
                        activeWorkers.remove(workerId);

                        // 释放并发许可
                        processPool.releaseConcurrency();
                        log.info("✅ 并发许可已释放");

                        // 标记 Worker 完成
                        processPool.markWorkerCompleted(worker);

                    } catch (Exception e) {
                        log.error("❌ 清理 Worker {} 失败: {}", workerId, e.getMessage(), e);
                    }
                }
            }
        }

        // 移除会话
        sessions.remove(webSocketSessionId);
        sessionMetadataMap.remove(webSocketSessionId);

        log.info("✅ 会话清理完成: sessionId={}", webSocketSessionId);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        // 🔥 EOFException 是客户端正常断开连接，降级为 INFO 日志
        if (exception instanceof java.io.EOFException) {
            log.info("🔌 客户端断开连接 (EOF): sessionId={}", session.getId());
            return;
        }

        // 其他异常仍然记录为 ERROR
        log.error("❌ WebSocket 传输错误: sessionId={}", session.getId(), exception);

        String sessionIdKey = session.getId();
        if (sessionIdKey == null) {
            log.warn("⚠️  Session ID 为空，无法更新错误计数");
            return;
        }

        SessionMetadata metadata = sessionMetadataMap.get(sessionIdKey);
        if (metadata != null) {
            metadata.setErrorCount(metadata.getErrorCount() + 1);

            if (metadata.getErrorCount() > 5) {
                log.warn("⚠️  错误次数过多，关闭连接: sessionId={}", session.getId());
                session.close();
            }
        }
    }

    /**
     * 处理 AGENT_CHAT 消息
     */
    private void handleAgentChat(WebSocketSession session, JsonNode data) {
        String sessionId = data.has("sessionId") ? data.get("sessionId").asText() : null;
        String projectKey = data.has("projectKey") ? data.get("projectKey").asText() : null;
        String projectPath = data.has("projectPath") ? data.get("projectPath").asText() : null;
        String userMessage = data.has("message") ? data.get("message").asText() : null;
        String mode = data.has("mode") ? data.get("mode").asText() : null;

        // 验证参数
        if (userMessage == null || userMessage.isEmpty()) {
            sendError(session, "INVALID_PARAMS", "message 缺失");
            return;
        }

        // 验证 Session ID
        String sessionIdKey = session.getId();
        if (sessionIdKey == null) {
            sendError(session, "INVALID_SESSION", "WebSocket Session ID 为空");
            return;
        }

        // 更新会话元数据
        SessionMetadata metadata = sessionMetadataMap.get(sessionIdKey);
        if (metadata == null) {
            sendError(session, "SESSION_NOT_FOUND", "会话不存在");
            return;
        }

        // 🔥 关键修复：优先使用消息体中的 sessionId，如果为空则使用 metadata 中的 sessionId (从 URL 获取)
        String effectiveSessionId = sessionId;
        if (effectiveSessionId == null || effectiveSessionId.isEmpty()) {
            effectiveSessionId = metadata.getUserSessionId();  // 从 URL 获取的 UUID
        }

        // 🔥 使用 effectiveSessionId 生成固定的会话日志标识符(整个会话使用同一个时间戳)
        String shortUuid = effectiveSessionId.length() > 12
            ? effectiveSessionId.substring(effectiveSessionId.length() - 12)
            : effectiveSessionId;

        String timestamp = java.time.LocalTime.now().format(
            java.time.format.DateTimeFormatter.ofPattern("HHmmss")
        );

        String logTag = "[" + shortUuid + "_" + timestamp + "]";

        // 临时设置当前线程名称，方便追踪整个会话
        Thread currentThread = Thread.currentThread();
        String originalThreadName = currentThread.getName();
        currentThread.setName(logTag);

        try {
            log.info("========================================");
            log.info("📨 收到 AGENT_CHAT 请求");
            log.info("  sessionId: {}", effectiveSessionId);
            log.info("  projectKey: {}", projectKey);
            log.info("  message: {}", userMessage);
            log.info("  mode: {}", mode);
            log.info("========================================");

        // 🔥 保存 logTag 到 metadata (整个会话复用同一个标识符)
        metadata.setLogTag(logTag);
        metadata.setLastActivityAt(System.currentTimeMillis());
        metadata.setMessageCount(metadata.getMessageCount() + 1);

        // 使用消息中的 projectPath（如果提供）
        if (projectPath != null && !projectPath.isEmpty()) {
            metadata.setProjectPath(projectPath);
        }

        // 检查是否应该启用降级模式
        boolean shouldFallback = fallbackDetector.shouldEnableFallback();

        if (shouldFallback) {
            log.warn("⚠️  检测到降级触发条件，使用降级模式");
            handleAgentChatFallback(session, userMessage, projectKey, effectiveSessionId);
            return;
        }

        // 正常模式：使用 Claude Code
            handleAgentChatNormal(session, userMessage, projectKey, effectiveSessionId, metadata);

        } finally {
            // 恢复原始线程名
            currentThread.setName(originalThreadName);
        }
    }

    /**
     * 正常模式处理（使用 Claude Code）
     */
    private void handleAgentChatNormal(WebSocketSession session, String userMessage,
                                       String projectKey, String sessionId,
                                       SessionMetadata metadata) {
        try {
            // 🔥 获取 projectPath：始终使用配置文件中的路径（不使用前端传来的路径）
            String projectPath;
            if (projectConfigService.hasProject(projectKey)) {
                projectPath = projectConfigService.getProjectPath(projectKey);
                log.info("✅ 从配置文件获取 projectPath: projectKey={}, projectPath={}", projectKey, projectPath);
            } else {
                sendError(session, "PROJECT_NOT_FOUND",
                    "未找到 projectKey 映射: " + projectKey + "，请检查 application.yml 配置");
                return;
            }

            // 构建发送给 Claude Code 的消息
            String webSocketSessionId = session.getId();  // 获取 WebSocket Session ID
            String messageWithProjectKey = "<message>" + userMessage + "</message>"
                + "<projectKey>" + projectKey + "</projectKey>"
                + "<webSocketSessionId>" + webSocketSessionId + "</webSocketSessionId>";
            String claudeMessage = buildClaudeMessage(messageWithProjectKey, projectKey, sessionId, projectPath, metadata.getMode());

            // 获取并发许可
            log.info("🔄 等待并发许可 (sessionId={})...", sessionId);
            processPool.acquireConcurrency();
            log.info("✅ 获得并发许可 (sessionId={})", sessionId);

            // 创建 Worker 进程
            log.info("🚀 创建 Worker 进程 (sessionId={}, projectKey={}, projectPath={}, mode={})...",
                    sessionId, projectKey, projectPath, metadata.getMode());

            // 🔥 解析执行模式并传递给 createWorker
            ClaudeCodeProcessPool.ExecutionMode execMode =
                ClaudeCodeProcessPool.ExecutionMode.fromString(metadata.getMode());

            // 🔥 传递 logTag 给 Worker,确保整个会话使用同一个时间戳
            ClaudeCodeWorker worker = processPool.createWorker(
                sessionId, projectKey, projectPath, metadata.getLogTag(), execMode);

            log.info("✅ Worker 进程创建成功: {} (sessionId={}, mode={})",
                    worker.getWorkerId(), sessionId, execMode);

            // 保存 workerId 到 metadata (用于后续取消操作)
            metadata.setWorkerId(worker.getWorkerId());

            // 保存 Worker 到活跃 Map (用于取消操作)
            activeWorkers.put(worker.getWorkerId(), worker);

            // 异步执行并流式推送响应
            executeWorkerAsync(worker, claudeMessage, session, metadata);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            sendError(session, "INTERRUPTED", "请求被中断");
            // 释放并发许可（避免许可泄漏）
            processPool.releaseConcurrency();

        } catch (Exception e) {
            log.error("❌ 处理 AGENT_CHAT 失败", e);
            sendError(session, "PROCESSING_FAILED", e.getMessage());
            processPool.releaseConcurrency();
        }
    }

    /**
     * 降级模式处理
     */
    private void handleAgentChatFallback(WebSocketSession session, String userMessage,
                                         String projectKey, String sessionId) {
        try {
            log.info("🔴 使用降级模式处理请求");

            // 调用降级编排器
            String result = fallbackOrchestrator.processRequest(userMessage, projectKey, sessionId);

            // 推送降级响应
            sendMessage(session, "AGENT_RESPONSE", Map.of(
                "sessionId", sessionId,
                "content", result,
                "status", "SUCCESS",  // 🔥 修复：使用大写 SUCCESS（前端检查大写）
                "fallbackMode", true
            ));

        } catch (Exception e) {
            log.error("❌ 降级模式处理失败", e);
            sendError(session, "FALLBACK_FAILED", e.getMessage());
        }
    }

    /**
     * 异步执行 Worker 并流式推送响应
     */
    private void executeWorkerAsync(ClaudeCodeWorker worker, String message,
                                    WebSocketSession session,
                                    SessionMetadata metadata) {
        CompletableFuture.runAsync(() -> {
            try {
                log.info("📤 发送消息给 Claude Code（流式模式）(sessionId={})...", metadata.getUserSessionId());

                // 🆕 使用流式读取
                worker.sendAndReceiveStreaming(message, new ClaudeCodeWorker.StreamingCallback() {
                    @Override
                    public void onLineRead(String line) {
                        // 可选：推送原始行（用于调试）
                        log.debug("🔵 [流式] 读取到行: {}", line);
                    }

                    @Override
                    public void onMarkdownChunk(String markdown, int chunkIndex, boolean isComplete) {
                        // 推送 Markdown 块到前端
                        sendMessage(session, "STREAMING_CONTENT", Map.of(
                            "sessionId", metadata.getUserSessionId(),
                            "content", markdown,
                            "contentType", "markdown",
                            "chunkIndex", chunkIndex,
                            "isComplete", isComplete,
                            "workerId", worker.getWorkerId()
                        ));
                    }

                    @Override
                    public void onComplete(String fullResponse) {
                        log.info("✅ [流式] 分析完成 (sessionId={})", metadata.getUserSessionId());

                        // 推送最终完成信号
                        sendMessage(session, "AGENT_RESPONSE", Map.of(
                            "sessionId", metadata.getUserSessionId(),
                            "content", fullResponse,
                            "status", "SUCCESS",  // 🔥 修复：使用大写 SUCCESS（前端检查大写）
                            "workerId", worker.getWorkerId(),
                            "fallbackMode", false,
                            "streamingComplete", true  // 标记流式传输完成
                        ));
                    }

                    @Override
                    public void onError(String error) {
                        log.error("❌ [流式] Worker 执行失败: {}", error);
                        sendError(session, "WORKER_EXECUTION_FAILED", error);
                    }
                }, 600);

            } catch (Exception e) {
                log.error("❌ Worker 执行失败", e);
                sendError(session, "WORKER_EXECUTION_FAILED", e.getMessage());

            } finally {
                // 释放并发许可
                processPool.releaseConcurrency();

                // 从活跃 Map 中移除 Worker
                activeWorkers.remove(worker.getWorkerId());

                // 标记 Worker 完成
                processPool.markWorkerCompleted(worker);
                log.info("✅ Worker {} 完成 (sessionId={})",
                    worker.getWorkerId(), metadata.getUserSessionId());
            }
        }, webSocketExecutor);
    }

    /**
     * 处理 TOOL_RESULT 消息
     */
    private void handleToolResult(WebSocketSession session, JsonNode data) {
        String toolCallId = data.has("toolCallId") ? data.get("toolCallId").asText() : null;
        Boolean success = data.has("success") ? data.get("success").asBoolean() : null;
        String result = data.has("result") ? data.get("result").asText() : null;
        String error = data.has("error") ? data.get("error").asText() : null;

        log.info("📨 收到 TOOL_RESULT: toolCallId={}, success={}", toolCallId, success);

        // 🆕 使用 ToolForwardingService 处理工具结果
        boolean handled = toolForwardingService.handleToolResult(data);
        if (handled) {
            log.info("✅ TOOL_RESULT 已成功处理: toolCallId={}", toolCallId);
        } else {
            log.warn("⚠️  TOOL_RESULT 处理失败: toolCallId={}", toolCallId);
        }
    }

    /**
     * 实现 WebSocketSessionManager 接口
     * 用于从 webSocketSessionId 获取 WebSocket Session
     */
    @Override
    public WebSocketSession getSession(String webSocketSessionId) {
        return sessions.get(webSocketSessionId);
    }

    /**
     * 通过 userSessionId 查询对应的 webSocketSessionId
     * 用于 HTTP Tool API 调用时获取 webSocketSessionId
     *
     * @param userSessionId 用户会话 ID
     * @return WebSocket Session ID，如果未找到返回 null
     */
    public String getWebSocketSessionId(String userSessionId) {
        if (userSessionId == null) {
            return null;
        }
        for (Map.Entry<String, SessionMetadata> entry : sessionMetadataMap.entrySet()) {
            SessionMetadata metadata = entry.getValue();
            String metadataSessionId = metadata.getUserSessionId();
            if (metadataSessionId != null && metadataSessionId.equals(userSessionId)) {
                return metadata.getWebSocketSessionId();
            }
        }
        return null;
    }

    /**
     * 处理 PING 消息
     */
    private void handlePing(WebSocketSession session, JsonNode data) throws IOException {
        Long timestamp = data.has("timestamp") ? data.get("timestamp").asLong() : null;

        // 回复 PONG
        sendMessage(session, "PONG", Map.of(
            "timestamp", timestamp != null ? timestamp : System.currentTimeMillis()
        ));
    }

    /**
     * 处理 STOP 消息（用户主动取消）
     */
    private void handleStop(WebSocketSession session, JsonNode data) {
        String webSocketSessionId = session.getId();
        SessionMetadata metadata = sessionMetadataMap.get(webSocketSessionId);

        if (metadata == null) {
            log.warn("⚠️  收到 STOP 消息，但未找到会话元数据: sessionId={}", webSocketSessionId);
            sendError(session, "SESSION_NOT_FOUND", "会话不存在");
            return;
        }

        String workerId = metadata.getWorkerId();
        String userSessionId = metadata.getUserSessionId();

        log.info("========================================");
        log.info("🛑 收到 STOP 请求");
        log.info("  WebSocket sessionId: {}", webSocketSessionId);
        log.info("  用户 sessionId: {}", userSessionId);
        log.info("  Worker ID: {}", workerId);
        log.info("========================================");

        if (workerId != null && !workerId.isEmpty()) {
            ClaudeCodeWorker worker = activeWorkers.get(workerId);
            if (worker != null) {
                log.info("🔪 正在终止 Worker {}...", workerId);

                try {
                    // 终止进程
                    Process process = worker.getProcess();
                    if (process != null && process.isAlive()) {
                        process.destroyForcibly();
                        log.info("✅ Worker {} 进程已终止", workerId);
                    }

                    // 从活跃 Map 中移除
                    activeWorkers.remove(workerId);

                    // 释放并发许可
                    processPool.releaseConcurrency();

                    // 标记 Worker 完成
                    processPool.markWorkerCompleted(worker);

                } catch (Exception e) {
                    log.error("❌ 终止 Worker {} 失败: {}", workerId, e.getMessage(), e);
                }
            } else {
                log.warn("⚠️  Worker {} 不在活跃 Map 中（可能已完成）", workerId);
            }
        } else {
            log.warn("⚠️  会话没有关联的 Worker (userSessionId={})", userSessionId);
        }

        // 发送 STOPPED 确认消息
        sendMessage(session, "STOPPED", Map.of(
            "sessionId", userSessionId,
            "message", "分析已取消",
            "timestamp", System.currentTimeMillis()
        ));

        log.info("✅ STOP 处理完成 (userSessionId={})", userSessionId);
    }

    /**
     * 构建发送给 Claude Code 的消息
     */
    private String buildClaudeMessage(String userMessage, String projectKey,
                                      String sessionId, String projectPath, String mode) {
        StringBuilder sb = new StringBuilder();
        sb.append(userMessage);
        return sb.toString();
    }

    /**
     * 发送连接成功消息
     */
    private void sendConnectedMessage(WebSocketSession session, SessionMetadata metadata) throws IOException {
        Map<String, Object> response = Map.of(
            "message", "WebSocket connected successfully",
            "sessionId", metadata.getUserSessionId(),
            "projectKey", metadata.getProjectKey(),
            "projectPath", metadata.getProjectPath(),
            "mode", metadata.getMode(),
            "serverTime", System.currentTimeMillis(),
            "protocolVersion", "2.0"
        );

        sendMessage(session, "CONNECTED", response);
    }

    /**
     * 发送消息到客户端
     */
    private void sendMessage(WebSocketSession session, String type, Map<String, Object> data) {
        try {
            // 使用 Map 构建消息,然后转换为 JSON
            Map<String, Object> payload = new java.util.HashMap<>();
            payload.put("type", type);
            payload.put("data", data);
            payload.put("timestamp", System.currentTimeMillis());

            String jsonPayload = objectMapper.writeValueAsString(payload);
            session.sendMessage(new TextMessage(jsonPayload));

        } catch (IOException e) {
            log.error("❌ 发送消息失败: type={}", type, e);
        }
    }

    /**
     * 发送错误消息
     */
    private void sendError(WebSocketSession session, String errorCode, String errorMessage) {
        sendMessage(session, "ERROR", Map.of(
            "errorCode", errorCode,
            "errorMessage", errorMessage,
            "timestamp", System.currentTimeMillis()
        ));
    }

    /**
     * 从 URI 中提取 sessionId
     */
    private String extractSessionId(WebSocketSession session) {
        return extractQueryParam(session, "sessionId");
    }

    /**
     * 从 URI 中提取查询参数
     */
    private String extractQueryParam(WebSocketSession session, String paramName) {
        return extractQueryParam(session, paramName, null);
    }

    /**
     * 从 URI 中提取查询参数（带默认值）
     */
    private String extractQueryParam(WebSocketSession session, String paramName, String defaultValue) {
        String uri = session.getUri().toString();
        String query = uri.contains("?") ? uri.substring(uri.indexOf("?") + 1) : "";

        for (String param : query.split("&")) {
            String[] pair = param.split("=");
            if (pair.length == 2 && pair[0].equals(paramName)) {
                try {
                    return java.net.URLDecoder.decode(pair[1], "UTF-8");
                } catch (java.io.UnsupportedEncodingException e) {
                    return pair[1];
                }
            }
        }

        return defaultValue;
    }

    /**
     * 验证 UUID 格式
     */
    private boolean isValidUuid(String sessionId) {
        try {
            UUID.fromString(sessionId);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * 会话元数据
     */
    public static class SessionMetadata {
        private String webSocketSessionId;
        private String userSessionId;
        private String projectKey;
        private String projectPath;
        private String mode;
        private String workerId;  // 关联的 Claude Code Worker ID
        private String logTag;    // 日志标识符 (格式: [shortUuid_HHMMSS])
        private long connectedAt;
        private long lastActivityAt;
        private int messageCount;
        private int errorCount;

        // Getters and Setters
        public String getWebSocketSessionId() { return webSocketSessionId; }
        public void setWebSocketSessionId(String webSocketSessionId) { this.webSocketSessionId = webSocketSessionId; }

        public String getUserSessionId() { return userSessionId; }
        public void setUserSessionId(String userSessionId) { this.userSessionId = userSessionId; }

        public String getProjectKey() { return projectKey; }
        public void setProjectKey(String projectKey) { this.projectKey = projectKey; }

        public String getProjectPath() { return projectPath; }
        public void setProjectPath(String projectPath) { this.projectPath = projectPath; }

        public String getMode() { return mode; }
        public void setMode(String mode) { this.mode = mode; }

        public String getWorkerId() { return workerId; }
        public void setWorkerId(String workerId) { this.workerId = workerId; }

        public long getConnectedAt() { return connectedAt; }
        public void setConnectedAt(long connectedAt) { this.connectedAt = connectedAt; }

        public long getLastActivityAt() { return lastActivityAt; }
        public void setLastActivityAt(long lastActivityAt) { this.lastActivityAt = lastActivityAt; }

        public int getMessageCount() { return messageCount; }
        public void setMessageCount(int messageCount) { this.messageCount = messageCount; }

        public int getErrorCount() { return errorCount; }
        public void setErrorCount(int errorCount) { this.errorCount = errorCount; }

        public String getLogTag() { return logTag; }
        public void setLogTag(String logTag) { this.logTag = logTag; }
    }
}
