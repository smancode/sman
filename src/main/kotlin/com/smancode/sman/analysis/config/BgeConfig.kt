package com.smancode.sman.analysis.config

/**
 * 截断策略枚举
 */
enum class TruncationStrategy {
    HEAD,       // 保留头部
    TAIL,       // 保留尾部
    MIDDLE,     // 保留头部和尾部
    SMART       // 智能截断（按段落/句子）
}

/**
 * BGE-M3 配置（增强版）
 *
 * @property endpoint BGE-M3 服务端点
 * @property modelName 模型名称
 * @property dimension 向量维度
 * @property timeoutSeconds 超时时间（秒）
 * @property batchSize 批处理大小
 * @property maxTokens 最大 token 数（BGE-M3 限制为 8192）
 * @property truncationStrategy 截断策略
 * @property truncationStepSize 每次截断的字符数
 * @property maxTruncationRetries 最大截断重试次数
 * @property maxRetries 最大重试次数
 * @property baseDelayMs 基础延迟（毫秒）
 * @property concurrentLimit 并发限制
 * @property circuitBreakerThreshold 熔断器阈值
 */
data class BgeM3Config(
    val endpoint: String,
    val modelName: String = "BAAI/bge-m3",
    val dimension: Int = 1024,
    val timeoutSeconds: Int = 30,
    val batchSize: Int = 10,
    // Token 限制配置
    val maxTokens: Int = 8192,
    // 截断配置
    val truncationStrategy: TruncationStrategy = TruncationStrategy.TAIL,
    val truncationStepSize: Int = 1000,
    val maxTruncationRetries: Int = 10,
    // 重试配置
    val maxRetries: Int = 3,
    val baseDelayMs: Long = 1000,
    // 并发配置
    val concurrentLimit: Int = 3,
    // 熔断器配置
    val circuitBreakerThreshold: Int = 5
) {
    init {
        require(endpoint.isNotBlank()) { "endpoint must not be blank" }
        require(dimension > 0) { "dimension must be positive" }
        require(timeoutSeconds > 0) { "timeoutSeconds must be positive" }
        require(batchSize > 0) { "batchSize must be positive" }
        require(maxTokens > 0) { "maxTokens must be positive" }
        require(truncationStepSize > 0) { "truncationStepSize must be positive" }
        require(maxTruncationRetries > 0) { "maxTruncationRetries must be positive" }
        require(maxRetries >= 0) { "maxRetries must be non-negative" }
        require(baseDelayMs > 0) { "baseDelayMs must be positive" }
        require(concurrentLimit > 0) { "concurrentLimit must be positive" }
        require(circuitBreakerThreshold > 0) { "circuitBreakerThreshold must be positive" }
    }
}

/**
 * BGE-Reranker 配置
 *
 * @property enabled 是否启用
 * @property baseUrl 服务地址
 * @property model 模型名称
 * @property apiKey API 密钥
 * @property timeoutSeconds 超时时间（秒）
 * @property retry 重试次数
 * @property maxRounds 最多遍历端点轮数
 * @property topK 返回 top K
 * @property threshold 相似度阈值 (0.0-1.0)，低于此值的结果将被过滤
 */
data class RerankerConfig(
    val enabled: Boolean = true,
    val baseUrl: String = "http://localhost:8001/v1",
    val model: String = "BAAI/bge-reranker-v2-m3",
    val apiKey: String = "",
    val timeoutSeconds: Int = 30,
    val retry: Int = 2,
    val maxRounds: Int = 3,
    val topK: Int = 15,
    val threshold: Double = 0.0
) {
    init {
        require(baseUrl.isNotBlank()) { "baseUrl must not be blank" }
        require(timeoutSeconds > 0) { "timeoutSeconds must be positive" }
        require(retry >= 0) { "retry must be non-negative" }
        require(maxRounds > 0) { "maxRounds must be positive" }
        require(topK > 0) { "topK must be positive" }
        require(threshold in 0.0..1.0) { "threshold must be between 0.0 and 1.0" }
    }
}
