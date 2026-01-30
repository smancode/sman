package com.smancode.smanagent.analysis.config

/**
 * BGE-M3 配置
 *
 * @property endpoint BGE-M3 服务端点
 * @property modelName 模型名称
 * @property dimension 向量维度
 * @property timeoutSeconds 超时时间（秒）
 * @property batchSize 批处理大小
 */
data class BgeM3Config(
    val endpoint: String,
    val modelName: String = "BAAI/bge-m3",
    val dimension: Int = 1024,
    val timeoutSeconds: Int = 30,
    val batchSize: Int = 10
) {
    init {
        require(endpoint.isNotBlank()) { "endpoint must not be blank" }
        require(dimension > 0) { "dimension must be positive" }
        require(timeoutSeconds > 0) { "timeoutSeconds must be positive" }
        require(batchSize > 0) { "batchSize must be positive" }
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
 */
data class RerankerConfig(
    val enabled: Boolean = true,
    val baseUrl: String = "http://localhost:8001/v1",
    val model: String = "BAAI/bge-reranker-v2-m3",
    val apiKey: String = "",
    val timeoutSeconds: Int = 30,
    val retry: Int = 2,
    val maxRounds: Int = 3,
    val topK: Int = 15
) {
    init {
        require(baseUrl.isNotBlank()) { "baseUrl must not be blank" }
        require(timeoutSeconds > 0) { "timeoutSeconds must be positive" }
        require(retry >= 0) { "retry must be non-negative" }
        require(maxRounds > 0) { "maxRounds must be positive" }
        require(topK > 0) { "topK must be positive" }
    }
}
