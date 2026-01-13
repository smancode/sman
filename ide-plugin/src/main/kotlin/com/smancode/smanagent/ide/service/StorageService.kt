package com.smancode.smanagent.ide.service

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.util.xmlb.XmlSerializerUtil
import com.smancode.smanagent.ide.model.PartData
import com.smancode.smanagent.ide.model.GraphModels.UserPartData
import com.smancode.smanagent.ide.model.GraphModels.PartType
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.*

/**
 * 完整会话（包含消息历史）
 */
data class Session(
    val id: String,
    val projectKey: String,
    var title: String,
    val createdTime: Long = System.currentTimeMillis(),
    var updatedTime: Long = System.currentTimeMillis(),
    var parts: MutableList<PartData> = mutableListOf(),
    var isActive: Boolean = false
)

/**
 * 会话信息（用于历史列表展示，轻量级）
 */
data class SessionInfo(
    val id: String,
    val projectKey: String,
    var title: String,
    val createdTime: Long,
    var updatedTime: Long
)

/**
 * 当前活动会话状态
 */
data class ActiveSessionState(
    var currentSessionId: String? = null
)

/**
 * 存储服务
 * <p>
 * 管理插件的持久化数据，包括会话历史和当前活动会话状态。
 */
@Service(Service.Level.PROJECT)
@State(name = "SmanAgentSettings", storages = [com.intellij.openapi.components.Storage("SmanAgentSettings.xml")])
class StorageService : PersistentStateComponent<StorageService.SettingsState> {

    private val logger: Logger = LoggerFactory.getLogger(StorageService::class.java)

    /**
     * 持久化状态（保存到 XML）
     */
    data class SettingsState(
        var backendUrl: String = "ws://localhost:8080/ws/agent",
        var currentSessionId: String = "",

        // 历史会话列表（仅保存 SessionInfo，parts 另外存储）
        var sessionInfos: MutableList<SessionInfo> = mutableListOf()
    )

    private var state = SettingsState()

    // 内存中的完整会话存储（不持久化到 XML，避免文件过大）
    private val sessions = mutableMapOf<String, Session>()

    override fun getState(): SettingsState = state

    override fun loadState(state: SettingsState) {
        XmlSerializerUtil.copyBean(state, this.state)
    }

    // ==================== 会话管理 ====================

    /**
     * 创建或获取会话
     */
    fun createOrGetSession(sessionId: String, projectKey: String): Session {
        val existingSession = sessions[sessionId]
        if (existingSession != null) {
            return existingSession
        }

        val newSession = Session(
            id = sessionId,
            projectKey = projectKey,
            title = "新会话",
            createdTime = System.currentTimeMillis(),
            updatedTime = System.currentTimeMillis()
        )
        sessions[sessionId] = newSession

        // 同时创建 SessionInfo（用于历史列表）
        val sessionInfo = SessionInfo(
            id = sessionId,
            projectKey = projectKey,
            title = "新会话",
            createdTime = newSession.createdTime,
            updatedTime = newSession.updatedTime
        )
        addSessionInfo(sessionInfo)

        return newSession
    }

    /**
     * 获取会话
     */
    fun getSession(sessionId: String): Session? {
        return sessions[sessionId]
    }

    /**
     * 添加 Part 到会话
     */
    fun addPartToSession(sessionId: String, part: PartData) {
        val session = sessions[sessionId]
        if (session != null) {
            session.parts.add(part)
            session.updatedTime = System.currentTimeMillis()

            // 如果是用户消息，更新会话标题
            if (part.type == PartType.USER) {
                val userText = (part as? UserPartData)?.text ?: ""
                if (session.title == "新会话" && userText.isNotEmpty()) {
                    session.title = userText.take(50).let {
                        if (userText.length > 50) "$it..." else it
                    }
                    // 同步更新 SessionInfo
                    updateSessionInfoTitle(sessionId, session.title)
                }
            }

            // 更新 SessionInfo 的时间戳
            updateSessionInfoTimestamp(sessionId, session.updatedTime)

            logger.debug("添加 Part 到会话: sessionId={}, partType={}, partsCount={}", sessionId, part.type, session.parts.size)
        } else {
            logger.warn("会话不存在，无法添加 Part: sessionId={}", sessionId)
        }
    }

    /**
     * 更新会话时间戳
     */
    fun updateSessionTimestamp(sessionId: String) {
        val session = sessions[sessionId]
        if (session != null) {
            session.updatedTime = System.currentTimeMillis()
            updateSessionInfoTimestamp(sessionId, session.updatedTime)
        }
    }

    // ==================== SessionInfo 管理 ====================

    private fun addSessionInfo(info: SessionInfo) {
        // 移除已存在的（去重）
        state.sessionInfos.removeIf { it.id == info.id }
        state.sessionInfos.add(0, info) // 添加到头部
    }

    private fun updateSessionInfoTitle(sessionId: String, title: String) {
        val info = state.sessionInfos.find { it.id == sessionId }
        if (info != null) {
            info.title = title
        }
    }

    private fun updateSessionInfoTimestamp(sessionId: String, timestamp: Long) {
        val info = state.sessionInfos.find { it.id == sessionId }
        if (info != null) {
            info.updatedTime = timestamp
            // 移动到头部
            state.sessionInfos.remove(info)
            state.sessionInfos.add(0, info)
        }
    }

    /**
     * 获取所有会话信息（用于历史列表）
     */
    fun getHistorySessions(): List<SessionInfo> {
        return state.sessionInfos.toList()
    }

    /**
     * 获取会话信息
     */
    fun getSessionInfo(sessionId: String): SessionInfo? {
        return state.sessionInfos.find { it.id == sessionId }
    }

    /**
     * 删除会话
     */
    fun deleteSession(sessionId: String) {
        sessions.remove(sessionId)
        state.sessionInfos.removeIf { it.id == sessionId }

        // 如果删除的是当前会话，清空当前 ID
        if (state.currentSessionId == sessionId) {
            state.currentSessionId = ""
        }

        logger.info("删除会话: sessionId={}", sessionId)
    }

    // ==================== 当前活动会话 ====================

    /**
     * 获取当前活动会话 ID
     */
    fun getCurrentSessionId(): String? {
        return state.currentSessionId.takeIf { it.isNotEmpty() }
    }

    /**
     * 设置当前活动会话 ID
     */
    fun setCurrentSessionId(sessionId: String?) {
        state.currentSessionId = sessionId ?: ""
    }

    // ==================== 配置管理 ====================

    var backendUrl: String
        get() = state.backendUrl
        set(value) { state.backendUrl = value }

    companion object {
        fun getInstance(project: Project): StorageService {
            return project.service()
        }
    }
}

// 扩展函数
fun Project.storageService(): StorageService {
    return StorageService.getInstance(this)
}
