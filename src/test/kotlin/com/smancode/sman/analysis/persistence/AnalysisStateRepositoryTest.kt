package com.smancode.sman.analysis.persistence

import com.smancode.sman.analysis.config.JVectorConfig
import com.smancode.sman.analysis.config.VectorDatabaseConfig
import com.smancode.sman.analysis.config.VectorDbType
import com.smancode.sman.analysis.model.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * AnalysisStateRepository 测试
 *
 * 测试分析状态的持久化操作
 */
@DisplayName("AnalysisStateRepository 测试")
class AnalysisStateRepositoryTest {

    private lateinit var stateRepository: AnalysisStateRepository
    private lateinit var config: VectorDatabaseConfig

    @TempDir
    lateinit var tempDir: Path

    @BeforeEach
    fun setUp() {
        // 使用临时目录创建测试数据库
        val dbPath = tempDir.resolve("test-analysis").toString()
        config = createTestConfig(dbPath)
        stateRepository = AnalysisStateRepository(config)
    }

    @AfterEach
    fun tearDown() {
        stateRepository.close()
    }

    private fun createTestConfig(dbPath: String): VectorDatabaseConfig {
        return VectorDatabaseConfig(
            projectKey = "test-project",
            type = VectorDbType.JVECTOR,
            jvector = JVectorConfig(),
            databasePath = dbPath
        )
    }

    // ========== AnalysisLoopState CRUD 测试 ==========

    @Nested
    @DisplayName("AnalysisLoopState CRUD 测试")
    inner class LoopStateCrudTest {

        @Test
        @DisplayName("应能保存和读取分析循环状态")
        fun `should save and load analysis loop state`() = runBlocking {
            // Given: 一个完整的状态
            val state = AnalysisLoopState(
                projectKey = "test-project",
                enabled = true,
                currentPhase = AnalysisPhase.ANALYZING,
                currentAnalysisType = AnalysisType.PROJECT_STRUCTURE,
                currentStep = 3,
                analysisTodos = listOf(
                    AnalysisTodo(id = "todo-001", content = "分析中", priority = 1)
                ),
                totalAnalyses = 5,
                successfulAnalyses = 4,
                lastAnalysisTime = System.currentTimeMillis(),
                lastAnalysisTimeReadable = "2024-01-01 12:00:00"
            )

            // When: 保存后再读取
            stateRepository.saveLoopState(state)
            val loaded = stateRepository.getLoopState("test-project")

            // Then: 应该一致
            assertNotNull(loaded)
            assertEquals(state.projectKey, loaded.projectKey)
            assertEquals(state.enabled, loaded.enabled)
            assertEquals(state.currentPhase, loaded.currentPhase)
            assertEquals(state.currentAnalysisType, loaded.currentAnalysisType)
            assertEquals(state.currentStep, loaded.currentStep)
            assertEquals(state.totalAnalyses, loaded.totalAnalyses)
            assertEquals(state.successfulAnalyses, loaded.successfulAnalyses)
            assertEquals(1, loaded.analysisTodos.size)
        }

        @Test
        @DisplayName("读取不存在的项目应返回 null")
        fun `should return null for non-existent project`() = runBlocking {
            // When: 读取不存在的项目
            val loaded = stateRepository.getLoopState("non-existent-project")

            // Then: 应为 null
            assertNull(loaded)
        }

        @Test
        @DisplayName("应能更新已存在的状态")
        fun `should update existing state`() = runBlocking {
            // Given: 先保存一个状态
            val original = AnalysisLoopState(
                projectKey = "test-project",
                currentPhase = AnalysisPhase.IDLE,
                totalAnalyses = 1
            )
            stateRepository.saveLoopState(original)

            // When: 更新状态
            val updated = original.copy(
                currentPhase = AnalysisPhase.ANALYZING,
                currentAnalysisType = AnalysisType.TECH_STACK,
                totalAnalyses = 2,
                successfulAnalyses = 1
            )
            stateRepository.saveLoopState(updated)
            val loaded = stateRepository.getLoopState("test-project")

            // Then: 应该是更新后的值
            assertNotNull(loaded)
            assertEquals(AnalysisPhase.ANALYZING, loaded.currentPhase)
            assertEquals(AnalysisType.TECH_STACK, loaded.currentAnalysisType)
            assertEquals(2, loaded.totalAnalyses)
            assertEquals(1, loaded.successfulAnalyses)
        }

        @Test
        @DisplayName("空 projectKey 应抛出异常")
        fun `empty projectKey should throw exception`() = runBlocking {
            // Given: 空的 projectKey
            val state = AnalysisLoopState(projectKey = "")

            // When & Then: 应抛出异常
            assertFailsWith<IllegalArgumentException> {
                stateRepository.saveLoopState(state)
            }
            assertFailsWith<IllegalArgumentException> {
                stateRepository.getLoopState("")
            }
        }
    }

