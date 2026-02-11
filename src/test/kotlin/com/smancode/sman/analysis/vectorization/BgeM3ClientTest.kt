package com.smancode.sman.analysis.vectorization

import com.smancode.sman.analysis.config.BgeM3Config
import io.mockk.every
import io.mockk.mockk
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * BgeM3Client 单元测试
 *
 * 测试覆盖：
 * 1. 白名单准入测试：正常响应解析
 * 2. 白名单拒绝测试：空数据、非法格式
 * 3. 边界值测试：空数组、错误响应
 * 4. 优雅降级测试：解析失败不影响调用方
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("BgeM3Client 测试套件")
class BgeM3ClientTest {

    private lateinit var mockWebServer: MockWebServer
    private lateinit var client: BgeM3Client
    private lateinit var config: BgeM3Config

    @BeforeEach
    fun setUp() {
        mockWebServer = MockWebServer()
        config = BgeM3Config(
            endpoint = mockWebServer.url("/").toString().removeSuffix("/"),
            modelName = "bge-m3",
            timeoutSeconds = 10,
            batchSize = 10
        )
        client = BgeM3Client(config)
    }

    @AfterEach
    fun tearDown() {
        mockWebServer.shutdown()
        client.close()
    }

    @Nested
    @DisplayName("白名单准入测试：正常响应解析")
    inner class HappyPathTests {

        @Test
        @DisplayName("标准嵌入响应 - 解析成功")
        fun testEmbed_标准响应_解析成功() {
            // Given: 标准的 BGE-M3 响应
            val responseJson = """
                {
                    "object": "list",
                    "data": [
                        {
                            "object": "embedding",
                            "embedding": [0.1, 0.2, 0.3, 0.4, 0.5],
                            "index": 0
                        }
                    ],
                    "model": "bge-m3",
                    "usage": {
                        "prompt_tokens": 10,
                        "total_tokens": 10
                    }
                }
            """.trimIndent()

            mockWebServer.enqueue(
                MockResponse()
                    .setBody(responseJson)
                    .setResponseCode(200)
                    .addHeader("Content-Type", "application/json")
            )

            // When: 调用 embed
            val result = client.embed("测试文本")

            // Then: 验证向量正确解析
            assertEquals(5, result.size)
            assertEquals(0.1f, result[0])
            assertEquals(0.5f, result[4])
        }

        @Test
        @DisplayName("批量嵌入响应 - 解析成功")
        fun testBatchEmbed_批量响应_解析成功() {
            // Given: 批量响应 - 为每个文本返回单独的响应
            val responseJson1 = """
                {
                    "object": "list",
                    "data": [
                        {
                            "embedding": [0.1, 0.2, 0.3],
                            "index": 0
                        }
                    ],
                    "model": "bge-m3"
                }
            """.trimIndent()

            val responseJson2 = """
                {
                    "object": "list",
                    "data": [
                        {
                            "embedding": [0.4, 0.5, 0.6],
                            "index": 0
                        }
                    ],
                    "model": "bge-m3"
                }
            """.trimIndent()

            // batchEmbed 是串行调用，所以需要为每个请求 enqueue 一个响应
            mockWebServer.enqueue(
                MockResponse()
                    .setBody(responseJson1)
                    .setResponseCode(200)
            )
            mockWebServer.enqueue(
                MockResponse()
                    .setBody(responseJson2)
                    .setResponseCode(200)
            )

            // When: 调用 batchEmbed
            val result = client.batchEmbed(listOf("文本1", "文本2"))

            // Then: 验证两个向量都正确解析
            assertEquals(2, result.size)
            assertEquals(0.1f, result[0][0])
            assertEquals(0.3f, result[0][2])
            assertEquals(0.4f, result[1][0])
            assertEquals(0.6f, result[1][2])
        }

        @Test
        @DisplayName("包含空格和换行的响应 - 解析成功")
        fun testEmbed_包含空格换行_解析成功() {
            // Given: 格式不规范的 JSON（有空格和换行）
            val responseJson = """
                {
                    "data": [
                        {
                            "embedding" : [ 0.1 , 0.2 , 0.3 ]
                        }
                    ]
                }
            """.trimIndent()

            mockWebServer.enqueue(
                MockResponse()
                    .setBody(responseJson)
                    .setResponseCode(200)
            )

            // When: 调用 embed
            val result = client.embed("测试")

            // Then: 应该正确解析
            assertEquals(3, result.size)
        }
    }

