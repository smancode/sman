package com.smancode.sman.analysis.model

import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// ========== 枚举定义 ==========

/**
 * 分析阶段枚举
 *
 * 定义项目分析循环的各个阶段
 */
enum class AnalysisPhase {
    /** 空闲 - 等待下一次分析 */
    IDLE,
    /** 检查变更中 - 检测文件 MD5 变化 */
    CHECKING_CHANGES,
    /** 加载状态中 - 从数据库加载之前的状态 */
    LOADING_STATE,
    /** 分析中 - 正在执行分析任务 */
    ANALYZING,
    /** 持久化中 - 正在保存分析结果 */
    PERSISTING,
    /** 错误状态 - 分析过程出错 */
    ERROR
}

/**
 * 任务状态枚举
 *
 * 定义分析任务的生命周期状态
 */
enum class TaskStatus {
    /** 待处理 - 任务已创建但未开始 */
    PENDING,
    /** 进行中 - 任务正在执行 */
    DOING,
    /** 已完成 - 任务成功完成 */
    COMPLETED,
    /** 已失败 - 任务执行失败 */
    FAILED
}

/**
 * TODO 状态枚举
 *
 * 定义分析待办事项的状态
 */
enum class TodoStatus {
    /** 待处理 */
    PENDING,
    /** 进行中 */
    IN_PROGRESS,
    /** 已完成 */
    COMPLETED
}

// ========== 时间戳工具 ==========

/**
 * 时间戳格式化工具
 */
object TimestampFormatter {
    private val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        .withZone(ZoneId.systemDefault())

    /**
     * 将毫秒时间戳格式化为可读字符串
     */
    fun format(timestamp: Long?): String? {
        if (timestamp == null) return null
        return formatter.format(Instant.ofEpochMilli(timestamp))
    }

    /**
     * 获取当前时间的可读字符串
     */
    fun now(): String = formatter.format(Instant.now())

    /**
     * 获取当前时间的毫秒时间戳
     */
    fun nowMillis(): Long = System.currentTimeMillis()
}

// ========== 数据模型 ==========

/**
 * 分析待办事项
 *
 * 记录分析任务中需要补充或改进的内容
 *
 * @property id 唯一标识符
 * @property content 待办内容描述
 * @property status 当前状态
 * @property priority 优先级（数值越小优先级越高）
 */
@Serializable
data class AnalysisTodo(
    val id: String,
    val content: String,
    val status: TodoStatus = TodoStatus.PENDING,
    val priority: Int = 1
)

/**
 * 分析任务
 *
 * 记录单个分析类型的执行状态
 */