    // ========== AnalysisResult CRUD 测试 ==========

    @Nested
    @DisplayName("AnalysisResult CRUD 测试")
    inner class AnalysisResultCrudTest {

        @Test
        @DisplayName("应能保存和读取分析结果")
        fun `should save and load analysis result`() = runBlocking {
            // Given: 一个分析结果
            val result = AnalysisResultEntity(
                projectKey = "test-project",
                analysisType = AnalysisType.PROJECT_STRUCTURE,
                mdFilePath = ".sman/base/01_project_structure.md",
                completeness = 0.85,
                missingSections = listOf("外部依赖", "模块划分"),
                analysisTodos = listOf(
                    AnalysisTodo(id = "todo-001", content = "补充外部依赖分析", priority = 1)
                )
            )

            // When: 保存后再读取
            stateRepository.saveAnalysisResult(result)
            val loaded = stateRepository.getAnalysisResult("test-project", AnalysisType.PROJECT_STRUCTURE)

            // Then: 应该一致
            assertNotNull(loaded)
            assertEquals(result.projectKey, loaded.projectKey)
            assertEquals(result.analysisType, loaded.analysisType)
            assertEquals(result.mdFilePath, loaded.mdFilePath)
            assertEquals(result.completeness, loaded.completeness, 0.001)
            assertEquals(2, loaded.missingSections.size)
            assertEquals(1, loaded.analysisTodos.size)
        }

        @Test
        @DisplayName("读取不存在的分析结果应返回 null")
        fun `should return null for non-existent result`() = runBlocking {
            // When: 读取不存在的分析结果
            val loaded = stateRepository.getAnalysisResult("non-existent", AnalysisType.TECH_STACK)

            // Then: 应为 null
            assertNull(loaded)
        }

        @Test
        @DisplayName("应能更新已存在的分析结果")
        fun `should update existing result`() = runBlocking {
            // Given: 先保存一个结果
            val original = AnalysisResultEntity(
                projectKey = "test-project",
                analysisType = AnalysisType.API_ENTRIES,
                mdFilePath = ".sman/base/03_api_entries.md",
                completeness = 0.5,
                missingSections = listOf("请求参数")
            )
            stateRepository.saveAnalysisResult(original)

            // When: 更新结果
            val updated = original.copy(
                completeness = 0.9,
                missingSections = emptyList()
            )
            stateRepository.saveAnalysisResult(updated)
            val loaded = stateRepository.getAnalysisResult("test-project", AnalysisType.API_ENTRIES)

            // Then: 应该是更新后的值
            assertNotNull(loaded)
            assertEquals(0.9, loaded.completeness, 0.001)
            assertTrue(loaded.missingSections.isEmpty())
        }

        @Test
        @DisplayName("应能获取项目的所有分析结果")
        fun `should get all results for project`() = runBlocking {
            // Given: 多个分析结果
            stateRepository.saveAnalysisResult(
                AnalysisResultEntity(
                    projectKey = "test-project",
                    analysisType = AnalysisType.PROJECT_STRUCTURE,
                    completeness = 0.8
                )
            )
            stateRepository.saveAnalysisResult(
                AnalysisResultEntity(
                    projectKey = "test-project",
                    analysisType = AnalysisType.TECH_STACK,
                    completeness = 0.9
                )
            )

            // When: 获取所有结果
            val results = stateRepository.getAllAnalysisResults("test-project")

            // Then: 应有 2 个结果
            assertEquals(2, results.size)
        }
    }

    // ========== TaskStatus 更新测试 ==========

