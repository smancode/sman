package com.smancode.smanagent.smancode.core

import com.smancode.smanagent.model.session.Session
import com.smancode.smanagent.model.session.SessionStatus
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * 会话管理器
 *
 * 管理父子会话关系，实现上下文隔离
 * （移除 Spring 和文件持久化依赖）
 */
class SessionManager {

    private val logger = LoggerFactory.getLogger(SessionManager::class.java)

    /**
     * 会话存储
     */
    private val sessions: MutableMap<String, Session> = ConcurrentHashMap()

    /**
     * 父子关系映射: childSessionId -> parentSessionId
     */
    private val childToParent: MutableMap<String, String> = ConcurrentHashMap()

    /**
     * 注册已存在的会话（用于从文件加载的会话）
     *
     * @param session 会话
     */
    fun registerSession(session: Session?) {
        if (session == null || session.id == null) {
            return
        }
        val sessionId = session.id!!
        sessions[sessionId] = session
        logger.debug("注册会话: sessionId={}", sessionId)
    }

    /**
     * 获取或注册会话
     *
     * @param session 会话（如果不存在则注册）
     * @return 会话
     */
    fun getOrRegister(session: Session?): Session? {
        if (session == null || session.id == null) {
            return null
        }
        val sessionId = session.id!!
        val existing = sessions[sessionId]
        if (existing != null) {
            return existing
        }
        sessions[sessionId] = session
        logger.debug("注册会话: sessionId={}", sessionId)
        return session
    }

    /**
     * 创建根会话（用户会话）
     */
    fun createRootSession(projectId: String): Session {
        val session = Session()
        val sessionId = generateSessionId()
        session.id = sessionId
        session.status = SessionStatus.IDLE

        val projectInfo = com.smancode.smanagent.model.session.ProjectInfo()
        projectInfo.projectKey = projectId
        session.projectInfo = projectInfo

        sessions[sessionId] = session
        logger.info("创建根会话: sessionId={}, projectId={}", sessionId, projectId)

        return session
    }

    /**
     * 创建子会话（用于工具调用隔离）
     *
     * @param parentSessionId 父会话 ID
     * @return 子会话
     */
    fun createChildSession(parentSessionId: String): Session {
        val parentSession = sessions[parentSessionId]
            ?: throw IllegalArgumentException("父会话不存在: $parentSessionId")

        val childSession = Session()
        val childSessionId = generateSessionId()
        childSession.id = childSessionId
        childSession.status = SessionStatus.IDLE
        childSession.projectInfo = parentSession.projectInfo
        // 继承父会话的 WebSocket Session ID（用于工具转发）
        childSession.webSocketSessionId = parentSession.webSocketSessionId

        // 继承父会话的项目信息，但不继承消息
        sessions[childSessionId] = childSession
        childToParent[childSessionId] = parentSessionId

        logger.info("创建子会话: sessionId={}, parentSessionId={}, webSocketSessionId={}",
            childSessionId, parentSessionId, parentSession.webSocketSessionId)

        return childSession
    }

    /**
     * 获取会话
     */
    fun getSession(sessionId: String): Session? = sessions[sessionId]

    /**
     * 获取或创建会话
     *
     * @param sessionId  会话 ID
     * @param projectKey 项目 Key
     * @return 会话
     */
    fun getOrCreateSession(sessionId: String?, projectKey: String): Session {
        val actualSessionId = if (sessionId.isNullOrEmpty()) {
            generateSessionId()
        } else {
            sessionId
        }

        val session = sessions[actualSessionId]
        return if (session == null) {
            // 创建新会话
            val projectInfo = com.smancode.smanagent.model.session.ProjectInfo()
            projectInfo.projectKey = projectKey
            projectInfo.projectPath = "/path/to/project" // TODO: 从配置获取

            val newSession = Session(actualSessionId, projectInfo)
            newSession.createdTime = java.time.Instant.now()
            newSession.status = SessionStatus.IDLE

            sessions[actualSessionId] = newSession
            logger.info("创建新会话: sessionId={}, projectKey={}", actualSessionId, projectKey)
            newSession
        } else {
            session
        }
    }

    /**
     * 结束会话
     *
     * @param sessionId 会话 ID
     */
    fun endSession(sessionId: String) {
        val session = sessions[sessionId]
        if (session != null) {
            session.markIdle()
            logger.info("结束会话: sessionId={}", sessionId)
        }
    }

    /**
     * 获取父会话 ID
     */
    fun getParentSessionId(sessionId: String): String? = childToParent[sessionId]

    /**
     * 获取根会话 ID
     */
    fun getRootSessionId(sessionId: String): String {
        var current = sessionId
        while (childToParent.containsKey(current)) {
            current = childToParent[current]!!
        }
        return current
    }

    /**
     * 清理子会话（工具执行完成后）
     */
    fun cleanupChildSession(childSessionId: String) {
        val childSession = sessions.remove(childSessionId)
        childToParent.remove(childSessionId)

        if (childSession != null) {
            logger.info("清理子会话: sessionId={}, messages={}",
                childSessionId, childSession.messages.size)
        }
    }

    /**
     * 清理会话及其所有子会话
     */
    fun cleanupSession(sessionId: String) {
        // 递归清理所有子会话
        childToParent.entries
            .filter { it.value == sessionId }
            .forEach { cleanupSession(it.key) }

        sessions.remove(sessionId)
        childToParent.remove(sessionId)

        logger.info("清理会话: sessionId={}", sessionId)
    }

    /**
     * 生成会话 ID
     */
    private fun generateSessionId(): String {
        return "session-" + System.currentTimeMillis() + "-" +
                (Math.random() * 10000).toInt()
    }

    /**
     * 获取会话统计信息
     */
    fun getStats(): SessionStats {
        var rootCount = 0
        var childCount = 0

        for ((key, value) in childToParent) {
            if (value == null) {
                rootCount++
            } else {
                childCount++
            }
        }

        // 加上没有父节点的会话（根会话）
        rootCount += sessions.size - childToParent.size

        return SessionStats(sessions.size, rootCount, childCount)
    }

    /**
     * 会话统计信息
     */
    data class SessionStats(
        val total: Int,
        val root: Int,
        val child: Int
    )
}
