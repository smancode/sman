package com.smancode.sman.tools

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@DisplayName("AbstractTool 单元测试")
class AbstractToolTest {

    private inner class TestTool : AbstractTool() {
        override fun getName() = "test"
        override fun getDescription() = "Test tool"
        override fun getParameters(): Map<String, ParameterDef> = mapOf(
            "message" to ParameterDef("message", String::class.java, false, "Test message"),
            "count" to ParameterDef("count", Int::class.java, false, "Test count")
        )
        override fun execute(projectKey: String, params: Map<String, Any>): ToolResult {
            return ToolResult.success("OK", null, "OK")
        }

        // Expose protected methods for testing
        fun testGetOptString(params: Map<String, Any>, key: String, defaultValue: String): String {
            return getOptString(params, key, defaultValue)
        }

        fun testGetOptInt(params: Map<String, Any>, key: String, defaultValue: Int): Int {
            return getOptInt(params, key, defaultValue)
        }

        fun testGetOptBoolean(params: Map<String, Any>, key: String, defaultValue: Boolean): Boolean {
            return getOptBoolean(params, key, defaultValue)
        }

        fun testGetReqString(params: Map<String, Any>, key: String): String {
            return getReqString(params, key)
        }

        fun testGetReqInt(params: Map<String, Any>, key: String): Int {
            return getReqInt(params, key)
        }
    }

    private val tool = TestTool()

    @Nested
    @DisplayName("获取可选字符串参数")
    inner class GetOptString {

        @Test
        @DisplayName("参数存在返回字符串值")
        fun testGetOptString_exists_returnsString() {
            // Given
            val params = mapOf("key" to "value")

            // When
            val result = tool.testGetOptString(params, "key", "default")

            // Then
            assertEquals("value", result)
        }

        @Test
        @DisplayName("参数不存在返回默认值")
        fun testGetOptString_notExists_returnsDefault() {
            // Given
            val params = mapOf<String, Any>("other" to "value")

            // When
            val result = tool.testGetOptString(params, "key", "default")

            // Then
            assertEquals("default", result)
        }

        @Test
        @DisplayName("参数为null返回默认值")
        fun testGetOptString_null_returnsDefault() {
            // Given
            val params: Map<String, Any?> = mapOf("key" to null)

            // When
            val result = tool.testGetOptString(params as Map<String, Any>, "key", "default")

            // Then
            assertEquals("default", result)
        }

        @Test
        @DisplayName("非字符串参数转换为字符串")
        fun testGetOptString_nonString_convertsToString() {
            // Given
            val params = mapOf("key" to 123)

            // When
            val result = tool.testGetOptString(params, "key", "default")

            // Then
            assertEquals("123", result)
        }
    }

    @Nested
    @DisplayName("获取可选整数参数")
    inner class GetOptInt {

        @Test
        @DisplayName("整数参数返回整数值")
        fun testGetOptInt_integer_returnsInt() {
            // Given
            val params = mapOf("key" to 42)

            // When
            val result = tool.testGetOptInt(params, "key", 0)

            // Then
            assertEquals(42, result)
        }

        @Test
        @DisplayName("Long参数转换为整数")
        fun testGetOptInt_long_convertsToInt() {
            // Given
            val params = mapOf("key" to 42L)

            // When
            val result = tool.testGetOptInt(params, "key", 0)

            // Then
            assertEquals(42, result)
        }

        @Test
        @DisplayName("Double参数转换为整数")
        fun testGetOptInt_double_convertsToInt() {
            // Given
            val params = mapOf("key" to 42.9)

            // When
            val result = tool.testGetOptInt(params, "key", 0)

            // Then
            assertEquals(42, result)
        }

        @Test
        @DisplayName("字符串参数解析为整数")
        fun testGetOptInt_string_parsesToInt() {
            // Given
            val params = mapOf("key" to "42")

            // When
            val result = tool.testGetOptInt(params, "key", 0)

            // Then
            assertEquals(42, result)
        }

        @Test
        @DisplayName("无效字符串返回默认值")
        fun testGetOptInt_invalidString_returnsDefault() {
            // Given
            val params = mapOf("key" to "not-a-number")

            // When
            val result = tool.testGetOptInt(params, "key", 0)

            // Then
            assertEquals(0, result)
        }

        @Test
        @DisplayName("参数不存在返回默认值")
        fun testGetOptInt_notExists_returnsDefault() {
            // Given
            val params = mapOf<String, Any>("other" to 123)

            // When
            val result = tool.testGetOptInt(params, "key", 99)

            // Then
            assertEquals(99, result)
        }

        @Test
        @DisplayName("参数为null返回默认值")
        fun testGetOptInt_null_returnsDefault() {
            // Given
            val params: Map<String, Any?> = mapOf("key" to null)

            // When
            val result = tool.testGetOptInt(params as Map<String, Any>, "key", 99)

            // Then
            assertEquals(99, result)
        }
    }

