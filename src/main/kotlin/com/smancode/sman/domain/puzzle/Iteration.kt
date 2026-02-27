package com.smancode.sman.domain.puzzle

import com.smancode.sman.shared.model.Puzzle
import java.time.Instant

// 导入 TaskStatus（从 AnalysisTask 复用）
import com.smancode.sman.domain.puzzle.TaskStatus

/**
 * 知识进化循环迭代
 *
 * 代表一次完整的知识进化循环：观察 → 假设 → 审查 → 计划 → 执行 → 评估 → 合并
 */
data class Iteration(
    val id: String,
    val trigger: Trigger,                    // 触发原因
    val hypothesis: String,                // 本轮假设/目标
    val tasks: List<IterationTask>,         // 拆解的任务
    val results: List<TaskResult>,         // 执行结果
    val evaluation: Evaluation,             // 评估结果
    val status: IterationStatus,           // 状态
    val createdAt: Instant,
    val completedAt: Instant?
) {
    companion object {
        /**
         * 创建新迭代
         */
        fun create(
            id: String,
            trigger: Trigger,
            hypothesis: String = ""
        ): Iteration {
            return Iteration(
                id = id,
                trigger = trigger,
                hypothesis = hypothesis,
                tasks = emptyList(),
                results = emptyList(),
                evaluation = Evaluation(
                    hypothesisConfirmed = false,
                    newKnowledgeGained = 0,
                    conflictsFound = emptyList(),
                    qualityScore = 0.0,
                    lessonsLearned = emptyList()
                ),
                status = IterationStatus.OBSERVING,
                createdAt = Instant.now(),
                completedAt = null
            )
        }
    }

    /**
     * 是否已完成
     */
    fun isCompleted(): Boolean = status == IterationStatus.COMPLETED || status == IterationStatus.FAILED

    /**
     * 是否正在进行中
     */
    fun isInProgress(): Boolean = !isCompleted()
}

/**
 * 迭代任务
 *
 * 具体的分析任务单元
 */
data class IterationTask(
    val id: String,
    val description: String,      // 任务描述
    val target: String,          // 分析目标（文件路径/模式）
    val priority: Double,        // 优先级 0-1
    val status: TaskStatus,      // 任务状态
    val assignee: String?,      // 认领者（null = 系统自动执行）
    val result: TaskResult?     // 执行结果
) {
    companion object {
        /**
         * 创建新任务
         */
        fun create(
            id: String,
            description: String,
            target: String,
            priority: Double = 0.5
        ): IterationTask {
            return IterationTask(
                id = id,
                description = description,
                target = target,
                priority = priority,
                status = TaskStatus.PENDING,
                assignee = null,
                result = null
            )
        }
    }

    /**
     * 是否已完成
     */
    fun isCompleted(): Boolean = status == TaskStatus.COMPLETED || status == TaskStatus.FAILED
}

/**
 * 任务执行结果
 */
data class TaskResult(
    val taskId: String,
    val assignee: String,
    val output: String,              // LLM 输出
    val tags: List<String>,         // 提取的标签
    val confidence: Double,          // 置信度 0-1
    val filesAnalyzed: List<String>  // 分析的文件列表
)

/**
 * 评估结果
 *
 * 对本轮迭代的评估
 */
data class Evaluation(
    val hypothesisConfirmed: Boolean,     // 假设是否被证实
    val newKnowledgeGained: Int,          // 获取的新知识数
    val conflictsFound: List<Conflict>,   // 发现的知识冲突
    val qualityScore: Double,              // 质量评分 0-1
    val lessonsLearned: List<String>        // 学到的教训
) {
    init {
        require(qualityScore in 0.0..1.0) { "质量评分必须在 0-1 之间" }
    }

    /**
     * 是否成功
     */
    fun isSuccessful(): Boolean = hypothesisConfirmed && qualityScore > 0.5
}

/**
 * 知识冲突
 */
data class Conflict(
    val type: ConflictType,
    val description: String,
    val puzzleIds: List<String>
)

/**
 * 冲突类型
 */
enum class ConflictType {
    CONTRADICTION,   // 矛盾：两个说法完全相反
    OVERLAP,        // 重叠：可以合并
    OUTDATED        // 过时：新信息推翻旧信息
}

/**
 * 假设
 *
 * 本轮迭代的假设/目标
 */
data class Hypothesis(
    val statement: String,         // 假设描述
    val confidence: Double,        // 置信度 0-1
    val evidence: List<String>     // 支持的证据（已有 Puzzle ID）
) {
    init {
        require(confidence in 0.0..1.0) { "置信度必须在 0-1 之间" }
    }
}

/**
 * 触发原因
 *
 * 触发知识进化循环的原因
 */
sealed class Trigger {
    /**
     * 用户查询触发
     */
    data class UserQuery(val query: String) : Trigger()

    /**
     * 文件变更触发
     */
    data class FileChange(val files: List<String>) : Trigger()

    /**
     * 定时触发
     */
    data class Scheduled(val reason: String) : Trigger()

    /**
     * 手动触发
     */
    data class Manual(val reason: String) : Trigger()
}

/**
 * 迭代状态
 */
enum class IterationStatus {
    OBSERVING,      // 观察阶段
    HYPOTHESIZING,  // 假设阶段
    REVIEWING,      // 审查阶段
    PLANNING,      // 计划阶段
    EXECUTING,     // 执行阶段
    EVALUATING,    // 评估阶段
    COMPLETED,     // 完成
    FAILED         // 失败
}

/**
 * 观察结果
 */
data class ObservationResult(
    val existingKnowledge: List<PuzzleSummary>,  // 现有相关知识
    val gaps: List<KnowledgeGap>,               // 知识空白
    val opportunities: List<String>             // 机会点
)

/**
 * 知识空白
 */
data class KnowledgeGap(
    val description: String,
    val relatedPuzzleIds: List<String>,
    val priority: Double
)

/**
 * Puzzle 摘要
 */
data class PuzzleSummary(
    val id: String,
    val title: String,
    val tags: List<String>,
    val lastUpdated: Instant
)

/**
 * 审查结果
 */
data class ReviewResult(
    val approved: Boolean,              // 是否通过审查
    val risks: List<String>,            // 识别的风险
    val conflicts: List<Conflict>,      // 识别的冲突
    val suggestions: List<String>        // 改进建议
)

/**
 * 进化结果
 */
data class EvolutionResult(
    val status: IterationStatus,
    val iterationId: String,
    val hypothesis: String?,
    val evaluation: Evaluation?,
    val puzzlesCreated: Int,
    val reason: String? = null
) {
    companion object {
        fun success(
            iterationId: String,
            hypothesis: String,
            evaluation: Evaluation,
            puzzlesCreated: Int
        ): EvolutionResult {
            return EvolutionResult(
                status = IterationStatus.COMPLETED,
                iterationId = iterationId,
                hypothesis = hypothesis,
                evaluation = evaluation,
                puzzlesCreated = puzzlesCreated
            )
        }

        fun failed(
            iterationId: String,
            reason: String
        ): EvolutionResult {
            return EvolutionResult(
                status = IterationStatus.FAILED,
                iterationId = iterationId,
                hypothesis = null,
                evaluation = null,
                puzzlesCreated = 0,
                reason = reason
            )
        }
    }
}
