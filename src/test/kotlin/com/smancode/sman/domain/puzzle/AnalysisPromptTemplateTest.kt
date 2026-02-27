package com.smancode.sman.domain.puzzle

import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*

@DisplayName("AnalysisPromptTemplate 测试套件")
class AnalysisPromptTemplateTest {

    // ========== 基础生成测试 ==========

    @Nested
    @DisplayName("基础生成测试")
    inner class BasicGenerationTests {

        @Test
        @DisplayName("应能生成包含目标路径的 Prompt")
        fun `should generate prompt with target path`() {
            val template = AnalysisPromptTemplate()
            val context = AnalysisContext.empty()

            val prompt = template.build("src/main/kotlin/User.kt", context)

            assertTrue(prompt.contains("src/main/kotlin/User.kt"))
        }

        @Test
        @DisplayName("应能生成包含文件内容的 Prompt")
        fun `should generate prompt with file content`() {
            val template = AnalysisPromptTemplate()
            val files = mapOf("User.kt" to "class User(val name: String)")
            val context = AnalysisContext(relatedFiles = files, existingPuzzles = emptyList())

            val prompt = template.build("User.kt", context)

            assertTrue(prompt.contains("class User(val name: String)"))
        }

        @Test
        @DisplayName("应能生成包含用户查询的 Prompt")
        fun `should generate prompt with user query`() {
            val template = AnalysisPromptTemplate()
            val context = AnalysisContext(
                relatedFiles = emptyMap(),
                existingPuzzles = emptyList(),
                userQuery = "这个项目使用什么认证方式？"
            )

            val prompt = template.build("auth", context)

            assertTrue(prompt.contains("这个项目使用什么认证方式？"))
        }
    }

    // ========== 通用性测试 ==========

    @Nested
    @DisplayName("通用性测试")
    inner class GeneralityTests {

        @Test
        @DisplayName("Prompt 不应包含预设的业务术语")
        fun `prompt should not contain preset business terms`() {
            val template = AnalysisPromptTemplate()
            val context = AnalysisContext.empty()

            val prompt = template.build("test.kt", context)

            // 不应包含预设的业务术语
            assertFalse(prompt.contains("订单"))
            assertFalse(prompt.contains("支付"))
            assertFalse(prompt.contains("用户管理"))
        }

        @Test
        @DisplayName("Prompt 不应强制要求特定输出格式")
        fun `prompt should not enforce specific output format`() {
            val template = AnalysisPromptTemplate()
            val context = AnalysisContext.empty()

            val prompt = template.build("test.kt", context)

            // 不应强制要求 "API 接口" 或 "流程图" 等特定格式
            // 只要求 Markdown 格式
            assertTrue(prompt.contains("Markdown") || prompt.contains("markdown"))
            assertFalse(prompt.contains("必须列出 API"))
            assertFalse(prompt.contains("必须画出流程图"))
        }

        @Test
        @DisplayName("Prompt 应引导 LLM 自由发现")
        fun `prompt should guide LLM to discover freely`() {
            val template = AnalysisPromptTemplate()
            val context = AnalysisContext.empty()

            val prompt = template.build("test.kt", context)

            // 应包含引导性语言
            val hasDiscoveryLanguage = prompt.contains("发现") ||
                    prompt.contains("识别") ||
                    prompt.contains("分析") ||
                    prompt.contains("discover") ||
                    prompt.contains("identify") ||
                    prompt.contains("analyze")

            assertTrue(hasDiscoveryLanguage)
        }
    }

    // ========== 输出格式测试 ==========

    @Nested
    @DisplayName("输出格式测试")
    inner class OutputFormatTests {

        @Test
        @DisplayName("Prompt 应要求输出标题")
        fun `prompt should request title`() {
            val template = AnalysisPromptTemplate()
            val context = AnalysisContext.empty()

            val prompt = template.build("test.kt", context).lowercase()

            assertTrue(prompt.contains("title") || prompt.contains("标题"))
        }

        @Test
        @DisplayName("Prompt 应要求输出标签")
        fun `prompt should request tags`() {
            val template = AnalysisPromptTemplate()
            val context = AnalysisContext.empty()

            val prompt = template.build("test.kt", context).lowercase()

            assertTrue(prompt.contains("tag") || prompt.contains("标签"))
        }

        @Test
        @DisplayName("Prompt 应要求输出置信度")
        fun `prompt should request confidence`() {
            val template = AnalysisPromptTemplate()
            val context = AnalysisContext.empty()

            val prompt = template.build("test.kt", context).lowercase()

            assertTrue(prompt.contains("confidence") || prompt.contains("置信度"))
        }
    }

    // ========== Token 限制测试 ==========

    @Nested
    @DisplayName("Token 限制测试")
    inner class TokenLimitTests {

        @Test
        @DisplayName("Prompt 应在合理长度内")
        fun `prompt should be in reasonable length`() {
            val template = AnalysisPromptTemplate()
            val context = AnalysisContext.empty()

            val prompt = template.build("test.kt", context)

            // 模板本身不应过长（< 2000 字符）
            assertTrue(prompt.length < 2000)
        }
    }
}
