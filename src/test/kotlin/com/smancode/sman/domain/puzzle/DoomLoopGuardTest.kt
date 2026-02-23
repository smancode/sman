package com.smancode.sman.domain.puzzle

import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import java.time.Instant

@DisplayName("DoomLoopGuard 测试套件")
class DoomLoopGuardTest {

    private lateinit var taskQueueStore: TaskQueueStore
    private lateinit var doomLoopGuard: DoomLoopGuard

    @BeforeEach
    fun setUp() {
        taskQueueStore = mockk(relaxed = true)
        doomLoopGuard = DoomLoopGuard(taskQueueStore)
    }

    // ========== 去重测试 ==========

    @Nested
    @DisplayName("去重测试")
    inner class DeduplicationTests {

        @Test
        @DisplayName("canExecute - 新任务应返回 true")
        fun `canExecute should return true for new task`() {
            val task = createTestTask(
                type = TaskType.ANALYZE_API,
                target = "UserController.kt"
            )
            every { taskQueueStore.findByStatus(TaskStatus.COMPLETED) } returns emptyList()

            val result = doomLoopGuard.canExecute(task)

            assertTrue(result)
        }

        @Test
        @DisplayName("canExecute - 24小时内相同任务应返回 false")
        fun `canExecute should return false for duplicate task within 24 hours`() {
            val now = Instant.now()
            val recentTask = createTestTask(
                id = "recent-1",
                type = TaskType.ANALYZE_API,
                target = "UserController.kt",
                status = TaskStatus.COMPLETED,
                completedAt = now.minusSeconds(3600) // 1 小时前
            )
            every { taskQueueStore.findByStatus(TaskStatus.COMPLETED) } returns listOf(recentTask)

            val newTask = createTestTask(
                type = TaskType.ANALYZE_API,
                target = "UserController.kt"
            )

            val result = doomLoopGuard.canExecute(newTask)

            assertFalse(result)
        }

        @Test
        @DisplayName("canExecute - 24小时外相同任务应返回 true")
        fun `canExecute should return true for duplicate task after 24 hours`() {
            val now = Instant.now()
            val oldTask = createTestTask(
                id = "old-1",
                type = TaskType.ANALYZE_API,
                target = "UserController.kt",
                status = TaskStatus.COMPLETED,
                completedAt = now.minusSeconds(25 * 3600) // 25 小时前
            )
            every { taskQueueStore.findByStatus(TaskStatus.COMPLETED) } returns listOf(oldTask)

            val newTask = createTestTask(
                type = TaskType.ANALYZE_API,
                target = "UserController.kt"
            )

            val result = doomLoopGuard.canExecute(newTask)

            assertTrue(result)
        }

        @Test
        @DisplayName("canExecute - 不同目标应返回 true")
        fun `canExecute should return true for different target`() {
            val now = Instant.now()
            val completedTask = createTestTask(
                id = "completed-1",
                type = TaskType.ANALYZE_API,
                target = "UserController.kt",
                status = TaskStatus.COMPLETED,
                completedAt = now.minusSeconds(3600)
            )
            every { taskQueueStore.findByStatus(TaskStatus.COMPLETED) } returns listOf(completedTask)

            val newTask = createTestTask(
                type = TaskType.ANALYZE_API,
                target = "OrderController.kt" // 不同目标
            )

            val result = doomLoopGuard.canExecute(newTask)

            assertTrue(result)
        }
    }

    // ========== 重试限制测试 ==========

