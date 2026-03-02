package com.smancode.sman.usage.updater

import com.smancode.sman.infra.storage.PuzzleStore
import com.smancode.sman.shared.model.Puzzle
import com.smancode.sman.shared.model.PuzzleStatus
import com.smancode.sman.usage.converter.PuzzleToSkillConverter
import com.smancode.sman.usage.model.PuzzleQualityUpdate
import java.time.Instant

/**
 * Puzzle 质量更新器
 *
 * 根据使用反馈更新 Puzzle 的置信度，并在达到阈值时触发重分析。
 *
 * 更新规则：
 * - 置信度变化累积后，如果低于阈值则标记需要重分析
 * - 置信度上限为 1.0，下限为 0.0
 * - 更新后自动重新生成 Skill
 */
class PuzzleQualityUpdater(
    private val puzzleStore: PuzzleStore,
    private val skillConverter: PuzzleToSkillConverter
) {
    companion object {
        /**
         * 触发重分析的置信度阈值
         */
        const val REANALYSIS_THRESHOLD = 0.3

        /**
         * 单次更新最大变化量（防止大幅波动）
         */
        const val MAX_DELTA = 0.15
    }

    /**
     * 应用单个质量更新
     *
     * @param update 质量更新
     * @return 是否成功
     */
    fun update(update: PuzzleQualityUpdate): Boolean {
        return updateInternal(update)
    }

    /**
     * 批量应用质量更新
     *
     * @param updates 质量更新列表
     * @return 成功更新的数量
     */
    fun updateAll(updates: List<PuzzleQualityUpdate>): Int {
        return updates.count { updateInternal(it) }
    }

    /**
     * 内部更新逻辑
     */
    private fun updateInternal(update: PuzzleQualityUpdate): Boolean {
        // 加载 Puzzle
        val puzzleResult = puzzleStore.load(update.puzzleId)
        val puzzle = puzzleResult.getOrNull() ?: return false

        // 计算新的置信度（限制变化幅度）
        val clampedDelta = update.confidenceDelta.coerceIn(-MAX_DELTA, MAX_DELTA)
        val newConfidence = (puzzle.confidence + clampedDelta).coerceIn(0.0, 1.0)

        // 如果置信度没有变化，跳过
        if (newConfidence == puzzle.confidence) {
            return false
        }

        // 判断是否需要重分析
        val needsReanalysis = newConfidence < REANALYSIS_THRESHOLD && puzzle.status == PuzzleStatus.COMPLETED

        // 更新 Puzzle
        val updatedPuzzle = puzzle.copy(
            confidence = newConfidence,
            lastUpdated = Instant.now(),
            status = if (needsReanalysis) PuzzleStatus.PENDING else puzzle.status
        )

        // 保存
        val saveResult = puzzleStore.save(updatedPuzzle)
        if (saveResult.isFailure) {
            return false
        }

        // 如果完成状态未变，重新生成 Skill
        if (!needsReanalysis && updatedPuzzle.status == PuzzleStatus.COMPLETED) {
            skillConverter.convertAndSave(updatedPuzzle)
        }

        // 如果需要重分析，可以在这里触发（预留扩展点）
        if (needsReanalysis) {
            triggerReanalysis(updatedPuzzle)
        }

        return true
    }

    /**
     * 触发重分析（预留扩展点）
     *
     * 可以在这里集成到调度系统，触发重新分析任务
     */
    private fun triggerReanalysis(puzzle: Puzzle) {
        // TODO: 集成到调度系统
        // 例如：analysisScheduler.scheduleReanalysis(puzzle.id)
    }

    /**
     * 获取需要重分析的 Puzzle 列表
     *
     * @return 需要重分析的 Puzzle ID 列表
     */
    fun getPuzzlesNeedingReanalysis(): List<String> {
        val allPuzzles = puzzleStore.loadAll().getOrNull() ?: return emptyList()

        return allPuzzles
            .filter { it.status == PuzzleStatus.PENDING && it.confidence < REANALYSIS_THRESHOLD }
            .map { it.id }
    }

    /**
     * 手动标记 Puzzle 需要重分析
     *
     * @param puzzleId Puzzle ID
     * @param reason 原因
     * @return 是否成功
     */
    fun markForReanalysis(puzzleId: String, reason: String): Boolean {
        val puzzleResult = puzzleStore.load(puzzleId)
        val puzzle = puzzleResult.getOrNull() ?: return false

        val updatedPuzzle = puzzle.copy(
            status = PuzzleStatus.PENDING,
            confidence = 0.0,
            lastUpdated = Instant.now()
        )

        return puzzleStore.save(updatedPuzzle).isSuccess
    }

    /**
     * 获取 Puzzle 的质量统计
     *
     * @return 质量统计信息
     */
    fun getQualityStats(): QualityStats {
        val allPuzzles = puzzleStore.loadAll().getOrNull() ?: return QualityStats.empty()
        if (allPuzzles.isEmpty()) return QualityStats.empty()

        return QualityStats(
            totalPuzzles = allPuzzles.size,
            averageConfidence = allPuzzles.map { it.confidence }.average(),
            averageCompleteness = allPuzzles.map { it.completeness }.average(),
            needsReanalysis = allPuzzles.count { it.confidence < REANALYSIS_THRESHOLD },
            byStatus = allPuzzles.groupingBy { it.status }.eachCount(),
            byType = allPuzzles.groupingBy { it.type }.eachCount()
        )
    }

    /**
     * 质量统计信息
     */
    data class QualityStats(
        val totalPuzzles: Int,
        val averageConfidence: Double,
        val averageCompleteness: Double,
        val needsReanalysis: Int,
        val byStatus: Map<PuzzleStatus, Int>,
        val byType: Map<com.smancode.sman.shared.model.PuzzleType, Int>
    ) {
        companion object {
            fun empty() = QualityStats(
                totalPuzzles = 0,
                averageConfidence = 0.0,
                averageCompleteness = 0.0,
                needsReanalysis = 0,
                byStatus = emptyMap(),
                byType = emptyMap()
            )
        }
    }
}
