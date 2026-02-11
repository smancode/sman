package com.smancode.sman.verification.service

import com.smancode.sman.verification.model.AnalysisQueryRequest
import com.smancode.sman.verification.model.AnalysisQueryResponse
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
 * AnalysisQueryService 测试
 *
 * TDD 测试：先写测试，后写代码
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("AnalysisQueryService 测试")
class AnalysisQueryServiceTest {

    private lateinit var mockH2QueryService: H2QueryService
    private lateinit var analysisQueryService: AnalysisQueryService

    @BeforeEach
    fun setUp() {
        mockH2QueryService = mockk(relaxed = true)

        // 配置默认行为
        every { mockH2QueryService.queryAnalysisResults(any(), any(), any(), any()) } returns
            mapOf("data" to emptyList<Any>(), "total" to 0)

        analysisQueryService = AnalysisQueryService(mockH2QueryService)
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
            val request = AnalysisQueryRequest(
                module = "project_structure",
                projectKey = "test-project",
                page = 0,
                size = 20
            )

            val mockData = listOf(
                mapOf("name" to "module1", "type" to "java"),
                mapOf("name" to "module2", "type" to "kotlin")
            )

            every {
                mockH2QueryService.queryAnalysisResults(
                    "project_structure",
                    "test-project",
                    0,
                    20
                )
            } returns mapOf("data" to mockData, "total" to 2)

            // When: 查询分析结果
            val response: AnalysisQueryResponse<Map<String, Any>> = analysisQueryService.queryResults(request)

            // Then: 验证结果
            assertNotNull(response)
            assertEquals("project_structure", response.module)
            assertEquals("test-project", response.projectKey)
            assertEquals(2, response.data.size)
            assertEquals(2, response.total)
            assertEquals(0, response.page)
            assertEquals(20, response.size)

            verify(exactly = 1) {
                mockH2QueryService.queryAnalysisResults("project_structure", "test-project", 0, 20)
            }
        }

        @Test
        @DisplayName("支持 filters 参数 - 成功返回结果")
        fun testWithFilters_Success() {
            // Given: 带 filters 的参数
            val request = AnalysisQueryRequest(
                module = "tech_stack_detection",
                projectKey = "test-project",
                filters = mapOf("category" to "framework"),
                page = 0,
                size = 10
            )

            every {
                mockH2QueryService.queryAnalysisResults(
                    "tech_stack_detection",
                    "test-project",
                    0,
                    10
                )
            } returns mapOf("data" to emptyList<Any>(), "total" to 0)

            // When: 查询分析结果
            val response: AnalysisQueryResponse<Map<String, Any>> = analysisQueryService.queryResults(request)

            // Then: 验证结果
            assertNotNull(response)
            assertEquals("tech_stack_detection", response.module)
        }
    }

    @Nested
    @DisplayName("白名单拒绝测试")
    inner class WhitelistRejectionTests {

        @Test
        @DisplayName("缺少 module 参数 - 抛出异常")
        fun testMissingModule_ThrowsException() {
            // Given: module 为空字符串
            val request = AnalysisQueryRequest(
                module = "",
                projectKey = "test-project"
            )

            // When & Then: 必须抛出异常
            val exception = kotlin.test.assertFailsWith<IllegalArgumentException> {
                @Suppress("UNUSED_VARIABLE")
                val response: AnalysisQueryResponse<Map<String, Any>> = analysisQueryService.queryResults(request)
            }

            assertTrue(exception.message?.contains("module") == true)
            verify(exactly = 0) { mockH2QueryService.queryAnalysisResults(any(), any(), any(), any()) }
        }

        @Test
        @DisplayName("缺少 projectKey 参数 - 抛出异常")
        fun testMissingProjectKey_ThrowsException() {
            // Given: projectKey 为空字符串
            val request = AnalysisQueryRequest(
                module = "project_structure",
                projectKey = ""
            )

            // When & Then: 必须抛出异常
            val exception = kotlin.test.assertFailsWith<IllegalArgumentException> {
                @Suppress("UNUSED_VARIABLE")
                val response: AnalysisQueryResponse<Map<String, Any>> = analysisQueryService.queryResults(request)
            }

            assertTrue(exception.message?.contains("projectKey") == true)
        }

        @Test
        @DisplayName("page 小于 0 - 抛出异常")
        fun testPageLessThanZero_ThrowsException() {
            // Given: page < 0
            val request = AnalysisQueryRequest(
                module = "project_structure",
                projectKey = "test-project",
                page = -1
            )

            // When & Then: 必须抛出异常
            val exception = kotlin.test.assertFailsWith<IllegalArgumentException> {
                @Suppress("UNUSED_VARIABLE")
                val response: AnalysisQueryResponse<Map<String, Any>> = analysisQueryService.queryResults(request)
            }

            assertTrue(exception.message?.contains("page") == true)
        }

        @Test
        @DisplayName("size 小于等于 0 - 抛出异常")
        fun testSizeLessThanOrZero_ThrowsException() {
            // Given: size <= 0
            val request = AnalysisQueryRequest(
                module = "project_structure",
                projectKey = "test-project",
                size = 0
            )

            // When & Then: 必须抛出异常
            val exception = kotlin.test.assertFailsWith<IllegalArgumentException> {
                @Suppress("UNUSED_VARIABLE")
                val response: AnalysisQueryResponse<Map<String, Any>> = analysisQueryService.queryResults(request)
            }

            assertTrue(exception.message?.contains("size") == true)
        }

        @Test
        @DisplayName("不支持的模块 - 抛出异常")
        fun testUnsupportedModule_ThrowsException() {
            // Given: 不支持的模块
            val request = AnalysisQueryRequest(
                module = "unsupported_module",
                projectKey = "test-project"
            )

            // When & Then: 必须抛出异常
            val exception = kotlin.test.assertFailsWith<IllegalArgumentException> {
                @Suppress("UNUSED_VARIABLE")
                val response: AnalysisQueryResponse<Map<String, Any>> = analysisQueryService.queryResults(request)
            }

            assertTrue(exception.message?.contains("不支持的模块") == true)
        }
    }

    @Nested
    @DisplayName("支持的模块测试")
    inner class SupportedModulesTests {

        @Test
        @DisplayName("支持所有 9 个模块")
        fun testAllSupportedModules() {
            val supportedModules = listOf(
                "project_structure",
                "tech_stack_detection",
                "ast_scanning",
                "db_entity_detection",
                "api_entry_scanning",
                "external_api_scanning",
                "enum_scanning",
                "common_class_scanning",
                "xml_code_scanning"
            )

            supportedModules.forEach { module ->
                // Given: 每个支持的模块
                val request = AnalysisQueryRequest(
                    module = module,
                    projectKey = "test-project"
                )

                every {
                    mockH2QueryService.queryAnalysisResults(module, "test-project", 0, 20)
                } returns mapOf("data" to emptyList<Any>(), "total" to 0)

                // When: 查询
                val response: AnalysisQueryResponse<Map<String, Any>> = analysisQueryService.queryResults(request)

                // Then: 成功返回
                assertNotNull(response)
                assertEquals(module, response.module)
            }
        }
    }
}
