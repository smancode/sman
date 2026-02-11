package com.smancode.sman.smancode.llm.config

import java.util.concurrent.atomic.AtomicInteger

/**
 * LLM 端点池配置
 */
class LlmPoolConfig {

    /**
     * 端点列表
     */
    var endpoints: MutableList<LlmEndpoint> = mutableListOf()

    /**
     * 重试策略
     */
    var retry: LlmRetryPolicy = LlmRetryPolicy()

    /**
     * Round-Robin 轮询索引
     */
    private val roundRobinIndex = AtomicInteger(0)

    /**
     * 获取下一个可用端点（Round-Robin + 故障过滤）
     *
     * @return 可用端点，如果全部不可用返回 null
     */
    fun getNextAvailableEndpoint(): LlmEndpoint? {
        val enabledEndpoints = endpoints.filter { it.isEnabled }

        if (enabledEndpoints.isEmpty()) {
            return null
        }

        var attempts = 0
        val totalEndpoints = enabledEndpoints.size

        while (attempts < totalEndpoints) {
            val index = Math.abs(roundRobinIndex.getAndIncrement() % totalEndpoints)
            val endpoint = enabledEndpoints[index]

            // 情况 1：端点可用，直接返回
            if (endpoint.isAvailable) {
                return endpoint
            }

            // 情况 2：端点不可用，检查冷却期
            val cooldownMs = retry.baseDelay
            if (endpoint.isCooldownOver(cooldownMs)) {
                // 冷却期已过，重置可用状态并返回
                endpoint.markSuccess()
                return endpoint
            }

            // 情况 3：冷却期未过，尝试下一个端点
            attempts++
        }

        // 所有端点都在冷却期，强制重置第一个端点（避免级联失败）
        val firstEndpoint = enabledEndpoints[0]
        firstEndpoint.markSuccess()
        return firstEndpoint
    }

    /**
     * 获取所有启用的端点
     */
    fun getEnabledEndpoints(): List<LlmEndpoint> = endpoints.filter { it.isEnabled }

    /**
     * 标记所有端点为可用（用于服务恢复）
     */
    fun resetAllEndpoints() {
        endpoints.forEach { it.markSuccess() }
    }

    // ========== 属性访问方式（兼容 Java 风格调用） ==========

    /**
     * 下一个可用端点（属性访问方式）
     */
    val nextAvailableEndpoint: LlmEndpoint?
        @JvmName("getNextAvailableEndpointProp")
        get() = getNextAvailableEndpoint()
}
