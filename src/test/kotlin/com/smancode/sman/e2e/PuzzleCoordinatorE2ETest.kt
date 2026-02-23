package com.smancode.sman.e2e

import com.smancode.sman.domain.puzzle.*
import com.smancode.sman.infra.storage.PuzzleStore
import com.smancode.sman.shared.model.Puzzle
import com.smancode.sman.shared.model.PuzzleStatus
import com.smancode.sman.shared.model.PuzzleType
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.Instant

/**
 * E2E 健壮性测试
 *
 * 验证自迭代系统的健壮性：
 * - 中断恢复
 * - 死循环防护
 * - 幂等执行
 * - 知识过期
 */
@DisplayName("E2E 健壮性测试 - 自迭代系统")
class PuzzleCoordinatorE2ETest {

    @TempDir
    lateinit var tempDir: File

    private lateinit var puzzleStore: PuzzleStore
    private lateinit var taskQueueStore: TaskQueueStore
    private lateinit var checksumCalculator: ChecksumCalculator

    @BeforeEach
    fun setUp() {
        puzzleStore = PuzzleStore(tempDir.absolutePath)
        taskQueueStore = TaskQueueStore(tempDir.absolutePath)
        checksumCalculator = ChecksumCalculator()
    }

    // ========== 场景 1：中断恢复 ==========

    @Nested
    @DisplayName("场景 1：中断恢复")
    inner class InterruptionRecoveryTests {

        @Test
        @DisplayName("任务执行到一半停机，重启后应恢复")
        fun `should recover after interruption`() = runBlocking {
            // 1. 创建一个 RUNNING 状态的任务（模拟中断）
            val task = AnalysisTask(
                id = "interrupted-task",
                type = TaskType.ANALYZE_API,
                target = "Test.kt",
                puzzleId = "test-puzzle",
                status = TaskStatus.RUNNING,
                priority = 0.8,
                checksum = "abc123",
                relatedFiles = emptyList(),
                createdAt = Instant.now().minusSeconds(120), // 2 分钟前
                startedAt = Instant.now().minusSeconds(120), // 2 分钟前开始
                completedAt = null,
                retryCount = 0,
                errorMessage = null
            )
            taskQueueStore.enqueue(task)

            // 2. 创建恢复服务
            val recoveryService = RecoveryService(taskQueueStore)

            // 3. 检测是否需要恢复
            assertTrue(recoveryService.needsRecovery())

            // 4. 执行恢复
            val recovered = recoveryService.recover()
            assertEquals(1, recovered)

            // 5. 验证任务已重置为 PENDING
            val resetTask = taskQueueStore.findById("interrupted-task")
            assertNotNull(resetTask)
            assertEquals(TaskStatus.PENDING, resetTask?.status)
            assertEquals(1, resetTask?.retryCount) // 重试次数增加
        }

        @Test
        @DisplayName("重试次数达上限的任务应标记为失败")
        fun `should mark task as failed when retry limit reached`() = runBlocking {
            // 创建一个重试次数达上限的 RUNNING 任务
            val task = AnalysisTask(
                id = "max-retry-task",
                type = TaskType.ANALYZE_API,
                target = "Test.kt",
                puzzleId = "test-puzzle",
                status = TaskStatus.RUNNING,
                priority = 0.8,
                checksum = "abc123",
                relatedFiles = emptyList(),
                createdAt = Instant.now().minusSeconds(120),
                startedAt = Instant.now().minusSeconds(120),
                completedAt = null,
                retryCount = DoomLoopGuard.MAX_RETRY, // 已达上限
                errorMessage = null
            )
            taskQueueStore.enqueue(task)

            // 执行恢复
            val recoveryService = RecoveryService(taskQueueStore)
            recoveryService.recover()

            // 验证任务已标记为失败
            val failedTask = taskQueueStore.findById("max-retry-task")
            assertEquals(TaskStatus.FAILED, failedTask?.status)
            assertNotNull(failedTask?.errorMessage)
        }
    }

    // ========== 场景 2：死循环防护 ==========

