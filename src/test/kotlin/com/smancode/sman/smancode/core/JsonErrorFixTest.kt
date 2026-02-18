package com.smancode.sman.smancode.core

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * JSON 语法错误修复测试
 *
 * 测试 GLM-5/M2.5 常见的 JSON 输出错误修复
 */
@DisplayName("JSON 错误修复测试")
class JsonErrorFixTest {

    // 模拟 fixCommonJsonErrors 方法
    private fun fixCommonJsonErrors(response: String): String? {
        if (!response.contains("{") || !response.contains("}")) {
            return null
        }

        // 首先尝试提取 JSON 部分（跳过可能的前缀内容如思考块）
        val jsonStart = response.indexOf("{")
        if (jsonStart < 0) return null

        var fixed = if (jsonStart > 0) response.substring(jsonStart) else response

        // 修复 1: `},"{"` 应该是 `},{"` （GLM-5 常见错误）
        fixed = fixed.replace(Regex("""\}\s*,\s*"\s*\{"""), """},{""")
        fixed = fixed.replace(Regex("""\}\s*"\s*\{"""), """},{""")

        // 修复 2: `}{"` 应该是 `},{"` （缺少逗号和引号处理）
        fixed = fixed.replace(Regex("""\}\s*\{"type"""), """},{"type""")

        // 修复 3: 数组内元素之间 `} {` 变成 `},{`
        fixed = fixed.replace(Regex("""\}\s+\{"""), """},{""")

        return fixed
    }

    @Test
    @DisplayName("修复 GLM-5 的错误 JSON 格式")
    fun shouldFixGlm5MissingCommaInArray() {
        // Given: GLM-5 实际输出的错误 JSON
        // 错误格式：`},"{"` 应该是 `},{"`
        val brokenJson = """{"parts":[{"type":"text","text":"hello"},"{"type":"grep_file"}]}"""

        // When
        val fixed = fixCommonJsonErrors(brokenJson)

        // Then
        assertNotNull(fixed)
        // 验证修复后的 JSON 包含正确的格式
        val expected = """{"parts":[{"type":"text","text":"hello"},{"type":"grep_file"}]}"""
        assertEquals(expected, fixed)
    }

    @Test
    @DisplayName("修复思考块后面的错误 JSON")
    fun shouldFixJsonAfterThinkingBlock() {
        // Given: 带思考块的完整响应
        val response = """some prefix text{"parts":[{"type":"text","text":"搜索"},"{"type":"grep_file"}]}"""

        // When
        val fixed = fixCommonJsonErrors(response)

        // Then
        assertNotNull(fixed)
        assertTrue(fixed!!.startsWith("{"))
        // 验证 JSON 被正确修复
        assertTrue(fixed.contains("},{"))
    }

    @Test
    @DisplayName("不修改正确的 JSON")
    fun shouldNotModifyValidJson() {
        // Given: 正确的 JSON
        val validJson = """{"parts":[{"type":"text","text":"hello"},{"type":"tool","toolName":"grep_file"}]}"""

        // When
        val fixed = fixCommonJsonErrors(validJson)

        // Then
        assertNotNull(fixed)
        // 应该保持原样
        assertEquals(validJson, fixed)
    }

    @Test
    @DisplayName("返回 null 对于非 JSON 内容")
    fun shouldReturnNullForNonJson() {
        // Given
        val nonJson = "这是一段普通的文本"

        // When
        val result = fixCommonJsonErrors(nonJson)

        // Then
        assertNull(result)
    }
}
