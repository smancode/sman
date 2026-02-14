package com.smancode.sman.evolution.guard

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ToolCallDeduplicator 测试
 */
@DisplayName("ToolCallDeduplicator 测试")
class ToolCallDeduplicatorTest {

    private lateinit var deduplicator: ToolCallDeduplicator

    @BeforeEach
    fun setUp() {
        deduplicator = ToolCallDeduplicator()
    }

    @Nested
    @DisplayName("重复检测测试")
    inner class DuplicateDetectionTest {

        @Test
        @DisplayName("相同工具名和参数应被识别为重复")
        fun `same tool name and parameters should be detected as duplicate`() {
            // Given: 记录一次工具调用
            val toolName = "read_file"
            val params = mapOf("path" to "/src/main.kt")
            deduplicator.recordCall(toolName, params, "file content")

            // When: 检查相同调用
            val isDuplicate = deduplicator.isDuplicate(toolName, params)

            // Then: 应该是重复
            assertTrue(isDuplicate)
        }

        @Test
        @DisplayName("不同参数不应被识别为重复")
        fun `different parameters should not be detected as duplicate`() {
            // Given: 记录一次工具调用
            val toolName = "read_file"
            val params1 = mapOf("path" to "/src/main.kt")
            deduplicator.recordCall(toolName, params1, "content 1")

            // When: 检查不同参数的调用
            val params2 = mapOf("path" to "/src/test.kt")
            val isDuplicate = deduplicator.isDuplicate(toolName, params2)

            // Then: 不应该是重复
            assertFalse(isDuplicate)
        }

        @Test
        @DisplayName("相同参数不同顺序应被识别为相同调用")
        fun `same parameters in different order should be detected as duplicate`() {
            // Given: 记录一次工具调用
            val toolName = "search"
            val params1 = mapOf("pattern" to "fun", "path" to "/src")
            deduplicator.recordCall(toolName, params1, "results")

            // When: 检查相同参数但顺序不同
            val params2 = mapOf("path" to "/src", "pattern" to "fun")
            val isDuplicate = deduplicator.isDuplicate(toolName, params2)

            // Then: 应该是重复（因为参数哈希相同）
            assertTrue(isDuplicate)
        }
    }

    @Nested
    @DisplayName("缓存结果测试")
    inner class CachedResultTest {

        @Test
        @DisplayName("应能获取缓存的结果")
        fun `should be able to get cached result`() {
            // Given: 记录一次工具调用
            val toolName = "read_file"
            val params = mapOf("path" to "/src/main.kt")
            val expectedResult = "file content here"
            deduplicator.recordCall(toolName, params, expectedResult)

            // When: 获取缓存结果
            val cachedResult = deduplicator.getCachedResult(toolName, params)

            // Then: 应该返回缓存的结果
            assertEquals(expectedResult, cachedResult)
        }

        @Test
        @DisplayName("不存在的调用应返回 null")
        fun `non existent call should return null`() {
            // Given: 一个未记录的工具调用
            val toolName = "unknown_tool"
            val params = mapOf("key" to "value")

            // When: 获取缓存结果
            val cachedResult = deduplicator.getCachedResult(toolName, params)

            // Then: 应该返回 null
            assertNull(cachedResult)
        }

        @Test
        @DisplayName("应能获取完整的缓存条目")
        fun `should be able to get complete cache entry`() {
            // Given: 记录一次工具调用
            val toolName = "read_file"
            val params = mapOf("path" to "/src/main.kt")
            val result = "file content"
            deduplicator.recordCall(toolName, params, result)

            // When: 获取缓存条目
            val entry = deduplicator.getCacheEntry(toolName, params)

            // Then: 条目应该包含完整信息
            assertEquals(toolName, entry?.toolName)
            assertEquals(params, entry?.parameters)
            assertEquals(result, entry?.result)
        }
    }

    @Nested
    @DisplayName("LRU 缓存测试")
    inner class LruCacheTest {

        @Test
        @DisplayName("缓存应正确记录大小")
        fun `cache should track size correctly`() {
            // Given: 空缓存
            assertTrue(deduplicator.isEmpty())
            assertEquals(0, deduplicator.size())

            // When: 记录多次调用
            deduplicator.recordCall("tool1", mapOf("p" to "1"), "result1")
            deduplicator.recordCall("tool2", mapOf("p" to "2"), "result2")
            deduplicator.recordCall("tool3", mapOf("p" to "3"), "result3")

            // Then: 大小应该正确
            assertFalse(deduplicator.isEmpty())
            assertEquals(3, deduplicator.size())
        }

        @Test
        @DisplayName("清空缓存应有效")
        fun `clear should empty cache`() {
            // Given: 缓存中有记录
            deduplicator.recordCall("tool", mapOf("p" to "v"), "result")
            assertEquals(1, deduplicator.size())

            // When: 清空缓存
            deduplicator.clear()

            // Then: 缓存应为空
            assertTrue(deduplicator.isEmpty())
            assertEquals(0, deduplicator.size())
        }

        @Test
        @DisplayName("LRU 应在达到最大容量时淘汰旧条目")
        fun `lru should evict old entries when max capacity reached`() {
            // Given: 创建小容量去重器
            val smallDeduplicator = ToolCallDeduplicator(maxSize = 3)

            // When: 记录超过容量的调用
            smallDeduplicator.recordCall("tool1", mapOf("p" to "1"), "result1")
            smallDeduplicator.recordCall("tool2", mapOf("p" to "2"), "result2")
            smallDeduplicator.recordCall("tool3", mapOf("p" to "3"), "result3")
            smallDeduplicator.recordCall("tool4", mapOf("p" to "4"), "result4")

            // Then: 最早的条目应该被淘汰
            assertEquals(3, smallDeduplicator.size())
            assertFalse(smallDeduplicator.isDuplicate("tool1", mapOf("p" to "1")))
        }
    }

    @Nested
    @DisplayName("嵌套参数哈希测试")
    inner class NestedParameterHashTest {

        @Test
        @DisplayName("嵌套 Map 参数应正确计算哈希")
        fun `nested map parameters should hash correctly`() {
            // Given: 嵌套参数
            val toolName = "search"
            val nestedParams = mapOf(
                "query" to "test",
                "options" to mapOf("caseSensitive" to "true", "regex" to "false")
            )
            deduplicator.recordCall(toolName, nestedParams, "results")

            // When: 检查相同嵌套参数
            val isDuplicate = deduplicator.isDuplicate(toolName, nestedParams)

            // Then: 应该识别为重复
            assertTrue(isDuplicate)
        }

        @Test
        @DisplayName("List 参数应正确计算哈希")
        fun `list parameters should hash correctly`() {
            // Given: 包含 List 的参数
            val toolName = "batch_read"
            val params = mapOf(
                "files" to listOf("/a.kt", "/b.kt", "/c.kt")
            )
            deduplicator.recordCall(toolName, params, "batch results")

            // When: 检查相同 List 参数
            val isDuplicate = deduplicator.isDuplicate(toolName, params)

            // Then: 应该识别为重复
            assertTrue(isDuplicate)
        }
    }
}
