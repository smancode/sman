package com.smancode.sman.architect.model

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * EvaluationResult 测试
 *
 * 测试评估结果的创建和判断逻辑
 */
@DisplayName("EvaluationResult 测试")
class EvaluationResultTest {

    @Nested
    @DisplayName("工厂方法测试")
    inner class FactoryMethodTest {

        @Test
        @DisplayName("应该创建失败结果")
        fun `should create failure result`() {
            // When
            val result = EvaluationResult.failure("测试失败")

            // Then
            assertEquals(0.0, result.completeness)
            assertFalse(result.isComplete)
            assertTrue(result.summary.contains("测试失败"))
            assertEquals(0.0, result.confidence)
        }

        @Test
        @DisplayName("应该创建空结果")
        fun `should create empty result`() {
            // When
            val result = EvaluationResult.empty()

            // Then
            assertEquals(0.0, result.completeness)
            assertFalse(result.isComplete)
            assertEquals("无分析结果", result.summary)
        }

        @Test
        @DisplayName("应该创建完成结果")
        fun `should create complete result`() {
            // When
            val result = EvaluationResult.complete("分析完成", 0.95)

            // Then
            assertEquals(0.95, result.completeness)
            assertTrue(result.isComplete)
            assertEquals("分析完成", result.summary)
            assertEquals(1.0, result.confidence)
        }
    }

    @Nested
    @DisplayName("needsFollowUp 测试")
    inner class NeedsFollowUpTest {

        @Test
        @DisplayName("未完成且有追问时应需要追问")
        fun `should need follow up when incomplete with questions`() {
            // Given
            val result = EvaluationResult(
                completeness = 0.5,
                isComplete = false,
                summary = "部分完成",
                todos = emptyList(),
                followUpQuestions = listOf("问题1", "问题2")
            )

            // Then
            assertTrue(result.needsFollowUp)
        }

        @Test
        @DisplayName("已完成时不需要追问")
        fun `should not need follow up when complete`() {
            // Given
            val result = EvaluationResult(
                completeness = 0.9,
                isComplete = true,
                summary = "已完成",
                todos = emptyList(),
                followUpQuestions = listOf("问题1")  // 即使有追问
            )

            // Then
            assertFalse(result.needsFollowUp)
        }

        @Test
        @DisplayName("无追问时不需要追问")
        fun `should not need follow up when no questions`() {
            // Given
            val result = EvaluationResult(
                completeness = 0.5,
                isComplete = false,
                summary = "部分完成",
                todos = emptyList(),
                followUpQuestions = emptyList()
            )

            // Then
            assertFalse(result.needsFollowUp)
        }
    }

    @Nested
    @DisplayName("TODO 测试")
    inner class TodoTest {

        @Test
        @DisplayName("应该正确判断是否有 TODO")
        fun `should check has todos`() {
            // Given
            val result = EvaluationResult(
                completeness = 0.5,
                isComplete = false,
                summary = "部分完成",
                todos = listOf(TodoItem("待办事项", TodoPriority.HIGH)),
                followUpQuestions = emptyList()
            )

            // Then
            assertTrue(result.hasTodos)
        }

        @Test
        @DisplayName("应该正确筛选高优先级 TODO")
        fun `should filter high priority todos`() {
            // Given
            val result = EvaluationResult(
                completeness = 0.5,
                isComplete = false,
                summary = "部分完成",
                todos = listOf(
                    TodoItem("高优先级1", TodoPriority.HIGH),
                    TodoItem("中等优先级", TodoPriority.MEDIUM),
                    TodoItem("高优先级2", TodoPriority.HIGH),
                    TodoItem("低优先级", TodoPriority.LOW)
                ),
                followUpQuestions = emptyList()
            )

            // When
            val highPriority = result.highPriorityTodos

            // Then
            assertEquals(2, highPriority.size)
            assertTrue(highPriority.all { it.priority == TodoPriority.HIGH })
        }
    }

    @Nested
    @DisplayName("格式化测试")
    inner class FormatTest {

        @Test
        @DisplayName("应该正确格式化摘要")
        fun `should format summary`() {
            // Given
            val result = EvaluationResult(
                completeness = 0.85,
                isComplete = true,
                summary = "分析完成",
                todos = listOf(TodoItem("待办", TodoPriority.HIGH)),
                followUpQuestions = listOf("追问")
            )

            // When
            val summary = result.formatSummary()

            // Then
            assertTrue(summary.contains("85%"))
            assertTrue(summary.contains("是"))
            assertTrue(summary.contains("TODO 数量: 1"))
            assertTrue(summary.contains("追问数量: 1"))
        }
    }
}
