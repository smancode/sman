package com.smancode.sman.domain.puzzle

import com.smancode.sman.infra.storage.PuzzleStore
import com.smancode.sman.shared.model.PuzzleStatus
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import java.io.File
import java.nio.file.Files
import java.time.Instant

/**
 * E2E 测试：TaskExecutor 完整流程验证
 *
 * 验证从任务创建到 Puzzle 生成的完整链路
 */
@DisplayName("TaskExecutor E2E 测试")
class TaskExecutorE2ETest {

    private lateinit var tempDir: File
    private lateinit var taskQueueStore: TaskQueueStore
    private lateinit var puzzleStore: PuzzleStore
    private lateinit var checksumCalculator: ChecksumCalculator
    private lateinit var llmAnalyzer: LlmAnalyzer
    private lateinit var fileReader: FileReader
    private lateinit var taskExecutor: TaskExecutor

    @BeforeEach
    fun setUp() {
        tempDir = Files.createTempDirectory("task-executor-e2e").toFile()

        taskQueueStore = mockk(relaxed = true)
        puzzleStore = PuzzleStore(tempDir.absolutePath)
        checksumCalculator = ChecksumCalculator()
        llmAnalyzer = mockk()
        fileReader = DefaultFileReader(tempDir.absolutePath)
        taskExecutor = TaskExecutor(
            taskQueueStore = taskQueueStore,
            puzzleStore = puzzleStore,
            checksumCalculator = checksumCalculator,
            llmAnalyzer = llmAnalyzer,
            fileReader = fileReader
        )
    }

    @AfterEach
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    // ========== 完整流程测试 ==========

    @Nested
    @DisplayName("完整流程测试")
    inner class FullFlowTests {

        @Test
        @DisplayName("应完成从文件到 Puzzle 的完整流程")
        fun `should complete full flow from file to puzzle`() = runBlocking {
            // 1. 准备测试文件
            val sourceFile = File(tempDir, "UserService.kt")
            sourceFile.writeText("""
                package com.example

                class UserService {
                    fun createUser(name: String): User {
                        // 创建用户逻辑
                    }

                    fun findUser(id: String): User? {
                        // 查找用户逻辑
                    }
                }
            """.trimIndent())

            // 2. 创建任务
            val task = AnalysisTask(
                id = "task-user-service",
                type = TaskType.ANALYZE_API,
                target = "UserService.kt",
                puzzleId = "user-service-analysis",
                status = TaskStatus.PENDING,
                priority = 0.8,
                checksum = checksumCalculator.calculate(sourceFile),
                relatedFiles = listOf("UserService.kt"),
                createdAt = Instant.now(),
                startedAt = null,
                completedAt = null,
                retryCount = 0,
                errorMessage = null
            )

            // 3. Mock LLM 响应
            val analysisResult = AnalysisResult(
                title = "用户服务分析",
                content = """
                    # UserService 分析

                    ## 概述
                    UserService 提供用户管理功能。

                    ## 方法
                    - `createUser(name: String)`: 创建新用户
                    - `findUser(id: String)`: 根据 ID 查找用户

                    ## 设计模式
                    使用简单的服务类模式，职责单一。
                """.trimIndent(),
                tags = listOf("service", "user", "kotlin"),
                confidence = 0.85,
                sourceFiles = listOf("UserService.kt")
            )
            coEvery { llmAnalyzer.analyze("UserService.kt", any()) } returns analysisResult

            // 4. 执行任务
            val result = taskExecutor.execute(task)

            // 5. 验证结果
            assertTrue(result is ExecutionResult.Success)

            // 6. 验证 Puzzle 已保存
            val savedPuzzle = puzzleStore.load("user-service-analysis").getOrNull()
            assertNotNull(savedPuzzle)
            assertEquals("user-service-analysis", savedPuzzle?.id)
            assertTrue(savedPuzzle?.content?.contains("UserService") == true)
            assertEquals(0.85, savedPuzzle?.confidence)
            assertEquals(PuzzleStatus.COMPLETED, savedPuzzle?.status)
        }

        @Test
        @DisplayName("应支持增量更新")
        fun `should support incremental update`() = runBlocking {
            // 1. 先创建一个初始 Puzzle
            val initialResult = AnalysisResult(
                title = "初始分析",
                content = "# 初始\n\n初始内容",
                tags = listOf("initial"),
                confidence = 0.5,
                sourceFiles = listOf("Test.kt")
            )

            val initialPuzzle = com.smancode.sman.shared.model.Puzzle(
                id = "test-puzzle",
                type = com.smancode.sman.shared.model.PuzzleType.STRUCTURE,
                status = PuzzleStatus.COMPLETED,
                content = "# 初始分析\n\n初始内容",
                completeness = 0.5,
                confidence = 0.5,
                lastUpdated = Instant.now().minusSeconds(3600),
                filePath = ".sman/puzzles/test-puzzle.md"
            )
            puzzleStore.save(initialPuzzle)

            // 2. 准备更新任务（checksum 已变更）
            val sourceFile = File(tempDir, "Test.kt")
            sourceFile.writeText("class Updated")

            val task = AnalysisTask(
                id = "task-update",
                type = TaskType.UPDATE_PUZZLE,
                target = "Test.kt",
                puzzleId = "test-puzzle",
                status = TaskStatus.PENDING,
                priority = 0.9,
                checksum = "old-checksum", // 旧 checksum
                relatedFiles = listOf("Test.kt"),
                createdAt = Instant.now(),
                startedAt = null,
                completedAt = null,
                retryCount = 0,
                errorMessage = null
            )

            // 3. Mock LLM 响应（更新后的分析）
            val updatedResult = AnalysisResult(
                title = "更新分析",
                content = "# 更新\n\n更新后的内容",
                tags = listOf("updated"),
                confidence = 0.9,
                sourceFiles = listOf("Test.kt")
            )
            coEvery { llmAnalyzer.analyze("Test.kt", any()) } returns updatedResult

            // 4. 执行更新
            val result = taskExecutor.execute(task)

            // 5. 验证更新成功
            assertTrue(result is ExecutionResult.Success)

            val updatedPuzzle = puzzleStore.load("test-puzzle").getOrNull()
            assertNotNull(updatedPuzzle)
            assertTrue(updatedPuzzle?.content?.contains("更新") == true)
            assertEquals(0.9, updatedPuzzle?.confidence)
        }
    }

