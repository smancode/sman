package com.smancode.sman.evolution.guard

import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap

/**
 * 每日配额
 * 存储项目的每日使用计数
 */
data class DailyQuota(
    var questionsToday: Int,
    var explorationsToday: Int,
    var lastResetDate: String
) {
    /**
     * 重置配额
     */
    fun reset(date: String) {
        questionsToday = 0
        explorationsToday = 0
        lastResetDate = date
    }
}

/**
 * 配额信息
 * 返回给调用方的剩余配额信息
 */
data class QuotaInfo(
    val questionsRemaining: Int,
    val explorationsRemaining: Int,
    val resetsAt: Long
)

/**
 * 每日配额管理器
 * Layer 5 防护：限制每日最大操作数，防止死循环消耗过多资源
 *
 * @param maxQuestionsPerDay 每天最大问题生成数，默认 50
 * @param maxExplorationsPerDay 每天最大探索次数，默认 100
 */
class DailyQuotaManager(
    private val maxQuestionsPerDay: Int = DEFAULT_MAX_QUESTIONS_PER_DAY,
    private val maxExplorationsPerDay: Int = DEFAULT_MAX_EXPLORATIONS_PER_DAY
) {
    // 项目 -> 配额状态
    private val quotas = ConcurrentHashMap<String, DailyQuota>()

    /**
     * 检查是否可以生成问题
     *
     * @param projectKey 项目标识
     * @return true 表示还有配额，false 表示已达上限
     */
    fun canGenerateQuestion(projectKey: String): Boolean {
        if (projectKey.isEmpty()) {
            throw IllegalArgumentException("缺少 projectKey 参数")
        }
        val quota = getOrCreateQuota(projectKey)
        checkAndResetIfNeeded(quota)
        return quota.questionsToday < maxQuestionsPerDay
    }

    /**
     * 检查是否可以进行探索
     *
     * @param projectKey 项目标识
     * @return true 表示还有配额，false 表示已达上限
     */
    fun canExplore(projectKey: String): Boolean {
        if (projectKey.isEmpty()) {
            throw IllegalArgumentException("缺少 projectKey 参数")
        }
        val quota = getOrCreateQuota(projectKey)
        checkAndResetIfNeeded(quota)
        return quota.explorationsToday < maxExplorationsPerDay
    }

    /**
     * 记录问题生成
     *
     * @param projectKey 项目标识
     */
    fun recordQuestionGenerated(projectKey: String) {
        if (projectKey.isEmpty()) {
            throw IllegalArgumentException("缺少 projectKey 参数")
        }
        quotas.compute(projectKey) { _, quota ->
            val current = quota ?: createNewQuota()
            current.copy(questionsToday = current.questionsToday + 1)
        }
    }

    /**
     * 记录探索
     *
     * @param projectKey 项目标识
     */
    fun recordExploration(projectKey: String) {
        if (projectKey.isEmpty()) {
            throw IllegalArgumentException("缺少 projectKey 参数")
        }
        quotas.compute(projectKey) { _, quota ->
            val current = quota ?: createNewQuota()
            current.copy(explorationsToday = current.explorationsToday + 1)
        }
    }

    /**
     * 获取剩余配额信息
     *
     * @param projectKey 项目标识
     * @return 配额信息，包含剩余问题数、剩余探索数和重置时间
     */
    fun getRemainingQuota(projectKey: String): QuotaInfo {
        if (projectKey.isEmpty()) {
            throw IllegalArgumentException("缺少 projectKey 参数")
        }
        val quota = getOrCreateQuota(projectKey)
        checkAndResetIfNeeded(quota)
        return QuotaInfo(
            questionsRemaining = maxQuestionsPerDay - quota.questionsToday,
            explorationsRemaining = maxExplorationsPerDay - quota.explorationsToday,
            resetsAt = getNextResetTime()
        )
    }

    /**
     * 检查并重置配额 (每天 00:00)
     */
    private fun checkAndResetIfNeeded(quota: DailyQuota) {
        val today = LocalDate.now().toString()
        if (quota.lastResetDate != today) {
            quota.reset(today)
        }
    }

    private fun getOrCreateQuota(projectKey: String): DailyQuota {
        return quotas.computeIfAbsent(projectKey) { createNewQuota() }
    }

    private fun createNewQuota(): DailyQuota {
        return DailyQuota(
            questionsToday = 0,
            explorationsToday = 0,
            lastResetDate = LocalDate.now().toString()
        )
    }

    /**
     * 获取下次重置时间 (明天 00:00 的时间戳)
     */
    private fun getNextResetTime(): Long {
        val tomorrow = LocalDate.now().plusDays(1)
        return tomorrow.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }

    companion object {
        const val DEFAULT_MAX_QUESTIONS_PER_DAY = 50
        const val DEFAULT_MAX_EXPLORATIONS_PER_DAY = 100
    }
}
