package com.smancode.sman.usage.analyzer

import com.smancode.sman.usage.model.*
import com.smancode.sman.usage.store.SkillUsageStore
import java.time.Instant

/**
 * 使用记录分析器
 *
 * 分析 LLM 使用记录，区分两种场景：
 * 1. 调用了 Skill → 评估 Skill 效果
 * 2. 未调用 Skill → 学习用户习惯
 */
class UsageAnalyzer(
    private val usageStore: SkillUsageStore
) {
    /**
     * 分析指定范围内的记录
     *
     * @param records 使用记录列表
     * @param jobId 关联的任务 ID
     * @return 分析结果
     */
    fun analyze(records: List<SkillUsageRecord>, jobId: String): UsageAnalysisResult {
        if (records.isEmpty()) {
            return UsageAnalysisResult(
                jobId = jobId,
                analyzedAt = Instant.now(),
                skillEffectiveness = emptyMap(),
                userPatterns = emptyList(),
                puzzleUpdates = emptyList(),
                totalRecordsAnalyzed = 0
            )
        }

        // 分离两类记录
        val (withSkill, withoutSkill) = records.partition { it.hasSkillUsage() }

        // 分析 Skill 效果
        val skillEffectiveness = analyzeSkillEffectiveness(withSkill)

        // 学习用户习惯
        val userPatterns = learnUserPatterns(withoutSkill)

        // 计算 Puzzle 质量更新
        val puzzleUpdates = calculatePuzzleUpdates(skillEffectiveness)

        return UsageAnalysisResult(
            jobId = jobId,
            analyzedAt = Instant.now(),
            skillEffectiveness = skillEffectiveness,
            userPatterns = userPatterns,
            puzzleUpdates = puzzleUpdates,
            totalRecordsAnalyzed = records.size
        )
    }

    /**
     * 分析所有记录
     */
    fun analyzeAll(jobId: String): UsageAnalysisResult {
        return analyze(usageStore.loadAll(), jobId)
    }

    /**
     * 分析指定时间之后的记录
     */
    fun analyzeRecent(since: Instant, jobId: String): UsageAnalysisResult {
        return analyze(usageStore.loadRecent(since), jobId)
    }

    /**
     * 分析 Skill 效果
     *
     * 计算指标：
     * - 接受率：accepted / total
     * - 平均修改次数：avg(editCount)
     * - 效果得分：综合计算
     */
    internal fun analyzeSkillEffectiveness(records: List<SkillUsageRecord>): Map<String, SkillEffectiveness> {
        if (records.isEmpty()) return emptyMap()

        // 按 Skill 分组
        val bySkill = records
            .filter { it.skillsUsed.isNotEmpty() }
            .flatMap { record -> record.skillsUsed.map { skill -> skill to record } }
            .groupBy({ it.first }, { it.second })

        return bySkill.mapValues { (skillName, skillRecords) ->
            val totalUsage = skillRecords.size
            val acceptedCount = skillRecords.count { it.accepted }
            val avgEditCount = skillRecords.map { it.editCount }.average()
            val avgResponseTime = skillRecords.map { it.responseTimeMs }.average()

            // 效果得分计算：
            // - 接受率权重 0.5
            // - 低修改次数权重 0.3（修改越少越好）
            // - 响应时间权重 0.2（越快越好，但有下限）
            val acceptanceScore = if (totalUsage > 0) acceptedCount.toDouble() / totalUsage else 0.0
            val editScore = maxOf(0.0, 1.0 - avgEditCount / 5.0) // 5 次修改为 0 分
            val responseScore = calculateResponseScore(avgResponseTime)

            val effectiveness = acceptanceScore * 0.5 + editScore * 0.3 + responseScore * 0.2

            SkillEffectiveness(
                skillName = skillName,
                totalUsage = totalUsage,
                acceptedCount = acceptedCount,
                avgEditCount = avgEditCount,
                avgResponseTimeMs = avgResponseTime,
                effectiveness = effectiveness
            )
        }
    }

    /**
     * 学习用户习惯（从未调用 Skill 的记录）
     *
     * 识别模式：
     * - 偏好简洁回复
     * - 常用代码风格
     * - 高频问题类型
     */
    internal fun learnUserPatterns(records: List<SkillUsageRecord>): List<UserPattern> {
        if (records.isEmpty()) return emptyList()

        val patterns = mutableListOf<UserPattern>()

        // 1. 分析接受率与编辑次数的关系（简洁偏好）
        val avgEditForAccepted = records
            .filter { it.accepted }
            .map { it.editCount }
            .average()

        if (avgEditForAccepted < 1.0) {
            patterns.add(
                UserPattern(
                    patternType = "偏好简洁回复",
                    examples = records.filter { it.accepted && it.editCount == 0 }
                        .take(3)
                        .map { it.userQuery.take(50) },
                    confidence = minOf(1.0, (1.0 - avgEditForAccepted) * 0.8),
                    occurrenceCount = records.count { it.accepted && it.editCount == 0 }
                )
            )
        }

        // 2. 分析高频问题类型
        val queryPatterns = extractQueryPatterns(records)
        patterns.addAll(queryPatterns)

        // 3. 分析响应时间偏好
        val fastAccepted = records.filter { it.accepted && it.responseTimeMs < 3000 }
        if (fastAccepted.size > records.size * 0.6) {
            patterns.add(
                UserPattern(
                    patternType = "偏好快速响应",
                    examples = fastAccepted.take(3).map { "响应时间: ${it.responseTimeMs}ms" },
                    confidence = 0.7,
                    occurrenceCount = fastAccepted.size
                )
            )
        }

        return patterns
    }

    /**
     * 计算 Puzzle 质量更新
     *
     * 根据 Skill 效果反向修正 Puzzle 置信度：
     * - 效果好 → 提升 confidence
     * - 效果差 → 降低 confidence
     */
    internal fun calculatePuzzleUpdates(
        skillEffectiveness: Map<String, SkillEffectiveness>
    ): List<PuzzleQualityUpdate> {
        val updates = mutableListOf<PuzzleQualityUpdate>()

        for ((skillName, effectiveness) in skillEffectiveness) {
            // 需要至少 3 次使用才进行评估
            if (effectiveness.totalUsage < 3) continue

            val puzzleId = skillToPuzzleId(skillName)
            val confidenceDelta = calculateConfidenceDelta(effectiveness)

            // 只记录有意义的变动
            if (kotlin.math.abs(confidenceDelta) >= 0.05) {
                updates.add(
                    PuzzleQualityUpdate(
                        puzzleId = puzzleId,
                        skillName = skillName,
                        confidenceDelta = confidenceDelta,
                        reason = buildUpdateReason(effectiveness),
                        timestamp = Instant.now()
                    )
                )
            }
        }

        return updates
    }

    /**
     * 响应时间得分计算
     */
    private fun calculateResponseScore(avgResponseTimeMs: Double): Double {
        return when {
            avgResponseTimeMs < 1000 -> 1.0
            avgResponseTimeMs < 3000 -> 0.8
            avgResponseTimeMs < 5000 -> 0.6
            avgResponseTimeMs < 10000 -> 0.4
            else -> 0.2
        }
    }

    /**
     * 提取问题模式
     */
    private fun extractQueryPatterns(records: List<SkillUsageRecord>): List<UserPattern> {
        val patterns = mutableListOf<UserPattern>()

        // 按关键词分组
        val keywordGroups = mutableMapOf<String, MutableList<SkillUsageRecord>>()

        for (record in records) {
            val keywords = extractKeywords(record.userQuery)
            for (keyword in keywords) {
                keywordGroups.getOrPut(keyword) { mutableListOf() }.add(record)
            }
        }

        // 提取高频模式
        for ((keyword, groupRecords) in keywordGroups) {
            if (groupRecords.size >= 3) {
                val acceptedRate = groupRecords.count { it.accepted }.toDouble() / groupRecords.size
                patterns.add(
                    UserPattern(
                        patternType = "高频问题: $keyword",
                        examples = groupRecords.take(3).map { it.userQuery.take(50) },
                        confidence = acceptedRate,
                        occurrenceCount = groupRecords.size
                    )
                )
            }
        }

        return patterns.sortedByDescending { it.occurrenceCount }.take(5)
    }

    /**
     * 提取关键词（简化实现）
     */
    private fun extractKeywords(query: String): List<String> {
        val keywords = mutableListOf<String>()

        // 检测常见技术关键词
        val techKeywords = listOf(
            "如何", "怎么", "为什么", "什么是",
            "实现", "配置", "部署", "测试",
            "bug", "错误", "异常", "问题",
            "优化", "重构", "添加", "修改"
        )

        for (keyword in techKeywords) {
            if (query.contains(keyword, ignoreCase = true)) {
                keywords.add(keyword)
            }
        }

        return keywords
    }

    /**
     * Skill 名称转 Puzzle ID
     */
    private fun skillToPuzzleId(skillName: String): String {
        // 例如：project-structure → PUZZLE_STRUCTURE
        // 例如：business-flow → PUZZLE_FLOW
        return "PUZZLE_${skillName.replace("-", "_").uppercase()}"
    }

    /**
     * 效果等级定义
     */
    private data class EffectLevel(
        val label: String,
        val confidenceDelta: Double,
        val suffix: String = ""
    )

    private val effectLevels = listOf(
        EffectLevel("效果优秀", 0.1),
        EffectLevel("效果良好", 0.05),
        EffectLevel("效果一般", 0.0),
        EffectLevel("效果较差", -0.05, "，建议检查"),
        EffectLevel("效果很差", -0.1, "，需要重分析")
    )

    /**
     * 计算置信度变化量
     */
    private fun calculateConfidenceDelta(effectiveness: SkillEffectiveness): Double {
        return getEffectLevel(effectiveness.effectiveness).confidenceDelta
    }

    /**
     * 构建更新原因说明
     */
    private fun buildUpdateReason(effectiveness: SkillEffectiveness): String {
        val level = getEffectLevel(effectiveness.effectiveness)
        val effect = effectiveness.effectiveness
        val usage = effectiveness.totalUsage
        val acceptance = effectiveness.acceptanceRate

        return "${level.label}(得分${String.format("%.2f", effect)})，使用${usage}次，接受率${String.format("%.0f%%", acceptance * 100)}${level.suffix}"
    }

    /**
     * 根据效果得分获取等级
     */
    private fun getEffectLevel(effect: Double): EffectLevel {
        return when {
            effect >= 0.8 -> effectLevels[0]
            effect >= 0.6 -> effectLevels[1]
            effect >= 0.4 -> effectLevels[2]
            effect >= 0.2 -> effectLevels[3]
            else -> effectLevels[4]
        }
    }
}
