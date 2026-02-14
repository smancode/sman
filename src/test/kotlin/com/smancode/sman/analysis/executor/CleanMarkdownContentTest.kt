package com.smancode.sman.analysis.executor

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * cleanMarkdownContent 方法测试
 *
 * 测试目标：验证清理 Markdown 内容的功能
 *
 * 注意：这个测试直接测试 cleanMarkdownContent 方法的正则表达式逻辑
 */
@DisplayName("cleanMarkdownContent 方法测试")
class CleanMarkdownContentTest {

    /**
     * 直接实现 cleanMarkdownContent 的逻辑用于测试
     * 与 AnalysisTaskExecutor.cleanMarkdownContent 保持一致
     */
    private fun cleanMarkdownContent(content: String): String {
        var cleaned = content

        // 1. 过滤 thinking 标签（标准格式）
        cleaned = cleaned.replace(Regex("""<thinking>[\s\S]*?</thinking>""", RegexOption.IGNORE_CASE), "")
        cleaned = cleaned.replace(Regex("""<thinkData>[\s\S]*?</thinkData>""", RegexOption.IGNORE_CASE), "")
        cleaned = cleaned.replace(Regex("""<think(?:ing|Data)?>[\s\S]*?</think(?:ing|Data)?>""", RegexOption.IGNORE_CASE), "")

        // 2. 过滤备用 thinking 格式
        cleaned = cleaned.replace(Regex("""THINKING[\s\S]*?THINKING_END""", RegexOption.IGNORE_CASE), "")
        cleaned = cleaned.replace(Regex("""<reasoning>[\s\S]*?</reasoning>""", RegexOption.IGNORE_CASE), "")

        // 3. 过滤工具调用格式
        cleaned = cleaned.replace(Regex("""\[TOOL_CALL\][\s\S]*?\[/TOOL_CALL\]""", RegexOption.IGNORE_CASE), "")
        cleaned = cleaned.replace(Regex("""<minimax:tool_call>[\s\S]*?</minimax:tool_call>""", RegexOption.IGNORE_CASE), "")
        cleaned = cleaned.replace(Regex("""\{tool\s*=>[^}]+\}""", RegexOption.IGNORE_CASE), "")
        cleaned = cleaned.replace(Regex("""\{parameter[^}]+\}""", RegexOption.IGNORE_CASE), "")
        cleaned = cleaned.replace(Regex("""--\w+\s+["'][^"']+["']""", RegexOption.IGNORE_CASE), "")
        cleaned = cleaned.replace(Regex("""<invoke[\s\S]*?</invoke>""", RegexOption.IGNORE_CASE), "")
        cleaned = cleaned.replace(Regex("""<parameter[\s\S]*?</parameter>""", RegexOption.IGNORE_CASE), "")

        // 4. 过滤 LLM 的思考过程标记（元语言）
        // 移除包含"第X步"或"Step X"的标题行（X可以是数字或中文数字）
        cleaned = cleaned.replace(Regex("""##\s*第[一二三四五六七八九十\d]+\s*步[^\n]*\n?"""), "")
        cleaned = cleaned.replace(Regex("""##\s*Step\s*\d+[^\n]*\n?""", RegexOption.IGNORE_CASE), "")
        // 移除思考语句
        cleaned = cleaned.replace(Regex("""我将按照[^。\n]*[。\n]?"""), "")
        cleaned = cleaned.replace(Regex("""让我[^。\n]*[。\n]?"""), "")
        cleaned = cleaned.replace(Regex("""我来[^。\n]*[。\n]?"""), "")
        cleaned = cleaned.replace(Regex("""首先[^。\n]*[。\n]?"""), "")

        // 5. 过滤 JSON 格式的工具调用（单独一行）
        cleaned = cleaned.lines().filter { line ->
            val trimmedLine = line.trim()
            !(trimmedLine.startsWith("{") && trimmedLine.endsWith("}") &&
              (trimmedLine.contains("\"tool\"") || trimmedLine.contains("\"parameters\"") ||
               trimmedLine.contains("\"invoke\"") || trimmedLine.contains("\"name\"")))
        }.joinToString("\n")

        // 6. 清理多余的空行
        cleaned = cleaned.replace(Regex("""\n{3,}"""), "\n\n")

        // 7. 清理行首行尾空白
        cleaned = cleaned.trim()

        return cleaned
    }

