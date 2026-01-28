package com.smancode.smanagent.ide.service

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.util.xmlb.XmlSerializerUtil
import com.smancode.smanagent.ide.model.PartData
import com.smancode.smanagent.ide.model.GraphModels
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
 * 可序列化的 Part 数据（用于持久化）
 */
data class SerializablePart(
    var id: String = "",
    var type: String = "",  // PartType.name()
    var messageId: String = "",
    var sessionId: String = "",
    var createdTime: Long = 0,
    var updatedTime: Long = 0,
    var data: Map<String, String> = mapOf()  // 简化：所有值都转为字符串
)

/**
 * 会话信息（用于历史列表展示，可持久化）
 */
data class SessionInfo(
    var id: String = "",
    var projectKey: String = "",
    var title: String = "",
    var createdTime: Long = 0,
    var updatedTime: Long = 0,
    var parts: List<SerializablePart> = emptyList()  // 可序列化的 parts
)

/**
 * 存储服务
 * <p>
 * 管理插件的持久化数据，包括会话历史和当前活动会话状态。
 */
@Service(Service.Level.APP)
@State(name = "SmanAgentSettings", storages = [com.intellij.openapi.components.Storage("SmanAgentSettings.xml")], reportStatistic = false)
class StorageService : PersistentStateComponent<StorageService.SettingsState> {

    private val logger: Logger = LoggerFactory.getLogger(StorageService::class.java)

    // JSON ObjectMapper 用于序列化复杂数据结构
    private val jsonMapper = jacksonObjectMapper()

    /**
     * 持久化状态（保存到 XML）
     */
    data class SettingsState(
        var backendUrl: String = "ws://localhost:8080/ws/agent",
        var currentSessionId: String = "",

        // 历史会话列表（仅 SessionInfo，不含 parts）
        var sessionInfos: MutableList<SessionInfo> = mutableListOf()
    )

    private var state = SettingsState()

    // 用于快速访问的会话缓存（包含完整 parts）
    private val sessionsCache = mutableMapOf<String, Session>()

    override fun getState(): SettingsState = state

    override fun loadState(state: SettingsState) {
        XmlSerializerUtil.copyBean(state, this.state)

        // 根据 SessionInfo 重建 Session 对象（包含从 SerializablePart 反序列化的 parts）
        sessionsCache.clear()
        this.state.sessionInfos.forEach { sessionInfo ->
            val parts = sessionInfo.parts.mapNotNull { serializablePart ->
                deserializePartData(serializablePart)
            }.toMutableList()

            val session = Session(
                id = sessionInfo.id,
                projectKey = sessionInfo.projectKey,
                title = sessionInfo.title,
                createdTime = sessionInfo.createdTime,
                updatedTime = sessionInfo.updatedTime,
                parts = parts // 从持久化的数据恢复
            )
            sessionsCache[sessionInfo.id] = session
        }

        logger.info("加载持久化状态: sessionInfos数量={}, 重建Session数量={}, 总parts数={}",
            this.state.sessionInfos.size, sessionsCache.size,
            sessionsCache.values.sumOf { it.parts.size })
    }

    /**
     * 手动触发保存（用于调试）
     */
    fun saveSettings() {
        // IntelliJ 会在适当的时机自动调用 getState() 保存
        // 这个方法只是为了验证数据是否正确
        logger.info("当前状态: sessionInfos数量={}", state.sessionInfos.size)
        state.sessionInfos.forEach {
            logger.info("  - id={}, title={}", it.id, it.title)
        }
    }

    // ==================== 会话管理 ====================

