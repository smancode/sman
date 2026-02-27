package com.smancode.sman.domain.puzzle

import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import java.time.Instant

@DisplayName("Iteration 数据模型测试套件")
class IterationModelTest {

    // ========== Iteration 测试 ==========

    @Nested
    @DisplayName("Iteration 测试")
    inner class IterationTests {

        @Test
        @DisplayName("应能创建迭代")
        fun `should create iteration`() {
            val iteration = Iteration(
                id = "iter-001",
                trigger = Trigger.UserQuery("如何实现认证？"),
                hypothesis = "项目使用 Spring Security",
                tasks = emptyList(),
                results = emptyList(),
                evaluation = Evaluation(
                    hypothesisConfirmed = true,
                    newKnowledgeGained = 2,
                    conflictsFound = emptyList(),
                    qualityScore = 0.8,
                    lessonsLearned = listOf("发现自定义认证过滤器")
                ),
                status = IterationStatus.COMPLETED,
                createdAt = Instant.now(),
                completedAt = Instant.now()
            )

            assertEquals("iter-001", iteration.id)
            assertTrue(iteration.trigger is Trigger.UserQuery)
            assertEquals(IterationStatus.COMPLETED, iteration.status)
        }

        @Test
        @DisplayName("应能创建用户查询触发的迭代")
        fun `should create user query triggered iteration`() {
            val iteration = Iteration(
                id = "iter-002",
                trigger = Trigger.UserQuery("订单模块在哪里？"),
                hypothesis = "",
                tasks = emptyList(),
                results = emptyList(),
                evaluation = Evaluation(
                    hypothesisConfirmed = false,
                    newKnowledgeGained = 0,
                    conflictsFound = emptyList(),
                    qualityScore = 0.0,
                    lessonsLearned = emptyList()
                ),
                status = IterationStatus.OBSERVING,
                createdAt = Instant.now(),
                completedAt = null
            )

            val trigger = iteration.trigger as Trigger.UserQuery
            assertEquals("订单模块在哪里？", trigger.query)
        }

        @Test
        @DisplayName("应能创建文件变更触发的迭代")
        fun `should create file change triggered iteration`() {
            val iteration = Iteration(
                id = "iter-003",
                trigger = Trigger.FileChange(listOf("UserService.kt", "AuthFilter.kt")),
                hypothesis = "",
                tasks = emptyList(),
                results = emptyList(),
                evaluation = Evaluation(
                    hypothesisConfirmed = false,
                    newKnowledgeGained = 0,
                    conflictsFound = emptyList(),
                    qualityScore = 0.0,
                    lessonsLearned = emptyList()
                ),
                status = IterationStatus.OBSERVING,
                createdAt = Instant.now(),
                completedAt = null
            )

            val trigger = iteration.trigger as Trigger.FileChange
            assertEquals(2, trigger.files.size)
            assertTrue(trigger.files.contains("UserService.kt"))
        }
    }

    // ========== IterationTask 测试 ==========

    @Nested
    @DisplayName("IterationTask 测试")
    inner class IterationTaskTests {

        @Test
        @DisplayName("应能创建任务")
        fun `should create task`() {
            val task = IterationTask(
                id = "task-001",
                description = "扫描安全注解",
                target = "**/*Security*.kt",
                priority = 0.9,
                status = TaskStatus.PENDING,
                assignee = "system",
                result = null
            )

            assertEquals("task-001", task.id)
            assertEquals("system", task.assignee)
            assertEquals(TaskStatus.PENDING, task.status)
        }

        @Test
        @DisplayName("应能创建已完成的任务")
        fun `should create completed task`() {
            val result = TaskResult(
                taskId = "task-001",
                assignee = "system",
                output = "分析结果",
                tags = listOf("security", "spring"),
                confidence = 0.85,
                filesAnalyzed = listOf("SecurityConfig.kt")
            )

            val task = IterationTask(
                id = "task-001",
                description = "扫描安全注解",
                target = "**/*Security*.kt",
                priority = 0.9,
                status = TaskStatus.COMPLETED,
                assignee = "system",
                result = result
            )

            assertEquals(TaskStatus.COMPLETED, task.status)
            assertNotNull(task.result)
            assertEquals(0.85, task.result?.confidence)
        }
    }

    // ========== Evaluation 测试 ==========

