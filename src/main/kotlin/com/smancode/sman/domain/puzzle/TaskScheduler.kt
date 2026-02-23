package com.smancode.sman.domain.puzzle

/**
 * 任务调度器
 *
 * 负责 Gap 优先级计算、排序和任务选择
 */
class TaskScheduler {

    /**
     * 计算 Gap 的优先级
     *
     * @param gap 要计算的空白
     * @param context 调度上下文
     * @return 优先级分数（0.0-1.0）
     * @throws IllegalArgumentException 如果 gap 为 null
     */
    fun calculatePriority(gap: Gap, context: SchedulingContext): Double {
        requireNotNull(gap) { "gap 不能为 null" }

        // 基础优先级
        var score = gap.priority

        // ROI 因子：与最近查询的关联度
        val roiBonus = calculateRoiBonus(gap, context)
        score += roiBonus

        // 新鲜度因子：与最近变更文件的关联度
        val freshnessBonus = calculateFreshnessBonus(gap, context)
        score += freshnessBonus

        // 归一化到 0-1 范围
        return score.coerceIn(0.0, 1.0)
    }

    /**
     * 对 Gap 列表按优先级排序
     *
     * @param gaps 要排序的空白列表
     * @return 按优先级降序排列的列表
     */
    fun prioritize(gaps: List<Gap>): List<Gap> {
        return gaps.sortedByDescending { it.priority }
    }

    /**
     * 选择下一个要执行的任务
     *
     * @param gaps 候选空白列表
     * @param budget 可用的 Token 预算
     * @return 选中的 Gap，如果预算不足则返回 null
     */
    fun selectNext(gaps: List<Gap>, budget: TokenBudget): Gap? {
        if (!budget.isAvailable()) {
            return null
        }

        val sorted = prioritize(gaps)
        return sorted.firstOrNull()
    }

    /**
     * 计算 ROI 奖励分
     *
     * 基于 Gap 相关文件与最近查询的关联度
     */
    private fun calculateRoiBonus(gap: Gap, context: SchedulingContext): Double {
        if (gap.relatedFiles.isEmpty() || context.recentQueries.isEmpty()) {
            return 0.0
        }

        // 检查相关文件是否出现在最近的查询中
        val queryText = context.recentQueries.joinToString(" ").lowercase()
        val matchedFiles = gap.relatedFiles.count { file ->
            val fileName = file.substringAfterLast("/").lowercase()
            queryText.contains(fileName.substringBefore("."))
        }

        // 每匹配一个文件增加 0.1 分，最多 0.3
        return (matchedFiles * 0.1).coerceAtMost(0.3)
    }

    /**
     * 计算新鲜度奖励分
     *
     * 基于 Gap 相关文件是否为最近变更的文件
     */
    private fun calculateFreshnessBonus(gap: Gap, context: SchedulingContext): Double {
        if (gap.relatedFiles.isEmpty() || context.recentFileChanges.isEmpty()) {
            return 0.0
        }

        // 检查相关文件是否在最近变更列表中
        val matchedFiles = gap.relatedFiles.intersect(context.recentFileChanges.toSet()).size

        // 每匹配一个文件增加 0.15 分，最多 0.3
        return (matchedFiles * 0.15).coerceAtMost(0.3)
    }
}