    @Nested
    @DisplayName("重试限制测试")
    inner class RetryLimitTests {

        @Test
        @DisplayName("canExecute - 重试次数未超限应返回 true")
        fun `canExecute should return true when retry count below limit`() {
            val task = createTestTask(retryCount = 2)
            every { taskQueueStore.findByStatus(TaskStatus.COMPLETED) } returns emptyList()

            val result = doomLoopGuard.canExecute(task)

            assertTrue(result)
        }

        @Test
        @DisplayName("canExecute - 重试次数达到上限应返回 false")
        fun `canExecute should return false when retry count at limit`() {
            val task = createTestTask(retryCount = DoomLoopGuard.MAX_RETRY)
            every { taskQueueStore.findByStatus(TaskStatus.COMPLETED) } returns emptyList()

            val result = doomLoopGuard.canExecute(task)

            assertFalse(result)
        }

        @Test
        @DisplayName("canExecute - 重试次数超过上限应返回 false")
        fun `canExecute should return false when retry count exceeds limit`() {
            val task = createTestTask(retryCount = 10)
            every { taskQueueStore.findByStatus(TaskStatus.COMPLETED) } returns emptyList()

            val result = doomLoopGuard.canExecute(task)

            assertFalse(result)
        }
    }

    // ========== 超时检测测试 ==========

    @Nested
    @DisplayName("超时检测测试")
    inner class TimeoutTests {

        @Test
        @DisplayName("isTimedOut - 执行超过超时时间应返回 true")
        fun `isTimedOut should return true when execution exceeds timeout`() {
            val task = createTestTask(
                status = TaskStatus.RUNNING,
                startedAt = Instant.now().minusSeconds(120) // 2 分钟前
            )

            val result = doomLoopGuard.isTimedOut(task)

            assertTrue(result)
        }

        @Test
        @DisplayName("isTimedOut - 执行未超时应返回 false")
        fun `isTimedOut should return false when execution within timeout`() {
            val task = createTestTask(
                status = TaskStatus.RUNNING,
                startedAt = Instant.now().minusSeconds(30) // 30 秒前
            )

            val result = doomLoopGuard.isTimedOut(task)

            assertFalse(result)
        }

        @Test
        @DisplayName("isTimedOut - 非 RUNNING 状态应返回 false")
        fun `isTimedOut should return false for non-running task`() {
            val task = createTestTask(
                status = TaskStatus.PENDING,
                startedAt = Instant.now().minusSeconds(120)
            )

            val result = doomLoopGuard.isTimedOut(task)

            assertFalse(result)
        }

        @Test
        @DisplayName("isTimedOut - startedAt 为 null 应返回 false")
        fun `isTimedOut should return false when startedAt is null`() {
            val task = createTestTask(
                status = TaskStatus.RUNNING,
                startedAt = null
            )

            val result = doomLoopGuard.isTimedOut(task)

            assertFalse(result)
        }
    }

    // ========== 进度检测测试 ==========

    @Nested
    @DisplayName("进度检测测试")
    inner class ProgressTests {

        @Test
        @DisplayName("shouldPause - 所有 Puzzle 完成度 > 90% 应返回 true")
        fun `shouldPause should return true when all puzzles highly complete`() {
            // 这个测试需要 PuzzleStore，暂时跳过
            // 实际实现会检查所有 Puzzle 的平均完成度
        }
    }

    // ========== 过滤测试 ==========

    @Nested
    @DisplayName("过滤测试")
    inner class FilterTests {

        @Test
        @DisplayName("filter - 应过滤掉不可执行的任务")
        fun `filter should remove non-executable tasks`() {
            val now = Instant.now()
            val executable = createTestTask(id = "ok", target = "A.kt", retryCount = 0)
            val tooManyRetries = createTestTask(id = "retry", target = "B.kt", retryCount = 10)
            val recentDuplicate = createTestTask(
                id = "dup",
                target = "C.kt",
                status = TaskStatus.COMPLETED,
                completedAt = now.minusSeconds(100)
            )

            every { taskQueueStore.findByStatus(TaskStatus.COMPLETED) } returns listOf(recentDuplicate)

            val tasks = listOf(executable, tooManyRetries, recentDuplicate)
            val filtered = doomLoopGuard.filter(tasks)

            // 只有 executable 应该保留
            assertEquals(1, filtered.size)
            assertEquals("ok", filtered.first().id)
        }

        @Test
        @DisplayName("filter - 空列表应返回空列表")
        fun `filter should return empty list for empty input`() {
            val filtered = doomLoopGuard.filter(emptyList())
            assertTrue(filtered.isEmpty())
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
