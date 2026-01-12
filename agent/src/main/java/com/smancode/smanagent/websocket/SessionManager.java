package com.smancode.smanagent.websocket;

import com.smancode.smanagent.model.message.Message;
import com.smancode.smanagent.model.session.ProjectInfo;
import com.smancode.smanagent.model.session.Session;
import com.smancode.smanagent.model.session.SessionStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 会话管理器
 * <p>
 * 管理所有活跃的会话。
 */
@Service
public class SessionManager {

    private static final Logger logger = LoggerFactory.getLogger(SessionManager.class);

    /**
     * 存储所有会话
     * key: sessionId, value: Session
     */
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    /**
     * 获取或创建会话
     *
     * @param sessionId  会话 ID
     * @param projectKey 项目 Key
     * @return 会话
     */
    public Session getOrCreateSession(String sessionId, String projectKey) {
        if (sessionId == null || sessionId.isEmpty()) {
            sessionId = UUID.randomUUID().toString();
        }

        Session session = sessions.get(sessionId);
        if (session == null) {
            session = createSession(sessionId, projectKey);
            sessions.put(sessionId, session);
            logger.info("创建新会话: sessionId={}, projectKey={}", sessionId, projectKey);
        }

        return session;
    }

    /**
     * 获取会话
     *
     * @param sessionId 会话 ID
     * @return 会话，如果不存在返回 null
     */
    public Session getSession(String sessionId) {
        return sessions.get(sessionId);
    }

    /**
     * 结束会话
     *
     * @param sessionId 会话 ID
     */
    public void endSession(String sessionId) {
        Session session = sessions.get(sessionId);
        if (session != null) {
            session.complete();
            logger.info("结束会话: sessionId={}", sessionId);
        }
    }

    /**
     * 删除会话
     *
     * @param sessionId 会话 ID
     */
    public void removeSession(String sessionId) {
        sessions.remove(sessionId);
        logger.info("删除会话: sessionId={}", sessionId);
    }

    /**
     * 创建新会话
     */
    private Session createSession(String sessionId, String projectKey) {
        ProjectInfo projectInfo = new ProjectInfo();
        projectInfo.setProjectKey(projectKey);
        projectInfo.setProjectPath("/path/to/project"); // TODO: 从配置获取

        Session session = new Session(sessionId, projectInfo);
        session.setCreatedTime(Instant.now());
        session.setStatus(SessionStatus.IDLE);

        return session;
    }

    /**
     * 获取所有会话数量
     */
    public int getSessionCount() {
        return sessions.size();
    }
}