    /**
     * 创建或获取会话
     */
    fun createOrGetSession(sessionId: String, projectKey: String): Session {
        val existingSession = sessionsCache[sessionId]
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
        sessionsCache[sessionId] = newSession

        // 同时创建 SessionInfo 用于持久化
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
     * 添加或更新 SessionInfo
     */
    private fun addSessionInfo(info: SessionInfo) {
        // 移除已存在的（去重）
        state.sessionInfos.removeIf { it.id == info.id }
        state.sessionInfos.add(0, info) // 添加到头部
        logger.info("添加 SessionInfo: id={}, title, 总数={}", info.id, info.title, state.sessionInfos.size)
    }

    /**
     * 获取会话
     */
    fun getSession(sessionId: String): Session? {
        return sessionsCache[sessionId]
    }

    /**
     * 添加 Part 到会话
     */
    fun addPartToSession(sessionId: String, part: PartData) {
        val session = sessionsCache[sessionId]
        if (session != null) {
            session.parts.add(part)
            session.updatedTime = System.currentTimeMillis()

            // 如果是第一个 USER 类型的 Part，自动生成标题
            if (part.type == PartType.USER && session.title == "新会话") {
                val text = (part.data["text"] as? String) ?: ""
                session.title = generateTitleFromUserMessage(text)
                logger.info("自动生成会话标题: sessionId={}, title={}", sessionId, session.title)
            }

            // 同步更新 SessionInfo（包括可序列化的 parts）
            updateSessionInfoWithParts(sessionId, session)
        } else {
            logger.warn("会话不存在，无法添加 Part: sessionId={}", sessionId)
        }
    }

    /**
     * 从用户消息生成标题（前30个字符）
     */
    private fun generateTitleFromUserMessage(text: String): String {
        val cleaned = text.trim().lines().firstOrNull()?.trim() ?: text.trim()
        return if (cleaned.length <= 30) {
            cleaned
        } else {
            cleaned.substring(0, 30)
        }
    }

    /**
     * 更新 SessionInfo（包括可序列化的 parts）
     */
    private fun updateSessionInfoWithParts(sessionId: String, session: Session) {
        val info = state.sessionInfos.find { it.id == sessionId }
        if (info != null) {
            info.title = session.title
            info.updatedTime = session.updatedTime
            // 转换 parts 为可序列化格式
            info.parts = session.parts.map { part ->
                SerializablePart(
                    id = part.id,
                    type = part.type.name,
                    messageId = part.messageId,
                    sessionId = part.sessionId,
                    createdTime = part.createdTime.toEpochMilli(),
                    updatedTime = part.updatedTime.toEpochMilli(),
                    data = serializePartData(part.data)
                )
            }
            // 移动到头部
            state.sessionInfos.remove(info)
            state.sessionInfos.add(0, info)
        }
    }

    /**
     * 序列化 Part.data 为 Map<String, String>
     * 对于复杂对象（如 List、Map），使用 JSON 序列化
     */
    private fun serializePartData(data: Map<String, Any>): Map<String, String> {
        return data.mapValues { entry ->
            val value = entry.value
            when (value) {
                is String -> value
                is Number -> value.toString()
                is Boolean -> value.toString()
                is List<*> -> jsonMapper.writeValueAsString(value)
                is Map<*, *> -> jsonMapper.writeValueAsString(value)
                else -> jsonMapper.writeValueAsString(value)
            }
        }
    }

    /**
     * 反序列化 SerializablePart 为 PartData
     */
    private fun deserializePartData(serializablePart: SerializablePart): PartData? {
        return try {
            // 将 Map<String, String> 转回 Map<String, Any>
            // 对于 JSON 字符串，反序列化为原始对象
            val dataMap = serializablePart.data.mapValues { (_, value) ->
                try {
                    // 尝试解析 JSON，如果失败则作为字符串处理
                    when {
                        value.startsWith("[") || value.startsWith("{") -> {
                            jsonMapper.readValue(value)
                        }
                        else -> value
                    }
                } catch (e: Exception) {
                    // JSON 解析失败，作为字符串处理
                    value
                }
            }

            // 根据 PartType 创建对应的 PartData
            val partType = PartType.valueOf(serializablePart.type)
            val createdTime = Instant.ofEpochMilli(serializablePart.createdTime)
            val updatedTime = Instant.ofEpochMilli(serializablePart.updatedTime)

            when (partType) {
                PartType.TEXT -> GraphModels.TextPartData(
                    id = serializablePart.id,
                    messageId = serializablePart.messageId,
                    sessionId = serializablePart.sessionId,
                    createdTime = createdTime,
                    updatedTime = updatedTime,
                    data = dataMap
                )
                PartType.TOOL -> GraphModels.ToolPartData(
                    id = serializablePart.id,
                    messageId = serializablePart.messageId,
                    sessionId = serializablePart.sessionId,
                    createdTime = createdTime,
                    updatedTime = updatedTime,
                    data = dataMap
                )
                PartType.REASONING -> GraphModels.ReasoningPartData(
                    id = serializablePart.id,
                    messageId = serializablePart.messageId,
                    sessionId = serializablePart.sessionId,
                    createdTime = createdTime,
                    updatedTime = updatedTime,
                    data = dataMap
                )
                PartType.GOAL -> GraphModels.GoalPartData(
                    id = serializablePart.id,
                    messageId = serializablePart.messageId,
                    sessionId = serializablePart.sessionId,
                    createdTime = createdTime,
                    updatedTime = updatedTime,
                    data = dataMap
                )
                PartType.PROGRESS -> GraphModels.ProgressPartData(
                    id = serializablePart.id,
                    messageId = serializablePart.messageId,
                    sessionId = serializablePart.sessionId,
                    createdTime = createdTime,
                    updatedTime = updatedTime,
                    data = dataMap
                )
                PartType.USER -> GraphModels.UserPartData(
                    id = serializablePart.id,
                    messageId = serializablePart.messageId,
                    sessionId = serializablePart.sessionId,
                    createdTime = createdTime,
                    updatedTime = updatedTime,
                    data = dataMap
                )
                PartType.TODO -> GraphModels.TodoPartData(
                    id = serializablePart.id,
                    messageId = serializablePart.messageId,
                    sessionId = serializablePart.sessionId,
                    createdTime = createdTime,
                    updatedTime = updatedTime,
                    data = dataMap
                )
            }
        } catch (e: Exception) {
            logger.warn("反序列化 Part 失败: type={}, id={}, error={}",
                serializablePart.type, serializablePart.id, e.message)
            null
        }
    }

    /**
     * 更新会话时间戳
     */
    fun updateSessionTimestamp(sessionId: String) {
        val session = sessionsCache[sessionId]
        if (session != null) {
            session.updatedTime = System.currentTimeMillis()
            // 同步更新 SessionInfo
            updateSessionInfo(sessionId, session.title, session.updatedTime)
        }
    }

    /**
     * 更新 SessionInfo
     */
    private fun updateSessionInfo(sessionId: String, title: String, updatedTime: Long) {
        val info = state.sessionInfos.find { it.id == sessionId }
        if (info != null) {
            info.title = title
            info.updatedTime = updatedTime
            // 移动到头部
            state.sessionInfos.remove(info)
            state.sessionInfos.add(0, info)
        }
    }

    /**
     * 获取指定项目的会话信息（用于历史列表）
     */
    fun getHistorySessions(projectKey: String): List<SessionInfo> {
        val sessions = state.sessionInfos.filter { it.projectKey == projectKey }
        logger.info("获取历史会话: projectKey={}, 数量={}", projectKey, sessions.size)
        sessions.forEach { logger.info("  - id={}, title={}", it.id, it.title) }
        return sessions
    }

    /**
     * 删除会话
     */
    fun deleteSession(sessionId: String) {
        sessionsCache.remove(sessionId)
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