    @Nested
    @DisplayName("白名单拒绝测试：非法格式")
    inner class RejectionTests {

        @Test
        @DisplayName("空字符串 - 抛异常")
        fun testEmbed_空字符串_抛异常() {
            // Given & Then: 空字符串必须抛异常
            val exception = assertThrows<IllegalArgumentException> {
                client.embed("")
            }

            assertTrue(exception.message!!.contains("不能为空"))
        }

        @Test
        @DisplayName("空列表 - 抛异常")
        fun testBatchEmbed_空列表_抛异常() {
            // Given & Then: 空列表必须抛异常
            val exception = assertThrows<IllegalArgumentException> {
                client.batchEmbed(emptyList())
            }

            assertTrue(exception.message!!.contains("不能为空"))
        }

        @Test
        @DisplayName("批量大小超限 - 抛异常")
        fun testBatchEmbed_批量大小超限_抛异常() {
            // Given: 超过 batchSize 的列表
            val oversizedList = List(11) { "text$it" }

            // & Then: 必须抛异常
            val exception = assertThrows<IllegalArgumentException> {
                client.batchEmbed(oversizedList)
            }

            assertTrue(exception.message!!.contains("批次大小不能超过"))
        }

        @Test
        @DisplayName("HTTP 404 错误 - 抛异常")
        fun testEmbed_HTTP404_抛异常() {
            // Given: HTTP 404 响应
            mockWebServer.enqueue(
                MockResponse()
                    .setResponseCode(404)
                    .setBody("Not Found")
            )

            // When & Then: 必须抛异常
            val exception = assertThrows<Exception> {
                client.embed("测试")
            }

            assertTrue(exception.message!!.contains("HTTP 404"))
        }

        @Test
        @DisplayName("HTTP 500 错误 - 重试后抛异常")
        fun testEmbed_HTTP500_重试后抛异常() {
            // Given: 连续返回 500 错误（超过最大重试次数）
            repeat(4) {
                mockWebServer.enqueue(
                    MockResponse()
                        .setResponseCode(500)
                        .setBody("Internal Server Error")
                )
            }

            // When & Then: 超过重试次数后抛异常
            val exception = assertThrows<Exception> {
                client.embed("测试")
            }

            // IOException 会被重试执行器捕获并重试，最终在重试次数用完后抛出
            assertTrue(
                exception.message!!.contains("超过最大重试次数") ||
                exception.message!!.contains("HTTP 500")
            )
        }
    }

    @Nested
    @DisplayName("边界值测试：特殊情况")
    inner class BoundaryTests {

        @Test
        @DisplayName("空 embedding 数组 - 抛异常")
        fun testEmbed_空Embedding数组_抛异常() {
            // Given: embedding 为空数组
            val responseJson = """
                {
                    "data": [
                        {
                            "embedding": []
                        }
                    ]
                }
            """.trimIndent()

            mockWebServer.enqueue(
                MockResponse()
                    .setBody(responseJson)
                    .setResponseCode(200)
            )

            // When & Then: 必须抛异常
            assertThrows<RuntimeException> {
                client.embed("测试")
            }
        }

        @Test
        @DisplayName("响应体为空 - 抛异常")
        fun testEmbed_响应体为空_抛异常() {
            // Given: 响应体为空
            mockWebServer.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody("")
            )

            // When & Then: 必须抛异常
            val exception = assertThrows<RuntimeException> {
                client.embed("测试")
            }

            assertTrue(exception.message!!.contains("响应为空"))
        }

        @Test
        @DisplayName("超大向量 - 解析成功")
        fun testEmbed_超大向量_解析成功() {
            // Given: 1024 维向量（BGE-M3 标准维度）
            val vector = (1..1024).map { it.toFloat() / 1024.0f }
            val vectorStr = vector.joinToString(",")

            val responseJson = """
                {
                    "data": [
                        {
                            "embedding": [$vectorStr]
                        }
                    ]
                }
            """.trimIndent()

            mockWebServer.enqueue(
                MockResponse()
                    .setBody(responseJson)
                    .setResponseCode(200)
            )

            // When: 调用 embed
            val result = client.embed("测试")

            // Then: 验证维度正确
            assertEquals(1024, result.size)
            // 最后一个元素应该是 1024 / 1024 = 1.0
            assertEquals(1.0f, result[1023])
            // 第一个元素应该是 1 / 1024
            assertEquals(1.0f / 1024, result[0])
        }

        @Test
        @DisplayName("HTTP 429 Too Many Requests - 重试成功")
        fun testEmbed_HTTP429_重试成功() {
            // Given: 第一次返回 429，第二次成功
            val successResponse = """
                {
                    "data": [{"embedding": [0.1, 0.2]}]
                }
            """.trimIndent()

            mockWebServer.enqueue(
                MockResponse().setResponseCode(429).setBody("Too Many Requests")
            )
            mockWebServer.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody(successResponse)
            )

            // When: 调用 embed
            val result = client.embed("测试")

            // Then: 重试后应该成功
            assertEquals(2, result.size)
            assertEquals(0.1f, result[0])
        }
    }

    @Nested
    @DisplayName("优雅降级测试")
    inner class GracefulDegradationTests {

        @Test
        @DisplayName("JSON 格式错误 - 抛出明确异常")
        fun testEmbed_JSON格式错误_抛出明确异常() {
            // Given: 返回非法 JSON
            mockWebServer.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody("not a json")
            )

            // When & Then: 抛出明确异常（不崩溃）
            val exception = assertThrows<RuntimeException> {
                client.embed("测试")
            }

            // 验证异常信息明确
            assertTrue(exception.message!!.contains("BGE-M3") || exception.message!!.contains("解析"))
        }

        @Test
        @DisplayName("缺少 embedding 字段 - 抛出明确异常")
        fun testEmbed_缺少Embedding字段_抛出明确异常() {
            // Given: 响应中没有 embedding 字段
            val responseJson = """
                {
                    "data": [
                        {
                            "index": 0
                        }
                    ]
                }
            """.trimIndent()

            mockWebServer.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody(responseJson)
            )

            // When & Then: 抛出明确异常
            assertThrows<RuntimeException> {
                client.embed("测试")
            }
        }
    }
}
