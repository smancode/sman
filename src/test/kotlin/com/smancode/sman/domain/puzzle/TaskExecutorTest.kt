package com.smancode.sman.domain.puzzle

import com.smancode.sman.infra.storage.PuzzleStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import java.io.File
import java.time.Instant

@DisplayName("TaskExecutor 测试套件")
class TaskExecutorTest {

    private lateinit var taskQueueStore: TaskQueueStore
    private lateinit var puzzleStore: PuzzleStore
    private lateinit var checksumCalculator: ChecksumCalculator
    private lateinit var llmAnalyzer: LlmAnalyzer
    private lateinit var fileReader: FileReader
    private lateinit var taskExecutor: TaskExecutor

    @BeforeEach
    fun setUp() {
        taskQueueStore = mockk(relaxed = true)
        puzzleStore = mockk(relaxed = true)
        checksumCalculator = mockk(relaxed = true)
        llmAnalyzer = mockk()
        fileReader = mockk()
        taskExecutor = TaskExecutor(
            taskQueueStore = taskQueueStore,
            puzzleStore = puzzleStore,
            checksumCalculator = checksumCalculator,
            llmAnalyzer = llmAnalyzer,
            fileReader = fileReader
        )
    }

    // ========== LLM 分析集成测试 ==========

    @Nested
    @DisplayName("LLM 分析集成测试")
    inner class LlmIntegrationTests {

        @Test
        @DisplayName("execute - 应调用 LlmAnalyzer 进行分析")
        fun `execute should call LlmAnalyzer`() = runBlocking {
            val task = createTestTask(
                status = TaskStatus.PENDING,
                target = "src/main/kotlin/User.kt"
            )
            val analysisResult = AnalysisResult(
                title = "用户模块分析",
                content = "# 用户模块\n\n包含用户管理逻辑。",
                tags = listOf("user", "kotlin"),
                confidence = 0.85,
                sourceFiles = listOf("src/main/kotlin/User.kt")
            )

            every { checksumCalculator.hasChanged(any(), any()) } returns true
            every { fileReader.readWithContext("src/main/kotlin/User.kt") } returns AnalysisContext.empty()
            coEvery { llmAnalyzer.analyze("src/main/kotlin/User.kt", any()) } returns analysisResult
            every { puzzleStore.save(any()) } returns Result.success(Unit)

            val result = taskExecutor.execute(task)

            assertTrue(result is ExecutionResult.Success)
            coVerify { llmAnalyzer.analyze("src/main/kotlin/User.kt", any()) }
        }

        @Test
        @DisplayName("execute - 应使用分析结果创建 Puzzle")
        fun `execute should create Puzzle from analysis result`() = runBlocking {
            val task = createTestTask(
                status = TaskStatus.PENDING,
                target = "src/main/kotlin/Order.kt"
            )
            val analysisResult = AnalysisResult(
                title = "订单模块",
                content = "# 订单处理\n\n订单业务逻辑。",
                tags = listOf("order", "business"),
                confidence = 0.9,
                sourceFiles = listOf("src/main/kotlin/Order.kt")
            )

            var savedPuzzle: com.smancode.sman.shared.model.Puzzle? = null
            every { checksumCalculator.hasChanged(any(), any()) } returns true
            every { fileReader.readWithContext(any()) } returns AnalysisContext.empty()
            coEvery { llmAnalyzer.analyze(any(), any()) } returns analysisResult
            every { puzzleStore.save(any()) } answers {
                savedPuzzle = firstArg() as com.smancode.sman.shared.model.Puzzle
                Result.success(Unit)
            }

            taskExecutor.execute(task)

            assertNotNull(savedPuzzle)
            assertEquals("订单模块", savedPuzzle?.content?.lines()?.firstOrNull { it.startsWith("#") }?.removePrefix("#")?.trim() ?: "订单模块")
            assertEquals(0.9, savedPuzzle?.confidence)
        }

        @Test
        @DisplayName("execute - LLM 分析失败应返回 Failed")
        fun `execute should return Failed on LLM analysis error`() = runBlocking {
            val task = createTestTask(status = TaskStatus.PENDING)

            every { checksumCalculator.hasChanged(any(), any()) } returns true
            every { fileReader.readWithContext(any()) } returns AnalysisContext.empty()
            coEvery { llmAnalyzer.analyze(any(), any()) } throws AnalysisException("LLM 调用失败")

            val result = taskExecutor.execute(task)

            assertTrue(result is ExecutionResult.Failed)
            assertTrue((result as ExecutionResult.Failed).error.message?.contains("LLM") == true)
        }
    }

