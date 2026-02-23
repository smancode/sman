package com.smancode.sman.domain.puzzle

import java.time.Instant

/**
 * 任务类型枚举
 */
enum class TaskType {
    /** 分析项目结构 */
    ANALYZE_STRUCTURE,
    /** 分析 API 入口 */
    ANALYZE_API,
    /** 分析数据模型 */
    ANALYZE_DATA,
    /** 分析业务流程 */
    ANALYZE_FLOW,
    /** 分析业务规则 */
    ANALYZE_RULE,
    /** 更新过期知识 */
    UPDATE_PUZZLE
}

/**
 * 任务状态枚举
 */
enum class TaskStatus {
    /** 等待执行 */
    PENDING,
    /** 执行中 */
    RUNNING,
    /** 已完成 */
    COMPLETED,
    /** 失败 */
    FAILED,
    /** 跳过（已存在或无需更新） */
    SKIPPED
}

/**
 * 分析任务数据类
 *
 * @property id 任务唯一标识符
 * @property type 任务类型
 * @property target 分析目标（文件路径或模块名）
 * @property puzzleId 关联的 Puzzle ID
 * @property status 任务状态
 * @property priority 优先级（0.0-1.0）
 * @property checksum 目标文件的 checksum
 * @property relatedFiles 相关文件列表
 * @property createdAt 创建时间
 * @property startedAt 开始执行时间
 * @property completedAt 完成时间
 * @property retryCount 重试次数
 * @property errorMessage 错误信息
 */
data class AnalysisTask(
    val id: String,
    val type: TaskType,
    val target: String,
    val puzzleId: String,
    val status: TaskStatus,
    val priority: Double,
    val checksum: String,
    val relatedFiles: List<String>,
    val createdAt: Instant,
    val startedAt: Instant?,
    val completedAt: Instant?,
    val retryCount: Int,
    val errorMessage: String?
) {
    companion object {
        /** 创建新的 PENDING 任务 */
        fun create(
            id: String,
            type: TaskType,
            target: String,
            puzzleId: String,
            priority: Double = 0.5,
            checksum: String = "",
            relatedFiles: List<String> = emptyList()
        ): AnalysisTask = AnalysisTask(
            id = id,
            type = type,
            target = target,
            puzzleId = puzzleId,
            status = TaskStatus.PENDING,
            priority = priority,
            checksum = checksum,
            relatedFiles = relatedFiles,
            createdAt = Instant.now(),
            startedAt = null,
            completedAt = null,
            retryCount = 0,
            errorMessage = null
        )
    }

    /** 标记为正在执行 */
    fun start(): AnalysisTask = copy(
        status = TaskStatus.RUNNING,
        startedAt = Instant.now()
    )

    /** 标记为完成 */
    fun complete(): AnalysisTask = copy(
        status = TaskStatus.COMPLETED,
        completedAt = Instant.now()
    )

    /** 标记为跳过 */
    fun skip(): AnalysisTask = copy(
        status = TaskStatus.SKIPPED,
        completedAt = Instant.now()
    )
}
