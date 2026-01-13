package com.smancode.smanagent.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smancode.smanagent.model.message.Message;
import com.smancode.smanagent.model.message.Role;
import com.smancode.smanagent.model.part.Part;
import com.smancode.smanagent.model.part.TextPart;
import com.smancode.smanagent.model.session.Session;
import com.smancode.smanagent.model.session.SessionStatus;
import com.smancode.smanagent.service.SessionFileService;
import com.smancode.smanagent.smancode.core.SmanAgentLoop;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;

/**
 * Agent WebSocket 处理器
 * <p>
 * 处理前端与后端的 WebSocket 通信。
 */
@Component
public class AgentWebSocketHandler extends TextWebSocketHandler {

    private static final Logger logger = LoggerFactory.getLogger(AgentWebSocketHandler.class);

    @Autowired
    private SmanAgentLoop smanAgentLoop;

    @Autowired
    private WebSocketSessionManager sessionManager;

    @Autowired
    private SessionFileService sessionFileService;

    @Resource(name = "webSocketExecutorService")
    private ExecutorService executorService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 正在处理的会话
     * key: sessionId, value: Session
     */
    private final Map<String, Session> processingSessions = new ConcurrentHashMap<>();

    /**
     * 存储活跃的 WebSocket 会话
     * key: sessionId, value: WebSocketSession
     */
    private final Map<String, WebSocketSession> activeSessions = new ConcurrentHashMap<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        logger.info("WebSocket 连接建立: sessionId={}", session.getId());

