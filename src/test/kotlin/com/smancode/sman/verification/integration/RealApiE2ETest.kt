package com.smancode.sman.verification.integration

import com.smancode.sman.analysis.config.BgeM3Config
import com.smancode.sman.analysis.config.RerankerConfig
import com.smancode.sman.analysis.config.VectorDatabaseConfig
import com.smancode.sman.analysis.config.VectorDbType
import com.smancode.sman.analysis.config.JVectorConfig
import com.smancode.sman.analysis.database.TieredVectorStore
import com.smancode.sman.analysis.database.VectorStoreService
import com.smancode.sman.analysis.model.VectorFragment
import com.smancode.sman.analysis.vectorization.BgeM3Client
import com.smancode.sman.analysis.vectorization.RerankerClient
import com.smancode.sman.verification.service.VectorSearchService
import com.smancode.sman.verification.model.VectorSearchRequest
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * SmanAgent E2E 测试
 *
 * **端到端测试** - 完整流程测试
 *
 * 测试流程：
 * 1. 真实 BGE API 调用（向量化）
 * 2. 真实向量存储（H2 + JVector）
 * 3. 真实语义搜索（召回 + 重排）
 *
 * 前置条件：
 * 1. BGE 服务运行在 http://localhost:8000
 * 2. Reranker 服务运行在 http://localhost:8001
 *
 * 运行方式：
 * ./gradlew test --tests "*RealApiE2ETest*"
 *
 * 跳过集成测试：
 * SKIP_INTEGRATION_TESTS=true ./gradlew test
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("SmanAgent E2E 测试（端到端）")
class RealApiE2ETest {

    private lateinit var bgeClient: BgeM3Client
    private lateinit var vectorStore: VectorStoreService
    private lateinit var vectorSearchService: VectorSearchService

    private val projectKey = "e2e-test-${System.currentTimeMillis()}"

    @BeforeAll
    fun setUp() {
        // 检查是否跳过集成测试
        val skipTests = System.getenv("SKIP_INTEGRATION_TESTS") == "true"
        Assumptions.assumeFalse(skipTests, "E2E 测试已跳过（SKIP_INTEGRATION_TESTS=true）")

        // 真实的 BGE 配置
        // 注意：endpoint 不包含 /v1，因为代码会自动添加 /v1/embeddings
        val bgeConfig = BgeM3Config(
            endpoint = "http://localhost:8000",
            modelName = "BAAI/bge-m3",
            timeoutSeconds = 30,
            batchSize = 10
        )

        bgeClient = BgeM3Client(bgeConfig)

        // 真实的向量存储配置
        val vectorDbConfig = VectorDatabaseConfig.create(
            projectKey = projectKey,
            type = VectorDbType.JVECTOR,
            jvector = JVectorConfig(),
            vectorDimension = 1024,
            l1CacheSize = 100
        )

        vectorStore = TieredVectorStore(vectorDbConfig)

        // 真实的 Reranker 配置
        val rerankerConfig = RerankerConfig(
            enabled = true,
            baseUrl = "http://localhost:8001/v1",
            model = "BAAI/bge-reranker-v2-m3",
            apiKey = "",
            timeoutSeconds = 30,
            retry = 2,
            maxRounds = 3,
            topK = 5
        )

        val rerankerClient = RerankerClient(rerankerConfig)

        vectorSearchService = VectorSearchService(
            bgeClient = bgeClient,
            vectorStore = vectorStore as TieredVectorStore,
            rerankerClient = rerankerClient
        )
    }

