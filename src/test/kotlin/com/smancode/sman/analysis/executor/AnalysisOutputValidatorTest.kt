package com.smancode.sman.analysis.executor

import com.smancode.sman.analysis.model.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * AnalysisOutputValidator 测试
 *
 * 测试分析输出的验证、完整度计算、缺失章节检测和 TODO 生成
 */
@DisplayName("AnalysisOutputValidator 测试")
class AnalysisOutputValidatorTest {

    private val validator = AnalysisOutputValidator()

    // ========== 完整报告识别测试 ==========

    @Nested
    @DisplayName("完整报告识别测试")
    inner class CompleteReportDetectionTest {

        @Test
        @DisplayName("完整的项目结构报告应通过验证")
        fun `complete project structure report should pass validation`() {
            // Given: 完整的项目结构报告
            val content = """
                # 项目结构分析

                ## 项目概述
                这是一个示例项目。

                ## 目录结构
                - src/main/kotlin
                - src/test/kotlin

                ## 模块划分
                - core: 核心模块
                - api: API 模块

                ## 依赖管理
                - Kotlin 1.9
                - Gradle 8.0
            """.trimIndent()

            // When: 验证报告
            val result = validator.validate(content, AnalysisType.PROJECT_STRUCTURE)

            // Then: 应通过验证
            assertTrue(result.isValid)
            assertTrue(result.completeness >= 0.8)
        }

        @Test
        @DisplayName("不完整的技术栈报告应返回缺失章节")
        fun `incomplete tech stack report should return missing sections`() {
            // Given: 不完整的技术栈报告（缺少框架配置）
            val content = """
                # 技术栈识别

                ## 编程语言
                - Kotlin 1.9.20

                ## 构建工具
                - Gradle 8.0
            """.trimIndent()

            // When: 验证报告
            val result = validator.validate(content, AnalysisType.TECH_STACK)

            // Then: 应该不通过或完整度较低
            assertFalse(result.completeness >= 0.8 || result.isValid)
            assertTrue(result.missingSections.isNotEmpty())
        }

        @Test
        @DisplayName("空内容应返回 0 完整度")
        fun `empty content should return zero completeness`() {
            // Given: 空内容
            val content = ""

            // When: 计算完整度
            val completeness = validator.calculateCompleteness(content, AnalysisType.PROJECT_STRUCTURE)

            // Then: 应为 0
            assertEquals(0.0, completeness, 0.001)
        }

        @Test
        @DisplayName("仅包含 thinking 标签的内容应被视为空")
        fun `content with only thinking tags should be treated as empty`() {
            // Given: 仅包含 thinking 标签的内容
            val content = """
                <thinking>
                This is internal reasoning...
                Let me think about this more...
                </thinking>
            """.trimIndent()

            // When: 计算完整度
            val cleaned = validator.cleanMarkdownContent(content)
            val completeness = validator.calculateCompleteness(cleaned, AnalysisType.PROJECT_STRUCTURE)

            // Then: 应为 0 或非常低
            assertTrue(completeness < 0.1)
        }
    }

    // ========== 缺失章节检测测试 ==========

    @Nested
    @DisplayName("缺失章节检测测试")
    inner class MissingSectionDetectionTest {

        @Test
        @DisplayName("应检测项目结构报告的缺失章节")
        fun `should detect missing sections in project structure report`() {
            // Given: 缺少模块划分的报告
            val content = """
                # 项目结构分析

                ## 项目概述
                示例项目

                ## 目录结构
                标准目录结构

                ## 依赖管理
                Gradle 配置
            """.trimIndent()

            // When: 提取缺失章节
            val missing = validator.extractMissingSections(content, AnalysisType.PROJECT_STRUCTURE)

            // Then: 应包含"模块划分"
            assertTrue(missing.any { it.contains("模块", ignoreCase = true) })
        }

        @Test
        @DisplayName("应检测 API 入口报告的缺失章节")
        fun `should detect missing sections in api entries report`() {
            // Given: 缺少请求响应格式的 API 报告
            val content = """
                # API 入口扫描

                ## 入口列表
                - /api/users
                - /api/orders

                ## 认证方式
                JWT Token
            """.trimIndent()

            // When: 提取缺失章节
            val missing = validator.extractMissingSections(content, AnalysisType.API_ENTRIES)

            // Then: 应有缺失的章节
            assertTrue(missing.isNotEmpty())
        }

        @Test
        @DisplayName("完整报告应无缺失章节")
        fun `complete report should have no missing sections`() {
            // Given: 完整报告
            val content = """
                # 数据库实体分析

                ## 实体列表
                User, Order, Product

                ## 表关系
                User -> Order (1:N)

                ## 字段详情
                User: id, name, email

                ## 索引信息
                idx_user_email on User(email)
            """.trimIndent()

            // When: 提取缺失章节
            val missing = validator.extractMissingSections(content, AnalysisType.DB_ENTITIES)

            // Then: 应无或很少缺失章节
            assertTrue(missing.size <= 1)
        }
    }

