package com.smancode.sman.evolution.guard

import com.smancode.sman.evolution.persistence.BackoffStateEntity
import com.smancode.sman.evolution.persistence.DailyQuotaEntity
import com.smancode.sman.evolution.persistence.EvolutionStateRepository
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory

/**
 * 死循环检查结果
 *
 * @param shouldSkip 是否应该跳过该操作
 * @param reason 跳过原因，如果 shouldSkip 为 false 则为 null
 * @param remainingBackoff 剩余退避时间（毫秒），仅在退避期时有值
 * @param cachedResult 缓存的结果，仅在工具调用去重时有值
 */
data class DoomLoopCheckResult(
    val shouldSkip: Boolean,
    val reason: String? = null,
    val remainingBackoff: Long? = null,
    val cachedResult: Any? = null
) {
    companion object {
        /**
         * 创建"通过检查"的结果
         */
        fun pass(): DoomLoopCheckResult = DoomLoopCheckResult(shouldSkip = false)

        /**
         * 创建"跳过"的结果
         */
        fun skip(reason: String, remainingBackoff: Long? = null, cachedResult: Any? = null): DoomLoopCheckResult =
            DoomLoopCheckResult(
                shouldSkip = true,
                reason = reason,
                remainingBackoff = remainingBackoff,
                cachedResult = cachedResult
            )
    }
}

/**
 * 死循环防护器 - 统一入口
 *
 * 组合多个防护组件，提供统一的死循环检测和防护接口。
 * 这是 Doom Loop 防护机制的统一入口点。
 *
 * 防护层级：
 * - Layer 2: 工具调用去重 (ToolCallDeduplicator)
 * - Layer 4: 指数退避 (BackoffStateManager)
 * - Layer 5: 每日配额 (DailyQuotaManager)
 *
 * @param toolCallDeduplicator 工具调用去重器
 * @param backoffStateManager 退避状态管理器
 * @param dailyQuotaManager 每日配额管理器
 * @param stateRepository 状态仓储（可选，用于断点续传）
 */