    @Nested
    @DisplayName("E2E 测试：完整向量化 + 搜索流程")
    inner class EndToEndTests {

        @Test
        @DisplayName("完整流程：文本向量化 → 存储 → 搜索")
        fun testCompleteFlow_文本向量化存储搜索() {
            val fragmentId = "test-frag-${System.currentTimeMillis()}"

            // 步骤 1: 向量化
            val text = "RepaymentController.repay() 是还款入口"
            val vector = bgeClient.embed(text)
            assertEquals(1024, vector.size, "向量化应该返回 1024 维向量")

            // 步骤 2: 存储向量
            val fragment = VectorFragment(
                id = fragmentId,
                title = "RepaymentController",
                content = "fun repay(@RequestBody request: RepaymentRequest) { ... }",
                fullContent = "完整的还款方法实现",
                tags = listOf("controller", "repayment"),
                metadata = mapOf("type" to "api", "file" to "RepaymentController.kt"),
                vector = vector
            )

            vectorStore.add(fragment)
            Thread.sleep(500) // 等待存储完成

            // 步骤 3: 搜索
            val searchResults = vectorStore.search(vector, topK = 10)
            assertFalse(searchResults.isEmpty(), "搜索应该能找到刚存储的片段")

            // 验证搜索结果
            val found = searchResults.find { it.id == fragmentId }
            assertNotNull(found, "应该能找到刚存储的片段")
            assertEquals(fragmentId, found?.id)
        }

        @Test
        @DisplayName("语义搜索：还款入口查询")
        fun testSemanticSearch_还款入口查询() {
            // 步骤 1: 先存储一些测试数据
            val testFragments = listOf(
                VectorFragment(
                    id = "frag-1-${System.currentTimeMillis()}",
                    title = "RepaymentController",
                    content = "fun repay(@RequestBody request: RepaymentRequest): ResponseEntity<RepaymentResult>",
                    fullContent = "还款处理方法",
                    tags = listOf("repayment", "api"),
                    vector = bgeClient.embed("还款接口 RepaymentController.repay")
                ),
                VectorFragment(
                    id = "frag-2-${System.currentTimeMillis()}",
                    title = "LoginController",
                    content = "fun login(@RequestBody request: LoginRequest): ResponseEntity<LoginResult>",
                    fullContent = "登录处理方法",
                    tags = listOf("login", "api"),
                    vector = bgeClient.embed("登录接口 LoginController.login")
                ),
                VectorFragment(
                    id = "frag-3-${System.currentTimeMillis()}",
                    title = "UserController",
                    content = "fun getUser(@PathVariable id: String): ResponseEntity<UserInfo>",
                    fullContent = "用户查询方法",
                    tags = listOf("user", "api"),
                    vector = bgeClient.embed("用户接口 UserController.getUser")
                )
            )

            testFragments.forEach { vectorStore.add(it) }
            Thread.sleep(1000) // 等待存储完成

            // 步骤 2: 语义搜索
            val query = "还款入口是哪个"
            val queryVector = bgeClient.embed(query)

            val searchResults = vectorStore.search(queryVector, topK = 3)

            // 步骤 3: 验证结果
            assertFalse(searchResults.isEmpty(), "应该能找到相关结果")

            // 第一个结果应该是还款相关的
            val topResult = searchResults[0]
            assertTrue(
                topResult.tags.contains("repayment") || topResult.content.contains("repay"),
                "第一个结果应该是还款相关的: ${topResult.title}"
            )
        }

        @Test
        @DisplayName("语义搜索 + 重排：完整流程")
        fun testSemanticSearchWithRerank_完整流程() {
            // 步骤 1: 存储测试数据
            val testFragments = listOf(
                VectorFragment(
                    id = "rerank-frag-1-${System.currentTimeMillis()}",
                    title = "还款接口",
                    content = "RepaymentController.repay() 处理还款请求",
                    fullContent = "还款接口处理",
                    vector = bgeClient.embed("还款接口")
                ),
                VectorFragment(
                    id = "rerank-frag-2-${System.currentTimeMillis()}",
                    title = "登录接口",
                    content = "LoginController.login() 处理登录请求",
                    fullContent = "登录接口处理",
                    vector = bgeClient.embed("登录接口")
                ),
                VectorFragment(
                    id = "rerank-frag-3-${System.currentTimeMillis()}",
                    title = "用户管理",
                    content = "UserController.getUser() 查询用户信息",
                    fullContent = "用户管理",
                    vector = bgeClient.embed("用户管理")
                )
            )

            testFragments.forEach { vectorStore.add(it) }
            Thread.sleep(1000)

            // 步骤 2: 语义搜索 + 重排
            val request = VectorSearchRequest(
                query = "还款",
                projectKey = projectKey,
                topK = 3,
                enableRerank = true,
                rerankTopN = 2
            )

            val response = vectorSearchService.semanticSearch(request)

            // 步骤 3: 验证结果
            assertNotNull(response.recallResults, "应该有召回结果")
            assertNotNull(response.rerankResults, "应该有重排结果")

            // 验证有重排结果
            assertTrue(response.rerankResults!!.isNotEmpty(), "应该有重排结果")

            // 第一个结果应该是最相关的（还款接口）
            val topResult = response.rerankResults!![0]
            assertTrue(
                topResult.fragmentId.contains("rerank-frag-1") || topResult.fragmentId.contains("还款"),
                "重排后第一个应该是还款接口，实际: ${topResult.fragmentId}"
            )
        }
    }

    @Nested
    @DisplayName("E2E 测试：性能和压力测试")
    inner class PerformanceTests {

        @Test
        @DisplayName("批量向量化性能 - 10 个文本")
        fun testBatchEmbeddingPerformance_10个文本() {
            val texts = (1..10).map { "测试文本 $it" }

            val startTime = System.currentTimeMillis()
            val results = bgeClient.batchEmbed(texts)
            val endTime = System.currentTimeMillis()

            assertEquals(10, results.size)

            val duration = endTime - startTime
            val avgTime = duration / 10.0

            println("批量向量化 10 个文本:")
            println("  总时间: ${duration}ms")
            println("  平均时间: ${avgTime}ms")

            // 性能要求：平均每个文本应该在 500ms 以内（CPU 模型）
            assertTrue(avgTime < 500, "平均每个文本应该在 500ms 以内，实际: ${avgTime}ms")
        }

        @Test
        @DisplayName("向量存储性能 - 100 个片段")
        fun testVectorStorePerformance_100个片段() {
            val fragments = (1..100).map { i ->
                VectorFragment(
                    id = "perf-frag-$i-${System.currentTimeMillis()}",
                    title = "Title $i",
                    content = "Content $i",
                    fullContent = "Full content $i",
                    tags = listOf("tag1", "tag2"),
                    vector = bgeClient.embed("Text $i")
                )
            }

            val startTime = System.currentTimeMillis()
            fragments.forEach { vectorStore.add(it) }
            val endTime = System.currentTimeMillis()

            println("存储 100 个片段: ${endTime - startTime}ms")

            // 性能要求：存储 100 个片段应该在 10 秒内完成
            assertTrue((endTime - startTime) < 10000, "存储 100 个片段应该在 10 秒内完成")
        }
    }
}
