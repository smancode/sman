package com.smancode.sman.verification.service

import com.smancode.sman.analysis.config.BgeM3Config
import com.smancode.sman.analysis.config.RerankerConfig
import com.smancode.sman.analysis.config.VectorDatabaseConfig
import com.smancode.sman.analysis.database.TieredVectorStore
import com.smancode.sman.analysis.model.VectorFragment
import com.smancode.sman.analysis.vectorization.BgeM3Client
import com.smancode.sman.verification.model.SearchResult
import com.smancode.sman.verification.model.VectorSearchRequest
import com.smancode.sman.verification.model.VectorSearchResponse
import io.mockk.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * VectorSearchService 测试
 *
 * TDD 测试：先写测试，后写代码
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("VectorSearchService 测试")
class VectorSearchServiceTest {

    private lateinit var mockBgeClient: BgeM3Client
    private lateinit var mockVectorStore: TieredVectorStore
    private lateinit var mockRerankerClient: com.smancode.sman.analysis.vectorization.RerankerClient
    private lateinit var vectorSearchService: VectorSearchService

    private val testQueryVector = FloatArray(1024) { it * 0.001f }

    @BeforeEach
    fun setUp() {
        mockBgeClient = mockk(relaxed = true)
        mockVectorStore = mockk(relaxed = true)
        mockRerankerClient = mockk(relaxed = true)

        // 配置 mockBgeClient 默认行为
        every { mockBgeClient.embed(any()) } returns testQueryVector

        // 配置 mockVectorStore 默认行为
        every { mockVectorStore.search(any(), any()) } returns emptyList()

        // 配置 mockRerankerClient 默认行为
        every { mockRerankerClient.rerankWithScores(any(), any(), any()) } returns emptyList()

        vectorSearchService = VectorSearchService(
            bgeClient = mockBgeClient,
            vectorStore = mockVectorStore,
            rerankerClient = mockRerankerClient
        )
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    @Nested
    @DisplayName("白名单准入测试")
    inner class WhitelistAcceptanceTests {

        @Test
        @DisplayName("所有参数合法 - 成功返回结果")
        fun testAllParametersValid_Success() {
            // Given: 合法的参数
            val request = VectorSearchRequest(
                query = "如何使用 Spring Boot",
                projectKey = "test-project",
                topK = 10,
                enableRerank = true,
                rerankTopN = 5
            )

            val testFragments = listOf(
                VectorFragment(
                    id = "frag1",
                    title = "Spring Boot 使用指南",
                    content = "Spring Boot 是一个快速开发框架",
                    fullContent = "完整内容",
                    tags = listOf("spring", "boot"),
                    metadata = mapOf("type" to "guide"),
                    vector = testQueryVector
                )
            )

            every { mockVectorStore.search(testQueryVector, 10) } returns testFragments
            every { mockRerankerClient.rerankWithScores(any(), any(), 5) } returns listOf(0 to 1.0)

            // When: 执行搜索
            val response = vectorSearchService.semanticSearch(request)

            // Then: 验证结果
            assertNotNull(response)
            assertEquals("如何使用 Spring Boot", response.query)
            assertEquals(1, response.recallResults.size)
            assertTrue(response.processingTimeMs >= 0)

            verify(exactly = 1) { mockBgeClient.embed("如何使用 Spring Boot") }
            verify(exactly = 1) { mockVectorStore.search(testQueryVector, 10) }
            verify(exactly = 1) { mockRerankerClient.rerankWithScores(any(), any(), 5) }
        }

        @Test
        @DisplayName("不启用重排 - 只返回召回结果")
        fun testRerankDisabled_OnlyRecallResults() {
            // Given: 不启用重排
            val request = VectorSearchRequest(
                query = "测试查询",
                projectKey = "test-project",
                topK = 10,
                enableRerank = false
            )

            val testFragments = listOf(
                VectorFragment(
                    id = "frag1",
                    title = "测试标题",
                    content = "测试内容",
                    fullContent = "完整内容",
                    vector = testQueryVector
                )
            )

            every { mockVectorStore.search(testQueryVector, 10) } returns testFragments

            // When: 执行搜索
            val response = vectorSearchService.semanticSearch(request)

            // Then: 验证结果
            assertNotNull(response)
            assertEquals(1, response.recallResults.size)
            assertEquals(null, response.rerankResults)

            verify(exactly = 0) { mockRerankerClient.rerankWithScores(any(), any(), any()) }
        }
    }

    @Nested
    @DisplayName("白名单拒绝测试")
    inner class WhitelistRejectionTests {

        @Test
        @DisplayName("缺少 query 参数 - 抛出异常")
        fun testMissingQuery_ThrowsException() {
            // Given: 缺少 query 参数
            val request = VectorSearchRequest(
                query = "",
                projectKey = "test-project",
                topK = 10
            )

            // When & Then: 必须抛出异常
            val exception = kotlin.test.assertFailsWith<IllegalArgumentException> {
                vectorSearchService.semanticSearch(request)
            }

            assertTrue(exception.message?.contains("query") == true)
            verify(exactly = 0) { mockBgeClient.embed(any()) }
        }

        @Test
        @DisplayName("topK 小于等于 0 - 抛出异常")
        fun testTopKLessThanOrZero_ThrowsException() {
            // Given: topK <= 0
            val request = VectorSearchRequest(
                query = "测试查询",
                projectKey = "test-project",
                topK = 0
            )

            // When & Then: 必须抛出异常
            val exception = kotlin.test.assertFailsWith<IllegalArgumentException> {
                vectorSearchService.semanticSearch(request)
            }

            assertTrue(exception.message?.contains("topK") == true)
        }

        @Test
        @DisplayName("rerankTopN 小于等于 0 - 抛出异常")
        fun testRerankTopNLessThanOrZero_ThrowsException() {
            // Given: rerankTopN <= 0
            val request = VectorSearchRequest(
                query = "测试查询",
                projectKey = "test-project",
                topK = 10,
                enableRerank = true,
                rerankTopN = 0
            )

            // When & Then: 必须抛出异常
            val exception = kotlin.test.assertFailsWith<IllegalArgumentException> {
                vectorSearchService.semanticSearch(request)
            }

            assertTrue(exception.message?.contains("rerankTopN") == true)
        }
    }

    @Nested
    @DisplayName("边界值测试")
    inner class BoundaryValueTests {

        @Test
        @DisplayName("topK = 1 - 最小合法值")
        fun testTopKEqualsOne_Success() {
            // Given: topK = 1
            val request = VectorSearchRequest(
                query = "测试查询",
                projectKey = "test-project",
                topK = 1
            )

            every { mockVectorStore.search(any(), any()) } returns emptyList()

            // When: 执行搜索
            val response = vectorSearchService.semanticSearch(request)

            // Then: 成功返回
            assertNotNull(response)
            verify(exactly = 1) { mockVectorStore.search(any(), 1) }
        }

        @Test
        @DisplayName("rerankTopN = 1 - 最小合法值")
        fun testRerankTopNEqualsOne_Success() {
            // Given: rerankTopN = 1，且有召回结果
            val request = VectorSearchRequest(
                query = "测试查询",
                projectKey = "test-project",
                topK = 10,
                enableRerank = true,
                rerankTopN = 1
            )

            val testFragments = listOf(
                VectorFragment(
                    id = "frag1",
                    title = "测试标题",
                    content = "测试内容",
                    fullContent = "完整内容",
                    vector = testQueryVector
                )
            )

            every { mockVectorStore.search(any(), any()) } returns testFragments
            every { mockRerankerClient.rerankWithScores(any(), any(), 1) } returns listOf(0 to 1.0)

            // When: 执行搜索
            val response = vectorSearchService.semanticSearch(request)

            // Then: 成功返回
            assertNotNull(response)
            assertNotNull(response.rerankResults)
            verify(exactly = 1) { mockRerankerClient.rerankWithScores(any(), any(), 1) }
        }
    }

    @Nested
    @DisplayName("重排功能测试")
    inner class RerankFeatureTests {

        @Test
        @DisplayName("启用重排 - 返回重排结果")
        fun testRerankEnabled_ReturnsRerankedResults() {
            // Given: 启用重排
            val request = VectorSearchRequest(
                query = "Spring Boot",
                projectKey = "test-project",
                topK = 10,
                enableRerank = true,
                rerankTopN = 5
            )

            val testFragments = listOf(
                VectorFragment(
                    id = "frag1",
                    title = "Spring Boot 入门",
                    content = "Spring Boot 入门指南",
                    fullContent = "完整内容1",
                    vector = testQueryVector
                ),
                VectorFragment(
                    id = "frag2",
                    title = "Spring Boot 进阶",
                    content = "Spring Boot 进阶教程",
                    fullContent = "完整内容2",
                    vector = testQueryVector
                )
            )

            // 重排后顺序：frag2 排第一
            every { mockVectorStore.search(testQueryVector, 10) } returns testFragments
            every { mockRerankerClient.rerankWithScores(any(), any(), 5) } returns listOf(1 to 0.95, 0 to 0.85)

            // When: 执行搜索
            val response = vectorSearchService.semanticSearch(request)

            // Then: 验证重排结果
            assertNotNull(response)
            assertEquals(2, response.recallResults.size)
            assertNotNull(response.rerankResults)
            assertEquals(2, response.rerankResults!!.size)

            // 验证重排后的顺序
            assertEquals("frag2", response.rerankResults!![0].fragmentId)
            assertEquals("frag1", response.rerankResults!![1].fragmentId)
        }
    }
}
