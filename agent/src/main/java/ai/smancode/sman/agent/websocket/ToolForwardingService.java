package ai.smancode.sman.agent.websocket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
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
 *
 * 工作流程：
 * 1. Agent 收到 Claude Code 的工具调用
 * 2. 判断是否需要转发给 IDE Plugin
 * 3. 通过 WebSocket 发送 TOOL_CALL 消息给 IDE Plugin
 * 4. 等待 IDE Plugin 返回 TOOL_RESULT 消息
 * 5. 将结果返回给 Claude Code
 *
 * @author SiliconMan Team
 * @since 1.0.0
 */
@Service
public class ToolForwardingService {

    private static final Logger log = LoggerFactory.getLogger(ToolForwardingService.class);

    private final ObjectMapper objectMapper = new ObjectMapper();

    // 存储等待中的工具调用 (toolCallId -> CompletableFuture)
    private final Map<String, CompletableFuture<JsonNode>> pendingToolCalls = new ConcurrentHashMap<>();

    // 工具调用超时时间（秒）
    private static final long TOOL_TIMEOUT = 30;

    /**
     * 判断工具是否需要转发给 IDE Plugin
     */
    public boolean shouldForwardToIde(String toolName) {
        // 所有需要访问本地文件或 AST 分析的工具都必须转发给 IDE Plugin
        return switch (toolName) {
            case "call_chain", "read_file", "grep_file", "apply_change" -> true;
            default -> false;
        };
    }

    /**
     * 转发工具调用给 IDE Plugin
     *
     * @param webSocketSessionIdStr WebSocket Session ID（用于找到对应的连接）
     * @param toolName 工具名称
     * @param params 工具参数
     * @param sessionManager 会话管理器（用于获取 WebSocket Session）
     * @return 工具执行结果
     */
    public JsonNode forwardToolCall(String webSocketSessionIdStr, String toolName,
                                    Map<String, Object> params,
                                    WebSocketSessionManager sessionManager) throws Exception {
        log.info("🔧 转发工具调用: tool={}, webSocketSessionId={}", toolName, webSocketSessionIdStr);

        // 生成唯一的 toolCallId
        String toolCallId = generateToolCallId(toolName, webSocketSessionIdStr);

        // 创建 Future 用于等待结果
        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        pendingToolCalls.put(toolCallId, future);

        try {
            // 获取 WebSocket Session
            org.springframework.web.socket.WebSocketSession session =
                    sessionManager.getSession(webSocketSessionIdStr);
            if (session == null) {
                throw new IllegalStateException("WebSocket Session 未找到: " + webSocketSessionIdStr);
            }

            // 构建 TOOL_CALL 消息
            ObjectNode message = objectMapper.createObjectNode();
            message.put("type", "TOOL_CALL");
            message.put("toolCallId", toolCallId);
            message.put("toolName", toolName);
            message.set("params", objectMapper.valueToTree(params));
            message.put("webSocketSessionId", webSocketSessionIdStr);

            // 发送消息给 IDE Plugin
            String payload = objectMapper.writeValueAsString(message);
            session.sendMessage(new TextMessage(payload));
            log.info("✅ 已发送 TOOL_CALL 消息: toolCallId={}, tool={}", toolCallId, toolName);

            // 等待 IDE Plugin 返回结果（带超时）
            JsonNode result = future.get(TOOL_TIMEOUT, TimeUnit.SECONDS);
            log.info("✅ 收到 TOOL_RESULT: toolCallId={}", toolCallId);
            return result;

        } catch (TimeoutException e) {
            log.error("⏰ 工具调用超时: toolCallId={}, tool={}", toolCallId, toolName);
            pendingToolCalls.remove(toolCallId);
            throw new java.util.concurrent.TimeoutException("工具调用超时: " + toolName);

        } catch (Exception e) {
            log.error("❌ 转发工具调用失败: toolCallId={}, tool={}, error={}", toolCallId, toolName, e.getMessage(), e);
            pendingToolCalls.remove(toolCallId);
            throw e;
        }
    }

    /**
     * 处理 IDE Plugin 返回的 TOOL_RESULT 消息
     *
     * @param data TOOL_RESULT 消息数据
     * @return 是否成功处理
     */
    public boolean handleToolResult(JsonNode data) {
        String toolCallId = data.has("toolCallId") ? data.get("toolCallId").asText() : null;
        Boolean success = data.has("success") ? data.get("success").asBoolean() : null;
        String result = data.has("result") ? data.get("result").asText() : null;
        String error = data.has("error") ? data.get("error").asText() : null;

        log.info("📨 收到 TOOL_RESULT: toolCallId={}, success={}", toolCallId, success);

        // 🔥 防御性编程：toolCallId 为 null 时直接返回失败
        if (toolCallId == null || toolCallId.isEmpty()) {
            log.error("❌ TOOL_RESULT 中缺少 toolCallId 字段，无法匹配工具调用");
            log.error("   完整消息: {}", data.toString());
            return false;
        }

        CompletableFuture<JsonNode> future = pendingToolCalls.remove(toolCallId);
        if (future == null) {
            log.warn("⚠️  未找到对应的工具调用: toolCallId={}", toolCallId);
            return false;
        }

        // 完成 Future
        future.complete(data);
        return true;
    }

    /**
     * 生成唯一的 toolCallId
     */
    private String generateToolCallId(String toolName, String webSocketSessionId) {
        return toolName + "-" + webSocketSessionId.substring(0, 8) + "-" + System.currentTimeMillis();
    }

    /**
     * WebSocket Session 管理器接口
     * 由 AgentWebSocketHandler 实现
     */
    public interface WebSocketSessionManager {
        WebSocketSession getSession(String webSocketSessionId);
    }
}