        // 发送连接成功消息
        Map<String, Object> message = Map.of(
            "type", "connected",
            "message", "WebSocket 连接成功"
        );
        String json = objectMapper.writeValueAsString(message);
        logger.info("【发送到前端】sessionId={}, type=connected, 完整内容={}", session.getId(), json);
        sendMessage(session, message);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        String payload = message.getPayload();
        logger.debug("收到 WebSocket 消息: payload={}", payload);

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
    private void handleAnalyze(WebSocketSession wsSession, Map<String, Object> request) {
        String sessionId = null;
        try {
            sessionId = (String) request.get("sessionId");
            String userInput = (String) request.get("input");
            String projectKey = (String) request.get("projectKey");

            // 生成 traceId：sessionId_HHmmss
            String traceId = sessionId + "_" + java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HHmmss"));
            MDC.put("traceId", traceId);
            try {
                logger.info("处理分析请求: sessionId={}, input={}", sessionId, userInput);

                // 检查是否正在处理
                Session session = processingSessions.get(sessionId);

                if (session != null) {
                    // 正在处理中，添加新消息（用于 system-reminder，不发送到前端）
                    Message userMessage = createUserMessage(sessionId, userInput);
                    session.addMessage(userMessage);
                    logger.info("会话正在处理中，新消息已添加: sessionId={}", sessionId);
                    return;
                }

                // 没有在处理，从文件加载或创建
                session = sessionFileService.loadSession(sessionId);
                if (session == null) {
                    session = sessionManager.getOrCreateSession(sessionId, projectKey);
                }

                // 添加用户消息到会话（不发送到前端，前端已经显示了）
                Message userMessage = createUserMessage(sessionId, userInput);
                session.addMessage(userMessage);

                // 标记为处理中
                processingSessions.put(sessionId, session);

                // 注册 WebSocket 会话
                activeSessions.put(sessionId, wsSession);

                // 创建 effectively final 变量供 lambda 使用
                final String finalSessionId = sessionId;
                final String finalTraceId = traceId;  // traceId 用于日志追踪
                final String finalUserInput = userInput;
                final Session finalSession = session;
                final WebSocketSession finalWsSession = wsSession;

                // 创建异步任务
                Runnable processTask = () -> {
                    // 设置 MDC 用于日志追踪（使用固定的 traceId）
                    MDC.put("traceId", finalTraceId);
                    try {
                        smanAgentLoop.process(finalSession, finalUserInput, part -> {
                            pushPart(finalWsSession, finalSessionId, part);
                        });

                        sessionFileService.saveSession(finalSession);
                        Map<String, Object> completeMessage = Map.of("type", "complete", "sessionId", finalSessionId);
                        String completeJson = objectMapper.writeValueAsString(completeMessage);
                        logger.info("【发送到前端】sessionId={}, type=complete, 完整内容={}", finalSessionId, completeJson);
                        sendMessage(finalWsSession, completeMessage);

                        // 处理完成后关闭 WebSocket 连接
                        try {
                            finalWsSession.close(CloseStatus.NORMAL);
                            logger.info("WebSocket 连接已关闭（处理完成）: sessionId={}", finalSessionId);
                        } catch (Exception closeEx) {
                            logger.warn("关闭 WebSocket 连接失败: sessionId={}", finalSessionId, closeEx);
                        }

                    } catch (Exception e) {
                        logger.error("异步处理失败", e);
                    } finally {
                        processingSessions.remove(finalSessionId);

                        // 检查是否有新消息（中途打断）
                        Message lastAssistant = finalSession.getLatestAssistantMessage();
                        if (lastAssistant != null && finalSession.hasNewUserMessageAfter(lastAssistant.getId())) {
                            processingSessions.put(finalSessionId, finalSession);
                            Message newUser = finalSession.getLatestUserMessage();
                            try {
                                smanAgentLoop.process(finalSession, newUser.getContent(), part -> {
                                    pushPart(finalWsSession, finalSessionId, part);
                                });
                                sessionFileService.saveSession(finalSession);
                                sendMessage(finalWsSession, Map.of("type", "complete", "sessionId", finalSessionId));
                            } catch (Exception e) {
                                logger.error("处理新消息失败", e);
                            } finally {
                                processingSessions.remove(finalSessionId);
                            }
                        }

                        // 所有处理完成后关闭 WebSocket 连接
                        try {
                            finalWsSession.close(CloseStatus.NORMAL);
                            logger.info("WebSocket 连接已关闭（会话处理完成）: sessionId={}", finalSessionId);
                        } catch (Exception closeEx) {
                            logger.warn("关闭 WebSocket 连接失败: sessionId={}", finalSessionId, closeEx);
                        }

                        // 清理 MDC
                        MDC.remove("traceId");
                    }
                };

                // 提交到线程池处理
                try {
                    executorService.submit(processTask);
                } catch (RejectedExecutionException e) {
                    // 线程池已满，拒绝请求
                    logger.warn("线程池已满，拒绝请求: sessionId={}", sessionId);
                    processingSessions.remove(sessionId);
                    sendError(wsSession, "服务器繁忙，请稍后重试");
                }
            } finally {
                MDC.remove("traceId");
            }
        } catch (Exception e) {
            logger.error("处理分析请求异常: sessionId={}", sessionId, e);
            try {
                sendError(wsSession, "处理失败: " + e.getMessage());
            } catch (Exception sendError) {
                logger.error("发送错误消息失败", sendError);
            }
        }
    }


