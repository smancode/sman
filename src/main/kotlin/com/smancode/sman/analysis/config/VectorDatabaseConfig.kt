package com.smancode.sman.analysis.config

import java.io.File

/**
 * 向量数据库配置
 *
 * 所有数据存储路径按 projectKey 隔离：
 * - H2 database: ~/.sman/{projectKey}/analysis.mv.db
 *
 * @property projectKey 项目标识符（用于隔离不同项目的数据）
 * @property type 向量数据库类型
 * @property jvector JVector 配置
 * @property databasePath H2 数据库路径（已包含 projectKey）
 * @property vectorDimension 向量维度
 * @property l1CacheSize L1 缓存大小（内存 LRU）
 */
data class VectorDatabaseConfig(
    val projectKey: String,
    val type: VectorDbType,
    val jvector: JVectorConfig,
    val databasePath: String,
    val vectorDimension: Int = 1024,
    val l1CacheSize: Int = 500
) {
    companion object {
        private const val SMAN_DIR = ".sman"
        private const val DATABASE_FILE = "analysis"  // 不带 .mv.db 后缀，H2 会自动添加

        /**
         * 创建配置（自动构建 projectKey 隔离路径）
         */
        fun create(
            projectKey: String,
            type: VectorDbType,
            jvector: JVectorConfig,
            baseDir: String = System.getProperty("user.home"),
            vectorDimension: Int = 1024,
            l1CacheSize: Int = 500
        ): VectorDatabaseConfig {
            val projectDir = File(baseDir, SMAN_DIR).resolve(projectKey)
            return VectorDatabaseConfig(
                projectKey = projectKey,
                type = type,
                jvector = jvector,
                databasePath = File(projectDir, DATABASE_FILE).absolutePath,
                vectorDimension = vectorDimension,
                l1CacheSize = l1CacheSize
            )
        }
    }
}

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
 * @property rerankerThreshold Reranker 相似度阈值 (0.0-1.0)
 */
data class JVectorConfig(
    val dimension: Int = 1024,
    val M: Int = 16,
    val efConstruction: Int = 100,
    val efSearch: Int = 50,
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

