package ai.smancode.sman.agent.claude;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Claude Code 会话管理器
 *
 * 功能：
 * - 管理会话状态
 * - 记录工具调用活动
 * - 跟踪会话统计信息
 *
 * 实现原则：
 * - 无状态设计：会话数据保存在内存中
 * - 线程安全：使用 ConcurrentHashMap
 * - 自动清理：定期清理过期会话
 *
 * @author SiliconMan Team
 * @since 1.0.0
 */
@Component
public class SessionManager {

    private static final Logger log = LoggerFactory.getLogger(SessionManager.class);

    /**
     * 会话数据
     */
    private static class SessionData {
        private final String sessionId;
        private final String workerId;
        private final long createTime;
        private long lastActivityTime;
        private int toolCallCount;
        private final Map<String, Integer> toolUsageStats = new ConcurrentHashMap<>();

        public SessionData(String sessionId, String workerId) {
            this.sessionId = sessionId;
            this.workerId = workerId;
            this.createTime = System.currentTimeMillis();
            this.lastActivityTime = createTime;
            this.toolCallCount = 0;
        }

        public String getSessionId() { return sessionId; }
        public String getWorkerId() { return workerId; }
        public long getCreateTime() { return createTime; }
        public long getLastActivityTime() { return lastActivityTime; }
        public void setLastActivityTime(long lastActivityTime) { this.lastActivityTime = lastActivityTime; }
        public int getToolCallCount() { return toolCallCount; }
        public void setToolCallCount(int toolCallCount) { this.toolCallCount = toolCallCount; }
        public Map<String, Integer> getToolUsageStats() { return toolUsageStats; }
    }

    /** 活跃会话（sessionId -> SessionData） */
    private final Map<String, SessionData> sessions = new ConcurrentHashMap<>();

    /** Worker 到会话的映射（workerId -> sessionId） */
    private final Map<String, String> workerToSession = new ConcurrentHashMap<>();

    /** 会话超时时间（毫秒） */
    private static final long SESSION_TIMEOUT = 30 * 60 * 1000;  // 30 分钟

    /**
     * 创建会话
     *
     * @param workerId Worker ID
     * @return 会话 ID
     */
    public String createSession(String workerId) {
        String sessionId = "session-" + UUID.randomUUID().toString().substring(0, 8);

        SessionData sessionData = new SessionData(sessionId, workerId);
        sessions.put(sessionId, sessionData);
        workerToSession.put(workerId, sessionId);

        log.info("📝 创建会话: sessionId={}, workerId={}", sessionId, workerId);

        return sessionId;
    }

    /**
     * 记录活动
     *
     * @param workerId Worker ID
     * @param tool     工具名称
     */
    public void recordActivity(String workerId, String tool) {
        String sessionId = workerToSession.get(workerId);
        if (sessionId == null) {
            log.debug("未找到会话: workerId={}", workerId);
            return;
        }

        SessionData sessionData = sessions.get(sessionId);
        if (sessionData == null) {
            log.warn("会话数据不存在: sessionId={}", sessionId);
            return;
        }

        // 更新活动时间
        sessionData.setLastActivityTime(System.currentTimeMillis());

        // 更新工具调用次数
        sessionData.setToolCallCount(sessionData.getToolCallCount() + 1);

        // 更新工具使用统计
        sessionData.getToolUsageStats().merge(tool, 1, Integer::sum);

        log.debug("记录活动: sessionId={}, tool={}", sessionId, tool);
    }

    /**
     * 获取会话信息
     *
     * @param sessionId 会话 ID
     * @return 会话信息
     */
    public SessionInfo getSessionInfo(String sessionId) {
        SessionData sessionData = sessions.get(sessionId);
        if (sessionData == null) {
            return null;
        }

        SessionInfo info = new SessionInfo();
        info.sessionId = sessionData.getSessionId();
        info.workerId = sessionData.getWorkerId();
        info.createTime = sessionData.getCreateTime();
        info.lastActivityTime = sessionData.getLastActivityTime();
        info.toolCallCount = sessionData.getToolCallCount();
        info.toolUsageStats = sessionData.getToolUsageStats();
        info.idleTime = System.currentTimeMillis() - sessionData.getLastActivityTime();

        return info;
    }

    /**
     * 移除会话
     *
     * @param sessionId 会话 ID
     */
    public void removeSession(String sessionId) {
        SessionData sessionData = sessions.remove(sessionId);
        if (sessionData != null) {
            workerToSession.remove(sessionData.getWorkerId());
            log.info("🗑️ 移除会话: sessionId={}", sessionId);
        }
    }

    /**
     * 清理过期会话
     */
    public void cleanupExpiredSessions() {
        long now = System.currentTimeMillis();
        int cleanedCount = 0;

        for (Map.Entry<String, SessionData> entry : sessions.entrySet()) {
            String sessionId = entry.getKey();
            SessionData sessionData = entry.getValue();

            long idleTime = now - sessionData.getLastActivityTime();
            if (idleTime > SESSION_TIMEOUT) {
                removeSession(sessionId);
                cleanedCount++;
            }
        }

        if (cleanedCount > 0) {
            log.info("🧹 清理过期会话: count={}", cleanedCount);
        }
    }

    /**
     * 获取会话统计
     */
    public SessionStats getStats() {
        SessionStats stats = new SessionStats();
        stats.totalSessions = sessions.size();
        stats.activeSessions = (int) sessions.values().stream()
                .filter(s -> (System.currentTimeMillis() - s.getLastActivityTime()) < 5 * 60 * 1000)
                .count();
        stats.totalToolCalls = sessions.values().stream()
                .mapToInt(SessionData::getToolCallCount)
                .sum();

        return stats;
    }

    /**
     * 会话信息
     */
    public static class SessionInfo {
        public String sessionId;
        public String workerId;
        public long createTime;
        public long lastActivityTime;
        public int toolCallCount;
        public Map<String, Integer> toolUsageStats;
        public long idleTime;

        public String getSessionId() { return sessionId; }
        public String getWorkerId() { return workerId; }
        public long getCreateTime() { return createTime; }
        public long getLastActivityTime() { return lastActivityTime; }
        public int getToolCallCount() { return toolCallCount; }
        public Map<String, Integer> getToolUsageStats() { return toolUsageStats; }
        public long getIdleTime() { return idleTime; }
    }

    /**
     * 会话统计
     */
    public static class SessionStats {
        public int totalSessions;
        public int activeSessions;
        public int totalToolCalls;

        public int getTotalSessions() { return totalSessions; }
        public int getActiveSessions() { return activeSessions; }
        public int getTotalToolCalls() { return totalToolCalls; }
    }
}