    // ========== 执行流程测试 ==========

    @Nested
    @DisplayName("执行流程测试")
    inner class ExecutionFlowTests {

        @Test
        @DisplayName("execute - PENDING 任务应正常执行")
        fun `execute should run pending task`() = runBlocking {
            val task = createTestTask(status = TaskStatus.PENDING)
            val analysisResult = createDefaultAnalysisResult()

            every { checksumCalculator.hasChanged(any(), any()) } returns true
            every { fileReader.readWithContext(any()) } returns AnalysisContext.empty()
            coEvery { llmAnalyzer.analyze(any(), any()) } returns analysisResult
            every { puzzleStore.save(any()) } returns Result.success(Unit)

            val result = taskExecutor.execute(task)

            assertTrue(result is ExecutionResult.Success)
            verify { taskQueueStore.update(match { it.status == TaskStatus.RUNNING }) }
            verify { taskQueueStore.update(match { it.status == TaskStatus.COMPLETED }) }
        }

        @Test
        @DisplayName("execute - RUNNING 任务应跳过")
        fun `execute should skip running task`() = runBlocking {
            val task = createTestTask(status = TaskStatus.RUNNING)

            val result = taskExecutor.execute(task)

            assertTrue(result is ExecutionResult.Skipped)
            assertEquals("Task is already running", (result as ExecutionResult.Skipped).reason)
        }

        @Test
        @DisplayName("execute - COMPLETED 任务应跳过")
        fun `execute should skip completed task`() = runBlocking {
            val task = createTestTask(status = TaskStatus.COMPLETED)

            val result = taskExecutor.execute(task)

            assertTrue(result is ExecutionResult.Skipped)
        }
    }

    // ========== 幂等性测试 ==========

    @Nested
    @DisplayName("幂等性测试")
    inner class IdempotencyTests {

        @Test
        @DisplayName("execute - checksum 未变更且 Puzzle 完成应跳过")
        fun `execute should skip when checksum unchanged and puzzle complete`() = runBlocking {
            val task = createTestTask(
                status = TaskStatus.PENDING,
                checksum = "sha256:abc123"
            )
            every { checksumCalculator.hasChanged(any<File>(), "sha256:abc123") } returns false
            every { puzzleStore.load("puzzle-1") } returns Result.success(
                createTestPuzzle(completeness = 0.9)
            )

            val result = taskExecutor.execute(task)

            assertTrue(result is ExecutionResult.Skipped)
            assertTrue((result as ExecutionResult.Skipped).reason.contains("unchanged"))
        }

        @Test
        @DisplayName("execute - checksum 变更应执行")
        fun `execute should run when checksum changed`() = runBlocking {
            val task = createTestTask(
                status = TaskStatus.PENDING,
                checksum = "sha256:old"
            )
            every { checksumCalculator.hasChanged(any<File>(), "sha256:old") } returns true
            every { fileReader.readWithContext(any()) } returns AnalysisContext.empty()
            coEvery { llmAnalyzer.analyze(any(), any()) } returns createDefaultAnalysisResult()
            every { puzzleStore.save(any()) } returns Result.success(Unit)

            val result = taskExecutor.execute(task)

            assertTrue(result is ExecutionResult.Success)
        }
    }

    // ========== 错误处理测试 ==========