    // ========== 错误恢复测试 ==========

    @Nested
    @DisplayName("错误恢复测试")
    inner class ErrorRecoveryTests {

        @Test
        @DisplayName("LLM 失败后应能重试")
        fun `should retry after LLM failure`() = runBlocking {
            val sourceFile = File(tempDir, "Retry.kt")
            sourceFile.writeText("class Retry")

            val task = AnalysisTask(
                id = "task-retry",
                type = TaskType.ANALYZE_API,
                target = "Retry.kt",
                puzzleId = "retry-analysis",
                status = TaskStatus.PENDING,
                priority = 0.7,
                checksum = checksumCalculator.calculate(sourceFile),
                relatedFiles = listOf("Retry.kt"),
                createdAt = Instant.now(),
                startedAt = null,
                completedAt = null,
                retryCount = 0,
                errorMessage = null
            )

            // 第一次失败
            coEvery { llmAnalyzer.analyze("Retry.kt", any()) } throws AnalysisException("LLM 错误")

            val firstResult = taskExecutor.execute(task)
            assertTrue(firstResult is ExecutionResult.Failed)

            // 第二次成功
            coEvery { llmAnalyzer.analyze("Retry.kt", any()) } returns AnalysisResult(
                title = "重试成功",
                content = "# 成功",
                tags = listOf("retry"),
                confidence = 0.8,
                sourceFiles = listOf("Retry.kt")
            )

            // 重置任务状态
            val retryTask = task.copy(
                status = TaskStatus.PENDING,
                retryCount = 1
            )

            val secondResult = taskExecutor.execute(retryTask)
            assertTrue(secondResult is ExecutionResult.Success)
        }
    }

    // ========== 类型推断测试 ==========

    @Nested
    @DisplayName("类型推断测试")
    inner class TypeInferenceTests {

        @Test
        @DisplayName("应从标签正确推断 PuzzleType.API")
        fun `should infer API type from tags`() = runBlocking {
            val sourceFile = File(tempDir, "ApiController.kt")
            sourceFile.writeText("class ApiController")

            val task = createTask("ApiController.kt", "api-test")
            coEvery { llmAnalyzer.analyze(any(), any()) } returns AnalysisResult(
                title = "API",
                content = "# API",
                tags = listOf("api", "rest", "controller"),
                confidence = 0.8,
                sourceFiles = listOf("ApiController.kt")
            )

            taskExecutor.execute(task)

            val puzzle = puzzleStore.load("api-test").getOrNull()
            assertEquals(com.smancode.sman.shared.model.PuzzleType.API, puzzle?.type)
        }

        @Test
        @DisplayName("应从标签正确推断 PuzzleType.DATA")
        fun `should infer DATA type from tags`() = runBlocking {
            val sourceFile = File(tempDir, "Entity.kt")
            sourceFile.writeText("class Entity")

            val task = createTask("Entity.kt", "data-test")
            coEvery { llmAnalyzer.analyze(any(), any()) } returns AnalysisResult(
                title = "Data",
                content = "# Data",
                tags = listOf("entity", "model", "database"),
                confidence = 0.8,
                sourceFiles = listOf("Entity.kt")
            )

            taskExecutor.execute(task)

            val puzzle = puzzleStore.load("data-test").getOrNull()
            assertEquals(com.smancode.sman.shared.model.PuzzleType.DATA, puzzle?.type)
        }

        @Test
        @DisplayName("应从标签正确推断 PuzzleType.FLOW")
        fun `should infer FLOW type from tags`() = runBlocking {
            val sourceFile = File(tempDir, "Process.kt")
            sourceFile.writeText("class Process")

            val task = createTask("Process.kt", "flow-test")
            coEvery { llmAnalyzer.analyze(any(), any()) } returns AnalysisResult(
                title = "Flow",
                content = "# Flow",
                tags = listOf("flow", "process", "workflow"),
                confidence = 0.8,
                sourceFiles = listOf("Process.kt")
            )

            taskExecutor.execute(task)

            val puzzle = puzzleStore.load("flow-test").getOrNull()
            assertEquals(com.smancode.sman.shared.model.PuzzleType.FLOW, puzzle?.type)
        }

        private fun createTask(target: String, puzzleId: String): AnalysisTask {
            return AnalysisTask(
                id = "task-$puzzleId",
                type = TaskType.ANALYZE_API,
                target = target,
                puzzleId = puzzleId,
                status = TaskStatus.PENDING,
                priority = 0.7,
                checksum = "test-checksum",
                relatedFiles = listOf(target),
                createdAt = Instant.now(),
                startedAt = null,
                completedAt = null,
                retryCount = 0,
                errorMessage = null
            )
        }
    }
}