    @Nested
    @DisplayName("场景 2：死循环防护")
    inner class DoomLoopPreventionTests {

        @Test
        @DisplayName("相同任务 24 小时内不重复执行")
        fun `should not execute same task within 24 hours`() {
            val now = Instant.now()

            // 1. 创建一个已完成的任务（1 小时前）
            val completedTask = AnalysisTask(
                id = "completed-1",
                type = TaskType.ANALYZE_API,
                target = "UserService.kt",
                puzzleId = "api-user",
                status = TaskStatus.COMPLETED,
                priority = 0.8,
                checksum = "abc123",
                relatedFiles = emptyList(),
                createdAt = now.minusSeconds(3600),
                startedAt = now.minusSeconds(3600),
                completedAt = now.minusSeconds(3500),
                retryCount = 0,
                errorMessage = null
            )
            taskQueueStore.enqueue(completedTask)

            // 2. 尝试创建相同的新任务
            val newTask = AnalysisTask(
                id = "new-1",
                type = TaskType.ANALYZE_API,
                target = "UserService.kt", // 相同目标
                puzzleId = "api-user",
                status = TaskStatus.PENDING,
                priority = 0.8,
                checksum = "abc123",
                relatedFiles = emptyList(),
                createdAt = now,
                startedAt = null,
                completedAt = null,
                retryCount = 0,
                errorMessage = null
            )

            // 3. 使用 DoomLoopGuard 检查
            val guard = DoomLoopGuard(taskQueueStore)
            val canExecute = guard.canExecute(newTask)

            // 4. 验证不允许执行
            assertFalse(canExecute, "24 小时内的相同任务应该被阻止")
        }

        @Test
        @DisplayName("重试次数达上限的任务应被过滤")
        fun `should filter tasks with max retry count`() {
            val task = AnalysisTask(
                id = "retry-task",
                type = TaskType.ANALYZE_API,
                target = "Test.kt",
                puzzleId = "test",
                status = TaskStatus.PENDING,
                priority = 0.8,
                checksum = "abc",
                relatedFiles = emptyList(),
                createdAt = Instant.now(),
                startedAt = null,
                completedAt = null,
                retryCount = 10, // 超过上限
                errorMessage = null
            )

            val guard = DoomLoopGuard(taskQueueStore)
            val filtered = guard.filter(listOf(task))

            assertTrue(filtered.isEmpty(), "重试次数超限的任务应该被过滤")
        }
    }

    // ========== 场景 3：幂等执行 ==========

    @Nested
    @DisplayName("场景 3：幂等执行")
    inner class IdempotencyTests {

        @Test
        @DisplayName("重复调用 enqueue 结果一致")
        fun `enqueue should be idempotent`() {
            val task = AnalysisTask.create(
                id = "idempotent-task",
                type = TaskType.ANALYZE_API,
                target = "Test.kt",
                puzzleId = "test"
            )

            // 多次入队
            taskQueueStore.enqueue(task)
            taskQueueStore.enqueue(task)
            taskQueueStore.enqueue(task)

            // 验证只有一个任务
            val queue = taskQueueStore.load()
            assertEquals(1, queue.tasks.count { it.id == "idempotent-task" })
        }

        @Test
        @DisplayName("相同文件不产生重复 Gap")
        fun `should not produce duplicate gaps for same file`() {
            // 创建一个低完成度的 Puzzle
            val puzzle = Puzzle(
                id = "low-complete",
                type = PuzzleType.API,
                status = PuzzleStatus.IN_PROGRESS,
                content = "Test content",
                completeness = 0.3,
                confidence = 0.5,
                lastUpdated = Instant.now(),
                filePath = ".sman/puzzles/low-complete.md"
            )
            puzzleStore.save(puzzle)

            // 多次检测 Gap
            val detector = GapDetector()
            val gaps1 = detector.detect(listOf(puzzle))
            val gaps2 = detector.detect(listOf(puzzle))
            val gaps3 = detector.detect(listOf(puzzle))

            // 每次结果应该一致
            assertEquals(gaps1.size, gaps2.size)
            assertEquals(gaps2.size, gaps3.size)
        }
    }

    // ========== 场景 4：知识过期 ==========

    @Nested
    @DisplayName("场景 4：知识过期")
    inner class KnowledgeExpirationTests {

        @Test
        @DisplayName("文件变更后 checksum 应不同")
        fun `checksum should differ after file change`() {
            val file = File(tempDir, "test.kt")
            file.writeText("original content")

            val checksum1 = checksumCalculator.calculate(file)

            file.writeText("modified content")

            val checksum2 = checksumCalculator.calculate(file)

            assertNotEquals(checksum1, checksum2)
        }

        @Test
        @DisplayName("hasChanged 应正确检测变更")
        fun `hasChanged should detect file change correctly`() {
            val file = File(tempDir, "change.kt")
            file.writeText("original")
            val oldChecksum = checksumCalculator.calculate(file)

            // 未变更
            assertFalse(checksumCalculator.hasChanged(file, oldChecksum))

            // 变更后
            file.writeText("modified")
            assertTrue(checksumCalculator.hasChanged(file, oldChecksum))
        }
    }

