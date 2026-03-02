package com.smancode.sman.usage.model

import java.time.Instant

/**
 * 分析任务状态
 *
 * 用于追踪定时分析任务的执行状态，支持断点续传和异常恢复。
 *
 * @property jobId 任务唯一标识符
 * @property status 任务状态
 * @property startedAt 开始时间
 * @property completedAt 完成时间（可能为 null）
 * @property lastProcessedIndex 断点：已处理到的记录索引（-1 表示尚未开始）
 * @property totalRecords 总记录数
 * @property processedRecords 已处理记录数
 * @property errorMessage 错误信息（失败时记录）
 * @property result 分析结果（完成后填充）
 */
data class AnalysisJobState(
    val jobId: String,
    val status: JobStatus,
    val startedAt: Instant,
    val completedAt: Instant? = null,
    val lastProcessedIndex: Int = -1,
    val totalRecords: Int = 0,
    val processedRecords: Int = 0,
    val errorMessage: String? = null,
    val result: UsageAnalysisResult? = null
) {
    /**
     * 计算进度百分比
     */
    val progress: Double
        get() = if (totalRecords > 0) processedRecords.toDouble() / totalRecords else 0.0

    /**
     * 判断是否正在运行
     */
    fun isRunning(): Boolean = status == JobStatus.RUNNING

    /**
     * 判断是否可以断点续传
     */
    fun canResume(): Boolean = status == JobStatus.FAILED && lastProcessedIndex >= 0

    /**
     * 判断是否已完成
     */
    fun isCompleted(): Boolean = status == JobStatus.COMPLETED

    companion object {
        /**
         * 创建新任务
         */
        fun createNew(totalRecords: Int): AnalysisJobState {
            val jobId = "job-${Instant.now().toString().replace(":", "-").replace(".", "-")}"
            return AnalysisJobState(
                jobId = jobId,
                status = JobStatus.PENDING,
                startedAt = Instant.now(),
                totalRecords = totalRecords
            )
        }
    }
}

/**
 * 任务状态枚举
 */
enum class JobStatus {
    /** 待执行 */
    PENDING,
    /** 执行中 */
    RUNNING,
    /** 已完成 */
    COMPLETED,
    /** 失败 */
    FAILED,
    /** 取消（被新任务顶替或手动取消） */
    CANCELLED
}

/**
 * 调度执行结果
 */
sealed class ScheduleResult {
    /** 成功完成 */
    data class Success(val result: UsageAnalysisResult) : ScheduleResult()

    /** 跳过（有任务正在运行） */
    object SkippedRunning : ScheduleResult()

    /** 从断点恢复成功 */
    data class Resumed(val result: UsageAnalysisResult, val resumedFrom: Int) : ScheduleResult()

    /** 失败 */
    data class Failed(val error: String) : ScheduleResult()

    /** 无数据需要处理 */
    object NoData : ScheduleResult()
}
