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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 工具转发服务
 *
 * 功能：
 * - 将需要转发的工具调用发送给 IDE Plugin
 * - 等待 IDE Plugin 返回工具执行结果
 * - 管理工具调用的超时和异常
 */
@Service
public class ToolForwardingService {

    private static final Logger logger = LoggerFactory.getLogger(ToolForwardingService.class);

    private final ObjectMapper objectMapper = new ObjectMapper();

    // 存储 WebSocket Session (sessionId -> WebSocketSession)
    private final Map<String, WebSocketSession> activeSessions = new ConcurrentHashMap<>();

    // 存储等待中的工具调用 (toolCallId -> CompletableFuture)
    private final Map<String, CompletableFuture<JsonNode>> pendingToolCalls = new ConcurrentHashMap<>();

    // 工具调用超时时间（秒）
    private static final long TOOL_TIMEOUT = 30;

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
        WebSocketSession removed = activeSessions.remove(sessionId);
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
        logger.info("🔧 转发工具调用: tool={}, sessionId={}", toolName, webSocketSessionId);

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
            logger.info("📤 发送 TOOL_CALL 消息: toolCallId={}, 完整消息={}", toolCallId, payload);
            session.sendMessage(new TextMessage(payload));
            logger.info("✅ 已发送 TOOL_CALL: toolCallId={}", toolCallId);

            JsonNode result = future.get(TOOL_TIMEOUT, TimeUnit.SECONDS);
            logger.info("✅ 收到 TOOL_RESULT: toolCallId={}", toolCallId);
            return result;

        } catch (TimeoutException e) {
            logger.error("⏰ 工具调用超时: toolCallId={}", toolCallId);
            pendingToolCalls.remove(toolCallId);
            throw new java.util.concurrent.TimeoutException("工具调用超时: " + toolName);

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
        return toolName + "-" + webSocketSessionId.substring(0, Math.min(8, webSocketSessionId.length())) + "-" + System.currentTimeMillis();
    }
}
