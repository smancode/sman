package com.smancode.smanagent.analysis.config

/**
 * 向量数据库配置
 *
 * @property type 向量数据库类型
 * @property jvector JVector 配置
 * @property basePath 基础路径
 * @property storePath 存储路径
 * @property databasePath H2 数据库路径
 * @property vectorDimension 向量维度
 * @property l1CacheSize L1 缓存大小
 * @property l1AccessThreshold L1 访问阈值（升级到 L1）
 * @property l2AccessThreshold L2 访问阈值（升级到 L2）
 */
data class VectorDatabaseConfig(
    val type: VectorDbType,
    val jvector: JVectorConfig,
    val basePath: String = "${System.getProperty("user.home")}/.smanunion/vector_store",
    val storePath: String = "${System.getProperty("user.home")}/.smanunion/vector_store",
    val databasePath: String = "${System.getProperty("user.home")}/.smanunion/analysis.mv.db",
    val vectorDimension: Int = 1024,
    val l1CacheSize: Int = 100,
    val l1AccessThreshold: Int = 10,
    val l2AccessThreshold: Int = 3
)

/**
 * 向量数据库类型
 */
enum class VectorDbType {
    JVECTOR,
    MEMORY,
    MILVUS,
    CHROMA,
    PGVECTOR
}

/**
 * JVector 配置
 *
 * @property dimension BGE-M3 向量维度
 * @property M HNSW 图连接数 (8-32)
 * @property efConstruction HNSW 构建参数 (50-200)
 * @property efSearch HNSW 搜索参数 (20-100)
 * @property enablePersist 是否启用磁盘持久化
 * @property rerankerThreshold Reranker 相似度阈值 (0.0-1.0)
 */
data class JVectorConfig(
    val dimension: Int = 1024,
    val M: Int = 16,
    val efConstruction: Int = 100,
    val efSearch: Int = 50,
    val enablePersist: Boolean = true,
    val rerankerThreshold: Double = 0.1
) {
    init {
        require(dimension > 0) { "dimension must be positive" }
        require(M in 8..32) { "M must be between 8 and 32" }
        require(efConstruction in 50..200) { "efConstruction must be between 50 and 200" }
        require(efSearch in 20..100) { "efSearch must be between 20 and 100" }
        require(rerankerThreshold in 0.0..1.0) { "rerankerThreshold must be between 0.0 and 1.0" }
    }
}

