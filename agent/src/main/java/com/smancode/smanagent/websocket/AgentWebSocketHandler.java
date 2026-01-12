package com.smancode.smanagent.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smancode.smanagent.model.session.Session;
import com.smancode.smanagent.model.session.SessionStatus;
import com.smancode.smanagent.smancode.core.SmanCodeOrchestrator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Agent WebSocket 处理器
 * <p>
 * 处理前端与后端的 WebSocket 通信。
 */
@Component
public class AgentWebSocketHandler extends TextWebSocketHandler {

    private static final Logger logger = LoggerFactory.getLogger(AgentWebSocketHandler.class);

    @Autowired
    private SmanCodeOrchestrator orchestrator;

    @Autowired
    private SessionManager sessionManager;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 存储活跃的 WebSocket 会话
     * key: sessionId, value: WebSocketSession
     */
    private final Map<String, WebSocketSession> activeSessions = new ConcurrentHashMap<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        logger.info("WebSocket 连接建立: sessionId={}", session.getId());

        // 发送连接成功消息
        sendMessage(session, Map.of(
            "type", "connected",
            "message", "WebSocket 连接成功"
        ));
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        String payload = message.getPayload();
        logger.debug("收到 WebSocket 消息: payload={}", truncate(payload, 200));

        try {
            // 解析请求
            Map<String, Object> request = objectMapper.readValue(payload, Map.class);
            String type = (String) request.get("type");

            switch (type) {
                case "analyze" -> handleAnalyze(session, request);
                case "chat" -> handleChat(session, request);
                case "ping" -> handlePing(session);
                default -> logger.warn("未知消息类型: {}", type);
            }

        } catch (Exception e) {
            logger.error("处理消息失败", e);
            sendError(session, "处理失败: " + e.getMessage());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        logger.info("WebSocket 连接关闭: sessionId={}, status={}", session.getId(), status);

        // 清理会话
        activeSessions.entrySet().removeIf(entry -> entry.getValue().getId().equals(session.getId()));

        // 更新会话状态
        sessionManager.endSession(session.getId());
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        logger.error("WebSocket 传输错误: sessionId={}", session.getId(), exception);
    }

    /**
     * 处理分析请求
     */
    private void handleAnalyze(WebSocketSession wsSession, Map<String, Object> request) throws Exception {
        String sessionId = (String) request.get("sessionId");
        String userInput = (String) request.get("input");
        String projectKey = (String) request.get("projectKey");

        logger.info("处理分析请求: sessionId={}, input={}", sessionId, truncate(userInput, 50));

        // 获取或创建会话
        Session session = sessionManager.getOrCreateSession(sessionId, projectKey);
        session.setStatus(SessionStatus.WORKING);

        // 注册 WebSocket 会话
        activeSessions.put(sessionId, wsSession);

        // 创建 Part 推送器
        var partPusher = new WebSocketPartPusher(wsSession, sessionId);

        // 处理请求
        orchestrator.process(session, userInput, part -> {
            // 推送 Part 到前端
            partPusher.accept(part);
        });

        // 推送完成消息
        sendMessage(wsSession, Map.of(
            "type", "complete",
            "sessionId", sessionId
        ));

        session.setStatus(SessionStatus.DONE);
    }

    /**
     * 处理聊天请求
     */
    private void handleChat(WebSocketSession wsSession, Map<String, Object> request) throws Exception {
        String sessionId = (String) request.get("sessionId");
        String userInput = (String) request.get("input");

        logger.info("处理聊天请求: sessionId={}, input={}", sessionId, truncate(userInput, 50));

        // 获取会话
        Session session = sessionManager.getSession(sessionId);
        if (session == null) {
            sendError(wsSession, "会话不存在");
            return;
        }

        // 注册 WebSocket 会话
        activeSessions.put(sessionId, wsSession);

        // 创建 Part 推送器
        var partPusher = new WebSocketPartPusher(wsSession, sessionId);

        // 处理请求
        orchestrator.process(session, userInput, part -> {
            partPusher.accept(part);
        });
    }

    /**
     * 处理心跳
     */
    private void handlePing(WebSocketSession session) throws Exception {
        sendMessage(session, Map.of(
            "type", "pong",
            "timestamp", System.currentTimeMillis()
        ));
    }

    /**
     * 发送消息到 WebSocket
     */
    private void sendMessage(WebSocketSession session, Object data) throws Exception {
        String json = objectMapper.writeValueAsString(data);
        session.sendMessage(new TextMessage(json));
    }

    /**
     * 发送错误消息
     */
    private void sendError(WebSocketSession session, String error) {
        try {
            sendMessage(session, Map.of(
                "type", "error",
                "message", error
            ));
        } catch (Exception e) {
            logger.error("发送错误消息失败", e);
        }
    }

    /**
     * 截断字符串（用于日志）
     */
    private String truncate(String str, int maxLen) {
        if (str == null) return null;
        return str.length() > maxLen ? str.substring(0, maxLen) + "..." : str;
    }

    /**
     * WebSocket Part 推送器
     */
    private class WebSocketPartPusher implements java.util.function.Consumer<com.smancode.smanagent.model.part.Part> {
        private final WebSocketSession wsSession;
        private final String sessionId;

        public WebSocketPartPusher(WebSocketSession wsSession, String sessionId) {
            this.wsSession = wsSession;
            this.sessionId = sessionId;
        }

        @Override
        public void accept(com.smancode.smanagent.model.part.Part part) {
            try {
                Map<String, Object> message = Map.of(
                    "type", "part",
                    "sessionId", sessionId,
                    "part", partToMap(part)
                );
                sendMessage(wsSession, message);
            } catch (Exception e) {
                logger.error("推送 Part 失败", e);
            }
        }

        private Map<String, Object> partToMap(com.smancode.smanagent.model.part.Part part) {
            Map<String, Object> map = new ConcurrentHashMap<>();
            map.put("id", part.getId());
            map.put("type", part.getType().name());
            map.put("createdTime", part.getCreatedTime().toString());
            map.put("updatedTime", part.getUpdatedTime().toString());

            // 根据类型添加特定字段
            if (part instanceof com.smancode.smanagent.model.part.TextPart) {
                map.put("text", ((com.smancode.smanagent.model.part.TextPart) part).getText());
            } else if (part instanceof com.smancode.smanagent.model.part.ReasoningPart) {
                map.put("text", ((com.smancode.smanagent.model.part.ReasoningPart) part).getText());
            } else if (part instanceof com.smancode.smanagent.model.part.ToolPart) {
                com.smancode.smanagent.model.part.ToolPart toolPart = (com.smancode.smanagent.model.part.ToolPart) part;
                map.put("toolName", toolPart.getToolName());
                map.put("state", toolPart.getState().getClass().getSimpleName());
            } else if (part instanceof com.smancode.smanagent.model.part.GoalPart) {
                com.smancode.smanagent.model.part.GoalPart goalPart = (com.smancode.smanagent.model.part.GoalPart) part;
                map.put("title", goalPart.getTitle());
                map.put("description", goalPart.getDescription());
                map.put("status", goalPart.getStatus().name());
            }

            return map;
        }
    }
}
