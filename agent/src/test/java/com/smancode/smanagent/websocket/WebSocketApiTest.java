package com.smancode.smanagent.websocket;

import com.smancode.smanagent.controller.AgentController;
import com.smancode.smanagent.model.session.Session;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * WebSocket 和 REST API 测试
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WebSocketApiTest {

    @Autowired
    private AgentController agentController;

    @Autowired
    private SessionManager sessionManager;

    @Test
    void testAgentControllerWiring() {
        assertNotNull(agentController, "AgentController should be autowired");
        assertNotNull(sessionManager, "SessionManager should be autowired");
    }

    @Test
    void testHealthEndpoint() {
        // 健康检查
        var response = agentController.health();
        assertNotNull(response);
        assertNotNull(response.getBody());
        assertEquals("UP", response.getBody().get("status"));
    }

    @Test
    void testStatsEndpoint() {
        // 统计信息
        var response = agentController.stats();
        assertNotNull(response);
        assertNotNull(response.getBody());
        assertTrue(response.getBody().containsKey("activeSessions"));
    }

    @Test
    void testCreateSession() {
        // 创建会话
        var request = Map.of(
            "projectKey", "test-project"
        );

        var response = agentController.createSession(request);
        assertNotNull(response);
        assertNotNull(response.getBody());
        assertTrue(response.getBody().containsKey("sessionId"));
        assertTrue(response.getBody().containsKey("status"));
    }

    @Test
    void testSessionManager() {
        // 测试会话管理
        assertEquals(0, sessionManager.getSessionCount());

        String sessionId = "test-session-1";
        Session session = sessionManager.getOrCreateSession(sessionId, "test-project");

        assertNotNull(session);
        assertEquals(sessionId, session.getId());
        assertEquals(1, sessionManager.getSessionCount());

        // 再次获取应该返回同一个会话
        Session session2 = sessionManager.getOrCreateSession(sessionId, "test-project");
        assertSame(session, session2);

        // 删除会话
        sessionManager.removeSession(sessionId);
        assertEquals(0, sessionManager.getSessionCount());
    }

    @Test
    void testAnalyzeEndpoint_Sync() {
        // 同步分析接口
        var request = Map.of(
            "sessionId", "test-sync-session",
            "projectKey", "test-project",
            "input", "你好"
        );

        var response = agentController.analyze(request);
        assertNotNull(response);
        assertNotNull(response.getBody());
        assertTrue(response.getBody().containsKey("sessionId"));
        assertTrue(response.getBody().containsKey("response"));
    }
}