@Serializable
data class AnalysisTask(
    val type: AnalysisType,
    val status: TaskStatus = TaskStatus.PENDING,
    val startTime: Long? = null,
    val startTimeReadable: String? = null,
    val endTime: Long? = null,
    val endTimeReadable: String? = null,
    val todos: List<AnalysisTodo> = emptyList(),
    val completeness: Double = 0.0,
    val missingSections: List<String> = emptyList(),
    val mdFilePath: String? = null,
    val errorMessage: String? = null,
    val currentStep: Int = 0,
    val toolCallHistory: String? = null
) {
    companion object {
        /**
         * 创建新的待处理任务
         */
        fun pending(type: AnalysisType): AnalysisTask {
            return AnalysisTask(
                type = type,
                status = TaskStatus.PENDING,
                mdFilePath = type.getMdFilePath()
            )
        }

        /**
         * 开始任务（转换为 DOING 状态）
         */
        fun start(type: AnalysisType): AnalysisTask {
            val now = TimestampFormatter.nowMillis()
            return AnalysisTask(
                type = type,
                status = TaskStatus.DOING,
                startTime = now,
                startTimeReadable = TimestampFormatter.format(now),
                mdFilePath = type.getMdFilePath()
            )
        }
    }

    /**
     * 标记为进行中
     */
    fun markAsDoing(): AnalysisTask {
        val now = TimestampFormatter.nowMillis()
        return copy(
            status = TaskStatus.DOING,
            startTime = startTime ?: now,
            startTimeReadable = startTimeReadable ?: TimestampFormatter.format(now)
        )
    }

    /**
     * 标记为完成
     */
    fun markAsCompleted(completeness: Double, missingSections: List<String> = emptyList()): AnalysisTask {
        val now = TimestampFormatter.nowMillis()
        return copy(
            status = TaskStatus.COMPLETED,
            endTime = now,
            endTimeReadable = TimestampFormatter.format(now),
            completeness = completeness,
            missingSections = missingSections
        )
    }

    /**
     * 标记为失败
     */
    fun markAsFailed(errorMessage: String): AnalysisTask {
        val now = TimestampFormatter.nowMillis()
        return copy(
            status = TaskStatus.FAILED,
            endTime = now,
            endTimeReadable = TimestampFormatter.format(now),
            errorMessage = errorMessage
        )
    }

    /**
     * 更新完整度和缺失章节
     */
    fun updateCompleteness(completeness: Double, missingSections: List<String>): AnalysisTask {
        return copy(completeness = completeness, missingSections = missingSections)
    }

    /**
     * 添加待办事项
     */
    fun addTodo(todo: AnalysisTodo): AnalysisTask {
        return copy(todos = todos + todo)
    }

    /**
     * 获取未完成的待办事项
     */
    fun getPendingTodos(): List<AnalysisTodo> {
        return todos.filter { it.status != TodoStatus.COMPLETED }
    }

    /**
     * 是否需要补充分析
     */
    fun needsSupplement(): Boolean {
        return completeness < 0.8 || missingSections.isNotEmpty() || getPendingTodos().isNotEmpty()
    }
}

/**
 * 分析循环状态
 *
 * 记录项目分析主循环的运行状态，用于断点续传
 */
@Serializable
data class AnalysisLoopState(
    val projectKey: String,
    val enabled: Boolean = true,
    val currentPhase: AnalysisPhase = AnalysisPhase.IDLE,
    val currentAnalysisType: AnalysisType? = null,
    val currentStep: Int = 0,
    val analysisTodos: List<AnalysisTodo> = emptyList(),
    val totalAnalyses: Int = 0,
    val successfulAnalyses: Int = 0,
    val lastAnalysisTime: Long? = null,
    val lastAnalysisTimeReadable: String? = null,
    val lastError: String? = null,
    val lastUpdatedAt: Long = TimestampFormatter.nowMillis()
) {
    /**
     * 是否处于空闲状态
     */
    fun isIdle(): Boolean = currentPhase == AnalysisPhase.IDLE

    /**
     * 是否正在分析
     */
    fun isAnalyzing(): Boolean = currentPhase == AnalysisPhase.ANALYZING

    /**
     * 是否处于 ING 状态（有未完成的操作）
     */
    fun isInProgress(): Boolean {
        return currentPhase != AnalysisPhase.IDLE &&
               currentPhase != AnalysisPhase.ERROR &&
               currentPhase != AnalysisPhase.CHECKING_CHANGES
    }

    /**
     * 标记为开始分析
     */
    fun startAnalyzing(type: AnalysisType): AnalysisLoopState {
        val now = TimestampFormatter.nowMillis()
        return copy(
            currentPhase = AnalysisPhase.ANALYZING,
            currentAnalysisType = type,
            currentStep = 0,
            lastUpdatedAt = now
        )
    }

    /**
     * 标记为分析完成
     */
    fun finishAnalyzing(success: Boolean): AnalysisLoopState {
        val now = TimestampFormatter.nowMillis()
        return copy(
            currentPhase = AnalysisPhase.IDLE,
            currentAnalysisType = null,
            currentStep = 0,
            totalAnalyses = totalAnalyses + 1,
            successfulAnalyses = if (success) successfulAnalyses + 1 else successfulAnalyses,
            lastAnalysisTime = now,
            lastAnalysisTimeReadable = TimestampFormatter.format(now),
            lastUpdatedAt = now
        )
    }

    /**
     * 标记为错误状态
     */
    fun markAsError(error: String): AnalysisLoopState {
        return copy(
            currentPhase = AnalysisPhase.ERROR,
            lastError = error,
            lastUpdatedAt = TimestampFormatter.nowMillis()
        )
    }

    /**
     * 重置为空闲状态
     */
    fun reset(): AnalysisLoopState {
        return copy(
            currentPhase = AnalysisPhase.IDLE,
            currentAnalysisType = null,
            currentStep = 0,
            lastUpdatedAt = TimestampFormatter.nowMillis()
        )
    }

    /**
     * 更新当前步骤
     */
    fun updateStep(step: Int): AnalysisLoopState {
        return copy(currentStep = step, lastUpdatedAt = TimestampFormatter.nowMillis())
    }

    /**
     * 获取成功率
     */
    fun getSuccessRate(): Double {
        if (totalAnalyses == 0) return 0.0
        return successfulAnalyses.toDouble() / totalAnalyses
    }
}