    @Test
    @DisplayName("应该过滤 thinking 标签")
    fun shouldFilterThinkingTags() {
        val input = """
            <thinking>
            这是思考过程
            不应该出现在最终结果中
            </thinking>

            # 项目结构分析报告

            这是真正的分析结果。
        """.trimIndent()

        val result = cleanMarkdownContent(input)

        assertTrue(!result.contains("<thinking>"), "应该移除 thinking 标签")
        assertTrue(!result.contains("这是思考过程"), "应该移除思考内容")
        assertTrue(result.contains("# 项目结构分析报告"), "应该保留真正的分析结果")
    }

    @Test
    @DisplayName("应该过滤 [TOOL_CALL] 格式")
    fun shouldFilterToolCallFormat() {
        val input = """
            # 项目结构分析

            [TOOL_CALL]
            {tool => "find_file", parameters => {
              --filePattern "*.gradle"
            }}
            [/TOOL_CALL]

            ## 模块划分

            这是真正的分析内容。
        """.trimIndent()

        val result = cleanMarkdownContent(input)

        assertTrue(!result.contains("[TOOL_CALL]"), "应该移除 TOOL_CALL 标签")
        assertTrue(!result.contains("find_file"), "应该移除工具调用内容")
        assertTrue(result.contains("## 模块划分"), "应该保留真正的分析结果")
    }

    @Test
    @DisplayName("应该过滤 <minimax:tool_call> 格式")
    fun shouldFilterMinimaxToolCallFormat() {
        val input = """
            # 技术栈识别

            <minimax:tool_call>
            <invoke name="find_file">
            <parameter name="filePattern">pom.xml</parameter>
            </invoke>
            </minimax:tool_call>

            ## 框架层

            | 框架 | 版本 | 用途 |
            |------|------|------|
            | Spring Boot | 3.x | 核心框架 |
        """.trimIndent()

        val result = cleanMarkdownContent(input)

        assertTrue(!result.contains("<minimax:tool_call>"), "应该移除 minimax:tool_call 标签")
        assertTrue(!result.contains("<invoke"), "应该移除 invoke 标签")
        assertTrue(result.contains("## 框架层"), "应该保留真正的分析结果")
    }

    @Test
    @DisplayName("应该过滤 LLM 思考过程")
    fun shouldFilterLlmThinkingProcess() {
        val input = """
# 项目结构分析提示词

## 第一步：识别构建系统
我将按照系统化的步骤来分析项目结构。
首先查找构建配置文件。
让我开始执行这个分析流程。

# 项目结构分析报告

## 概述
这是一个 Gradle 多模块项目。
""".trimIndent()

        val result = cleanMarkdownContent(input)

        // 调试：打印结果
        println("=== 测试结果 ===")
        println(result)
        println("=== 原始输入 ===")
        println(input)

        // 由于 "## 第一步" 后面紧跟 "我将按照"，但 "## 概述" 是有效的标题
        // 我们期望：移除步骤标记和思考语句，保留最终报告
        assertTrue(!result.contains("## 第一步"), "应该移除步骤标记: result=$result")
        assertTrue(!result.contains("我将按照"), "应该移除思考过程: result=$result")
        assertTrue(result.contains("# 项目结构分析报告"), "应该保留报告标题: result=$result")
    }

    @Test
    @DisplayName("应该过滤 JSON 格式的工具调用")
    fun shouldFilterJsonToolCalls() {
        val input = """
            # 分析结果

            {"tool": "find_file", "parameters": {"filePattern": "*.java"}}

            ## 实体列表

            | 实体类 | 表名 | 字段数 |
            |--------|------|--------|
            | User | t_user | 10 |
        """.trimIndent()

        val result = cleanMarkdownContent(input)

        assertTrue(!result.contains("{\"tool\""), "应该移除 JSON 工具调用")
        assertTrue(result.contains("## 实体列表"), "应该保留真正的分析结果")
    }

