package com.smancode.sman.usage.model

import java.time.Instant

/**
 * LLM 使用记录
 *
 * 记录每次 LLM 调用的完整信息，用于后续分析 Skill 效果和学习用户习惯。
 *
 * @property id 记录唯一标识符
 * @property timestamp 调用时间
 * @property userQuery 用户问题
 * @property llmResponse LLM 响应（截取前 500 字符）
 * @property skillsUsed 使用的 Skill 列表（空=未调用 Skill）
 * @property editCount 用户修改次数
 * @property accepted 是否最终接受
 * @property responseTimeMs 响应时间（毫秒）
 * @property tokenCount Token 消耗
 */
data class SkillUsageRecord(
    val id: String,
    val timestamp: Instant,
    val userQuery: String,
    val llmResponse: String,
    val skillsUsed: List<String> = emptyList(),
    val editCount: Int = 0,
    val accepted: Boolean = false,
    val responseTimeMs: Long = 0,
    val tokenCount: Int = 0
) {
    /**
     * 判断是否调用了 Skill
     */
    fun hasSkillUsage(): Boolean = skillsUsed.isNotEmpty()

    companion object {
        /**
         * 响应内容最大保留长度
         */
        const val MAX_RESPONSE_LENGTH = 500
    }
}

/**
 * 用户行为枚举（用于分析）
 */
enum class UserAction {
    /** 直接接受（无修改） */
    ACCEPTED_DIRECTLY,
    /** 修改后接受 */
    ACCEPTED_WITH_EDITS,
    /** 拒绝/重试 */
    REJECTED,
    /** 点赞 */
    POSITIVE_FEEDBACK,
    /** 点踩 */
    NEGATIVE_FEEDBACK
}

/**
 * 用户习惯模式
 *
 * 从未调用 Skill 的记录中学习到的用户偏好。
 *
 * @property patternType 模式类型（如 "偏好简洁回复"、"常用代码风格"）
 * @property examples 示例列表
 * @property confidence 置信度（0.0-1.0）
 * @property occurrenceCount 出现次数
 */
data class UserPattern(
    val patternType: String,
    val examples: List<String>,
    val confidence: Double,
    val occurrenceCount: Int
)

/**
 * Puzzle 质量更新
 *
 * 根据使用反馈计算出的 Puzzle 质量变化。
 *
 * @property puzzleId Puzzle ID
 * @property skillName 关联的 Skill 名称
 * @property confidenceDelta 置信度变化（正/负）
 * @property reason 变化原因
 * @property timestamp 更新时间
 */
data class PuzzleQualityUpdate(
    val puzzleId: String,
    val skillName: String,
    val confidenceDelta: Double,
    val reason: String,
    val timestamp: Instant = Instant.now()
)

/**
 * Skill 效果统计
 *
 * 单个 Skill 的使用效果汇总。
 *
 * @property skillName Skill 名称
 * @property totalUsage 总使用次数
 * @property acceptedCount 接受次数
 * @property avgEditCount 平均修改次数
 * @property avgResponseTimeMs 平均响应时间
 * @property effectiveness 效果得分（0.0-1.0）
 */
data class SkillEffectiveness(
    val skillName: String,
    val totalUsage: Int,
    val acceptedCount: Int,
    val avgEditCount: Double,
    val avgResponseTimeMs: Double,
    val effectiveness: Double
) {
    /**
     * 接受率
     */
    val acceptanceRate: Double
        get() = if (totalUsage > 0) acceptedCount.toDouble() / totalUsage else 0.0
}

/**
 * 使用分析结果
 *
 * 定时分析任务的输出结果。
 *
 * @property jobId 关联的任务 ID
 * @property analyzedAt 分析时间
 * @property skillEffectiveness Skill 效果统计
 * @property userPatterns 用户习惯模式
 * @property puzzleUpdates 需要更新的 Puzzle
 * @property newGaps 发现的知识空白（预留）
 * @property totalRecordsAnalyzed 分析的总记录数
 */
data class UsageAnalysisResult(
    val jobId: String,
    val analyzedAt: Instant,
    val skillEffectiveness: Map<String, SkillEffectiveness>,
    val userPatterns: List<UserPattern>,
    val puzzleUpdates: List<PuzzleQualityUpdate>,
    val newGaps: List<String> = emptyList(),
    val totalRecordsAnalyzed: Int
)
