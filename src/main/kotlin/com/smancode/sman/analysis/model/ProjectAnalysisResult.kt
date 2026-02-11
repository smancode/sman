package com.smancode.sman.analysis.model

import kotlinx.serialization.Serializable
import java.time.Instant

/**
 * 项目分析结果
 *
 * @property projectKey 项目标识符
 * @property startTime 分析开始时间
 * @property endTime 分析结束时间（null 表示进行中）
 * @property status 分析状态
 * @property steps 各步骤的分析结果
 */
@Serializable
data class ProjectAnalysisResult(
    val projectKey: String,
    val startTime: Long,
    val endTime: Long? = null,
    val status: AnalysisStatus,
    val steps: Map<String, StepResult> = emptyMap(),
    val projectMd5: String? = null
) {
    companion object {
        fun create(projectKey: String): ProjectAnalysisResult {
            return ProjectAnalysisResult(
                projectKey = projectKey,
                startTime = System.currentTimeMillis(),
                status = AnalysisStatus.PENDING,
                steps = emptyMap()
            )
        }
    }

    /**
     * 标记分析开始
     */
    fun markStarted(): ProjectAnalysisResult {
        return copy(
            startTime = System.currentTimeMillis(),
            status = AnalysisStatus.RUNNING
        )
    }

    /**
     * 标记分析完成
     */
    fun markCompleted(): ProjectAnalysisResult {
        return copy(
            endTime = System.currentTimeMillis(),
            status = if (steps.values.all { it.status == StepStatus.COMPLETED }) {
                AnalysisStatus.COMPLETED
            } else {
                AnalysisStatus.PARTIAL
            }
        )
    }

    /**
     * 标记分析失败
     */
    fun markFailed(): ProjectAnalysisResult {
        return copy(
            endTime = System.currentTimeMillis(),
            status = AnalysisStatus.FAILED
        )
    }

    /**
     * 更新步骤结果
     */
    fun updateStep(stepResult: StepResult): ProjectAnalysisResult {
        return copy(
            steps = steps + (stepResult.stepName to stepResult)
        )
    }

    /**
     * 获取已完成步骤数
     */
    fun getCompletedStepCount(): Int {
        return steps.values.count { it.status == StepStatus.COMPLETED }
    }

    /**
     * 获取总步骤数
     */
    fun getTotalStepCount(): Int {
        return steps.size
    }
}

/**
 * 分析状态
 */
enum class AnalysisStatus {
    PENDING,   // 等待开始
    RUNNING,   // 分析中
    COMPLETED, // 全部完成
    PARTIAL,   // 部分完成
    FAILED     // 失败
}

/**
 * 步骤分析结果
 *
 * @property stepName 步骤名称
 * @property stepDescription 步骤描述
 * @property status 步骤状态
 * @property startTime 开始时间
 * @property endTime 结束时间（null 表示进行中）
 * @property data 结果数据（JSON 序列化）
 * @property error 错误信息（如果有）
 */
@Serializable
data class StepResult(
    val stepName: String,
    val stepDescription: String,
    val status: StepStatus,
    val startTime: Long,
    val endTime: Long? = null,
    val data: String? = null,
    val error: String? = null
) {
    companion object {
        fun create(stepName: String, stepDescription: String): StepResult {
            return StepResult(
                stepName = stepName,
                stepDescription = stepDescription,
                status = StepStatus.PENDING,
                startTime = System.currentTimeMillis()
            )
        }
    }

    /**
     * 标记步骤开始
     */
    fun markStarted(): StepResult {
        return copy(
            status = StepStatus.RUNNING,
            startTime = System.currentTimeMillis()
        )
    }

    /**
     * 标记步骤完成
     */
    fun markCompleted(data: String? = null): StepResult {
        return copy(
            status = StepStatus.COMPLETED,
            endTime = System.currentTimeMillis(),
            data = data
        )
    }

    /**
     * 标记步骤失败
     */
    fun markFailed(error: String): StepResult {
        return copy(
            status = StepStatus.FAILED,
            endTime = System.currentTimeMillis(),
            error = error
        )
    }

    /**
     * 标记步骤跳过
     */
    fun markSkipped(reason: String): StepResult {
        return copy(
            status = StepStatus.SKIPPED,
            endTime = System.currentTimeMillis(),
            error = reason
        )
    }

    /**
     * 获取耗时（毫秒）
     */
    fun getDuration(): Long? {
        return if (endTime != null && startTime > 0) {
            endTime!! - startTime
        } else {
            null
        }
    }
}

/**
 * 步骤状态
 */
enum class StepStatus {
    PENDING,   // 等待开始
    RUNNING,   // 执行中
    COMPLETED, // 完成
    FAILED,    // 失败
    SKIPPED    // 跳过
}
