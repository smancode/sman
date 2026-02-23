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
    private lateinit var taskExecutor: TaskExecutor

    @BeforeEach
    fun setUp() {
        taskQueueStore = mockk(relaxed = true)
        puzzleStore = mockk(relaxed = true)
        checksumCalculator = mockk(relaxed = true)
        taskExecutor = TaskExecutor(taskQueueStore, puzzleStore, checksumCalculator)
    }

    // ========== 执行流程测试 ==========

    @Nested
    @DisplayName("执行流程测试")
    inner class ExecutionFlowTests {

        @Test
        @DisplayName("execute - PENDING 任务应正常执行")
        fun `execute should run pending task`() = runBlocking {
            val task = createTestTask(status = TaskStatus.PENDING)
            every { taskQueueStore.findById("task-1") } returns task
            every { checksumCalculator.hasChanged(any(), any()) } returns true
            every { puzzleStore.load("puzzle-1") } returns Result.success(null)
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
            every { taskQueueStore.findById("task-1") } returns task

            val result = taskExecutor.execute(task)

            assertTrue(result is ExecutionResult.Skipped)
            assertEquals("Task is already running", (result as ExecutionResult.Skipped).reason)
        }

        @Test
        @DisplayName("execute - COMPLETED 任务应跳过")
        fun `execute should skip completed task`() = runBlocking {
            val task = createTestTask(status = TaskStatus.COMPLETED)
            every { taskQueueStore.findById("task-1") } returns task

            val result = taskExecutor.execute(task)

            assertTrue(result is ExecutionResult.Skipped)
        }
    }

    // ========== 幂等性测试 ==========

    @Nested
    @DisplayName("幂等性测试")
    inner class IdempotencyTests {

        @Test
        @DisplayName("execute - checksum 未变更应跳过")
        fun `execute should skip when checksum unchanged`() = runBlocking {
            val task = createTestTask(
                status = TaskStatus.PENDING,
                checksum = "sha256:abc123"
            )
            every { taskQueueStore.findById("task-1") } returns task
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
            every { taskQueueStore.findById("task-1") } returns task
            every { checksumCalculator.hasChanged(any<File>(), "sha256:old") } returns true
            every { checksumCalculator.calculate(any<File>()) } returns "sha256:new"
            every { puzzleStore.load("puzzle-1") } returns Result.success(null)
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
            every { taskQueueStore.update(any()) } returns Unit
            every { checksumCalculator.hasChanged(any(), any()) } returns true
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
            every { taskQueueStore.findById("task-1") } returns task
            every { checksumCalculator.hasChanged(any(), any()) } returns true
            every { puzzleStore.load("puzzle-1") } returns Result.success(null)
            every { puzzleStore.save(any()) } returns Result.success(Unit)

            taskExecutor.execute(task)

            // 验证状态变更顺序：PENDING -> RUNNING -> COMPLETED
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
            every { taskQueueStore.findById("task-1") } returns task
            every { checksumCalculator.hasChanged(any(), any()) } returns true
            every { puzzleStore.load("puzzle-1") } returns Result.success(null)
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
}
