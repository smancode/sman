package com.smancode.smanagent.smancode.core;

import com.smancode.smanagent.model.session.Session;
import com.smancode.smanagent.model.session.SessionStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 会话管理器
 * <p>
 * 管理父子会话关系，实现上下文隔离
 */
@Component
public class SessionManager {

    private static final Logger logger = LoggerFactory.getLogger(SessionManager.class);

    /**
     * 会话存储
     */
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    /**
     * 父子关系映射: childSessionId -> parentSessionId
     */
    private final Map<String, String> childToParent = new ConcurrentHashMap<>();

    /**
     * 创建根会话（用户会话）
     */
    public Session createRootSession(String projectId) {
        Session session = new Session();
        session.setId(generateSessionId());
        session.setStatus(SessionStatus.IDLE);

        com.smancode.smanagent.model.session.ProjectInfo projectInfo =
                new com.smancode.smanagent.model.session.ProjectInfo();
        projectInfo.setProjectKey(projectId);
        session.setProjectInfo(projectInfo);

        sessions.put(session.getId(), session);
        logger.info("创建根会话: sessionId={}, projectId={}", session.getId(), projectId);

        return session;
    }

    /**
     * 创建子会话（用于工具调用隔离）
     *
     * @param parentSessionId 父会话 ID
     * @return 子会话
     */
    public Session createChildSession(String parentSessionId) {
        Session parentSession = sessions.get(parentSessionId);
        if (parentSession == null) {
            throw new IllegalArgumentException("父会话不存在: " + parentSessionId);
        }

        Session childSession = new Session();
        childSession.setId(generateSessionId());
        childSession.setStatus(SessionStatus.IDLE);
        childSession.setProjectInfo(parentSession.getProjectInfo());

        // 继承父会话的项目信息，但不继承消息
        sessions.put(childSession.getId(), childSession);
        childToParent.put(childSession.getId(), parentSessionId);

        logger.info("创建子会话: sessionId={}, parentSessionId={}",
                childSession.getId(), parentSessionId);

        return childSession;
    }

    /**
     * 获取会话
     */
    public Session getSession(String sessionId) {
        return sessions.get(sessionId);
    }

    /**
     * 获取父会话 ID
     */
    public String getParentSessionId(String sessionId) {
        return childToParent.get(sessionId);
    }

    /**
     * 获取根会话 ID
     */
    public String getRootSessionId(String sessionId) {
        String current = sessionId;
        while (childToParent.containsKey(current)) {
            current = childToParent.get(current);
        }
        return current;
    }

    /**
     * 清理子会话（工具执行完成后）
     */
    public void cleanupChildSession(String childSessionId) {
        Session childSession = sessions.remove(childSessionId);
        childToParent.remove(childSessionId);

        if (childSession != null) {
            logger.info("清理子会话: sessionId={}, messages={}",
                    childSessionId, childSession.getMessages().size());
        }
    }

    /**
     * 清理会话及其所有子会话
     */
    public void cleanupSession(String sessionId) {
        // 递归清理所有子会话
        childToParent.entrySet().stream()
                .filter(e -> e.getValue().equals(sessionId))
                .forEach(e -> cleanupSession(e.getKey()));

        sessions.remove(sessionId);
        childToParent.remove(sessionId);

        logger.info("清理会话: sessionId={}", sessionId);
    }

    /**
     * 生成会话 ID
     */
    private String generateSessionId() {
        return "session-" + System.currentTimeMillis() + "-" +
                (int) (Math.random() * 10000);
    }

    /**
     * 获取会话统计信息
     */
    public SessionStats getStats() {
        int rootCount = 0;
        int childCount = 0;

        for (Map.Entry<String, String> entry : childToParent.entrySet()) {
            if (entry.getValue() == null) {
                rootCount++;
            } else {
                childCount++;
            }
        }

        // 加上没有父节点的会话（根会话）
        rootCount += sessions.size() - childToParent.size();

        return new SessionStats(sessions.size(), rootCount, childCount);
    }

    /**
     * 会话统计信息
     */
    public record SessionStats(int total, int root, int child) {
    }
}
