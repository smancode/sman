package com.smancode.sman.analysis.vectorization

import com.smancode.sman.analysis.config.BgeM3Config
import kotlinx.coroutines.*
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * BgeM3Client 集成测试
 *
 * **真实 API 调用测试** - 不使用 Mock
 *
 * 前置条件：
 * 1. BGE 服务必须运行在 http://localhost:8000
 * 2. 可以通过设置环境变量跳过：SKIP_INTEGRATION_TESTS=true
 *
 * 运行方式：
 * ./gradlew test --tests "*BgeM3ClientIntegrationTest*"
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("BGE-M3 集成测试（真实 API 调用）")
class BgeM3ClientIntegrationTest {

    private lateinit var client: BgeM3Client
    private lateinit var config: BgeM3Config

    @BeforeAll
    fun setUp() {
        // 检查是否跳过集成测试
        val skipTests = System.getenv("SKIP_INTEGRATION_TESTS") == "true"
        Assumptions.assumeFalse(skipTests, "集成测试已跳过（SKIP_INTEGRATION_TESTS=true）")

        // 真实的 BGE 配置
        // 注意：endpoint 不包含 /v1，因为代码会自动添加 /v1/embeddings
        config = BgeM3Config(
            endpoint = "http://localhost:8000",
            modelName = "BAAI/bge-m3",
            timeoutSeconds = 30,
            batchSize = 10
        )

        client = BgeM3Client(config)
    }

    @Nested
    @DisplayName("真实 API 调用测试")
    inner class RealApiTests {

        @Test
        @DisplayName("真实 API 调用 - 返回 1024 维向量")
        fun testRealApiCall_返回1024维向量() {
            // When: 调用真实 BGE API
            val result = client.embed("还款入口是哪个")

            // Then: 验证向量维度
            assertEquals(1024, result.size, "BGE-M3 应该返回 1024 维向量")

            // 验证向量不全为 0（真实计算）
            val allZeros = result.all { it == 0.0f }
            assertTrue(!allZeros, "向量不应该全为 0，说明进行了真实计算")

            // 验证向量值在合理范围内
            val maxValue = result.max()
            val minValue = result.min()
            assertTrue(maxValue <= 1.0f, "向量值不应超过 1.0")
            assertTrue(minValue >= -1.0f, "向量值不应小于 -1.0")
        }

        @Test
        @DisplayName("批量调用 - 返回多个向量")
        fun testBatchEmbed_返回多个向量() {
            // Given: 多个文本
            val texts = listOf("还款入口", "登录接口", "用户管理")

            // When: 批量调用真实 API
            val results = client.batchEmbed(texts)

            // Then: 验证结果
            assertEquals(3, results.size, "应该返回 3 个向量")

            // 验证每个向量维度
            results.forEach { vector ->
                assertEquals(1024, vector.size, "每个向量应该是 1024 维")
            }

            // 验证向量不重复（真实计算）
            val firstVector = results[0]
            val secondVector = results[1]
            val areDifferent = firstVector.indices.any { i ->
                Math.abs(firstVector[i] - secondVector[i]) > 0.0001f
            }
            assertTrue(areDifferent, "不同文本的向量应该不同")
        }

        @Test
        @DisplayName("中文文本 - 正常处理")
        fun testChineseText_正常处理() {
            // Given: 中文文本
            val chineseText = "还款入口是哪个，有哪些还款类型"

            // When: 调用真实 API
            val result = client.embed(chineseText)

            // Then: 应该成功返回
            assertEquals(1024, result.size)
        }

        @Test
        @DisplayName("英文文本 - 正常处理")
        fun testEnglishText_正常处理() {
            // Given: 英文文本
            val englishText = "What is the repayment entrance?"

            // When: 调用真实 API
            val result = client.embed(englishText)

            // Then: 应该成功返回
            assertEquals(1024, result.size)
        }

        @Test
        @DisplayName("混合文本 - 正常处理")
        fun testMixedText_正常处理() {
            // Given: 中英文混合
            val mixedText = "Repayment 还款 entrance 入口"

            // When: 调用真实 API
            val result = client.embed(mixedText)

            // Then: 应该成功返回
            assertEquals(1024, result.size)
        }
    }

    @Nested
    @DisplayName("向量质量测试")
    inner class VectorQualityTests {

        @Test
        @DisplayName("向量归一化 - L2 范数约等于 1")
        fun testVectorNormalization_L2范数约为1() {
            // Given: 任意文本
            val text = "测试文本归一化"

            // When: 调用真实 API
            val result = client.embed(text)

            // Then: 计算 L2 范数
            val l2Norm = kotlin.math.sqrt(result.map { it * it }.sum())

            // BGE-M3 返回的是归一化向量，L2 范数应该约等于 1
            assertTrue(l2Norm > 0.9f && l2Norm < 1.1f, "归一化向量的 L2 范数应该在 0.9-1.1 之间，实际值: $l2Norm")
        }

        @Test
        @DisplayName("相同文本 - 多次调用返回相同向量")
        fun testSameText_多次调用返回相同向量() {
            // Given: 相同文本
            val text = "还款入口"

            // When: 多次调用
            val result1 = client.embed(text)
            val result2 = client.embed(text)

            // Then: 应该返回相同的向量（浮点数误差允许）
            var maxDiff = 0.0f
            result1.forEachIndexed { i, value ->
                val diff = kotlin.math.abs(value - result2[i])
                maxDiff = maxOf(maxDiff, diff)
            }

            // 允许一定的浮点误差
            assertTrue(maxDiff < 0.001f, "相同文本应该返回相同向量，最大差异: $maxDiff")
        }

        @Test
        @DisplayName("不同文本 - 返回不同向量")
        fun testDifferentText_返回不同向量() {
            // Given: 完全不同的文本
            val text1 = "还款入口"
            val text2 = "用户登录"

            // When: 分别调用
            val result1 = client.embed(text1)
            val result2 = client.embed(text2)

            // Then: 向量应该明显不同
            var maxDiff = 0.0f
            result1.forEachIndexed { i, value ->
                val diff = kotlin.math.abs(value - result2[i])
                maxDiff = maxOf(maxDiff, diff)
            }

            // 不同文本的向量应该有差异
            assertTrue(maxDiff > 0.01f, "不同文本应该返回明显不同的向量，最大差异: $maxDiff")
        }
    }

    @Nested
    @DisplayName("性能测试")
    inner class PerformanceTests {

        @Test
        @DisplayName("单次调用 - 响应时间合理")
        fun testSingleCall_响应时间合理() {
            // Given: 文本
            val text = "测试响应时间"

            // When: 测量单次调用时间
            val startTime = System.currentTimeMillis()
            val result = client.embed(text)
            val endTime = System.currentTimeMillis()

            // Then: 验证结果正确
            assertEquals(1024, result.size)

            // 验证响应时间合理（应该 < 10 秒，考虑到 CPU 模型）
            val duration = endTime - startTime
            assertTrue(duration < 10000, "单次调用应该在 10 秒内完成，实际: ${duration}ms")
        }

        @Test
        @DisplayName("并发调用 - 不抛异常")
        fun testConcurrentCalls_不抛异常() {
            // Given: 多个文本
            val texts = listOf("文本1", "文本2", "文本3", "文本4", "文本5")

            // When: 并发调用（使用 Kotlin 协程）
            runBlocking {
                val results = texts.map { text ->
                    async {
                        client.embed(text)
                    }
                }.awaitAll()

                // Then: 所有调用都应该成功
                assertEquals(5, results.size)
                results.forEach {
                    assertEquals(1024, it.size)
                }
            }
        }
    }
}
