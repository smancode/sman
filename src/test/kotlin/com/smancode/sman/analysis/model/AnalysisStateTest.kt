package com.smancode.sman.analysis.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * AnalysisState 测试
 *
 * 测试分析状态数据模型的序列化、反序列化和状态转换
 */
@DisplayName("AnalysisState 数据模型测试")
class AnalysisStateTest {

    private val json = Json { ignoreUnknownKeys = true }

    // ========== AnalysisTodo 测试 ==========

    @Nested
    @DisplayName("AnalysisTodo 测试")
    inner class AnalysisTodoTest {

        @Test
        @DisplayName("应能创建 PENDING 状态的 TODO")
        fun `should create pending todo`() {
            // When: 创建待办事项
            val todo = AnalysisTodo(
                id = "todo-001",
                content = "补充分析：项目依赖",
                priority = 1
            )

            // Then: 默认状态应为 PENDING
            assertEquals("todo-001", todo.id)
            assertEquals("补充分析：项目依赖", todo.content)
            assertEquals(TodoStatus.PENDING, todo.status)
            assertEquals(1, todo.priority)
        }

        @Test
        @DisplayName("应能正确序列化和反序列化 AnalysisTodo")
        fun `should serialize and deserialize AnalysisTodo`() {
            // Given: 一个 TODO 对象
            val original = AnalysisTodo(
                id = "todo-002",
                content = "补充分析：API 端点",
                status = TodoStatus.IN_PROGRESS,
                priority = 2
            )

            // When: 序列化后再反序列化
            val jsonString = json.encodeToString(original)
            val deserialized = json.decodeFromString<AnalysisTodo>(jsonString)

            // Then: 应该完全一致
            assertEquals(original.id, deserialized.id)
            assertEquals(original.content, deserialized.content)
            assertEquals(original.status, deserialized.status)
            assertEquals(original.priority, deserialized.priority)
        }

        @Test
        @DisplayName("TODO 列表应能正确序列化和反序列化")
        fun `should serialize and deserialize todo list`() {
            // Given: 多个 TODO
            val todos = listOf(
                AnalysisTodo(id = "todo-001", content = "任务1", priority = 1),
                AnalysisTodo(id = "todo-002", content = "任务2", status = TodoStatus.COMPLETED, priority = 2),
                AnalysisTodo(id = "todo-003", content = "任务3", status = TodoStatus.IN_PROGRESS, priority = 3)
            )

            // When: 序列化后再反序列化
            val jsonString = json.encodeToString(todos)
            val deserialized = json.decodeFromString<List<AnalysisTodo>>(jsonString)

            // Then: 应该完全一致
            assertEquals(3, deserialized.size)
            assertEquals(TodoStatus.PENDING, deserialized[0].status)
            assertEquals(TodoStatus.COMPLETED, deserialized[1].status)
            assertEquals(TodoStatus.IN_PROGRESS, deserialized[2].status)
        }
    }

    // ========== AnalysisTask 测试 ==========