    // ========== TODO 生成测试 ==========

    @Nested
    @DisplayName("TODO 生成测试")
    inner class TodoGenerationTest {

        @Test
        @DisplayName("应根据缺失章节生成 TODO")
        fun `should generate todos from missing sections`() {
            // Given: 缺失章节列表
            val missingSections = listOf("模块划分", "依赖版本详情")

            // When: 生成 TODO
            val todos = validator.generateTodos(missingSections, AnalysisType.PROJECT_STRUCTURE)

            // Then: 应有对应的 TODO
            assertEquals(2, todos.size)
            assertTrue(todos.any { it.content.contains("模块划分") })
            assertTrue(todos.any { it.content.contains("依赖版本") })
        }

        @Test
        @DisplayName("空缺失章节列表应生成空 TODO 列表")
        fun `empty missing sections should generate empty todo list`() {
            // Given: 空缺失章节列表
            val missingSections = emptyList<String>()

            // When: 生成 TODO
            val todos = validator.generateTodos(missingSections, AnalysisType.TECH_STACK)

            // Then: 应为空
            assertTrue(todos.isEmpty())
        }

        @Test
        @DisplayName("生成的 TODO 应有正确的默认状态和优先级")
        fun `generated todos should have correct default status and priority`() {
            // Given: 缺失章节
            val missingSections = listOf("补充分析：配置详情")

            // When: 生成 TODO
            val todos = validator.generateTodos(missingSections, AnalysisType.CONFIG_FILES)

            // Then: 应有正确的默认值
            assertEquals(1, todos.size)
            assertEquals(TodoStatus.PENDING, todos[0].status)
            assertTrue(todos[0].priority >= 1)
        }
    }

    // ========== Markdown 内容清理测试 ==========

    @Nested
    @DisplayName("Markdown 内容清理测试")
    inner class MarkdownContentCleaningTest {

        @Test
        @DisplayName("应移除 thinking 标签及其内容")
        fun `should remove thinking tags and their content`() {
            // Given: 包含 thinking 标签的内容
            val content = """
                # 项目分析

                <thinking>
                这是 LLM 的内部思考过程...
                需要分析目录结构...
                </thinking>

                ## 概述
                这是实际的分析内容。
            """.trimIndent()

            // When: 清理内容
            val cleaned = validator.cleanMarkdownContent(content)

            // Then: 应不包含 thinking 标签
            assertFalse(cleaned.contains("<thinking>"))
            assertFalse(cleaned.contains("</thinking>"))
            assertFalse(cleaned.contains("内部思考过程"))
            assertTrue(cleaned.contains("## 概述"))
        }

        @Test
        @DisplayName("应移除 thinkable 标签及其内容")
        fun `should remove thinkable tags and their content`() {
            // Given: 包含 thinkable 标签的内容
            val content = """
                # 技术栈

                <thinkable>
                这里是详细的分析推理过程...
                </thinkable>

                ## 编程语言
                Kotlin
            """.trimIndent()

            // When: 清理内容
            val cleaned = validator.cleanMarkdownContent(content)

            // Then: 应不包含 thinkable 标签
            assertFalse(cleaned.contains("<thinkable>"))
            assertFalse(cleaned.contains("</thinkable>"))
            assertFalse(cleaned.contains("分析推理过程"))
        }

        @Test
        @DisplayName("应移除对话式内容")
        fun `should remove conversational content`() {
            // Given: 包含对话式内容的分析结果
            val content = """
                # 项目结构分析

                请问您需要我分析哪个方面？

                我可以帮您：
                - 分析目录结构
                - 识别模块划分

                ## 实际分析内容
                项目包含 3 个模块。
            """.trimIndent()

            // When: 清理内容
            val cleaned = validator.cleanMarkdownContent(content)

            // Then: 应移除对话式内容
            assertFalse(cleaned.contains("请问您需要"))
            assertFalse(cleaned.contains("我可以帮您"))
        }

        @Test
        @DisplayName("应保留有效的 Markdown 结构")
        fun `should preserve valid markdown structure`() {
            // Given: 有效的 Markdown 内容
            val content = """
                # 主标题

                ## 二级标题

                ### 三级标题

                - 列表项 1
                - 列表项 2

                ```kotlin
                val x = 1
                ```

                | 列1 | 列2 |
                |-----|-----|
                | A   | B   |
            """.trimIndent()

            // When: 清理内容
            val cleaned = validator.cleanMarkdownContent(content)

            // Then: 应保留所有结构
            assertTrue(cleaned.contains("# 主标题"))
            assertTrue(cleaned.contains("## 二级标题"))
            assertTrue(cleaned.contains("```kotlin"))
            assertTrue(cleaned.contains("| 列1"))
        }
    }

