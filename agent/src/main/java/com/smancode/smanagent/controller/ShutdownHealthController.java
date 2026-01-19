package com.smancode.smanagent.controller;

import com.smancode.smanagent.shutdown.GracefulShutdownManager;
import com.smancode.smanagent.websocket.AgentWebSocketHandler;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * 停机健康检查控制器
 *
 * <p>提供健康检查端点，用于 Kubernetes readinessProbe 和 livenessProbe
 */
@RestController
@RequestMapping("/health")
public class ShutdownHealthController {

    private final GracefulShutdownManager shutdownManager;
    private final AgentWebSocketHandler webSocketHandler;

    public ShutdownHealthController(GracefulShutdownManager shutdownManager,
                                    AgentWebSocketHandler webSocketHandler) {
        this.shutdownManager = shutdownManager;
        this.webSocketHandler = webSocketHandler;
    }

    /**
     * 停机健康检查端点
     *
     * <p>返回当前停机状态，供 Kubernetes 使用：
     * <ul>
     *   <li>status: UP（正常）或 SHUTTING_DOWN（停机中）</li>
     *   <li>pendingRequests: 待处理的请求数量</li>
     *   <li>activeSessions: 活跃的 WebSocket 会话数</li>
     *   <li>shuttingDown: 是否正在停机</li>
     *   <li>phase: 当前停机阶段</li>
     * </ul>
     */
    @GetMapping("/shutdown")
    public ResponseEntity<Map<String, Object>> shutdownHealth() {
        Map<String, Object> health = new HashMap<>();

        health.put("status", shutdownManager.isRunning() ? "UP" : "SHUTTING_DOWN");
        health.put("pendingRequests", shutdownManager.getPendingRequests());
        health.put("activeSessions", webSocketHandler.getActiveSessionCount());
        health.put("shuttingDown", shutdownManager.isShuttingDown());
        health.put("phase", shutdownManager.getShutdownPhase());

        return ResponseEntity.ok(health);
    }
}