class DoomLoopGuard(
    private val toolCallDeduplicator: ToolCallDeduplicator,
    private val backoffStateManager: BackoffStateManager,
    private val dailyQuotaManager: DailyQuotaManager,
    private val stateRepository: EvolutionStateRepository? = null
) {
    private val logger = LoggerFactory.getLogger(DoomLoopGuard::class.java)

    /**
     * 从数据库恢复状态
     *
     * 初始化时调用，从数据库加载退避状态和配额状态
     */
    fun restoreState(projectKey: String) {
        if (stateRepository == null) {
            logger.debug("stateRepository 未配置，跳过状态恢复")
            return
        }

        try {
            // 恢复退避状态（使用 runBlocking 因为这是同步初始化）
            val backoffState = runBlocking {
                stateRepository.getBackoffState(projectKey)
            }
            if (backoffState != null) {
                backoffStateManager.restoreState(projectKey, backoffState)
                logger.info("恢复退避状态: projectKey={}, errors={}, backoffUntil={}",
                    projectKey, backoffState.consecutiveErrors, backoffState.backoffUntil)
            }

            // 恢复配额状态
            val dailyQuota = runBlocking {
                stateRepository.getDailyQuota(projectKey)
            }
            if (dailyQuota != null) {
                dailyQuotaManager.restoreQuota(projectKey, dailyQuota)
                logger.info("恢复配额状态: projectKey={}, questions={}, explorations={}",
                    projectKey, dailyQuota.questionsToday, dailyQuota.explorationsToday)
            }
        } catch (e: Exception) {
            logger.error("恢复状态失败: projectKey={}", projectKey, e)
        }
    }

    /**
     * 检查是否应该跳过问题处理
     *
     * 检查顺序：
     * 1. 检查退避期 - 如果在退避期，直接返回跳过
     * 2. 检查配额 - 如果已达配额上限，返回跳过
     *
     * @param projectKey 项目标识
     * @return 检查结果，包含是否跳过及原因
     * @throws IllegalArgumentException 如果 projectKey 为空
     */
    fun shouldSkipQuestion(projectKey: String): DoomLoopCheckResult {
        if (projectKey.isEmpty()) {
            throw IllegalArgumentException("缺少 projectKey 参数")
        }

        // 1. 检查退避期
        if (backoffStateManager.isInBackoff(projectKey)) {
            val remainingBackoff = backoffStateManager.getRemainingBackoff(projectKey)
            return DoomLoopCheckResult.skip(
                reason = "在退避期中",
                remainingBackoff = remainingBackoff
            )
        }

        // 2. 检查配额
        if (!dailyQuotaManager.canGenerateQuestion(projectKey)) {
            return DoomLoopCheckResult.skip(reason = "已达每日配额")
        }

        return DoomLoopCheckResult.pass()
    }

    /**
     * 检查是否应该跳过工具调用
     *
     * 检查工具调用是否为重复调用，如果是，返回缓存结果。
     *
     * @param toolName 工具名称
     * @param parameters 工具参数
     * @return 检查结果，如果为重复调用则包含缓存结果
     * @throws IllegalArgumentException 如果 toolName 为空
     */
    fun shouldSkipToolCall(toolName: String, parameters: Map<String, Any?>): DoomLoopCheckResult {
        if (toolName.isEmpty()) {
            throw IllegalArgumentException("缺少 toolName 参数")
        }

        if (toolCallDeduplicator.isDuplicate(toolName, parameters)) {
            val cachedResult = toolCallDeduplicator.getCachedResult(toolName, parameters)
            return DoomLoopCheckResult.skip(
                reason = "工具调用已执行过",
                cachedResult = cachedResult
            )
        }

        return DoomLoopCheckResult.pass()
    }

    /**
     * 记录操作成功
     *
     * 重置退避状态，并记录问题生成。
     *
     * @param projectKey 项目标识
     * @throws IllegalArgumentException 如果 projectKey 为空
     */
    fun recordSuccess(projectKey: String) {
        if (projectKey.isEmpty()) {
            throw IllegalArgumentException("缺少 projectKey 参数")
        }

        backoffStateManager.recordSuccess(projectKey)
        dailyQuotaManager.recordQuestionGenerated(projectKey)

        // 持久化状态
        persistState(projectKey)
    }

    /**
     * 记录操作失败
     *
     * 触发退避机制，增加连续错误计数。
     *
     * @param projectKey 项目标识
     * @throws IllegalArgumentException 如果 projectKey 为空
     */
    fun recordFailure(projectKey: String) {
        if (projectKey.isEmpty()) {
            throw IllegalArgumentException("缺少 projectKey 参数")
        }

        backoffStateManager.recordError(projectKey)

        // 持久化状态
        persistState(projectKey)
    }

    /**
     * 记录工具调用
     *
     * 将工具调用结果缓存，用于后续去重判断。
     *
     * @param toolName 工具名称
     * @param parameters 工具参数
     * @param result 调用结果
     * @throws IllegalArgumentException 如果 toolName 为空
     */
    fun recordToolCall(toolName: String, parameters: Map<String, Any?>, result: Any?) {
        if (toolName.isEmpty()) {
            throw IllegalArgumentException("缺少 toolName 参数")
        }

        toolCallDeduplicator.recordCall(toolName, parameters, result)
    }

    /**
     * 获取剩余配额信息
     *
     * @param projectKey 项目标识
     * @return 配额信息
     * @throws IllegalArgumentException 如果 projectKey 为空
     */
    fun getRemainingQuota(projectKey: String): QuotaInfo {
        if (projectKey.isEmpty()) {
            throw IllegalArgumentException("缺少 projectKey 参数")
        }

        return dailyQuotaManager.getRemainingQuota(projectKey)
    }

    /**
     * 获取退避状态
     *
     * @param projectKey 项目标识
     * @return 退避状态
     * @throws IllegalArgumentException 如果 projectKey 为空
     */
    fun getBackoffState(projectKey: String): BackoffState {
        if (projectKey.isEmpty()) {
            throw IllegalArgumentException("缺少 projectKey 参数")
        }

        return backoffStateManager.getState(projectKey)
    }

    /**
     * 持久化状态到数据库
     */
    private fun persistState(projectKey: String) {
        if (stateRepository == null) {
            return
        }

        try {
            // 持久化退避状态
            val backoffState = backoffStateManager.getState(projectKey)
            val backoffEntity = BackoffStateEntity(
                projectKey = projectKey,
                consecutiveErrors = backoffState.consecutiveErrors,
                lastErrorTime = backoffState.lastErrorTime,
                backoffUntil = backoffState.backoffUntil
            )

            // 持久化配额状态
            val quota = dailyQuotaManager.getQuota(projectKey)
            val quotaEntity = if (quota != null) {
                DailyQuotaEntity(
                    projectKey = projectKey,
                    questionsToday = quota.questionsToday,
                    explorationsToday = quota.explorationsToday,
                    lastResetDate = quota.lastResetDate
                )
            } else {
                null
            }

            // 异步持久化（不阻塞主流程）
            kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    stateRepository.saveBackoffState(backoffEntity)
                    quotaEntity?.let { stateRepository.saveDailyQuota(it) }
                } catch (e: Exception) {
                    logger.error("持久化状态失败: projectKey={}", projectKey, e)
                }
            }
        } catch (e: Exception) {
            logger.error("持久化状态失败: projectKey={}", projectKey, e)
        }
    }

    companion object {
        /**
         * 创建默认配置的 DoomLoopGuard 实例
         */
        fun createDefault(): DoomLoopGuard {
            return DoomLoopGuard(
                toolCallDeduplicator = ToolCallDeduplicator(),
                backoffStateManager = BackoffStateManager(),
                dailyQuotaManager = DailyQuotaManager()
            )
        }

        /**
         * 创建带状态仓储的 DoomLoopGuard 实例
         */
        fun createWithStateRepository(
            stateRepository: EvolutionStateRepository
        ): DoomLoopGuard {
            return DoomLoopGuard(
                toolCallDeduplicator = ToolCallDeduplicator(),
                backoffStateManager = BackoffStateManager(),
                dailyQuotaManager = DailyQuotaManager(),
                stateRepository = stateRepository
            )
        }
    }
}
