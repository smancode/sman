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
        var currentSessionId: String = "",

        // LLM 配置（用户在设置中配置）
        var llmApiKey: String = "",                    // 用户配置的 API Key（加密存储）
        var llmBaseUrl: String = "",                   // 用户配置的 Base URL
        var llmModelName: String = "",                 // 用户配置的模型名称

        // BGE-M3 配置
        var bgeEndpoint: String = "",                  // BGE-M3 服务端点
        var bgeApiKey: String = "",                   // BGE-M3 API Key（可选）

        // BGE-Reranker 配置
        var rerankerEndpoint: String = "",            // Reranker 服务端点
        var rerankerApiKey: String = "",               // Reranker API Key（可选）

        // 性能配置（并发控制和重试机制）
        var bgeMaxTokens: String = "8192",             // BGE Token 限制
        var bgeTruncationStrategy: String = "TAIL",    // 截断策略 (HEAD/TAIL/MIDDLE/SMART)
        var bgeTruncationStepSize: String = "1000",    // 截断步长
        var bgeMaxTruncationRetries: String = "10",    // 最大截断重试次数
        var bgeRetryMax: String = "3",                 // 最大重试次数
        var bgeRetryBaseDelay: String = "1000",        // 重试基础延迟（毫秒）
        var bgeConcurrentLimit: String = "3",          // 并发限制
        var bgeCircuitBreakerThreshold: String = "5",  // 熔断器阈值

        // 项目分析配置
        var skipAnalysisConfigDialog: Boolean = false,  // 跳过项目分析配置对话框
        var lastEntryPackages: String = "",             // 上次配置的入口包路径
        var lastCustomAnnotations: String = "",         // 上次配置的自定义注解

        // RULES 配置（用户自定义规则，会追加到 system prompt 后面）
        var rules: String = "",                         // 用户自定义规则

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
        return data.mapValues { (_, value) ->
            when (value) {
                is String, is Number, is Boolean -> value.toString()
                is List<*>, is Map<*, *> -> jsonMapper.writeValueAsString(value)
                else -> jsonMapper.writeValueAsString(value)
            }
        }
    }

    /**
     * 反序列化 SerializablePart 为 PartData
     */
    private fun deserializePartData(serializablePart: SerializablePart): PartData? {
        return try {
            val dataMap = serializablePart.data.mapValues { (_, value) ->
                parseJsonValue(value)
            }

            val partType = PartType.valueOf(serializablePart.type)
            val timestamps = Instant.ofEpochMilli(serializablePart.createdTime) to
                            Instant.ofEpochMilli(serializablePart.updatedTime)

            createPartData(partType, serializablePart, dataMap, timestamps)
        } catch (e: Exception) {
            logger.warn("反序列化 Part 失败: type={}, id={}, error={}",
                serializablePart.type, serializablePart.id, e.message)
            null
        }
    }

    private fun parseJsonValue(value: String): Any {
        return try {
            when {
                value.startsWith("[") || value.startsWith("{") -> jsonMapper.readValue(value)
                else -> value
            }
        } catch (e: Exception) {
            value
        }
    }

    private fun createPartData(
        partType: PartType,
        serializablePart: SerializablePart,
        dataMap: Map<String, Any>,
        timestamps: Pair<Instant, Instant>
    ): PartData {
        val (createdTime, updatedTime) = timestamps

        return when (partType) {
            PartType.TEXT -> GraphModels.TextPartData(serializablePart.id, serializablePart.messageId, serializablePart.sessionId, createdTime, updatedTime, dataMap)
            PartType.TOOL -> GraphModels.ToolPartData(serializablePart.id, serializablePart.messageId, serializablePart.sessionId, createdTime, updatedTime, dataMap)
            PartType.REASONING -> GraphModels.ReasoningPartData(serializablePart.id, serializablePart.messageId, serializablePart.sessionId, createdTime, updatedTime, dataMap)
            PartType.GOAL -> GraphModels.GoalPartData(serializablePart.id, serializablePart.messageId, serializablePart.sessionId, createdTime, updatedTime, dataMap)
            PartType.PROGRESS -> GraphModels.ProgressPartData(serializablePart.id, serializablePart.messageId, serializablePart.sessionId, createdTime, updatedTime, dataMap)
            PartType.USER -> GraphModels.UserPartData(serializablePart.id, serializablePart.messageId, serializablePart.sessionId, createdTime, updatedTime, dataMap)
            PartType.TODO -> GraphModels.TodoPartData(serializablePart.id, serializablePart.messageId, serializablePart.sessionId, createdTime, updatedTime, dataMap)
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

    // LLM 配置
    var llmApiKey: String
        get() = state.llmApiKey
        set(value) { state.llmApiKey = value }

    var llmBaseUrl: String
        get() = state.llmBaseUrl
        set(value) { state.llmBaseUrl = value }

    var llmModelName: String
        get() = state.llmModelName
        set(value) { state.llmModelName = value }

    // BGE-M3 配置
    var bgeEndpoint: String
        get() = state.bgeEndpoint
        set(value) { state.bgeEndpoint = value }

    var bgeApiKey: String
        get() = state.bgeApiKey
        set(value) { state.bgeApiKey = value }

    // BGE-Reranker 配置
    var rerankerEndpoint: String
        get() = state.rerankerEndpoint
        set(value) { state.rerankerEndpoint = value }

    var rerankerApiKey: String
        get() = state.rerankerApiKey
        set(value) { state.rerankerApiKey = value }

    // 项目分析配置
    var skipAnalysisConfigDialog: Boolean
        get() = state.skipAnalysisConfigDialog
        set(value) { state.skipAnalysisConfigDialog = value }

    var lastEntryPackages: String
        get() = state.lastEntryPackages
        set(value) { state.lastEntryPackages = value }

    var lastCustomAnnotations: String
        get() = state.lastCustomAnnotations
        set(value) { state.lastCustomAnnotations = value }

    // 性能配置（并发控制和重试机制）
    var bgeMaxTokens: String
        get() = state.bgeMaxTokens
        set(value) { state.bgeMaxTokens = value }

    var bgeTruncationStrategy: String
        get() = state.bgeTruncationStrategy
        set(value) { state.bgeTruncationStrategy = value }

    var bgeTruncationStepSize: String
        get() = state.bgeTruncationStepSize
        set(value) { state.bgeTruncationStepSize = value }

    var bgeMaxTruncationRetries: String
        get() = state.bgeMaxTruncationRetries
        set(value) { state.bgeMaxTruncationRetries = value }

    var bgeRetryMax: String
        get() = state.bgeRetryMax
        set(value) { state.bgeRetryMax = value }

    var bgeRetryBaseDelay: String
        get() = state.bgeRetryBaseDelay
        set(value) { state.bgeRetryBaseDelay = value }

    var bgeConcurrentLimit: String
        get() = state.bgeConcurrentLimit
        set(value) { state.bgeConcurrentLimit = value }

    var bgeCircuitBreakerThreshold: String
        get() = state.bgeCircuitBreakerThreshold
        set(value) { state.bgeCircuitBreakerThreshold = value }

    // RULES 配置（当值为空时返回默认 RULES）
    var rules: String
        get() = state.rules.takeIf { it.isNotEmpty() } ?: DEFAULT_RULES
        set(value) { state.rules = value }

    // 获取原始的 rules 值（不使用默认值）
    fun getRawRules(): String = state.rules

    companion object {
        // 默认的 RULES（三阶段工作流）
        private const val DEFAULT_RULES = """## 🔄 三阶段工作流 (The Workflow)

### 1️⃣ 阶段一：深度分析 (Analyze)
**回答声明**：`【分析问题】`

**目标**：在动手之前，先确保"做正确的事"。

**必须执行的动作**：
1.  **全景扫描**：搜索并阅读所有相关文件，建立上下文。
2.  **领域对齐 (DDD Lite)**：
    *   确认本次修改涉及的核心业务名词（Ubiquitous Language）定义是否一致。
    *   检查是否破坏了现有的业务不变量（Invariants）。
3.  **根因分析**：从底层逻辑推导问题本质，而非仅修复表面报错。
4.  **方案构思**：提供 1~3 个解决方案。
    *   每个方案需评估：复杂度、副作用、技术债务风险。
    *   如果方案与用户目标冲突，必须直言相告。

**🚫 禁止**：写任何实现代码、急于给出最终方案。
---

### 2️⃣ 阶段二：方案蓝图 (Plan)
**回答声明**：`【制定方案】`

**前置条件**：用户已明确选择或确认了一个方案。

**目标**：将模糊的需求转化为精确的施工图纸 (SDD + TDD)。

**必须执行的动作**：
1.  **契约定义 (Spec-First)**：
    *   如果涉及数据结构变更，**必须**先列出修改后的 Interface/Type 定义。
    *   如果涉及 API 变更，**必须**先列出函数签名。
2.  **验证策略 (Test Plan)**：
    *   列出 3-5 个关键测试场景（包含 Happy Path 和 边缘情况）。
    *   *示例：* "验证当库存不足时，抛出 `InsufficientStockError` 而不是返回 false。"
3.  **文件变更清单**：
    *   列出所有受影响的文件及简要修改逻辑。

**🚫 禁止**：使用硬编码、模糊的描述。

---

### 3️⃣ 阶段三：稳健执行 (Execute)
**回答声明**：`【执行方案】`

**前置条件**：用户已确认方案蓝图。

**目标**：高质量、无坏味道地实现代码。

**必须执行的动作**：
1.  **分步实现**：严格按照既定方案编码，不要夹带私货。
2.  **代码优化**：使用 Task 工具调用 code-simplifier agent 优化代码。
    *   调用格式：`Use the Task tool to launch the code-simplifier agent to refine the implementation`
    *   等待 code-simplifier 完成后再继续
3.  **自我审查 (Self-Review)**：
    *   检查是否引入了新的"坏味道"（见下文）。
    *   检查是否破坏了单一职责原则。
4.  **验证闭环**：
    *   自动运行或编写对应的测试代码，证明代码是工作的。
    *   如果无法运行测试，请提供手动验证的步骤。

**🚫 禁止**：提交未经验证的代码、随意添加非给定内容。

---

## 📏 代码质量公约 (Code Quality Covenant)

### 🧱 物理约束 (必须遵守)
1.  **单一职责**：一个文件只做一件事。如果一个文件既做 UI 又做逻辑，必须拆分。
2.  **行数熔断**：
    *   动态语言 (JS/TS/Py)：单文件上限 **300 行**。
    *   静态语言 (Java/Go)：单文件上限 **500 行**。
    *   *超过限制必须重构拆分，无例外。*
3.  **目录结构**：单文件夹内文件不超过 **8 个**，超过则建立子目录归档。

### ☠️ 必须根除的"坏味道" (Bad Smells)
一旦发现以下迹象，必须在【阶段一】或【阶段二】提出重构建议：

1.  **僵化 (Rigidity)**：改一个地方需要改动很多关联文件。（解法：依赖倒置）
2.  **脆弱 (Fragility)**：改动这里导致无关的地方报错。（解法：解耦、高内聚）
3.  **重复 (DRY Violation)**：同样的逻辑复制粘贴。（解法：提取公共函数/组合模式）
4.  **数据泥团 (Data Clumps)**：总是结伴出现的参数列表。（解法：封装为 Value Object）
5.  **基本类型偏执 (Primitive Obsession)**：用字符串/数字代表复杂的业务概念。（解法：使用 Enum 或专用类型）

---

## ⚠️ 每次回复前的自我检查清单

```text
[ ] 我是否声明了当前所处的阶段？
[ ] (如果是阶段一) 我是否检查了业务名词和领域边界？
[ ] (如果是阶段二) 我是否列出了 Interface 定义和测试用例？
[ ] (如果是阶段三) 我是否遵守了 300/500 行限制？
[ ] 我是否在等待用户的确认指令？
```

---
"""

        fun getInstance(project: Project): StorageService {
            return project.service()
        }
    }
}

// 扩展函数
fun Project.storageService(): StorageService {
    return StorageService.getInstance(this)
}
