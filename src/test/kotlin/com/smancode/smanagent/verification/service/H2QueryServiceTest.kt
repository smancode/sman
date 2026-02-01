package com.smancode.smanagent.verification.service

import io.mockk.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.core.JdbcTemplate
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * H2QueryService 测试
 *
 * TDD 测试：先写测试，后写代码
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("H2QueryService 测试")
class H2QueryServiceTest {

    private lateinit var mockJdbcTemplate: JdbcTemplate
    private lateinit var h2QueryService: H2QueryService

    @BeforeEach
    fun setUp() {
        mockJdbcTemplate = mockk(relaxed = true)

        // 配置默认行为
        every { mockJdbcTemplate.queryForList(any<String>(), any<Any>()) } returns emptyList()
        every { mockJdbcTemplate.queryForMap(any<String>(), any<Any>()) } returns emptyMap()
        every { mockJdbcTemplate.queryForObject(any<String>(), any<Class<*>>(), any<Any>()) } returns Integer.valueOf(0)

        h2QueryService = H2QueryService(mockJdbcTemplate)
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    @Nested
    @DisplayName("queryAnalysisResults 测试")
    inner class QueryAnalysisResultsTests {

        @Test
        @DisplayName("查询项目结构 - 成功返回结果")
        fun testQueryProjectStructure_Success() {
            // Given: 模拟数据库返回
            val mockData = listOf(
                mapOf("data" to "{\"name\":\"module1\",\"type\":\"java\"}"),
                mapOf("data" to "{\"name\":\"module2\",\"type\":\"kotlin\"}")
            )

            every {
                mockJdbcTemplate.queryForList(
                    """
                    SELECT data FROM analysis_results
                    WHERE project_key = ? AND module = ?
                    ORDER BY created_at DESC
                    LIMIT ? OFFSET ?
                    """.trimIndent(),
                    "test-project", "project_structure", 20, 0
                )
            } returns mockData

            every {
                mockJdbcTemplate.queryForObject(
                    """
                    SELECT COUNT(*) FROM analysis_results
                    WHERE project_key = ? AND module = ?
                    """.trimIndent(),
                    Integer::class.java,
                    "test-project", "project_structure"
                )
            } returns 2 as Integer

            // When: 查询
            val result = h2QueryService.queryAnalysisResults(
                "project_structure",
                "test-project",
                0,
                20
            )

            // Then: 验证结果
            assertNotNull(result)
            assertEquals(2, (result["data"] as List<*>).size)
            assertEquals(2, result["total"])
        }

        @Test
        @DisplayName("缺少 module 参数 - 抛出异常")
        fun testMissingModule_ThrowsException() {
            // Given: module 为空
            // When & Then: 必须抛出异常
            val exception = kotlin.test.assertFailsWith<IllegalArgumentException> {
                h2QueryService.queryAnalysisResults("", "test-project", 0, 20)
            }

            assertTrue(exception.message?.contains("module") == true)
        }

        @Test
        @DisplayName("缺少 projectKey 参数 - 抛出异常")
        fun testMissingProjectKey_ThrowsException() {
            // Given: projectKey 为空
            // When & Then: 必须抛出异常
            val exception = kotlin.test.assertFailsWith<IllegalArgumentException> {
                h2QueryService.queryAnalysisResults("project_structure", "", 0, 20)
            }

            assertTrue(exception.message?.contains("projectKey") == true)
        }

        @Test
        @DisplayName("page 小于 0 - 抛出异常")
        fun testPageLessThanZero_ThrowsException() {
            // Given: page < 0
            // When & Then: 必须抛出异常
            val exception = kotlin.test.assertFailsWith<IllegalArgumentException> {
                h2QueryService.queryAnalysisResults("project_structure", "test-project", -1, 20)
            }

            assertTrue(exception.message?.contains("page") == true)
        }

        @Test
        @DisplayName("size 小于等于 0 - 抛出异常")
        fun testSizeLessThanOrZero_ThrowsException() {
            // Given: size <= 0
            // When & Then: 必须抛出异常
            val exception = kotlin.test.assertFailsWith<IllegalArgumentException> {
                h2QueryService.queryAnalysisResults("project_structure", "test-project", 0, 0)
            }

            assertTrue(exception.message?.contains("size") == true)
        }
    }

    @Nested
    @DisplayName("queryVectors 测试")
    inner class QueryVectorsTests {

        @Test
        @DisplayName("查询向量数据 - 成功返回结果")
        fun testQueryVectors_Success() {
            // Given: 模拟数据库返回
            val mockData = listOf(
                mapOf("id" to "vec1", "fragment_id" to "frag1"),
                mapOf("id" to "vec2", "fragment_id" to "frag2")
            )

            every {
                mockJdbcTemplate.queryForList(
                    "SELECT * FROM vectors LIMIT ? OFFSET ?",
                    10, 0
                )
            } returns mockData

            every {
                mockJdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM vectors",
                    Integer::class.java
                )
            } returns 2 as Integer

            // When: 查询
            val result = h2QueryService.queryVectors(0, 10)

            // Then: 验证结果
            assertNotNull(result)
            assertEquals(2, (result["data"] as List<*>).size)
            assertEquals(2, result["total"])
        }
    }