    @Nested
    @DisplayName("获取可选布尔参数")
    inner class GetOptBoolean {

        @Test
        @DisplayName("布尔参数返回布尔值")
        fun testGetOptBoolean_boolean_returnsBoolean() {
            // Given
            val params = mapOf("key" to true)

            // When
            val result = tool.testGetOptBoolean(params, "key", false)

            // Then
            assertTrue(result)
        }

        @Test
        @DisplayName("字符串'true'解析为true")
        fun testGetOptBoolean_stringTrue_parsesToTrue() {
            // Given
            val params = mapOf("key" to "true")

            // When
            val result = tool.testGetOptBoolean(params, "key", false)

            // Then
            assertTrue(result)
        }

        @Test
        @DisplayName("字符串'false'解析为false")
        fun testGetOptBoolean_stringFalse_parsesToFalse() {
            // Given
            val params = mapOf("key" to "false")

            // When
            val result = tool.testGetOptBoolean(params, "key", true)

            // Then
            assertFalse(result)
        }

        @Test
        @DisplayName("参数不存在返回默认值")
        fun testGetOptBoolean_notExists_returnsDefault() {
            // Given
            val params = mapOf<String, Any>("other" to true)

            // When
            val result = tool.testGetOptBoolean(params, "key", true)

            // Then
            assertTrue(result)
        }
    }

    @Nested
    @DisplayName("获取必需字符串参数")
    inner class GetReqString {

        @Test
        @DisplayName("参数存在返回字符串值")
        fun testGetReqString_exists_returnsString() {
            // Given
            val params = mapOf("key" to "value")

            // When
            val result = tool.testGetReqString(params, "key")

            // Then
            assertEquals("value", result)
        }

        @Test
        @DisplayName("参数不存在抛出异常")
        fun testGetReqString_notExists_throwsException() {
            // Given
            val params = mapOf<String, Any>("other" to "value")

            // When & Then
            val exception = assertFailsWith<IllegalArgumentException> {
                tool.testGetReqString(params, "key")
            }

            assertTrue(exception.message?.contains("缺少必需参数") == true)
        }

        @Test
        @DisplayName("参数为null抛出异常")
        fun testGetReqString_null_throwsException() {
            // Given
            val params: Map<String, Any?> = mapOf("key" to null)

            // When & Then
            val exception = assertFailsWith<IllegalArgumentException> {
                tool.testGetReqString(params as Map<String, Any>, "key")
            }

            assertTrue(exception.message?.contains("缺少必需参数") == true)
        }

        @Test
        @DisplayName("空字符串抛出异常")
        fun testGetReqString_empty_throwsException() {
            // Given
            val params = mapOf("key" to "   ")

            // When & Then
            val exception = assertFailsWith<IllegalArgumentException> {
                tool.testGetReqString(params, "key")
            }

            assertTrue(exception.message?.contains("参数不能为空") == true)
        }

        @Test
        @DisplayName("自动trim字符串")
        fun testGetReqString_whitespace_trims() {
            // Given
            val params = mapOf("key" to "  value  ")

            // When
            val result = tool.testGetReqString(params, "key")

            // Then
            assertEquals("value", result)
        }
    }

    @Nested
    @DisplayName("获取必需整数参数")
    inner class GetReqInt {

        @Test
        @DisplayName("整数参数返回整数值")
        fun testGetReqInt_integer_returnsInt() {
            // Given
            val params = mapOf("key" to 42)

            // When
            val result = tool.testGetReqInt(params, "key")

            // Then
            assertEquals(42, result)
        }

        @Test
        @DisplayName("字符串参数解析为整数")
        fun testGetReqInt_string_parsesToInt() {
            // Given
            val params = mapOf("key" to "42")

            // When
            val result = tool.testGetReqInt(params, "key")

            // Then
            assertEquals(42, result)
        }

        @Test
        @DisplayName("参数不存在抛出异常")
        fun testGetReqInt_notExists_throwsException() {
            // Given
            val params = mapOf<String, Any>("other" to 42)

            // When & Then
            val exception = assertFailsWith<IllegalArgumentException> {
                tool.testGetReqInt(params, "key")
            }

            assertTrue(exception.message?.contains("缺少必需参数") == true)
        }

        @Test
        @DisplayName("无效字符串抛出异常")
        fun testGetReqInt_invalidString_throwsException() {
            // Given
            val params = mapOf("key" to "not-a-number")

            // When & Then
            val exception = assertFailsWith<IllegalArgumentException> {
                tool.testGetReqInt(params, "key")
            }

            assertTrue(exception.message?.contains("参数类型错误") == true)
        }

        @Test
        @DisplayName("参数为null抛出异常")
        fun testGetReqInt_null_throwsException() {
            // Given
            val params: Map<String, Any?> = mapOf("key" to null)

            // When & Then
            assertFailsWith<IllegalArgumentException> {
                tool.testGetReqInt(params as Map<String, Any>, "key")
            }
        }
    }
}