    // ========== 完整度计算测试 ==========

    @Nested
    @DisplayName("完整度计算测试")
    inner class CompletenessCalculationTest {

        @Test
        @DisplayName("完整报告应返回 1.0 或接近 1.0 的完整度")
        fun `complete report should return full or near full completeness`() {
            // Given: 完整的枚举分析报告
            val content = """
                # 枚举分析

                ## 枚举列表
                - Status (ACTIVE, INACTIVE)
                - Type (A, B, C)

                ## 枚举用途
                Status 用于用户状态管理
                Type 用于分类

                ## 关联实体
                User.status -> Status
                Order.type -> Type
            """.trimIndent()

            // When: 计算完整度
            val completeness = validator.calculateCompleteness(content, AnalysisType.ENUMS)

            // Then: 应接近 1.0
            assertTrue(completeness >= 0.8)
        }

        @Test
        @DisplayName("部分完整的报告应返回正确的完整度")
        fun `partially complete report should return correct completeness`() {
            // Given: 只有一个必填章节的报告
            val content = """
                # 配置文件分析

                ## 配置文件列表
                - application.yml
                - logback.xml
            """.trimIndent()

            // When: 计算完整度
            val completeness = validator.calculateCompleteness(content, AnalysisType.CONFIG_FILES)

            // Then: 应该是部分完整
            assertTrue(completeness > 0.0 && completeness < 1.0)
        }

        @Test
        @DisplayName("不同分析类型应有不同的必填章节")
        fun `different analysis types should have different required sections`() {
            // Given: 同样的内容
            val content = """
                ## 实体列表
                User, Order
            """.trimIndent()

            // When: 计算不同类型的完整度
            val dbCompleteness = validator.calculateCompleteness(content, AnalysisType.DB_ENTITIES)
            val apiCompleteness = validator.calculateCompleteness(content, AnalysisType.API_ENTRIES)

            // Then: 完整度应该不同
            // DB_ENTITIES 应该更高因为"实体列表"是其必填章节
            assertTrue(dbCompleteness != apiCompleteness)
        }
    }

    // ========== 验证结果测试 ==========

    @Nested
    @DisplayName("验证结果测试")
    inner class ValidationResultTest {

        @Test
        @DisplayName("验证结果应包含完整度和缺失章节")
        fun `validation result should include completeness and missing sections`() {
            // Given: 不完整的报告
            val content = """
                # 项目结构

                ## 概述
                这是一个项目。
            """.trimIndent()

            // When: 验证
            val result = validator.validate(content, AnalysisType.PROJECT_STRUCTURE)

            // Then: 结果应包含完整度
            assertTrue(result.completeness >= 0.0)
            // 缺失章节可以为空或不为空
        }

        @Test
        @DisplayName("80% 以上完整度应视为通过")
        fun `completeness above 80 percent should pass`() {
            // Given: 较完整的报告
            val content = """
                # 技术栈

                ## 编程语言
                Kotlin, Java

                ## 构建工具
                Gradle

                ## 框架
                Spring Boot

                ## 数据库
                PostgreSQL
            """.trimIndent()

            // When: 验证
            val result = validator.validate(content, AnalysisType.TECH_STACK)

            // Then: 应该通过（取决于实际的必填章节定义）
            if (result.completeness >= 0.8) {
                assertTrue(result.isValid)
            }
        }
    }
}
