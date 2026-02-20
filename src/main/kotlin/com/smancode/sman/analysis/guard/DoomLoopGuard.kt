package com.smancode.sman.analysis.guard

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
 * 死循环防护器 - 分析模块专用
 *
 * 简化版 DoomLoopGuard，专为 analysis 模块设计。
 * 不依赖持久化，所有状态仅在内存中维护。
 *
 * 防护层级：
 * - Layer 2: 工具调用去重 (ToolCallDeduplicator)
 * - Layer 4: 指数退避 (BackoffStateManager)
 *
 * @param toolCallDeduplicator 工具调用去重器
 * @param backoffStateManager 退避状态管理器
 */
class DoomLoopGuard(
    private val toolCallDeduplicator: ToolCallDeduplicator = ToolCallDeduplicator(),
    private val backoffStateManager: BackoffStateManager = BackoffStateManager()
) {
    private val logger = LoggerFactory.getLogger(DoomLoopGuard::class.java)

    /**
     * 检查是否应该跳过问题处理
     *
     * 检查是否在退避期。
     *
     * @param projectKey 项目标识
     * @return 检查结果，包含是否跳过及原因
     * @throws IllegalArgumentException 如果 projectKey 为空
     */
    fun shouldSkipQuestion(projectKey: String): DoomLoopCheckResult {
        if (projectKey.isEmpty()) {
            throw IllegalArgumentException("缺少 projectKey 参数")
        }

        // 检查退避期
        if (backoffStateManager.isInBackoff(projectKey)) {
            val remainingBackoff = backoffStateManager.getRemainingBackoff(projectKey)
            return DoomLoopCheckResult.skip(
                reason = "在退避期中",
                remainingBackoff = remainingBackoff
            )
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
     * 重置退避状态。
     *
     * @param projectKey 项目标识
     * @throws IllegalArgumentException 如果 projectKey 为空
     */
    fun recordSuccess(projectKey: String) {
        if (projectKey.isEmpty()) {
            throw IllegalArgumentException("缺少 projectKey 参数")
        }

        backoffStateManager.recordSuccess(projectKey)
        logger.debug("记录成功: projectKey={}", projectKey)
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
        val state = backoffStateManager.getState(projectKey)
        logger.warn("记录失败: projectKey={}, consecutiveErrors={}", projectKey, state.consecutiveErrors)
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
        logger.debug("记录工具调用: toolName={}", toolName)
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
     * 清除所有状态
     */
    fun clear() {
        toolCallDeduplicator.clear()
        backoffStateManager.clearAllStates()
        logger.info("清除所有 DoomLoopGuard 状态")
    }

    companion object {
        /**
         * 创建默认配置的 DoomLoopGuard 实例
         */
        fun createDefault(): DoomLoopGuard {
            return DoomLoopGuard()
        }
    }
}
