package com.smancode.smanagent.verification.config

import com.smancode.smanagent.analysis.config.BgeM3Config
import com.smancode.smanagent.analysis.config.JVectorConfig
import com.smancode.smanagent.analysis.config.RerankerConfig
import com.smancode.smanagent.analysis.config.VectorDatabaseConfig
import com.smancode.smanagent.analysis.config.VectorDbType
import com.smancode.smanagent.analysis.database.TieredVectorStore
import com.smancode.smanagent.analysis.vectorization.BgeM3Client
import com.smancode.smanagent.analysis.vectorization.RerankerClient
import com.smancode.smanagent.smancode.llm.LlmService
import com.smancode.smanagent.smancode.llm.config.LlmEndpoint
import com.smancode.smanagent.smancode.llm.config.LlmPoolConfig
import com.smancode.smanagent.smancode.llm.config.LlmRetryPolicy
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
     * 连接到 ~/.smanunion/autoloop/analysis.mv.db 的 H2 数据库
     */
    @Bean
    open fun dataSource(): DataSource {
        // 连接到 autoloop 项目的数据库
        val projectKey = System.getenv("PROJECT_KEY") ?: "autoloop"
        val dbPath = System.getProperty("user.home") + "/.smanunion/" + projectKey + "/analysis.mv.db"

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
        return RerankerClient(RerankerConfig(
            enabled = true,
            baseUrl = baseUrl,
            apiKey = apiKey
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
}
