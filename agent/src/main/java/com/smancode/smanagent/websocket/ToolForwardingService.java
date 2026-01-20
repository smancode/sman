package com.smancode.smanagent.websocket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 工具转发服务
 *
 * 功能：
 * - 将需要转发的工具调用发送给 IDE Plugin
 * - 等待 IDE Plugin 返回工具执行结果
 * - 管理工具调用的超时和异常
 * - 使用单线程执行器串行发送 WebSocket 消息（避免并发写入冲突）
 */
@Service
public class ToolForwardingService {

    private static final Logger logger = LoggerFactory.getLogger(ToolForwardingService.class);

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 单线程执行器，用于串行发送 WebSocket 消息
     * <p>
     * WebSocket Session 不是线程安全的，多个线程同时调用 sendMessage()
     * 会导致 IllegalStateException: TEXT_PARTIAL_WRITING
     * <p>
     * 解决方案：将所有消息发送任务提交到单线程执行器，确保串行发送
     */
    private final ExecutorService messageSender = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ws-message-sender");
        t.setDaemon(true);
        return t;
    });

    // 存储 WebSocket Session (sessionId -> WebSocketSession)
    private final Map<String, WebSocketSession> activeSessions = new ConcurrentHashMap<>();

    // 存储等待中的工具调用 (toolCallId -> CompletableFuture)
    private final Map<String, CompletableFuture<JsonNode>> pendingToolCalls = new ConcurrentHashMap<>();

    // 工具调用超时时间（秒）
    private static final long TOOL_TIMEOUT = 30;

    // Session ID 遮蔽长度（用于日志）
    private static final int SESSION_ID_MASK_LENGTH = 8;

    /**
     * 遮蔽 Session ID（用于日志输出）
     *
     * @param sessionId Session ID，可能为 null
     * @return 掩盖后的字符串，例如 "01234567..." 或 "null"
     */
    private static String maskSessionId(String sessionId) {
        if (sessionId == null) {
            return "null";
        }
        int length = Math.min(sessionId.length(), SESSION_ID_MASK_LENGTH);
        return sessionId.substring(0, length) + "...";
    }

    /**
     * 注册 WebSocket Session
     */
    public void registerSession(String sessionId, WebSocketSession session) {
        activeSessions.put(sessionId, session);
        logger.info("📌 注册 WebSocket Session: sessionId={}, 当前总数={}", sessionId, activeSessions.size());
    }

    /**
     * 注销 WebSocket Session
     */
    public void unregisterSession(String sessionId) {
        activeSessions.remove(sessionId);
        logger.info("🔌 注销 WebSocket Session: sessionId={}, 当前总数={}", sessionId, activeSessions.size());
    }

    /**
     * 获取 WebSocket Session
     */
    public WebSocketSession getWebSocketSession(String sessionId) {
        WebSocketSession session = activeSessions.get(sessionId);
        if (session == null) {
            logger.warn("⚠️  WebSocket Session 未找到: sessionId={}, 已注册的SessionIDs={}",
                sessionId, activeSessions.keySet());
        }
        return session;
    }

    /**
     * 判断工具是否需要转发给 IDE Plugin
     */
    public boolean shouldForwardToIde(String toolName) {
        return switch (toolName) {
            case "find_file", "read_file", "grep_file", "call_chain", "extract_xml", "apply_change" -> true;
            default -> false;
        };
    }

    /**
     * 转发工具调用给 IDE Plugin
     */
    public JsonNode forwardToolCall(String webSocketSessionId, String toolName,
                                    Map<String, Object> params) throws Exception {
        logger.info("🔧 转发工具调用: tool={}, sessionId={}", toolName, maskSessionId(webSocketSessionId));

        String toolCallId = generateToolCallId(toolName, webSocketSessionId);
        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        pendingToolCalls.put(toolCallId, future);

        try {
            WebSocketSession session = activeSessions.get(webSocketSessionId);
            if (session == null) {
                throw new IllegalStateException("WebSocket Session 未找到: " + webSocketSessionId);
            }

            ObjectNode message = objectMapper.createObjectNode();
            message.put("type", "TOOL_CALL");
            message.put("toolCallId", toolCallId);
            message.put("toolName", toolName);
            message.set("params", objectMapper.valueToTree(params));

            String payload = objectMapper.writeValueAsString(message);

            // 使用单线程执行器发送消息，避免并发写入冲突
            CompletableFuture<Void> sendFuture = CompletableFuture.runAsync(() -> {
                try {
                    logger.info("📤 发送 TOOL_CALL 消息: toolCallId={}", toolCallId);
                    session.sendMessage(new TextMessage(payload));
                    logger.info("✅ 已发送 TOOL_CALL: toolCallId={}", toolCallId);
                } catch (Exception e) {
                    logger.error("❌ 发送消息失败: toolCallId={}", toolCallId, e);
                    throw new RuntimeException(e);
                }
            }, messageSender);

            // 等待消息发送完成
            sendFuture.get();

            // 等待 IDE 返回结果
            JsonNode result = future.get(TOOL_TIMEOUT, TimeUnit.SECONDS);
            logger.info("✅ 收到 TOOL_RESULT: toolCallId={}", toolCallId);
            return result;

        } catch (TimeoutException e) {
            logger.error("⏰ 工具调用超时: toolCallId={}", toolCallId);
            pendingToolCalls.remove(toolCallId);
            throw new TimeoutException("工具调用超时: " + toolName);

        } catch (Exception e) {
            logger.error("❌ 转发工具调用失败: toolCallId={}", toolCallId, e);
            pendingToolCalls.remove(toolCallId);
            throw e;
        }
    }

    /**
     * 处理 IDE Plugin 返回的 TOOL_RESULT 消息
     */
    public boolean handleToolResult(JsonNode data) {
        String toolCallId = data.has("toolCallId") ? data.get("toolCallId").asText() : null;

        logger.info("📨 收到 TOOL_RESULT: toolCallId={}", toolCallId);

        if (toolCallId == null || toolCallId.isEmpty()) {
            logger.error("❌ TOOL_RESULT 缺少 toolCallId");
            return false;
        }

        CompletableFuture<JsonNode> future = pendingToolCalls.remove(toolCallId);
        if (future == null) {
            logger.warn("⚠️ 未找到对应的工具调用: toolCallId={}", toolCallId);
            return false;
        }

        future.complete(data);
        return true;
    }

    private String generateToolCallId(String toolName, String webSocketSessionId) {
        String maskedId = maskSessionId(webSocketSessionId).replace(".", "");
        return toolName + "-" + maskedId + "-" + System.currentTimeMillis();
    }
}