    @Nested
    @DisplayName("AnalysisTask 测试")
    inner class AnalysisTaskTest {

        @Test
        @DisplayName("应能创建 PENDING 状态的分析任务")
        fun `should create pending analysis task`() {
            // When: 创建分析任务
            val task = AnalysisTask(
                type = AnalysisType.PROJECT_STRUCTURE,
                status = TaskStatus.PENDING
            )

            // Then: 应有正确的默认值
            assertEquals(AnalysisType.PROJECT_STRUCTURE, task.type)
            assertEquals(TaskStatus.PENDING, task.status)
            assertNull(task.startTime)
            assertNull(task.endTime)
            assertEquals(0.0, task.completeness)
            assertTrue(task.todos.isEmpty())
            assertTrue(task.missingSections.isEmpty())
        }

        @Test
        @DisplayName("应能创建带完整信息的分析任务")
        fun `should create task with full info`() {
            // Given: 完整的任务信息
            val now = System.currentTimeMillis()
            val todos = listOf(
                AnalysisTodo(id = "todo-001", content = "补充章节", priority = 1)
            )

            // When: 创建任务
            val task = AnalysisTask(
                type = AnalysisType.TECH_STACK,
                status = TaskStatus.DOING,
                startTime = now,
                startTimeReadable = "2024-01-01 10:00:00",
                todos = todos,
                completeness = 0.5,
                missingSections = listOf("依赖版本", "框架配置")
            )

            // Then: 所有字段应正确
            assertEquals(AnalysisType.TECH_STACK, task.type)
            assertEquals(TaskStatus.DOING, task.status)
            assertEquals(now, task.startTime)
            assertEquals(1, task.todos.size)
            assertEquals(0.5, task.completeness)
            assertEquals(2, task.missingSections.size)
        }

        @Test
        @DisplayName("应能正确序列化和反序列化 AnalysisTask")
        fun `should serialize and deserialize AnalysisTask`() {
            // Given: 一个完整的任务
            val original = AnalysisTask(
                type = AnalysisType.API_ENTRIES,
                status = TaskStatus.COMPLETED,
                startTime = 1704067200000L,
                startTimeReadable = "2024-01-01 00:00:00",
                endTime = 1704070800000L,
                endTimeReadable = "2024-01-01 01:00:00",
                todos = listOf(
                    AnalysisTodo(id = "todo-001", content = "任务", status = TodoStatus.COMPLETED, priority = 1)
                ),
                completeness = 0.95,
                missingSections = listOf(),
                mdFilePath = ".sman/base/03_api_entries.md"
            )

            // When: 序列化后再反序列化
            val jsonString = json.encodeToString(original)
            val deserialized = json.decodeFromString<AnalysisTask>(jsonString)

            // Then: 应该完全一致
            assertEquals(original.type, deserialized.type)
            assertEquals(original.status, deserialized.status)
            assertEquals(original.startTime, deserialized.startTime)
            assertEquals(original.completeness, deserialized.completeness)
            assertEquals(original.mdFilePath, deserialized.mdFilePath)
        }
    }

    // ========== AnalysisLoopState 测试 ==========

    @Nested
    @DisplayName("AnalysisLoopState 测试")
    inner class AnalysisLoopStateTest {

        @Test
        @DisplayName("应能创建默认的分析循环状态")
        fun `should create default analysis loop state`() {
            // When: 创建默认状态
            val state = AnalysisLoopState(projectKey = "test-project")

            // Then: 应有正确的默认值
            assertEquals("test-project", state.projectKey)
            assertTrue(state.enabled)
            assertEquals(AnalysisPhase.IDLE, state.currentPhase)
            assertNull(state.currentAnalysisType)
            assertEquals(0, state.currentStep)
            assertTrue(state.analysisTodos.isEmpty())
            assertEquals(0, state.totalAnalyses)
            assertEquals(0, state.successfulAnalyses)
        }

        @Test
        @DisplayName("应能创建 ING 状态")
        fun `should create in-progress state`() {
            // Given: 正在分析的状态
            val todos = listOf(
                AnalysisTodo(id = "todo-001", content = "分析中", status = TodoStatus.IN_PROGRESS, priority = 1)
            )

            // When: 创建 ING 状态
            val state = AnalysisLoopState(
                projectKey = "test-project",
                currentPhase = AnalysisPhase.ANALYZING,
                currentAnalysisType = AnalysisType.PROJECT_STRUCTURE,
                currentStep = 3,
                analysisTodos = todos,
                totalAnalyses = 5,
                successfulAnalyses = 4
            )

            // Then: 状态应正确
            assertEquals(AnalysisPhase.ANALYZING, state.currentPhase)
            assertEquals(AnalysisType.PROJECT_STRUCTURE, state.currentAnalysisType)
            assertEquals(3, state.currentStep)
            assertEquals(1, state.analysisTodos.size)
            assertEquals(5, state.totalAnalyses)
            assertEquals(4, state.successfulAnalyses)
        }

        @Test
        @DisplayName("应能正确序列化和反序列化 AnalysisLoopState")
        fun `should serialize and deserialize AnalysisLoopState`() {
            // Given: 一个完整的状态
            val original = AnalysisLoopState(
                projectKey = "my-project",
                enabled = true,
                currentPhase = AnalysisPhase.PERSISTING,
                currentAnalysisType = AnalysisType.DB_ENTITIES,
                currentStep = 5,
                analysisTodos = listOf(
                    AnalysisTodo(id = "t1", content = "TODO1", priority = 1),
                    AnalysisTodo(id = "t2", content = "TODO2", status = TodoStatus.COMPLETED, priority = 2)
                ),
                totalAnalyses = 10,
                successfulAnalyses = 8,
                lastAnalysisTime = System.currentTimeMillis(),
                lastAnalysisTimeReadable = "2024-01-01 12:00:00"
            )

            // When: 序列化后再反序列化
            val jsonString = json.encodeToString(original)
            val deserialized = json.decodeFromString<AnalysisLoopState>(jsonString)

            // Then: 应该完全一致
            assertEquals(original.projectKey, deserialized.projectKey)
            assertEquals(original.enabled, deserialized.enabled)
            assertEquals(original.currentPhase, deserialized.currentPhase)
            assertEquals(original.currentAnalysisType, deserialized.currentAnalysisType)
            assertEquals(original.currentStep, deserialized.currentStep)
            assertEquals(original.analysisTodos.size, deserialized.analysisTodos.size)
            assertEquals(original.totalAnalyses, deserialized.totalAnalyses)
            assertEquals(original.successfulAnalyses, deserialized.successfulAnalyses)
        }
    }

