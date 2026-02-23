package com.smancode.sman.domain.puzzle

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import java.time.Instant

@DisplayName("RecoveryService 测试套件")
class RecoveryServiceTest {

    private lateinit var taskQueueStore: TaskQueueStore
    private lateinit var recoveryService: RecoveryService

    @BeforeEach
    fun setUp() {
        taskQueueStore = mockk(relaxed = true)
        recoveryService = RecoveryService(taskQueueStore)
    }

    // ========== 中断检测测试 ==========

    @Nested
    @DisplayName("中断检测测试")
    inner class InterruptionDetectionTests {

        @Test
        @DisplayName("needsRecovery - 有超时的 RUNNING 任务应返回 true")
        fun `needsRecovery should return true when has stale running tasks`() {
            val staleTask = createTestTask(
                id = "stale-1",
                status = TaskStatus.RUNNING,
                startedAt = Instant.now().minusSeconds(120)
            )
            every { taskQueueStore.findStaleRunning(any()) } returns listOf(staleTask)

            val result = recoveryService.needsRecovery()

            assertTrue(result)
        }

        @Test
        @DisplayName("needsRecovery - 无超时任务应返回 false")
        fun `needsRecovery should return false when no stale tasks`() {
            every { taskQueueStore.findStaleRunning(any()) } returns emptyList()

            val result = recoveryService.needsRecovery()

            assertFalse(result)
        }
    }

    // ========== 恢复执行测试 ==========

    @Nested
    @DisplayName("恢复执行测试")
    inner class RecoveryExecutionTests {

        @Test
        @DisplayName("recover - 超时任务应重置为 PENDING")
        fun `recover should reset stale tasks to pending`() {
            val staleTask = createTestTask(
                id = "stale-1",
                status = TaskStatus.RUNNING,
                startedAt = Instant.now().minusSeconds(120),
                retryCount = 1
            )
            every { taskQueueStore.findStaleRunning(any()) } returns listOf(staleTask)

            val recovered = recoveryService.recover()

            assertEquals(1, recovered)
            verify { taskQueueStore.resetTask("stale-1") }
        }

        @Test
        @DisplayName("recover - 重试次数达上限应标记为 FAILED")
        fun `recover should mark task as failed when retry limit reached`() {
            val staleTask = createTestTask(
                id = "stale-1",
                status = TaskStatus.RUNNING,
                startedAt = Instant.now().minusSeconds(120),
                retryCount = DoomLoopGuard.MAX_RETRY
            )
            every { taskQueueStore.findStaleRunning(any()) } returns listOf(staleTask)

            val recovered = recoveryService.recover()

            assertEquals(1, recovered)
            verify { taskQueueStore.markFailed("stale-1", any()) }
        }

        @Test
        @DisplayName("recover - 多个超时任务应全部处理")
        fun `recover should handle multiple stale tasks`() {
            val stale1 = createTestTask(
                id = "stale-1",
                status = TaskStatus.RUNNING,
                startedAt = Instant.now().minusSeconds(120),
                retryCount = 0
            )
            val stale2 = createTestTask(
                id = "stale-2",
                status = TaskStatus.RUNNING,
                startedAt = Instant.now().minusSeconds(90),
                retryCount = 1
            )
            every { taskQueueStore.findStaleRunning(any()) } returns listOf(stale1, stale2)

            val recovered = recoveryService.recover()

            assertEquals(2, recovered)
            verify { taskQueueStore.resetTask("stale-1") }
            verify { taskQueueStore.resetTask("stale-2") }
        }

        @Test
        @DisplayName("recover - 无超时任务应返回 0")
        fun `recover should return zero when no stale tasks`() {
            every { taskQueueStore.findStaleRunning(any()) } returns emptyList()

            val recovered = recoveryService.recover()

            assertEquals(0, recovered)
        }
    }

    // ========== Checkpoint 测试 ==========

