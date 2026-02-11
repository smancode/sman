package com.smancode.sman.smancode.llm.config

/**
 * 单个 LLM 端点配置
 */
class LlmEndpoint {

    /**
     * API 基础 URL
     */
    var baseUrl: String? = null

    /**
     * API 密钥
     */
    var apiKey: String? = null

    /**
     * 模型名称
     */
    var model: String? = null

    /**
     * 最大 token 数
     */
    var maxTokens: Int = 8192

    /**
     * 超时时间（毫秒）
     */
    var timeout: Long = 60000

    /**
     * 是否启用
     */
    var isEnabled: Boolean = true

    /**
     * 当前是否可用（用于故障检测）
     */
    @Volatile
    var isAvailable: Boolean = true

    /**
     * 上次失败时间（用于冷却）
     */
    @Volatile
    var lastFailureTime: Long = 0

    /**
     * 标记端点失败
     */
    fun markFailed() {
        this.isAvailable = false
        this.lastFailureTime = System.currentTimeMillis()
    }

    /**
     * 标记端点成功
     */
    fun markSuccess() {
        this.isAvailable = true
        this.lastFailureTime = 0
    }

    /**
     * 检查端点是否已过冷却期
     *
     * @param cooldownMs 冷却时间（毫秒）
     * @return 是否可重试
     */
    fun isCooldownOver(cooldownMs: Long): Boolean {
        if (isAvailable) {
            return true
        }
        return System.currentTimeMillis() - lastFailureTime >= cooldownMs
    }

    override fun toString(): String {
        return "LlmEndpoint{" +
                "baseUrl='" + baseUrl + '\'' +
                ", model='" + model + '\'' +
                ", enabled=" + isEnabled +
                ", available=" + isAvailable +
                '}'
    }
}
