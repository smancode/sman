package com.smancode.sman.architect.model

import kotlinx.serialization.Serializable

/**
 * 评估结果
 *
 * LLM 对分析结果的阶段性评估
 *
 * @property completeness 完成度（0.0-1.0）
 * @property isComplete 是否完成（>= 阈值）
 * @property summary 本次分析总结
 * @property todos 未完成 TODO 列表
 * @property followUpQuestions 需要追问的问题
 * @property confidence 置信度
 */
@Serializable
data class EvaluationResult(
    val completeness: Double,
    val isComplete: Boolean,
    val summary: String,
    val todos: List<TodoItem>,
    val followUpQuestions: List<String>,
    val confidence: Double = 0.8
) {
    /**
     * 是否需要追问
     */
    val needsFollowUp: Boolean
        get() = followUpQuestions.isNotEmpty() && !isComplete

    /**
     * 是否需要记录 TODO
     */
    val hasTodos: Boolean
        get() = todos.isNotEmpty()

    /**
     * 高优先级 TODO
     */
    val highPriorityTodos: List<TodoItem>
        get() = todos.filter { it.priority == TodoPriority.HIGH }

    /**
     * 格式化为可读字符串
     */
    fun formatSummary(): String {
        return buildString {
            appendLine("评估结果:")
            appendLine("  - 完成度: ${(completeness * 100).toInt()}%")
            appendLine("  - 是否完成: ${if (isComplete) "是" else "否"}")
            appendLine("  - 置信度: ${(confidence * 100).toInt()}%")
            if (todos.isNotEmpty()) {
                appendLine("  - TODO 数量: ${todos.size}")
            }
            if (followUpQuestions.isNotEmpty()) {
                appendLine("  - 追问数量: ${followUpQuestions.size}")
            }
        }
    }

    companion object {
        /**
         * 默认完成阈值
         */
        const val COMPLETENESS_THRESHOLD = 0.8

        /**
         * 创建失败结果
         */
        fun failure(reason: String): EvaluationResult = EvaluationResult(
            completeness = 0.0,
            isComplete = false,
            summary = "评估失败: $reason",
            todos = emptyList(),
            followUpQuestions = emptyList(),
            confidence = 0.0
        )

        /**
         * 创建空结果
         */
        fun empty(): EvaluationResult = EvaluationResult(
            completeness = 0.0,
            isComplete = false,
            summary = "无分析结果",
            todos = emptyList(),
            followUpQuestions = emptyList(),
            confidence = 0.0
        )

        /**
         * 创建完成结果
         */
        fun complete(summary: String, completeness: Double = 1.0): EvaluationResult = EvaluationResult(
            completeness = completeness,
            isComplete = true,
            summary = summary,
            todos = emptyList(),
            followUpQuestions = emptyList(),
            confidence = 1.0
        )
    }
}

/**
 * TODO 项
 */
@Serializable
data class TodoItem(
    val content: String,
    val priority: TodoPriority,
    val category: String? = null
) {
    /**
     * 格式化为字符串
     */
    override fun toString(): String {
        val prefix = when (priority) {
            TodoPriority.HIGH -> "[HIGH] "
            TodoPriority.MEDIUM -> "[MEDIUM] "
            TodoPriority.LOW -> "[LOW] "
        }
        return "$prefix$content"
    }
}

/**
 * TODO 优先级
 */
@Serializable
enum class TodoPriority {
    HIGH,
    MEDIUM,
    LOW;

    companion object {
        /**
         * 从字符串解析
         */
        fun fromString(value: String): TodoPriority {
            return values().firstOrNull { it.name.equals(value, ignoreCase = true) } ?: MEDIUM
        }
    }
}
