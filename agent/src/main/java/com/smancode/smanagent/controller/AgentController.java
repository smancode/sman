package com.smancode.smanagent.controller;

import com.smancode.smanagent.model.session.Session;
import com.smancode.smanagent.smancode.core.SmanAgentLoop;
import com.smancode.smanagent.smancode.core.SessionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Agent REST API 控制器
 */
@RestController
@RequestMapping("/api/agent")
@CrossOrigin(origins = "*")
public class AgentController {

    private static final Logger logger = LoggerFactory.getLogger(AgentController.class);

    @Autowired
    private SmanAgentLoop smanAgentLoop;

    @Autowired
    private SessionManager sessionManager;

    /**
     * 创建新会话
     */
    @PostMapping("/sessions")
    public ResponseEntity<Map<String, String>> createSession(@RequestBody Map<String, String> request) {
        String projectKey = request.get("projectKey");
        String sessionId = UUID.randomUUID().toString();

        Session session = sessionManager.getOrCreateSession(sessionId, projectKey);

        return ResponseEntity.ok(Map.of(
            "sessionId", session.getId(),
            "status", session.getStatus().name()
        ));
    }

    /**
     * 获取会话信息
     */
    @GetMapping("/sessions/{sessionId}")
    public ResponseEntity<Map<String, Object>> getSession(@PathVariable String sessionId) {
        Session session = sessionManager.getSession(sessionId);
        if (session == null) {
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok(Map.of(
            "sessionId", session.getId(),
            "status", session.getStatus().name(),
            "messageCount", session.getMessages().size()
        ));
    }

    /**
     * 删除会话
     */
    @DeleteMapping("/sessions/{sessionId}")
    public ResponseEntity<Void> deleteSession(@PathVariable String sessionId) {
        sessionManager.cleanupSession(sessionId);
        return ResponseEntity.ok().build();
    }

    /**
     * 同步调用分析（非 WebSocket）
     */
    @PostMapping("/analyze")
    public ResponseEntity<Map<String, Object>> analyze(@RequestBody Map<String, String> request) {
        String sessionId = request.get("sessionId");
        String projectKey = request.get("projectKey");
        String userInput = request.get("input");

        logger.info("同步分析请求: sessionId={}, input={}", sessionId, userInput);

        try {
            // 获取或创建会话
            Session session = sessionManager.getOrCreateSession(sessionId, projectKey);

            // 处理请求
            var responseMessage = smanAgentLoop.process(session, userInput, part -> {
                // 同步模式下忽略 Part 推送
            });

            // 构建响应
            StringBuilder content = new StringBuilder();
            for (var part : responseMessage.getParts()) {
                if (part instanceof com.smancode.smanagent.model.part.TextPart) {
                    content.append(((com.smancode.smanagent.model.part.TextPart) part).getText());
                }
            }

            return ResponseEntity.ok(Map.of(
                "sessionId", session.getId(),
                "status", session.getStatus().name(),
                "response", content.toString()
            ));

        } catch (Exception e) {
            logger.error("分析失败", e);
            return ResponseEntity.status(500).body(Map.of(
                "error", e.getMessage()
            ));
        }
    }

    /**
     * 健康检查
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of(
            "status", "UP",
            "service", "smanagent"
        ));
    }

    /**
     * 获取统计信息
     */
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> stats() {
        return ResponseEntity.ok(Map.of(
            "activeSessions", sessionManager.getStats().total()
        ));
    }
}
