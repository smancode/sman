package com.smancode.sman.evolution.learning

/**
 * 用户互动记录
 *
 * 记录用户与 SmanLoop 的每次交互，用于学习用户习惯和偏好。
 *
 * 场景示例：
 * - 用户说"增加先本后息还款方式"
 * - SmanLoop 处理完成
 * - 自动提取学习：
 *   - 用户关注：还款模块
 *   - 操作类型：增加功能
 *   - 涉及文件：RepaymentType.java, RepaymentCalculator.java
 *   - 用户习惯：喜欢修改枚举+策略模式
 *
 * @property id 唯一标识
 * @property projectKey 所属项目
 * @property sessionId 会话 ID
 * @property createdAt 创建时间戳
 * @property userRequest 用户原始输入
 * @property requestType 操作类型
 * @property toolCalls 工具调用快照列表
 * @property modifiedFiles 修改的文件列表
 * @property mentionedFiles 提及的文件（未修改但相关）
 * @property focusModules 关注的模块
 * @property codeStylePreference 代码风格偏好（LLM 提取）
 * @property outcome 交互结果
 * @property metadata 元数据
 */
data class UserInteractionRecord(
    val id: String,
    val projectKey: String,
    val sessionId: String,
    val createdAt: Long,
    val userRequest: String,
    val requestType: OperationType,
    val toolCalls: List<ToolCallSnapshot> = emptyList(),
    val modifiedFiles: List<String> = emptyList(),
    val mentionedFiles: List<String> = emptyList(),
    val focusModules: List<String> = emptyList(),
    val codeStylePreference: String? = null,
    val outcome: InteractionOutcome,
    val metadata: Map<String, String> = emptyMap()
) {
    companion object {
        /**
         * 创建新记录的工厂方法
         */
        fun create(
            projectKey: String,
            sessionId: String,
            userRequest: String,
            requestType: OperationType = OperationType.UNKNOWN
        ): UserInteractionRecord {
            require(projectKey.isNotBlank()) { "projectKey 不能为空" }
            require(sessionId.isNotBlank()) { "sessionId 不能为空" }
            require(userRequest.isNotBlank()) { "userRequest 不能为空" }

            return UserInteractionRecord(
                id = java.util.UUID.randomUUID().toString(),
                projectKey = projectKey,
                sessionId = sessionId,
                createdAt = System.currentTimeMillis(),
                userRequest = userRequest,
                requestType = requestType,
                outcome = InteractionOutcome.PENDING
            )
        }
    }

    /**
     * 添加工具调用快照
     */
    fun withToolCall(toolCall: ToolCallSnapshot): UserInteractionRecord {
        return copy(toolCalls = toolCalls + toolCall)
    }

    /**
     * 添加修改的文件
     */
    fun withModifiedFile(filePath: String): UserInteractionRecord {
        require(filePath.isNotBlank()) { "filePath 不能为空" }
        return copy(modifiedFiles = modifiedFiles + filePath)
    }

    /**
     * 添加提及的文件
     */
    fun withMentionedFile(filePath: String): UserInteractionRecord {
        require(filePath.isNotBlank()) { "filePath 不能为空" }
        return copy(mentionedFiles = mentionedFiles + filePath)
    }

    /**
     * 添加关注的模块
     */
    fun withFocusModule(moduleName: String): UserInteractionRecord {
        require(moduleName.isNotBlank()) { "moduleName 不能为空" }
        return copy(focusModules = focusModules + moduleName)
    }

    /**
     * 设置代码风格偏好
     */
    fun withCodeStylePreference(preference: String): UserInteractionRecord {
        require(preference.isNotBlank()) { "preference 不能为空" }
        return copy(codeStylePreference = preference)
    }

    /**
     * 设置交互结果
     */
    fun withOutcome(outcome: InteractionOutcome): UserInteractionRecord {
        return copy(outcome = outcome)
    }

    /**
     * 添加元数据
     */
    fun withMetadata(key: String, value: String): UserInteractionRecord {
        require(key.isNotBlank()) { "key 不能为空" }
        return copy(metadata = metadata + (key to value))
    }

    /**
     * 完成记录（标记为成功）
     */
    fun completeSuccessfully(): UserInteractionRecord {
        return copy(outcome = InteractionOutcome.SUCCESS)
    }

    /**
     * 完成记录（标记为失败）
     */
    fun completeWithFailure(): UserInteractionRecord {
        return copy(outcome = InteractionOutcome.FAILED)
    }

    /**
     * 完成记录（标记为部分成功）
     */
    fun completePartially(): UserInteractionRecord {
        return copy(outcome = InteractionOutcome.PARTIAL)
    }

    /**
     * 获取持续时间（毫秒）
     */
    fun getDurationMs(currentTime: Long = System.currentTimeMillis()): Long {
        return currentTime - createdAt
    }

    /**
     * 判断是否涉及文件修改
     */
    fun hasFileModifications(): Boolean = modifiedFiles.isNotEmpty()

    /**
     * 获取所有相关文件（修改的 + 提及的）
     */
    fun getAllRelatedFiles(): List<String> = modifiedFiles + mentionedFiles
}