    @Test
    @DisplayName("应该清理多余空行")
    fun shouldCleanExtraEmptyLines() {
        val input = """
            # 项目结构分析报告





            ## 概述

            这是概述内容。
        """.trimIndent()

        val result = cleanMarkdownContent(input)

        assertTrue(!result.contains("\n\n\n"), "应该清理多余空行")
        assertTrue(result.contains("# 项目结构分析报告"), "应该保留报告标题")
    }

    @Test
    @DisplayName("应该保留有效的 Markdown 内容")
    fun shouldPreserveValidMarkdown() {
        val input = """
            # 项目结构分析报告

            ## 概述

            这是一个 Gradle 多模块项目，包含以下模块：

            ## 模块划分

            | 模块名 | 路径 | 业务含义 | 关键类/接口 |
            |--------|------|----------|-------------|
            | core | ./core | 核心模块 | CoreService |
            | web | ./web | Web 模块 | WebController |

            ## 架构评估

            - 模块划分清晰
            - 依赖关系合理
        """.trimIndent()

        val result = cleanMarkdownContent(input)

        assertEquals(input, result, "有效的 Markdown 内容应该被完整保留")
    }

    @Test
    @DisplayName("应该处理空内容")
    fun shouldHandleEmptyContent() {
        val input = ""
        val result = cleanMarkdownContent(input)
        assertEquals("", result, "空内容应该返回空字符串")
    }

    @Test
    @DisplayName("应该处理只有 thinking 标签的内容")
    fun shouldHandleOnlyThinkingTags() {
        val input = """
            <thinking>
            这是思考过程
            </thinking>
        """.trimIndent()

        val result = cleanMarkdownContent(input)
        assertEquals("", result, "只有 thinking 标签的内容应该返回空字符串")
    }

    @Test
    @DisplayName("应该处理 autoloop 项目实际生成的内容")
    fun shouldHandleAutoloopActualContent() {
        // 这是实际生成的 01_project_structure.md 文件内容
        val input = """
            # 项目结构分析

            我将按照系统化的步骤来分析项目结构。

            ## 第一步：识别构建系统
            [TOOL_CALL]
            {tool => "find_file", parameters => {
              --filePattern "*.gradle"
            }}
            [/TOOL_CALL]
            [TOOL_CALL]
            {tool => "find_file", parameters => {
              --filePattern "pom.xml"
            }}
            [/TOOL_CALL]
        """.trimIndent()

        val result = cleanMarkdownContent(input)

        assertTrue(!result.contains("[TOOL_CALL]"), "应该移除 TOOL_CALL 标签")
        assertTrue(!result.contains("--filePattern"), "应该移除参数格式")
        assertTrue(!result.contains("## 第一步"), "应该移除步骤标记")
        assertTrue(!result.contains("我将按照"), "应该移除思考过程")
    }

    @Test
    @DisplayName("应该处理 02_tech_stack.md 的实际内容")
    fun shouldHandleTechStackActualContent() {
        val input = """
            # 技术栈识别提示词

            <system_config>
                <language_rule>
                    <thinking_language>English (For logic & reasoning)</thinking_language>
                    <output_language>Simplified Chinese (For user readability)</output_language>
                    <terminology_preservation>Keep technical terms in English</terminology_preservation>
                </language_rule>
            </system_config>

            <minimax:tool_call>
            <invoke name="find_file">
            <parameter name="filePattern">*.gradle</parameter>
            </invoke>
            <invoke name="find_file">
            <parameter name="filePattern">pom.xml</parameter>
            </invoke>
            </minimax:tool_call>
        """.trimIndent()

        val result = cleanMarkdownContent(input)

        assertTrue(!result.contains("<minimax:tool_call>"), "应该移除 minimax:tool_call 标签")
        assertTrue(!result.contains("<invoke"), "应该移除 invoke 标签")
    }
}
