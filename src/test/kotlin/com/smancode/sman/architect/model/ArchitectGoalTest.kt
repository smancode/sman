package com.smancode.sman.architect.model

import com.smancode.sman.analysis.model.AnalysisType
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * ArchitectGoal 测试
 *
 * 测试架构师目标的创建和操作
 */
@DisplayName("ArchitectGoal 测试")
class ArchitectGoalTest {

    @Nested
    @DisplayName("创建测试")
    inner class CreationTest {

        @Test
        @DisplayName("应该正确创建默认目标")
        fun `should create default goal`() {
            // When
            val goal = ArchitectGoal.fromType(AnalysisType.PROJECT_STRUCTURE)

            // Then
            assertEquals(AnalysisType.PROJECT_STRUCTURE, goal.type)
            assertEquals("project_structure", goal.id)
            assertEquals(5, goal.priority)
            assertTrue(goal.context.isEmpty())
            assertTrue(goal.followUpQuestions.isEmpty())
            assertEquals(0, goal.retryCount)
            assertEquals(3, goal.maxRetries)
        }

        @Test
        @DisplayName("应该创建所有分析类型的目标")
        fun `should create all goals`() {
            // When
            val goals = ArchitectGoal.createAllGoals()

            // Then
            assertEquals(6, goals.size)
            assertTrue(goals.any { it.type == AnalysisType.PROJECT_STRUCTURE })
            assertTrue(goals.any { it.type == AnalysisType.TECH_STACK })
            assertTrue(goals.any { it.type == AnalysisType.API_ENTRIES })
        }

        @Test
        @DisplayName("应该创建核心分析类型的目标")
        fun `should create core goals`() {
            // When
            val goals = ArchitectGoal.createCoreGoals()

            // Then
            assertEquals(2, goals.size)
            assertTrue(goals.any { it.type == AnalysisType.PROJECT_STRUCTURE })
            assertTrue(goals.any { it.type == AnalysisType.TECH_STACK })
        }
    }

    @Nested
    @DisplayName("重试测试")
    inner class RetryTest {

        @Test
        @DisplayName("应该正确判断是否可以重试")
        fun `should check can retry`() {
            // Given
            val goal = ArchitectGoal(
                type = AnalysisType.PROJECT_STRUCTURE,
                retryCount = 0,
                maxRetries = 3
            )

            // Then
            assertTrue(goal.canRetry)
        }

        @Test
        @DisplayName("达到最大重试次数时应不能重试")
        fun `should not retry when max reached`() {
            // Given
            val goal = ArchitectGoal(
                type = AnalysisType.PROJECT_STRUCTURE,
                retryCount = 3,
                maxRetries = 3
            )

            // Then
            assertFalse(goal.canRetry)
        }

        @Test
        @DisplayName("重试应该增加计数并降低优先级")
        fun `should increase count and decrease priority on retry`() {
            // Given
            val goal = ArchitectGoal(
                type = AnalysisType.PROJECT_STRUCTURE,
                priority = 5,
                retryCount = 0
            )

            // When
            val retryGoal = goal.withRetry()

            // Then
            assertEquals(1, retryGoal.retryCount)
            assertEquals(4, retryGoal.priority)
        }
    }

    @Nested
    @DisplayName("追问测试")
    inner class FollowUpTest {

        @Test
        @DisplayName("应该正确添加追问")
        fun `should add follow up questions`() {
            // Given
            val goal = ArchitectGoal.fromType(AnalysisType.PROJECT_STRUCTURE)
            val questions = listOf("问题1", "问题2")

            // When
            val newGoal = goal.withFollowUp(questions)

            // Then
            assertEquals(2, newGoal.followUpQuestions.size)
            assertTrue(newGoal.followUpQuestions.contains("问题1"))
            assertTrue(newGoal.followUpQuestions.contains("问题2"))
        }

        @Test
        @DisplayName("追加追问应该保留原有追问")
        fun `should append follow up questions`() {
            // Given
            val goal = ArchitectGoal.fromType(AnalysisType.PROJECT_STRUCTURE)
                .withFollowUp(listOf("原有问题"))
            val newQuestions = listOf("新问题1", "新问题2")

            // When
            val newGoal = goal.withFollowUp(newQuestions)

            // Then
            assertEquals(3, newGoal.followUpQuestions.size)
            assertTrue(newGoal.followUpQuestions.contains("原有问题"))
        }
    }

    @Nested
    @DisplayName("上下文测试")
    inner class ContextTest {

        @Test
        @DisplayName("应该正确添加上下文")
        fun `should add context`() {
            // Given
            val goal = ArchitectGoal.fromType(AnalysisType.API_ENTRIES)

            // When
            val newGoal = goal
                .withContext("project_structure", "这是一个 MVC 架构的项目")
                .withContext("tech_stack", "Kotlin + Spring Boot")

            // Then
            assertEquals(2, newGoal.context.size)
            assertEquals("这是一个 MVC 架构的项目", newGoal.context["project_structure"])
        }
    }

    @Nested
    @DisplayName("描述测试")
    inner class DescriptionTest {

        @Test
        @DisplayName("应该正确生成描述")
        fun `should generate description`() {
            // Given
            val goal = ArchitectGoal.fromType(AnalysisType.PROJECT_STRUCTURE)

            // When
            val description = goal.description

            // Then
            assertTrue(description.contains("项目结构分析"))
        }

        @Test
        @DisplayName("有追问时描述应包含追问数量")
        fun `should include follow up count in description`() {
            // Given
            val goal = ArchitectGoal.fromType(AnalysisType.PROJECT_STRUCTURE)
                .withFollowUp(listOf("问题1", "问题2"))

            // When
            val description = goal.description

            // Then
            assertTrue(description.contains("2 个追问"))
        }
    }
}
