package com.smancode.sman.architect.model

import com.smancode.sman.analysis.model.AnalysisType

/**
 * 架构师目标定义
 *
 * 定义架构师 Agent 需要完成的分析目标
 *
 * @property type 分析类型
 * @property priority 优先级（1-10，10 最高）
 * @property context 上下文信息（已有的分析结果）
 * @property followUpQuestions 追问列表
 * @property retryCount 重试次数
 * @property maxRetries 最大重试次数
 * @property targetCompleteness 目标完成度阈值
 */
data class ArchitectGoal(
    val type: AnalysisType,
    val priority: Int = 5,
    val context: Map<String, String> = emptyMap(),
    val followUpQuestions: List<String> = emptyList(),
    val retryCount: Int = 0,
    val maxRetries: Int = 3,
    val targetCompleteness: Double = 0.8
) {
    /**
     * 目标唯一标识
     */
    val id: String
        get() = type.key

    /**
     * 是否可以重试
     */
    val canRetry: Boolean
        get() = retryCount < maxRetries

    /**
     * 创建重试目标
     */
    fun withRetry(): ArchitectGoal = copy(
        retryCount = retryCount + 1,
        priority = maxOf(1, priority - 1)
    )

    /**
     * 添加追问
     */
    fun withFollowUp(questions: List<String>): ArchitectGoal = copy(
        followUpQuestions = followUpQuestions + questions
    )

    /**
     * 添加上下文
     */
    fun withContext(key: String, value: String): ArchitectGoal = copy(
        context = context + (key to value)
    )

    /**
     * 目标描述（用于日志和提示词）
     */
    val description: String
        get() = buildString {
            append("分析目标: ${type.displayName}")
            if (followUpQuestions.isNotEmpty()) {
                append("（包含 ${followUpQuestions.size} 个追问）")
            }
        }

    companion object {
        /**
         * 从分析类型创建默认目标
         */
        fun fromType(type: AnalysisType, targetCompleteness: Double = 0.8): ArchitectGoal {
            return ArchitectGoal(
                type = type,
                targetCompleteness = targetCompleteness
            )
        }

        /**
         * 创建所有分析类型的目标列表
         */
        fun createAllGoals(targetCompleteness: Double = 0.8): List<ArchitectGoal> {
            return AnalysisType.allTypes().map { fromType(it, targetCompleteness) }
        }

        /**
         * 创建核心分析类型的目标列表（优先执行）
         */
        fun createCoreGoals(targetCompleteness: Double = 0.8): List<ArchitectGoal> {
            return AnalysisType.coreTypes().map { fromType(it, targetCompleteness) }
        }

        /**
         * 创建标准分析类型的目标列表（核心类型之后执行）
         */
        fun createStandardGoals(targetCompleteness: Double = 0.8): List<ArchitectGoal> {
            return AnalysisType.standardTypes().map { fromType(it, targetCompleteness) }
        }
    }
}