    /**
     * 处理聊天请求
     */
    private void handleChat(WebSocketSession wsSession, Map<String, Object> request) {
        String sessionId = null;
        try {
            sessionId = (String) request.get("sessionId");
            String userInput = (String) request.get("input");

            // 生成 traceId：sessionId_HHmmss
            String traceId = sessionId + "_" + java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HHmmss"));
            MDC.put("traceId", traceId);
            try {
                logger.info("处理聊天请求: sessionId={}, input={}", sessionId, userInput);

                // 检查是否正在处理
                Session session = processingSessions.get(sessionId);

                if (session != null) {
                    // 正在处理中，添加新消息（用于 system-reminder，不发送到前端）
                    Message userMessage = createUserMessage(sessionId, userInput);
                    session.addMessage(userMessage);
                    logger.info("会话正在处理中，新消息已添加: sessionId={}", sessionId);
                    return;
                }

                // 没有在处理，从文件加载或从 sessionManager 获取
                session = sessionFileService.loadSession(sessionId);
                if (session == null) {
                    session = sessionManager.getSession(sessionId);
                }
                if (session == null) {
                    sendError(wsSession, "会话不存在");
                    return;
                }

                // 添加用户消息到会话（不发送到前端，前端已经显示了）
                Message userMessage = createUserMessage(sessionId, userInput);
                session.addMessage(userMessage);

                // 标记为处理中
                processingSessions.put(sessionId, session);

                // 注册 WebSocket 会话
                activeSessions.put(sessionId, wsSession);

                // 创建 effectively final 变量供 lambda 使用
                final String finalSessionId = sessionId;
                final String finalTraceId = traceId;  // traceId 用于日志追踪
                final String finalUserInput = userInput;
                final Session finalSession = session;
                final WebSocketSession finalWsSession = wsSession;

                // 创建异步任务
                Runnable processTask = () -> {
                    // 设置 MDC 用于日志追踪（使用固定的 traceId）
                    MDC.put("traceId", finalTraceId);
                    try {
                        smanAgentLoop.process(finalSession, finalUserInput, part -> {
                            pushPart(finalWsSession, finalSessionId, part);
                        });

                        sessionFileService.saveSession(finalSession);
                        Map<String, Object> completeMessage = Map.of("type", "complete", "sessionId", finalSessionId);
                        String completeJson = objectMapper.writeValueAsString(completeMessage);
                        logger.info("【发送到前端】sessionId={}, type=complete, 完整内容={}", finalSessionId, completeJson);
                        sendMessage(finalWsSession, completeMessage);

                    } catch (Exception e) {
                        logger.error("异步处理失败", e);
                    } finally {
                        processingSessions.remove(finalSessionId);

                        // 检查是否有新消息（中途打断）
                        Message lastAssistant = finalSession.getLatestAssistantMessage();
                        if (lastAssistant != null && finalSession.hasNewUserMessageAfter(lastAssistant.getId())) {
                            processingSessions.put(finalSessionId, finalSession);
                            Message newUser = finalSession.getLatestUserMessage();
                            try {
                                smanAgentLoop.process(finalSession, newUser.getContent(), part -> {
                                    pushPart(finalWsSession, finalSessionId, part);
                                });
                                sessionFileService.saveSession(finalSession);
                                sendMessage(finalWsSession, Map.of("type", "complete", "sessionId", finalSessionId));
                            } catch (Exception e) {
                                logger.error("处理新消息失败", e);
                            } finally {
                                processingSessions.remove(finalSessionId);
                            }
                        }

                        // 所有处理完成后关闭 WebSocket 连接
                        try {
                            finalWsSession.close(CloseStatus.NORMAL);
                            logger.info("WebSocket 连接已关闭（会话处理完成）: sessionId={}", finalSessionId);
                        } catch (Exception closeEx) {
                            logger.warn("关闭 WebSocket 连接失败: sessionId={}", finalSessionId, closeEx);
                        }

                        // 清理 MDC
                        MDC.remove("traceId");
                    }
                };

                // 提交到线程池处理
                try {
                    executorService.submit(processTask);
                } catch (RejectedExecutionException e) {
                    // 线程池已满，拒绝请求
                    logger.warn("线程池已满，拒绝请求: sessionId={}", sessionId);
                    processingSessions.remove(sessionId);
                    sendError(wsSession, "服务器繁忙，请稍后重试");
                }
            } finally {
                MDC.remove("traceId");
            }
        } catch (Exception e) {
            logger.error("处理聊天请求异常: sessionId={}", sessionId, e);
            try {
                sendError(wsSession, "处理失败: " + e.getMessage());
            } catch (Exception sendError) {
                logger.error("发送错误消息失败", sendError);
            }
        }
    }