    @Nested
    @DisplayName("TaskStatus 更新测试")
    inner class TaskStatusUpdateTest {

        @Test
        @DisplayName("应能更新任务状态")
        fun `should update task status`() = runBlocking {
            // Given: 先保存一个 DOING 状态的结果
            val result = AnalysisResultEntity(
                projectKey = "test-project",
                analysisType = AnalysisType.DB_ENTITIES,
                completeness = 0.0,
                taskStatus = TaskStatus.DOING
            )
            stateRepository.saveAnalysisResult(result)

            // When: 更新状态为 COMPLETED
            stateRepository.updateTaskStatus(
                projectKey = "test-project",
                type = AnalysisType.DB_ENTITIES,
                status = TaskStatus.COMPLETED
            )
            val loaded = stateRepository.getAnalysisResult("test-project", AnalysisType.DB_ENTITIES)

            // Then: 状态应为 COMPLETED
            assertNotNull(loaded)
            assertEquals(TaskStatus.COMPLETED, loaded.taskStatus)
        }

        @Test
        @DisplayName("应能更新 DOING 状态的完整度")
        fun `should update completeness for doing task`() = runBlocking {
            // Given: 一个 DOING 状态的任务
            val result = AnalysisResultEntity(
                projectKey = "test-project",
                analysisType = AnalysisType.ENUMS,
                completeness = 0.0,
                taskStatus = TaskStatus.DOING
            )
            stateRepository.saveAnalysisResult(result)

            // When: 更新完整度
            stateRepository.updateCompleteness(
                projectKey = "test-project",
                type = AnalysisType.ENUMS,
                completeness = 0.7
            )
            val loaded = stateRepository.getAnalysisResult("test-project", AnalysisType.ENUMS)

            // Then: 完整度应更新
            assertNotNull(loaded)
            assertEquals(0.7, loaded.completeness, 0.001)
        }
    }

    // ========== DOING 状态恢复测试 ==========

    @Nested
    @DisplayName("DOING 状态恢复测试")
    inner class DoingStateRecoveryTest {

        @Test
        @DisplayName("应能找到 DOING 状态的任务")
        fun `should find doing tasks`() = runBlocking {
            // Given: 多个任务，其中一个是 DOING 状态
            stateRepository.saveAnalysisResult(
                AnalysisResultEntity(
                    projectKey = "test-project",
                    analysisType = AnalysisType.PROJECT_STRUCTURE,
                    taskStatus = TaskStatus.COMPLETED,
                    completeness = 1.0
                )
            )
            stateRepository.saveAnalysisResult(
                AnalysisResultEntity(
                    projectKey = "test-project",
                    analysisType = AnalysisType.TECH_STACK,
                    taskStatus = TaskStatus.DOING,
                    completeness = 0.3
                )
            )
            stateRepository.saveAnalysisResult(
                AnalysisResultEntity(
                    projectKey = "test-project",
                    analysisType = AnalysisType.API_ENTRIES,
                    taskStatus = TaskStatus.PENDING
                )
            )

            // When: 查找 DOING 任务
            val doingTasks = stateRepository.getDoingTasks("test-project")

            // Then: 应找到一个 DOING 任务
            assertEquals(1, doingTasks.size)
            assertEquals(AnalysisType.TECH_STACK, doingTasks[0].analysisType)
            assertEquals(0.3, doingTasks[0].completeness, 0.001)
        }

        @Test
        @DisplayName("应能重置 DOING 任务为 PENDING")
        fun `should reset doing task to pending`() = runBlocking {
            // Given: 一个 DOING 状态的任务
            stateRepository.saveAnalysisResult(
                AnalysisResultEntity(
                    projectKey = "test-project",
                    analysisType = AnalysisType.CONFIG_FILES,
                    taskStatus = TaskStatus.DOING,
                    completeness = 0.5
                )
            )

            // When: 重置为 PENDING
            stateRepository.resetDoingTasksToPending("test-project")
            val loaded = stateRepository.getAnalysisResult("test-project", AnalysisType.CONFIG_FILES)

            // Then: 状态应为 PENDING
            assertNotNull(loaded)
            assertEquals(TaskStatus.PENDING, loaded.taskStatus)
        }
    }

    // ========== TODO 管理测试 ==========