    // ========== 场景 5：完整流程 ==========

    @Nested
    @DisplayName("场景 5：完整流程")
    inner class FullFlowTests {

        @Test
        @DisplayName("完整自迭代流程：检测 → 调度 → 执行")
        fun `full self-iteration flow should work`() = runBlocking {
            // 1. 创建低完成度的 Puzzle
            val puzzle = Puzzle(
                id = "api-user",
                type = PuzzleType.API,
                status = PuzzleStatus.IN_PROGRESS,
                content = "# UserController\n\n## 概述\n用户控制器。",
                completeness = 0.3,
                confidence = 0.5,
                lastUpdated = Instant.now(),
                filePath = ".sman/puzzles/api-user.md"
            )
            puzzleStore.save(puzzle)

            // 2. 检测空白
            val detector = GapDetector()
            val gaps = detector.detect(listOf(puzzle))
            assertTrue(gaps.isNotEmpty(), "应该检测到空白")

            // 3. 调度
            val scheduler = TaskScheduler()
            val prioritized = scheduler.prioritize(gaps)
            assertTrue(prioritized.first().priority >= prioritized.last().priority)

            // 4. 创建任务并验证状态流转
            val task = AnalysisTask.create(
                id = "task-user-api",
                type = TaskType.UPDATE_PUZZLE,
                target = "UserController.kt",
                puzzleId = "api-user",
                priority = prioritized.first().priority
            )
            taskQueueStore.enqueue(task)

            // 5. 验证任务已入队
            val queued = taskQueueStore.findById("task-user-api")
            assertNotNull(queued)
            assertEquals(TaskStatus.PENDING, queued?.status)

            // 6. 模拟状态流转
            val runningTask = queued!!.start()
            taskQueueStore.update(runningTask)

            val afterStart = taskQueueStore.findById("task-user-api")
            assertEquals(TaskStatus.RUNNING, afterStart?.status)

            // 7. 完成任务
            val completedTask = runningTask.complete()
            taskQueueStore.update(completedTask)

            val afterComplete = taskQueueStore.findById("task-user-api")
            assertEquals(TaskStatus.COMPLETED, afterComplete?.status)
        }
    }

    // ========== 场景 6：Token 预算管理 ==========

    @Nested
    @DisplayName("场景 6：Token 预算管理")
    inner class TokenBudgetTests {

        @Test
        @DisplayName("预算耗尽时 selectNext 应返回 null")
        fun `selectNext should return null when budget exhausted`() {
            val task = AnalysisTask.create(
                id = "budget-task",
                type = TaskType.ANALYZE_API,
                target = "Test.kt",
                puzzleId = "test"
            )
            taskQueueStore.enqueue(task)

            val scheduler = TaskScheduler()
            val exhaustedBudget = TokenBudget(
                maxTokensPerTask = 0,
                maxTasksPerSession = 0
            )

            val next = scheduler.selectNext(
                listOf(com.smancode.sman.domain.puzzle.Gap(
                    type = com.smancode.sman.domain.puzzle.GapType.LOW_COMPLETENESS,
                    puzzleType = PuzzleType.API,
                    description = "Test",
                    priority = 0.8,
                    relatedFiles = emptyList(),
                    detectedAt = Instant.now()
                )),
                exhaustedBudget
            )

            assertNull(next)
        }

        @Test
        @DisplayName("有限预算应选择最高优先级任务")
        fun `should select highest priority task within budget`() {
            val scheduler = TaskScheduler()

            val gaps = listOf(
                com.smancode.sman.domain.puzzle.Gap(
                    type = com.smancode.sman.domain.puzzle.GapType.LOW_COMPLETENESS,
                    puzzleType = PuzzleType.API,
                    description = "Low priority",
                    priority = 0.3,
                    relatedFiles = emptyList(),
                    detectedAt = Instant.now()
                ),
                com.smancode.sman.domain.puzzle.Gap(
                    type = com.smancode.sman.domain.puzzle.GapType.USER_QUERY_TRIGGERED,
                    puzzleType = PuzzleType.API,
                    description = "High priority",
                    priority = 0.9,
                    relatedFiles = emptyList(),
                    detectedAt = Instant.now()
                )
            )

            val budget = TokenBudget(
                maxTokensPerTask = 4000,
                maxTasksPerSession = 1
            )

            val selected = scheduler.selectNext(gaps, budget)

            assertNotNull(selected)
            assertEquals(0.9, selected?.priority)
        }
    }
}