/**
 * 分析结果实体
 *
 * 持久化到数据库的分析结果记录
 */
@Serializable
data class AnalysisResultEntity(
    val projectKey: String,
    val analysisType: AnalysisType,
    val mdFilePath: String? = null,
    val completeness: Double = 0.0,
    val missingSections: List<String> = emptyList(),
    val analysisTodos: List<AnalysisTodo> = emptyList(),
    val taskStatus: TaskStatus = TaskStatus.PENDING,
    val currentStep: Int = 0,
    val toolCallHistory: String? = null,
    val createdAt: Long = TimestampFormatter.nowMillis(),
    val createdAtReadable: String? = TimestampFormatter.format(TimestampFormatter.nowMillis()),
    val updatedAt: Long = TimestampFormatter.nowMillis(),
    val updatedAtReadable: String? = TimestampFormatter.format(TimestampFormatter.nowMillis())
) {
    /**
     * 转换为 AnalysisTask
     */
    fun toTask(): AnalysisTask {
        return AnalysisTask(
            type = analysisType,
            status = taskStatus,
            todos = analysisTodos,
            completeness = completeness,
            missingSections = missingSections,
            mdFilePath = mdFilePath,
            currentStep = currentStep,
            toolCallHistory = toolCallHistory
        )
    }

    /**
     * 更新时间戳
     */
    fun touch(): AnalysisResultEntity {
        val now = TimestampFormatter.nowMillis()
        return copy(updatedAt = now, updatedAtReadable = TimestampFormatter.format(now))
    }

    companion object {
        /**
         * 从 AnalysisTask 创建实体
         */
        fun fromTask(projectKey: String, task: AnalysisTask): AnalysisResultEntity {
            val now = TimestampFormatter.nowMillis()
            return AnalysisResultEntity(
                projectKey = projectKey,
                analysisType = task.type,
                mdFilePath = task.mdFilePath ?: task.type.getMdFilePath(),
                completeness = task.completeness,
                missingSections = task.missingSections,
                analysisTodos = task.todos,
                taskStatus = task.status,
                currentStep = task.currentStep,
                toolCallHistory = task.toolCallHistory,
                createdAt = task.startTime ?: now,
                createdAtReadable = task.startTimeReadable ?: TimestampFormatter.format(now),
                updatedAt = now,
                updatedAtReadable = TimestampFormatter.format(now)
            )
        }
    }
}

/**
 * 项目分析统计信息
 */
data class AnalysisStatistics(
    val totalTasks: Int = 0,
    val completedTasks: Int = 0,
    val pendingTasks: Int = 0,
    val doingTasks: Int = 0,
    val failedTasks: Int = 0,
    val averageCompleteness: Double = 0.0
) {
    /**
     * 获取完成率
     */
    fun getCompletionRate(): Double {
        if (totalTasks == 0) return 0.0
        return completedTasks.toDouble() / totalTasks
    }
}
