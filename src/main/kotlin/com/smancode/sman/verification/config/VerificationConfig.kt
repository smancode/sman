package com.smancode.sman.verification.config

import com.smancode.sman.analysis.config.BgeM3Config
import com.smancode.sman.analysis.config.JVectorConfig
import com.smancode.sman.analysis.config.RerankerConfig
import com.smancode.sman.analysis.config.VectorDatabaseConfig
import com.smancode.sman.analysis.config.VectorDbType
import com.smancode.sman.analysis.database.TieredVectorStore
import com.smancode.sman.analysis.vectorization.BgeM3Client
import com.smancode.sman.analysis.vectorization.RerankerClient
import com.smancode.sman.smancode.llm.LlmService
import com.smancode.sman.smancode.llm.config.LlmEndpoint
import com.smancode.sman.smancode.llm.config.LlmPoolConfig
import com.smancode.sman.smancode.llm.config.LlmRetryPolicy
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.jdbc.DataSourceBuilder
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.JdbcTemplate
import javax.sql.DataSource

/**
 * 验证服务的 Spring 配置
 *
 * 注册验证服务所需的 Bean
 */
@Configuration
open class VerificationConfig {

    /**
     * LLM 端点池配置 Bean
     * 只在有 LLM_API_KEY 环境变量时创建
     */
    @Bean
    @ConditionalOnProperty(name = ["LLM_API_KEY"], matchIfMissing = false)
    open fun llmPoolConfig(): LlmPoolConfig {
        val config = LlmPoolConfig()

        // 从环境变量读取 LLM 配置
        // 注意：baseUrl 应该只是基础 URL，LlmService 会自动拼接 /chat/completions
        val baseUrl = System.getenv("LLM_BASE_URL") ?: "https://open.bigmodel.cn/api/paas/v4"
        val apiKey = System.getenv("LLM_API_KEY") ?: ""

        require(apiKey.isNotBlank()) {
            "LLM_API_KEY 环境变量未设置，专家咨询功能将无法使用"
        }

        val endpoint = LlmEndpoint()
        endpoint.baseUrl = baseUrl
        endpoint.apiKey = apiKey
        endpoint.model = "glm-4-flash"
        config.endpoints.add(endpoint)

        val retry = LlmRetryPolicy()
        retry.maxRetries = 3
        retry.baseDelay = 1000L
        config.retry = retry

        return config
    }

    /**
     * LLM 服务 Bean
     * 只在有 LLM_API_KEY 环境变量时创建
     */
    @Bean
    @ConditionalOnProperty(name = ["LLM_API_KEY"], matchIfMissing = false)
    open fun llmService(poolConfig: LlmPoolConfig): LlmService {
        return LlmService(poolConfig)
    }

    /**
     * H2 数据源 Bean
     *
     * 连接到 ~/.smanunion/{projectKey}/analysis.mv.db 的 H2 数据库
     * 注意：路径不含 .mv.db 后缀，H2 会自动添加
     */
    @Bean
    open fun dataSource(): DataSource {
        // 连接到项目的数据库
        val projectKey = System.getenv("PROJECT_KEY") ?: "autoloop"
        val dbPath = System.getProperty("user.home") + "/.smanunion/" + projectKey + "/analysis"

        return DataSourceBuilder.create()
            .url("jdbc:h2:$dbPath;MODE=PostgreSQL;AUTO_SERVER=TRUE")
            .driverClassName("org.h2.Driver")
            .username("sa")
            .build()
    }

    /**
     * JdbcTemplate Bean
     */
    @Bean
    open fun jdbcTemplate(dataSource: DataSource): JdbcTemplate {
        return JdbcTemplate(dataSource)
    }

    /**
     * BGE-M3 客户端 Bean
     */
    @Bean
    open fun bgeM3Client(): BgeM3Client {
        // 注意：BgeM3Client 会自动添加 /v1/embeddings，所以这里只提供基础 URL
        val endpoint = System.getenv("BGE_ENDPOINT") ?: "http://localhost:8000"
        val dimension = (System.getenv("BGE_DIMENSION") ?: "1024").toInt()
        return BgeM3Client(BgeM3Config(endpoint = endpoint, dimension = dimension))
    }

    /**
     * Reranker 客户端 Bean
     */
    @Bean
    open fun rerankerClient(): RerankerClient {
        val baseUrl = System.getenv("RERANKER_BASE_URL") ?: "http://localhost:8001/v1"
        val apiKey = System.getenv("RERANKER_API_KEY") ?: ""
        val threshold = (System.getenv("RERANKER_THRESHOLD") ?: "0.1").toDouble()
        return RerankerClient(RerankerConfig(
            enabled = true,
            baseUrl = baseUrl,
            apiKey = apiKey,
            threshold = threshold
        ))
    }

    /**
     * 向量数据库配置 Bean
     * 注意：使用 autoloop 项目，与 H2QueryService 共享数据库
     */
    @Bean
    open fun vectorDatabaseConfig(): VectorDatabaseConfig {
        val dimension = (System.getenv("BGE_DIMENSION") ?: "1024").toInt()
        return VectorDatabaseConfig.create(
            projectKey = "autoloop",
            type = VectorDbType.JVECTOR,
            jvector = JVectorConfig(dimension = dimension),
            vectorDimension = dimension
        )
    }

    @Bean
    open fun vectorStore(config: VectorDatabaseConfig): TieredVectorStore {
        return TieredVectorStore(config)
    }

    /**
     * 初始化 H2 数据库表
     * 确保验证服务启动时创建必要的表
     */
    @Bean
    open fun initializeDatabaseTables(dataSource: DataSource): DataSource {
        dataSource.connection.use { connection ->
            // 创建 PROJECT_ANALYSIS 表
            connection.createStatement().executeUpdate("""
                CREATE TABLE IF NOT EXISTS project_analysis (
                    project_key VARCHAR(255) PRIMARY KEY,
                    start_time BIGINT NOT NULL,
                    end_time BIGINT,
                    status VARCHAR(20) NOT NULL,
                    project_md5 VARCHAR(32),
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
            """.trimIndent())

            // 创建 ANALYSIS_STEP 表
            connection.createStatement().executeUpdate("""
                CREATE TABLE IF NOT EXISTS analysis_step (
                    id VARCHAR(255) PRIMARY KEY,
                    project_key VARCHAR(255) NOT NULL,
                    step_name VARCHAR(100) NOT NULL,
                    step_description VARCHAR(255),
                    status VARCHAR(20) NOT NULL,
                    start_time BIGINT NOT NULL,
                    end_time BIGINT,
                    data TEXT,
                    error TEXT,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
            """.trimIndent())

            // 创建索引
            try {
                connection.createStatement().executeUpdate("""
                    CREATE INDEX IF NOT EXISTS idx_analysis_step_project
                    ON analysis_step(project_key)
                """.trimIndent())
            } catch (e: Exception) {
                // 索引可能已存在
            }

            println("H2 数据库表初始化完成 (PROJECT_ANALYSIS, ANALYSIS_STEP)")
        }
        return dataSource
    }
}