    @Nested
    @DisplayName("queryProjects 测试")
    inner class QueryProjectsTests {

        @Test
        @DisplayName("查询项目列表 - 成功返回结果")
        fun testQueryProjects_Success() {
            // Given: 模拟数据库返回
            val mockData = listOf(
                mapOf("project_key" to "project1", "name" to "Project 1"),
                mapOf("project_key" to "project2", "name" to "Project 2")
            )

            every {
                mockJdbcTemplate.queryForList(
                    "SELECT * FROM projects LIMIT ? OFFSET ?",
                    10, 0
                )
            } returns mockData

            every {
                mockJdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM projects",
                    Integer::class.java
                )
            } returns 2 as Integer

            // When: 查询
            val result = h2QueryService.queryProjects(0, 10)

            // Then: 验证结果
            assertNotNull(result)
            assertEquals(2, (result["data"] as List<*>).size)
            assertEquals(2, result["total"])
        }
    }

    @Nested
    @DisplayName("executeSafeSql 测试")
    inner class ExecuteSafeSqlTests {

        @Test
        @DisplayName("执行安全 SQL - 成功返回结果")
        fun testExecuteSafeSql_Success() {
            // Given: 安全的 SQL
            val sql = "SELECT COUNT(*) FROM vectors"
            val params = emptyMap<String, Any>()

            every {
                mockJdbcTemplate.queryForList(sql, *emptyArray())
            } returns listOf(mapOf("COUNT(*)" to 100))

            // When: 执行
            val result = h2QueryService.executeSafeSql(sql, params)

            // Then: 验证结果
            assertNotNull(result)
            assertEquals(1, result.size)
        }

        @Test
        @DisplayName("SQL 包含危险关键字 - 抛出异常")
        fun testDangerousSql_ThrowsException() {
            // Given: 危险的 SQL
            val sql = "DROP TABLE vectors"
            val params = emptyMap<String, Any>()

            // When & Then: 必须抛出异常
            val exception = kotlin.test.assertFailsWith<IllegalArgumentException> {
                h2QueryService.executeSafeSql(sql, params)
            }

            assertTrue(exception.message?.contains("危险 SQL") == true)
        }

        @Test
        @DisplayName("参数包含非法字符 - 抛出异常")
        fun testInvalidParams_ThrowsException() {
            // Given: 参数包含分号
            val sql = "SELECT * FROM vectors WHERE id = :id"
            val params = mapOf("id" to "1; DROP TABLE users")

            // When & Then: 必须抛出异常
            val exception = kotlin.test.assertFailsWith<IllegalArgumentException> {
                h2QueryService.executeSafeSql(sql, params)
            }

            assertTrue(exception.message?.contains("非法参数") == true)
        }
    }
}