/**
 * 操作类型枚举
 *
 * 标识用户请求的操作类型
 */
enum class OperationType {
    /** 增加功能 */
    ADD_FEATURE,
    /** 修改功能 */
    MODIFY_FEATURE,
    /** 修复问题 */
    FIX_BUG,
    /** 查询信息 */
    QUERY,
    /** 重构代码 */
    REFACTOR,
    /** 配置相关 */
    CONFIG,
    /** 代码审查 */
    CODE_REVIEW,
    /** 测试相关 */
    TEST,
    /** 文档相关 */
    DOCUMENTATION,
    /** 未知类型 */
    UNKNOWN;

    companion object {
        /**
         * 从字符串解析操作类型
         */
        fun fromString(value: String): OperationType {
            return when (value.uppercase()) {
                "ADD_FEATURE", "ADD", "CREATE", "NEW" -> ADD_FEATURE
                "MODIFY_FEATURE", "MODIFY", "UPDATE", "CHANGE" -> MODIFY_FEATURE
                "FIX_BUG", "FIX", "BUGFIX", "HOTFIX" -> FIX_BUG
                "QUERY", "ASK", "SEARCH", "FIND" -> QUERY
                "REFACTOR", "CLEANUP", "OPTIMIZE" -> REFACTOR
                "CONFIG", "CONFIGURATION", "SETTING" -> CONFIG
                "CODE_REVIEW", "REVIEW", "CHECK" -> CODE_REVIEW
                "TEST", "TESTING" -> TEST
                "DOCUMENTATION", "DOC", "DOCS" -> DOCUMENTATION
                else -> UNKNOWN
            }
        }
    }

    /**
     * 获取显示名称
     */
    fun getDisplayName(): String {
        return when (this) {
            ADD_FEATURE -> "增加功能"
            MODIFY_FEATURE -> "修改功能"
            FIX_BUG -> "修复问题"
            QUERY -> "查询信息"
            REFACTOR -> "重构代码"
            CONFIG -> "配置相关"
            CODE_REVIEW -> "代码审查"
            TEST -> "测试相关"
            DOCUMENTATION -> "文档相关"
            UNKNOWN -> "未知类型"
        }
    }
}

/**
 * 工具调用快照
 *
 * 记录单次工具调用的关键信息
 *
 * @property toolName 工具名称
 * @property parameters 调用参数（简化版，只保留字符串值）
 * @property resultSummary 结果摘要
 * @property timestamp 调用时间戳
 * @property success 是否成功
 */
data class ToolCallSnapshot(
    val toolName: String,
    val parameters: Map<String, String> = emptyMap(),
    val resultSummary: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val success: Boolean = true
) {
    companion object {
        /**
         * 创建工具调用快照
         */
        fun create(
            toolName: String,
            parameters: Map<String, Any>? = null,
            resultSummary: String? = null,
            success: Boolean = true
        ): ToolCallSnapshot {
            require(toolName.isNotBlank()) { "toolName 不能为空" }

            val stringParams = parameters?.mapValues { it.value.toString() } ?: emptyMap()

            return ToolCallSnapshot(
                toolName = toolName,
                parameters = stringParams,
                resultSummary = resultSummary,
                success = success
            )
        }
    }

    /**
     * 获取参数的简要描述
     */
    fun getParametersBrief(): String {
        if (parameters.isEmpty()) {
            return ""
        }
        return parameters.entries.take(3).joinToString(", ") { "${it.key}=${it.value}" } +
                if (parameters.size > 3) "..." else ""
    }
}

/**
 * 交互结果枚举
 *
 * 标识交互的最终结果
 */
enum class InteractionOutcome {
    /** 进行中 */
    PENDING,
    /** 成功 */
    SUCCESS,
    /** 部分成功 */
    PARTIAL,
    /** 失败 */
    FAILED,
    /** 用户取消 */
    USER_CANCELLED,
    /** 超时 */
    TIMEOUT;

    /**
     * 判断是否为成功结果
     */
    fun isSuccess(): Boolean = this == SUCCESS || this == PARTIAL

    /**
     * 判断是否为最终状态
     */
    fun isFinal(): Boolean = this != PENDING

    /**
     * 获取显示名称
     */
    fun getDisplayName(): String {
        return when (this) {
            PENDING -> "进行中"
            SUCCESS -> "成功"
            PARTIAL -> "部分成功"
            FAILED -> "失败"
            USER_CANCELLED -> "用户取消"
            TIMEOUT -> "超时"
        }
    }
}