    @Nested
    @DisplayName("Checkpoint 测试")
    inner class CheckpointTests {

        @Test
        @DisplayName("saveCheckpoint - 应保存任务进度")
        fun `saveCheckpoint should save task progress`() {
            val task = createTestTask(id = "task-1", status = TaskStatus.RUNNING)

            recoveryService.saveCheckpoint(task, "Processed 50%")

            verify { taskQueueStore.update(match {
                it.id == "task-1" && it.status == TaskStatus.RUNNING
            }) }
        }

        @Test
        @DisplayName("getCheckpoint - 应获取最近的 checkpoint")
        fun `getCheckpoint should return latest checkpoint`() {
            val task = createTestTask(id = "task-1")

            // checkpoint 存储在 TaskQueueStore 中
            every { taskQueueStore.findById("task-1") } returns task

            val checkpoint = recoveryService.getCheckpoint("task-1")

            assertNotNull(checkpoint)
            assertEquals("task-1", checkpoint?.id)
        }

        @Test
        @DisplayName("getCheckpoint - 不存在的任务应返回 null")
        fun `getCheckpoint should return null for non-existent task`() {
            every { taskQueueStore.findById("non-existent") } returns null

            val checkpoint = recoveryService.getCheckpoint("non-existent")

            assertNull(checkpoint)
        }
    }

    // ========== 失败处理测试 ==========

    @Nested
    @DisplayName("失败处理测试")
    inner class FailureHandlingTests {

        @Test
        @DisplayName("handleFailure - 应标记任务失败并记录错误")
        fun `handleFailure should mark task as failed with error`() {
            // 重试次数接近上限时，应标记失败
            val task = createTestTask(
                id = "task-1",
                status = TaskStatus.RUNNING,
                retryCount = DoomLoopGuard.MAX_RETRY - 1  // 2
            )
            every { taskQueueStore.findById("task-1") } returns task

            val error = RuntimeException("Analysis failed")
            recoveryService.handleFailure("task-1", error)

            verify { taskQueueStore.markFailed("task-1", "Analysis failed") }
        }

        @Test
        @DisplayName("handleFailure - 重试次数未达上限应重置任务")
        fun `handleFailure should reset task when retry limit not reached`() {
            val task = createTestTask(
                id = "task-1",
                status = TaskStatus.RUNNING,
                retryCount = 0
            )
            every { taskQueueStore.findById("task-1") } returns task

            val error = RuntimeException("Analysis failed")
            recoveryService.handleFailure("task-1", error)

            verify { taskQueueStore.resetTask("task-1") }
        }

        @Test
        @DisplayName("handleFailure - 不存在的任务应抛出异常")
        fun `handleFailure should throw for non-existent task`() {
            every { taskQueueStore.findById("non-existent") } returns null

            assertThrows<IllegalArgumentException> {
                recoveryService.handleFailure("non-existent", RuntimeException("Error"))
            }
        }
    }

    // ========== 辅助方法 ==========

    private fun createTestTask(
        id: String = "test-task",
        type: TaskType = TaskType.ANALYZE_API,
        target: String = "Test.kt",
        puzzleId: String = "test-puzzle",
        status: TaskStatus = TaskStatus.PENDING,
        priority: Double = 0.5,
        checksum: String = "abc123",
        relatedFiles: List<String> = emptyList(),
        createdAt: Instant = Instant.now(),
        startedAt: Instant? = null,
        completedAt: Instant? = null,
        retryCount: Int = 0,
        errorMessage: String? = null
    ): AnalysisTask {
        return AnalysisTask(
            id = id,
            type = type,
            target = target,
            puzzleId = puzzleId,
            status = status,
            priority = priority,
            checksum = checksum,
            relatedFiles = relatedFiles,
            createdAt = createdAt,
            startedAt = startedAt,
            completedAt = completedAt,
            retryCount = retryCount,
            errorMessage = errorMessage
        )
    }
}