    @Nested
    @DisplayName("Evaluation 测试")
    inner class EvaluationTests {

        @Test
        @DisplayName("应能创建评估结果")
        fun `should create evaluation`() {
            val evaluation = Evaluation(
                hypothesisConfirmed = true,
                newKnowledgeGained = 3,
                conflictsFound = emptyList(),
                qualityScore = 0.85,
                lessonsLearned = listOf("使用 JWT", "有自定义过滤器")
            )

            assertTrue(evaluation.hypothesisConfirmed)
            assertEquals(3, evaluation.newKnowledgeGained)
            assertEquals(0.85, evaluation.qualityScore)
            assertEquals(2, evaluation.lessonsLearned.size)
        }

        @Test
        @DisplayName("应能检测知识冲突")
        fun `should detect knowledge conflicts`() {
            val conflict = Conflict(
                type = ConflictType.CONTRADICTION,
                description = "拼图 A 说使用 MySQL，拼图 B 说使用 PostgreSQL",
                puzzleIds = listOf("puzzle-mysql", "puzzle-postgres")
            )

            val evaluation = Evaluation(
                hypothesisConfirmed = false,
                newKnowledgeGained = 0,
                conflictsFound = listOf(conflict),
                qualityScore = 0.3,
                lessonsLearned = emptyList()
            )

            assertEquals(1, evaluation.conflictsFound.size)
            assertEquals(ConflictType.CONTRADICTION, evaluation.conflictsFound[0].type)
        }

        @Test
        @DisplayName("质量评分应在 0-1 范围内")
        fun `quality score should be in range`() {
            val evaluation = Evaluation(
                hypothesisConfirmed = false,
                newKnowledgeGained = 0,
                conflictsFound = emptyList(),
                qualityScore = 0.5,
                lessonsLearned = emptyList()
            )

            assertTrue(evaluation.qualityScore in 0.0..1.0)
        }
    }

    // ========== Hypothesis 测试 ==========

    @Nested
    @DisplayName("Hypothesis 测试")
    inner class HypothesisTests {

        @Test
        @DisplayName("应能创建假设")
        fun `should create hypothesis`() {
            val hypothesis = Hypothesis(
                statement = "项目使用 Spring Security 进行认证",
                confidence = 0.7,
                evidence = listOf("puzzle-001", "puzzle-002")
            )

            assertEquals("项目使用 Spring Security 进行认证", hypothesis.statement)
            assertEquals(0.7, hypothesis.confidence)
            assertEquals(2, hypothesis.evidence.size)
        }

        @Test
        @DisplayName("置信度应在有效范围内")
        fun `confidence should be in valid range`() {
            val hypothesis = Hypothesis(
                statement = "测试假设",
                confidence = 0.5,
                evidence = emptyList()
            )

            assertTrue(hypothesis.confidence in 0.0..1.0)
        }
    }

    // ========== Trigger 测试 ==========

    @Nested
    @DisplayName("Trigger 测试")
    inner class TriggerTests {

        @Test
        @DisplayName("Trigger.UserQuery 应正确工作")
        fun `user query trigger should work`() {
            val trigger = Trigger.UserQuery("如何实现单点登录？")

            assertTrue(trigger is Trigger.UserQuery)
            assertEquals("如何实现单点登录？", (trigger as Trigger.UserQuery).query)
        }

        @Test
        @DisplayName("Trigger.FileChange 应正确工作")
        fun `file change trigger should work`() {
            val trigger = Trigger.FileChange(listOf("OrderController.kt", "OrderService.kt"))

            assertTrue(trigger is Trigger.FileChange)
            assertEquals(2, (trigger as Trigger.FileChange).files.size)
        }

        @Test
        @DisplayName("Trigger.Scheduled 应正确工作")
        fun `scheduled trigger should work`() {
            val trigger = Trigger.Scheduled("每日定时扫描")

            assertTrue(trigger is Trigger.Scheduled)
            assertEquals("每日定时扫描", (trigger as Trigger.Scheduled).reason)
        }
    }

    // ========== IterationStatus 测试 ==========

    @Nested
    @DisplayName("IterationStatus 测试")
    inner class IterationStatusTests {

        @Test
        @DisplayName("应包含所有状态")
        fun `should have all statuses`() {
            assertEquals(8, IterationStatus.values().size)
            assertNotNull(IterationStatus.OBSERVING)
            assertNotNull(IterationStatus.HYPOTHESIZING)
            assertNotNull(IterationStatus.REVIEWING)
            assertNotNull(IterationStatus.PLANNING)
            assertNotNull(IterationStatus.EXECUTING)
            assertNotNull(IterationStatus.EVALUATING)
            assertNotNull(IterationStatus.COMPLETED)
            assertNotNull(IterationStatus.FAILED)
        }
    }
}