    /**
     * 处理心跳
     */
    private void handlePing(WebSocketSession session) throws Exception {
        Map<String, Object> message = Map.of(
            "type", "pong",
            "timestamp", System.currentTimeMillis()
        );
        String json = objectMapper.writeValueAsString(message);
        logger.debug("【发送到前端】sessionId={}, type=pong, 完整内容={}", session.getId(), json);
        sendMessage(session, message);
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
            Map<String, Object> message = Map.of(
                "type", "error",
                "message", error
            );
            String json = objectMapper.writeValueAsString(message);
            logger.info("【发送到前端】sessionId={}, type=error, 完整内容={}", session.getId(), json);
            sendMessage(session, message);
        } catch (Exception e) {
            logger.error("发送错误消息失败", e);
        }
    }

    /**
     * 创建用户消息
     *
     * @param sessionId 会话 ID
     * @param userInput 用户输入
     * @return 用户消息
     */
    private Message createUserMessage(String sessionId, String userInput) {
        Message message = new Message();
        message.setId(UUID.randomUUID().toString());
        message.setSessionId(sessionId);
        message.setRole(Role.USER);
        message.setContent(userInput);

        String partId = UUID.randomUUID().toString();
        TextPart textPart = new TextPart(partId, message.getId(), sessionId);
        textPart.setText(userInput);
        textPart.touch();

        message.addPart(textPart);
        message.touch();
        return message;
    }

    /**
     * 推送 Part 到前端
     *
     * @param wsSession WebSocket 会话
     * @param sessionId 会话 ID
     * @param part Part
     */
    private void pushPart(WebSocketSession wsSession, String sessionId, Part part) {
        try {
            Map<String, Object> message = Map.of(
                "type", "part",
                "sessionId", sessionId,
                "part", partToMap(part)
            );
            String json = objectMapper.writeValueAsString(message);
            logger.info("【发送到前端】sessionId={}, partType={}, 完整内容={}", sessionId, part.getType(), json);
            sendMessage(wsSession, message);
        } catch (Exception e) {
            logger.error("推送 Part 失败: sessionId={}, partType={}", sessionId, part.getType(), e);
        }
    }

    /**
     * 将 Part 转换为 Map
     *
     * @param part Part
     * @return Map
     */
    private Map<String, Object> partToMap(Part part) {
        Map<String, Object> map = new ConcurrentHashMap<>();
        map.put("id", part.getId() != null ? part.getId() : "");
        map.put("messageId", part.getMessageId() != null ? part.getMessageId() : "");
        map.put("sessionId", part.getSessionId() != null ? part.getSessionId() : "");
        map.put("type", part.getType() != null ? part.getType().name() : "UNKNOWN");
        map.put("createdTime", part.getCreatedTime() != null ? part.getCreatedTime().toString() : Instant.now().toString());
        map.put("updatedTime", part.getUpdatedTime() != null ? part.getUpdatedTime().toString() : Instant.now().toString());

        // 将所有特定字段放在 data 中，与前端模型一致
        Map<String, Object> data = new HashMap<>();

        if (part instanceof com.smancode.smanagent.model.part.TextPart) {
            data.put("text", ((com.smancode.smanagent.model.part.TextPart) part).getText());
        } else if (part instanceof com.smancode.smanagent.model.part.ReasoningPart) {
            data.put("text", ((com.smancode.smanagent.model.part.ReasoningPart) part).getText());
        } else if (part instanceof com.smancode.smanagent.model.part.ToolPart) {
            com.smancode.smanagent.model.part.ToolPart toolPart = (com.smancode.smanagent.model.part.ToolPart) part;
            data.put("toolName", toolPart.getToolName() != null ? toolPart.getToolName() : "");
            data.put("state", toolPart.getState() != null ? toolPart.getState().name() : "PENDING");
            // 从 ToolResult 中提取信息
            com.smancode.smanagent.tools.ToolResult result = toolPart.getResult();
            if (result != null) {
                data.put("title", result.getDisplayTitle());
                data.put("content", result.getDisplayContent());
                if (result.getError() != null) {
                    data.put("error", result.getError());
                }
            }
        } else if (part instanceof com.smancode.smanagent.model.part.GoalPart) {
            com.smancode.smanagent.model.part.GoalPart goalPart = (com.smancode.smanagent.model.part.GoalPart) part;
            data.put("title", goalPart.getTitle() != null ? goalPart.getTitle() : "");
            data.put("description", goalPart.getDescription() != null ? goalPart.getDescription() : "");
            data.put("status", goalPart.getStatus() != null ? goalPart.getStatus().name() : "PENDING");
        } else if (part instanceof com.smancode.smanagent.model.part.ProgressPart) {
            com.smancode.smanagent.model.part.ProgressPart progressPart = (com.smancode.smanagent.model.part.ProgressPart) part;
            data.put("currentStep", progressPart.getCurrentStep());
            data.put("totalSteps", progressPart.getTotalSteps());
            data.put("stepName", progressPart.getCurrent());
        } else if (part instanceof com.smancode.smanagent.model.part.TodoPart) {
            com.smancode.smanagent.model.part.TodoPart todoPart = (com.smancode.smanagent.model.part.TodoPart) part;
            data.put("items", todoPart.getItems() != null ? todoPart.getItems().stream()
                .map(item -> {
                    Map<String, Object> itemMap = new ConcurrentHashMap<>();
                    itemMap.put("id", item.getId() != null ? item.getId() : "");
                    itemMap.put("content", item.getContent() != null ? item.getContent() : "");
                    itemMap.put("status", item.getStatus() != null ? item.getStatus().name() : "PENDING");
                    return itemMap;
                })
                .toList() : List.of());
        }

        map.put("data", data);
        return map;
    }

    /**
     * WebSocket Part 推送器
     * <p>
     * 将 Part 推送到前端
     */
    private static class WebSocketPartPusher implements java.util.function.Consumer<Part> {

        private final WebSocketSession wsSession;
        private final String sessionId;
        private final ObjectMapper objectMapper = new ObjectMapper();

        public WebSocketPartPusher(WebSocketSession wsSession, String sessionId) {
            this.wsSession = wsSession;
            this.sessionId = sessionId;
        }

        @Override
        public void accept(Part part) {
            try {
                sendMessage(wsSession, Map.of(
                    "type", "part",
                    "sessionId", sessionId,
                    "part", partToMap(part)
                ));
            } catch (Exception e) {
                logger.error("推送 Part 失败", e);
            }
        }

        private void sendMessage(WebSocketSession session, Object data) throws Exception {
            String json = objectMapper.writeValueAsString(data);
            session.sendMessage(new TextMessage(json));
        }

        private Map<String, Object> partToMap(Part part) {
            Map<String, Object> map = new ConcurrentHashMap<>();
            map.put("id", part.getId());
            map.put("messageId", part.getMessageId());
            map.put("sessionId", part.getSessionId());
            map.put("type", part.getType().name());
            map.put("createdTime", part.getCreatedTime().toString());
            map.put("updatedTime", part.getUpdatedTime().toString());

            // 将所有特定字段放在 data 中，与前端模型一致
            java.util.Map<String, Object> data = new java.util.HashMap<>();

            if (part instanceof com.smancode.smanagent.model.part.TextPart) {
                data.put("text", ((com.smancode.smanagent.model.part.TextPart) part).getText());
            } else if (part instanceof com.smancode.smanagent.model.part.ReasoningPart) {
                data.put("text", ((com.smancode.smanagent.model.part.ReasoningPart) part).getText());
            } else if (part instanceof com.smancode.smanagent.model.part.ToolPart) {
                com.smancode.smanagent.model.part.ToolPart toolPart = (com.smancode.smanagent.model.part.ToolPart) part;
                data.put("toolName", toolPart.getToolName());
                data.put("state", toolPart.getState().name());
                // 从 ToolResult 中提取信息
                com.smancode.smanagent.tools.ToolResult result = toolPart.getResult();
                if (result != null) {
                    data.put("title", result.getDisplayTitle());
                    data.put("content", result.getDisplayContent());
                    if (result.getError() != null) {
                        data.put("error", result.getError());
                    }
                }
            } else if (part instanceof com.smancode.smanagent.model.part.GoalPart) {
                com.smancode.smanagent.model.part.GoalPart goalPart = (com.smancode.smanagent.model.part.GoalPart) part;
                data.put("title", goalPart.getTitle());
                data.put("description", goalPart.getDescription());
                data.put("status", goalPart.getStatus().name());
            } else if (part instanceof com.smancode.smanagent.model.part.ProgressPart) {
                com.smancode.smanagent.model.part.ProgressPart progressPart = (com.smancode.smanagent.model.part.ProgressPart) part;
                data.put("currentStep", progressPart.getCurrentStep());
                data.put("totalSteps", progressPart.getTotalSteps());
                data.put("stepName", progressPart.getCurrent());
            } else if (part instanceof com.smancode.smanagent.model.part.TodoPart) {
                com.smancode.smanagent.model.part.TodoPart todoPart = (com.smancode.smanagent.model.part.TodoPart) part;
                data.put("items", todoPart.getItems());
            }

            map.put("data", data);
            return map;
        }
    }
}