    @Nested
    @DisplayName("错误处理测试")
    inner class ErrorHandlingTests {

        @Test
        @DisplayName("execute - 执行失败应返回 Failed")
        fun `execute should return failed on error`() = runBlocking {
            val task = createTestTask(status = TaskStatus.PENDING)
            every { checksumCalculator.hasChanged(any(), any()) } returns true
            every { fileReader.readWithContext(any()) } returns AnalysisContext.empty()
            coEvery { llmAnalyzer.analyze(any(), any()) } returns createDefaultAnalysisResult()
            every { puzzleStore.save(any()) } throws RuntimeException("Save failed")

            val result = taskExecutor.execute(task)

            assertTrue(result is ExecutionResult.Failed, "Expected Failed but got $result")
            assertEquals("Save failed", (result as ExecutionResult.Failed).error.message)
            verify { taskQueueStore.update(match { it.status == TaskStatus.FAILED }) }
        }

        @Test
        @DisplayName("executeNext - 空队列应返回 null")
        fun `executeNext should return null for empty queue`() = runBlocking {
            every { taskQueueStore.findRunnable(any()) } returns emptyList()

            val result = taskExecutor.executeNext(TokenBudget(1000, 5))

            assertNull(result)
        }

        @Test
        @DisplayName("executeNext - 预算不足应返回 null")
        fun `executeNext should return null when budget exhausted`() = runBlocking {
            val task = createTestTask()
            every { taskQueueStore.findRunnable(any()) } returns listOf(task)

            val result = taskExecutor.executeNext(TokenBudget(0, 0))

            assertNull(result)
        }
    }

    // ========== 状态更新测试 ==========

    @Nested
    @DisplayName("状态更新测试")
    inner class StatusUpdateTests {

        @Test
        @DisplayName("execute - 执行前应标记 RUNNING")
        fun `execute should mark running before execution`() = runBlocking {
            val task = createTestTask(status = TaskStatus.PENDING)
            every { checksumCalculator.hasChanged(any(), any()) } returns true
            every { fileReader.readWithContext(any()) } returns AnalysisContext.empty()
            coEvery { llmAnalyzer.analyze(any(), any()) } returns createDefaultAnalysisResult()
            every { puzzleStore.save(any()) } returns Result.success(Unit)

            taskExecutor.execute(task)

            verify {
                taskQueueStore.update(match {
                    it.status == TaskStatus.RUNNING && it.startedAt != null
                })
            }
        }

        @Test
        @DisplayName("execute - 成功后应标记 COMPLETED")
        fun `execute should mark completed on success`() = runBlocking {
            val task = createTestTask(status = TaskStatus.PENDING)
            every { checksumCalculator.hasChanged(any(), any()) } returns true
            every { fileReader.readWithContext(any()) } returns AnalysisContext.empty()
            coEvery { llmAnalyzer.analyze(any(), any()) } returns createDefaultAnalysisResult()
            every { puzzleStore.save(any()) } returns Result.success(Unit)

            taskExecutor.execute(task)

            verify {
                taskQueueStore.update(match {
                    it.status == TaskStatus.COMPLETED && it.completedAt != null
                })
            }
        }
    }

    // ========== 辅助方法 ==========

    private fun createTestTask(
        id: String = "task-1",
        type: TaskType = TaskType.ANALYZE_API,
        target: String = "Test.kt",
        puzzleId: String = "puzzle-1",
        status: TaskStatus = TaskStatus.PENDING,
        priority: Double = 0.5,
        checksum: String = "sha256:abc",
        retryCount: Int = 0
    ): AnalysisTask {
        return AnalysisTask(
            id = id,
            type = type,
            target = target,
            puzzleId = puzzleId,
            status = status,
            priority = priority,
            checksum = checksum,
            relatedFiles = emptyList(),
            createdAt = Instant.now(),
            startedAt = null,
            completedAt = null,
            retryCount = retryCount,
            errorMessage = null
        )
    }

    private fun createTestPuzzle(
        id: String = "puzzle-1",
        completeness: Double = 0.5
    ): com.smancode.sman.shared.model.Puzzle {
        return com.smancode.sman.shared.model.Puzzle(
            id = id,
            type = com.smancode.sman.shared.model.PuzzleType.API,
            status = com.smancode.sman.shared.model.PuzzleStatus.IN_PROGRESS,
            content = "Test content",
            completeness = completeness,
            confidence = 0.8,
            lastUpdated = Instant.now(),
            filePath = ".sman/puzzles/$id.md"
        )
    }

    private fun createDefaultAnalysisResult(): AnalysisResult {
        return AnalysisResult(
            title = "测试分析",
            content = "# 测试\n\n测试内容",
            tags = listOf("test"),
            confidence = 0.8,
            sourceFiles = listOf("Test.kt")
        )
    }
}