    // ========== 状态转换测试 ==========

    @Nested
    @DisplayName("状态转换测试")
    inner class StateTransitionTest {

        @Test
        @DisplayName("AnalysisPhase 应能正确遍历")
        fun `AnalysisPhase should have correct values`() {
            // Then: 应包含所有预期的阶段
            val phases = AnalysisPhase.values()
            assertEquals(6, phases.size)
            assertTrue(phases.contains(AnalysisPhase.IDLE))
            assertTrue(phases.contains(AnalysisPhase.CHECKING_CHANGES))
            assertTrue(phases.contains(AnalysisPhase.LOADING_STATE))
            assertTrue(phases.contains(AnalysisPhase.ANALYZING))
            assertTrue(phases.contains(AnalysisPhase.PERSISTING))
            assertTrue(phases.contains(AnalysisPhase.ERROR))
        }

        @Test
        @DisplayName("TaskStatus 应能正确遍历")
        fun `TaskStatus should have correct values`() {
            // Then: 应包含所有预期的状态
            val statuses = TaskStatus.values()
            assertEquals(4, statuses.size)
            assertTrue(statuses.contains(TaskStatus.PENDING))
            assertTrue(statuses.contains(TaskStatus.DOING))
            assertTrue(statuses.contains(TaskStatus.COMPLETED))
            assertTrue(statuses.contains(TaskStatus.FAILED))
        }

        @Test
        @DisplayName("TodoStatus 应能正确遍历")
        fun `TodoStatus should have correct values`() {
            // Then: 应包含所有预期的状态
            val statuses = TodoStatus.values()
            assertEquals(3, statuses.size)
            assertTrue(statuses.contains(TodoStatus.PENDING))
            assertTrue(statuses.contains(TodoStatus.IN_PROGRESS))
            assertTrue(statuses.contains(TodoStatus.COMPLETED))
        }
    }

    // ========== 边界条件测试 ==========

    @Nested
    @DisplayName("边界条件测试")
    inner class EdgeCaseTest {

        @Test
        @DisplayName("空 TODO 列表应能正确处理")
        fun `should handle empty todo list`() {
            // When: 创建空 TODO 列表的任务
            val task = AnalysisTask(
                type = AnalysisType.ENUMS,
                status = TaskStatus.PENDING,
                todos = emptyList()
            )

            // Then: 应正确处理
            assertTrue(task.todos.isEmpty())
        }

        @Test
        @DisplayName("完整度应在 0-1 范围内")
        fun `completeness should be in valid range`() {
            // Given: 不同完整度的任务
            val task0 = AnalysisTask(type = AnalysisType.CONFIG_FILES, completeness = 0.0)
            val task50 = AnalysisTask(type = AnalysisType.CONFIG_FILES, completeness = 0.5)
            val task100 = AnalysisTask(type = AnalysisType.CONFIG_FILES, completeness = 1.0)

            // Then: 应在有效范围内
            assertTrue(task0.completeness >= 0.0 && task0.completeness <= 1.0)
            assertTrue(task50.completeness >= 0.0 && task50.completeness <= 1.0)
            assertTrue(task100.completeness >= 0.0 && task100.completeness <= 1.0)
        }

        @Test
        @DisplayName("缺失章节列表可以为空")
        fun `missing sections can be empty`() {
            // When: 创建无缺失章节的任务
            val task = AnalysisTask(
                type = AnalysisType.PROJECT_STRUCTURE,
                missingSections = emptyList()
            )

            // Then: 应正确处理
            assertTrue(task.missingSections.isEmpty())
        }
    }
}
