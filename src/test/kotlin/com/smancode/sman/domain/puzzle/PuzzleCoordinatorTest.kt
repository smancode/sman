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
import java.time.Instant

@DisplayName("PuzzleCoordinator 测试套件")
class PuzzleCoordinatorTest {

    private lateinit var puzzleStore: PuzzleStore
    private lateinit var taskQueueStore: TaskQueueStore
    private lateinit var gapDetector: GapDetector
    private lateinit var taskScheduler: TaskScheduler
    private lateinit var taskExecutor: TaskExecutor
    private lateinit var recoveryService: RecoveryService
    private lateinit var doomLoopGuard: DoomLoopGuard
    private lateinit var coordinator: PuzzleCoordinator

    @BeforeEach
    fun setUp() {
        puzzleStore = mockk(relaxed = true)
        taskQueueStore = mockk(relaxed = true)
        gapDetector = mockk(relaxed = true)
        taskScheduler = TaskScheduler()
        taskExecutor = mockk(relaxed = true)
        recoveryService = mockk(relaxed = true)
        doomLoopGuard = mockk(relaxed = true)

        // 默认配置
        every { puzzleStore.loadAll() } returns Result.success(emptyList())
        every { taskQueueStore.findByStatus(any()) } returns emptyList()
        every { doomLoopGuard.filter(any()) } answers {
            @Suppress("UNCHECKED_CAST")
            firstArg<List<AnalysisTask>>()
        }
        every { doomLoopGuard.canExecute(any()) } returns true
        every { gapDetector.detect(any()) } returns emptyList()

        coordinator = PuzzleCoordinator(
            puzzleStore = puzzleStore,
            taskQueueStore = taskQueueStore,
            gapDetector = gapDetector,
            taskScheduler = taskScheduler,
            taskExecutor = taskExecutor,
            recoveryService = recoveryService,
            doomLoopGuard = doomLoopGuard
        )
    }

    // ========== 启动流程测试 ==========

    @Nested
    @DisplayName("启动流程测试")
    inner class StartupTests {

        @Test
        @DisplayName("start - 应先执行恢复")
        fun `start should recover first`() = runBlocking {
            // 需要返回 true 才会调用 recover
            every { recoveryService.needsRecovery() } returns true
            every { recoveryService.recover() } returns 1

            coordinator.start()

            verify { recoveryService.recover() }
        }

        @Test
        @DisplayName("start - 应调用空白检测")
        fun `start should call gap detection`() = runBlocking {
            // 需要有 Puzzle 才会调用 detect
            val puzzle = com.smancode.sman.shared.model.Puzzle(
                id = "test-puzzle",
                type = com.smancode.sman.shared.model.PuzzleType.API,
                status = com.smancode.sman.shared.model.PuzzleStatus.IN_PROGRESS,
                content = "Test",
                completeness = 0.8,
                confidence = 0.9,
                lastUpdated = Instant.now(),
                filePath = ".sman/puzzles/test.md"
            )
            every { puzzleStore.loadAll() } returns Result.success(listOf(puzzle))

            coordinator.start()

            verify { gapDetector.detect(any()) }
        }
    }

    // ========== 触发机制测试 ==========

    @Nested
    @DisplayName("触发机制测试")
    inner class TriggerTests {

        @Test
        @DisplayName("trigger SCHEDULED - 应执行下一个任务")
        fun `trigger SCHEDULED should execute next task`() = runBlocking {
            coEvery { taskExecutor.executeNext(any()) } returns ExecutionResult.Success

            coordinator.trigger(TriggerType.SCHEDULED)

            coVerify { taskExecutor.executeNext(any()) }
        }

        @Test
        @DisplayName("trigger FILE_CHANGE - 应检测文件变更空白")
        fun `trigger FILE_CHANGE should detect file change gaps`() = runBlocking {
            val gap = Gap(
                type = GapType.FILE_CHANGE_TRIGGERED,
                puzzleType = com.smancode.sman.shared.model.PuzzleType.API,
                description = "File changed",
                priority = 0.8,
                relatedFiles = listOf("UserService.kt"),
                detectedAt = Instant.now()
            )
            every { gapDetector.detectByFileChange(any(), any()) } returns listOf(gap)

            coordinator.trigger(TriggerType.FILE_CHANGE, changedFiles = listOf("UserService.kt"))

            verify { gapDetector.detectByFileChange(any(), listOf("UserService.kt")) }
            verify { taskQueueStore.enqueue(any()) }
        }

        @Test
        @DisplayName("trigger USER_QUERY - 应基于查询检测空白")
        fun `trigger USER_QUERY should detect query gaps`() = runBlocking {
            val gap = Gap(
                type = GapType.USER_QUERY_TRIGGERED,
                puzzleType = com.smancode.sman.shared.model.PuzzleType.API,
                description = "Query related",
                priority = 0.9,
                relatedFiles = listOf("UserService.kt"),
                detectedAt = Instant.now()
            )
            every { gapDetector.detectByUserQuery(any(), any()) } returns listOf(gap)

            coordinator.trigger(TriggerType.USER_QUERY, query = "如何修改用户服务？")

            verify { gapDetector.detectByUserQuery(any(), "如何修改用户服务？") }
            verify { taskQueueStore.enqueue(any()) }
        }
    }

    // ========== 状态查询测试 ==========

    @Nested
    @DisplayName("状态查询测试")
    inner class StatusTests {

        @Test
        @DisplayName("getStatus - 无任务时应返回空闲状态")
        fun `getStatus should return idle status when no tasks`() {
            every { taskQueueStore.findByStatus(TaskStatus.RUNNING) } returns emptyList()
            every { taskQueueStore.findByStatus(TaskStatus.PENDING) } returns emptyList()

            val status = coordinator.getStatus()

            assertFalse(status.isRunning)
            assertEquals(0, status.pendingTasks)
            assertNull(status.runningTask)
        }

        @Test
        @DisplayName("getStatus - 有运行任务时应返回运行状态")
        fun `getStatus should return running status when has running task`() {
            val task = createTestTask(status = TaskStatus.RUNNING)
            every { taskQueueStore.findByStatus(TaskStatus.RUNNING) } returns listOf(task)
            every { taskQueueStore.findByStatus(TaskStatus.PENDING) } returns emptyList()

            val status = coordinator.getStatus()

            assertTrue(status.isRunning)
            assertNotNull(status.runningTask)
        }
    }

    // ========== 停止测试 ==========

    @Nested
    @DisplayName("停止测试")
    inner class StopTests {

        @Test
        @DisplayName("stop - 应正确停止")
        fun `stop should work correctly`() {
            coordinator.stop()
            // 验证无异常
        }
    }

    // ========== 辅助方法 ==========

    private fun createTestTask(
        id: String = "task-1",
        status: TaskStatus = TaskStatus.PENDING
    ): AnalysisTask {
        return AnalysisTask(
            id = id,
            type = TaskType.ANALYZE_API,
            target = "Test.kt",
            puzzleId = "puzzle-1",
            status = status,
            priority = 0.5,
            checksum = "sha256:abc",
            relatedFiles = emptyList(),
            createdAt = Instant.now(),
            startedAt = null,
            completedAt = null,
            retryCount = 0,
            errorMessage = null
        )
    }
}