    @Nested
    @DisplayName("TODO 管理测试")
    inner class TodoManagementTest {

        @Test
        @DisplayName("应能添加 TODO 到分析结果")
        fun `should add todo to result`() = runBlocking {
            // Given: 一个分析结果
            stateRepository.saveAnalysisResult(
                AnalysisResultEntity(
                    projectKey = "test-project",
                    analysisType = AnalysisType.PROJECT_STRUCTURE,
                    analysisTodos = emptyList()
                )
            )

            // When: 添加 TODO
            val newTodo = AnalysisTodo(
                id = "todo-new",
                content = "补充分析模块依赖",
                priority = 1
            )
            stateRepository.addTodo(
                projectKey = "test-project",
                type = AnalysisType.PROJECT_STRUCTURE,
                todo = newTodo
            )
            val loaded = stateRepository.getAnalysisResult("test-project", AnalysisType.PROJECT_STRUCTURE)

            // Then: 应有新的 TODO
            assertNotNull(loaded)
            assertEquals(1, loaded.analysisTodos.size)
            assertEquals("补充分析模块依赖", loaded.analysisTodos[0].content)
        }

        @Test
        @DisplayName("应能更新 TODO 状态")
        fun `should update todo status`() = runBlocking {
            // Given: 一个带 TODO 的分析结果
            val todo = AnalysisTodo(id = "todo-001", content = "任务1", priority = 1)
            stateRepository.saveAnalysisResult(
                AnalysisResultEntity(
                    projectKey = "test-project",
                    analysisType = AnalysisType.TECH_STACK,
                    analysisTodos = listOf(todo)
                )
            )

            // When: 更新 TODO 状态
            stateRepository.updateTodoStatus(
                projectKey = "test-project",
                type = AnalysisType.TECH_STACK,
                todoId = "todo-001",
                status = TodoStatus.COMPLETED
            )
            val loaded = stateRepository.getAnalysisResult("test-project", AnalysisType.TECH_STACK)

            // Then: TODO 状态应更新
            assertNotNull(loaded)
            assertEquals(TodoStatus.COMPLETED, loaded.analysisTodos[0].status)
        }

        @Test
        @DisplayName("应能删除已完成的 TODO")
        fun `should remove completed todos`() = runBlocking {
            // Given: 多个 TODO，部分已完成
            stateRepository.saveAnalysisResult(
                AnalysisResultEntity(
                    projectKey = "test-project",
                    analysisType = AnalysisType.DB_ENTITIES,
                    analysisTodos = listOf(
                        AnalysisTodo(id = "t1", content = "任务1", status = TodoStatus.COMPLETED, priority = 1),
                        AnalysisTodo(id = "t2", content = "任务2", status = TodoStatus.PENDING, priority = 2),
                        AnalysisTodo(id = "t3", content = "任务3", status = TodoStatus.COMPLETED, priority = 3)
                    )
                )
            )

            // When: 清理已完成的 TODO
            stateRepository.removeCompletedTodos("test-project", AnalysisType.DB_ENTITIES)
            val loaded = stateRepository.getAnalysisResult("test-project", AnalysisType.DB_ENTITIES)

            // Then: 只剩未完成的 TODO
            assertNotNull(loaded)
            assertEquals(1, loaded.analysisTodos.size)
            assertEquals("任务2", loaded.analysisTodos[0].content)
        }
    }

    // ========== 统计信息测试 ==========

    @Nested
    @DisplayName("统计信息测试")
    inner class StatisticsTest {

        @Test
        @DisplayName("应能获取项目的分析统计信息")
        fun `should get project statistics`() = runBlocking {
            // Given: 多个分析结果
            stateRepository.saveAnalysisResult(
                AnalysisResultEntity(
                    projectKey = "test-project",
                    analysisType = AnalysisType.PROJECT_STRUCTURE,
                    taskStatus = TaskStatus.COMPLETED,
                    completeness = 0.9
                )
            )
            stateRepository.saveAnalysisResult(
                AnalysisResultEntity(
                    projectKey = "test-project",
                    analysisType = AnalysisType.TECH_STACK,
                    taskStatus = TaskStatus.COMPLETED,
                    completeness = 0.8
                )
            )
            stateRepository.saveAnalysisResult(
                AnalysisResultEntity(
                    projectKey = "test-project",
                    analysisType = AnalysisType.API_ENTRIES,
                    taskStatus = TaskStatus.PENDING
                )
            )

            // When: 获取统计
            val stats = stateRepository.getProjectStatistics("test-project")

            // Then: 统计应正确
            assertEquals(3, stats.totalTasks)
            assertEquals(2, stats.completedTasks)
            assertEquals(1, stats.pendingTasks)
            assertEquals(0.85, stats.averageCompleteness, 0.01)  // (0.9 + 0.8) / 2
        }
    }
}
