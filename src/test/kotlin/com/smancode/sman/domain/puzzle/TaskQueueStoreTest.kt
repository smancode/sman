package com.smancode.sman.domain.puzzle

import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.Instant

@DisplayName("TaskQueueStore 测试套件")
class TaskQueueStoreTest {

    @TempDir
    lateinit var tempDir: File

    private lateinit var taskQueueStore: TaskQueueStore

    @BeforeEach
    fun setUp() {
        taskQueueStore = TaskQueueStore(tempDir.absolutePath)
    }

    // ========== 基础 CRUD 测试 ==========

    @Nested
    @DisplayName("基础 CRUD 测试")
    inner class BasicCrudTests {

        @Test
        @DisplayName("enqueue - 应添加任务到队列")
        fun `enqueue should add task to queue`() {
            val task = createTestTask(id = "task-1")

            taskQueueStore.enqueue(task)

            val queue = taskQueueStore.load()
            assertEquals(1, queue.tasks.size)
            assertEquals("task-1", queue.tasks.first().id)
        }

        @Test
        @DisplayName("enqueue - 相同 id 任务不应重复添加")
        fun `enqueue should not add duplicate task`() {
            val task = createTestTask(id = "task-1")
            taskQueueStore.enqueue(task)
            taskQueueStore.enqueue(task)

            val queue = taskQueueStore.load()
            assertEquals(1, queue.tasks.size)
        }

        @Test
        @DisplayName("dequeue - 应移除并返回最高优先级任务")
        fun `dequeue should remove and return highest priority task`() {
            val task1 = createTestTask(id = "task-1", priority = 0.5)
            val task2 = createTestTask(id = "task-2", priority = 0.9)
            val task3 = createTestTask(id = "task-3", priority = 0.7)

            taskQueueStore.enqueue(task1)
            taskQueueStore.enqueue(task2)
            taskQueueStore.enqueue(task3)

            val dequeued = taskQueueStore.dequeue()

            assertNotNull(dequeued)
            assertEquals("task-2", dequeued?.id)

            val queue = taskQueueStore.load()
            assertEquals(2, queue.tasks.size)
            assertFalse(queue.tasks.any { it.id == "task-2" })
        }

        @Test
        @DisplayName("dequeue - 空队列返回 null")
        fun `dequeue should return null for empty queue`() {
            val dequeued = taskQueueStore.dequeue()
            assertNull(dequeued)
        }

        @Test
        @DisplayName("update - 应更新任务状态")
        fun `update should update task status`() {
            val task = createTestTask(id = "task-1", status = TaskStatus.PENDING)
            taskQueueStore.enqueue(task)

            val updated = task.copy(status = TaskStatus.RUNNING)
            taskQueueStore.update(updated)

            val queue = taskQueueStore.load()
            assertEquals(TaskStatus.RUNNING, queue.tasks.first().status)
        }

        @Test
        @DisplayName("update - 不存在的任务应抛出异常")
        fun `update should throw for non-existent task`() {
            val task = createTestTask(id = "non-existent")

            assertThrows<IllegalArgumentException> {
                taskQueueStore.update(task)
            }
        }
    }

    // ========== 查询测试 ==========

    @Nested
    @DisplayName("查询测试")
    inner class QueryTests {

        @Test
        @DisplayName("load - 应正确加载队列")
        fun `load should load queue correctly`() {
            taskQueueStore.enqueue(createTestTask(id = "task-1"))
            taskQueueStore.enqueue(createTestTask(id = "task-2"))

            val queue = taskQueueStore.load()

            assertEquals(2, queue.tasks.size)
            assertEquals(TaskQueue.CURRENT_VERSION, queue.version)
        }

        @Test
        @DisplayName("load - 空队列应返回空列表")
        fun `load should return empty list for new queue`() {
            val queue = taskQueueStore.load()

            assertTrue(queue.tasks.isEmpty())
        }

        @Test
        @DisplayName("findById - 应返回指定任务")
        fun `findById should return specified task`() {
            taskQueueStore.enqueue(createTestTask(id = "task-1"))
            taskQueueStore.enqueue(createTestTask(id = "task-2"))

            val found = taskQueueStore.findById("task-1")

            assertNotNull(found)
            assertEquals("task-1", found?.id)
        }

        @Test
        @DisplayName("findById - 不存在返回 null")
        fun `findById should return null for non-existent task`() {
            val found = taskQueueStore.findById("non-existent")
            assertNull(found)
        }

        @Test
        @DisplayName("findByStatus - 应返回指定状态的任务")
        fun `findByStatus should return tasks with specified status`() {
            taskQueueStore.enqueue(createTestTask(id = "pending-1", status = TaskStatus.PENDING))
            taskQueueStore.enqueue(createTestTask(id = "running-1", status = TaskStatus.RUNNING))
            taskQueueStore.enqueue(createTestTask(id = "pending-2", status = TaskStatus.PENDING))

            val pendingTasks = taskQueueStore.findByStatus(TaskStatus.PENDING)

            assertEquals(2, pendingTasks.size)
            assertTrue(pendingTasks.all { it.status == TaskStatus.PENDING })
        }

        @Test
        @DisplayName("findRunnable - 应返回可执行的任务（PENDING 且未超重试次数）")
        fun `findRunnable should return runnable tasks`() {
            taskQueueStore.enqueue(createTestTask(id = "pending-1", status = TaskStatus.PENDING))
            taskQueueStore.enqueue(createTestTask(id = "running-1", status = TaskStatus.RUNNING))
            taskQueueStore.enqueue(createTestTask(id = "failed-1", status = TaskStatus.FAILED, retryCount = 3))

            val runnable = taskQueueStore.findRunnable(maxRetry = 3)

            assertEquals(1, runnable.size)
            assertEquals("pending-1", runnable.first().id)
        }
    }

    // ========== 持久化测试 ==========

    @Nested
    @DisplayName("持久化测试")
    inner class PersistenceTests {

        @Test
        @DisplayName("应持久化到 JSON 文件")
        fun `should persist to json file`() {
            taskQueueStore.enqueue(createTestTask(id = "task-1"))

            val queueFile = File(tempDir, ".sman/queue/pending.json")
            assertTrue(queueFile.exists())

            val content = queueFile.readText()
            assertTrue(content.contains("task-1"))
            assertTrue(content.contains("\"version\""))
        }

        @Test
        @DisplayName("重启后应能恢复队列")
        fun `should recover queue after restart`() {
            taskQueueStore.enqueue(createTestTask(id = "task-1"))
            taskQueueStore.enqueue(createTestTask(id = "task-2"))

            // 模拟重启：创建新实例
            val newStore = TaskQueueStore(tempDir.absolutePath)
            val queue = newStore.load()

            assertEquals(2, queue.tasks.size)
        }

        @Test
        @DisplayName("应正确保存中文内容")
        fun `should correctly save Chinese content`() {
            val task = createTestTask(
                id = "task-中文",
                target = "src/用户服务.kt"
            )
            taskQueueStore.enqueue(task)

            val newStore = TaskQueueStore(tempDir.absolutePath)
            val recovered = newStore.findById("task-中文")

            assertNotNull(recovered)
            assertEquals("src/用户服务.kt", recovered?.target)
        }
    }

    // ========== 中断恢复测试 ==========

    @Nested
    @DisplayName("中断恢复测试")
    inner class RecoveryTests {

        @Test
        @DisplayName("findStaleRunning - 应找到超时的 RUNNING 任务")
        fun `findStaleRunning should find stale running tasks`() {
            val now = Instant.now()
            val staleTask = createTestTask(
                id = "stale-1",
                status = TaskStatus.RUNNING,
                startedAt = now.minusSeconds(120) // 2 分钟前开始
            )
            val recentTask = createTestTask(
                id = "recent-1",
                status = TaskStatus.RUNNING,
                startedAt = now.minusSeconds(30) // 30 秒前开始
            )

            taskQueueStore.enqueue(staleTask)
            taskQueueStore.enqueue(recentTask)

            val staleTasks = taskQueueStore.findStaleRunning(timeoutSeconds = 60)

            assertEquals(1, staleTasks.size)
            assertEquals("stale-1", staleTasks.first().id)
        }

        @Test
        @DisplayName("resetTask - 应重置任务为 PENDING 并增加重试次数")
        fun `resetTask should reset to pending and increment retry count`() {
            val task = createTestTask(
                id = "task-1",
                status = TaskStatus.RUNNING,
                retryCount = 1
            )
            taskQueueStore.enqueue(task)

            taskQueueStore.resetTask("task-1")

            val reset = taskQueueStore.findById("task-1")
            assertEquals(TaskStatus.PENDING, reset?.status)
            assertEquals(2, reset?.retryCount)
            assertNull(reset?.startedAt)
        }

        @Test
        @DisplayName("markFailed - 应标记任务为 FAILED")
        fun `markFailed should mark task as failed`() {
            val task = createTestTask(id = "task-1", status = TaskStatus.RUNNING)
            taskQueueStore.enqueue(task)

            taskQueueStore.markFailed("task-1", "Something went wrong")

            val failed = taskQueueStore.findById("task-1")
            assertEquals(TaskStatus.FAILED, failed?.status)
            assertEquals("Something went wrong", failed?.errorMessage)
            assertNotNull(failed?.completedAt)
        }
    }

    // ========== 清理测试 ==========

    @Nested
    @DisplayName("清理测试")
    inner class CleanupTests {

        @Test
        @DisplayName("cleanup - 应清理已完成的任务")
        fun `cleanup should remove completed tasks`() {
            taskQueueStore.enqueue(createTestTask(id = "completed-1", status = TaskStatus.COMPLETED))
            taskQueueStore.enqueue(createTestTask(id = "pending-1", status = TaskStatus.PENDING))
            taskQueueStore.enqueue(createTestTask(id = "completed-2", status = TaskStatus.COMPLETED))

            taskQueueStore.cleanup()

            val queue = taskQueueStore.load()
            assertEquals(1, queue.tasks.size)
            assertEquals("pending-1", queue.tasks.first().id)
        }

        @Test
        @DisplayName("cleanup - 应清理过期的跳过任务（保留 7 天）")
        fun `cleanup should remove old skipped tasks`() {
            val now = Instant.now()
            val oldSkipped = createTestTask(
                id = "old-skipped",
                status = TaskStatus.SKIPPED,
                completedAt = now.minusSeconds(8 * 24 * 60 * 60) // 8 天前
            )
            val recentSkipped = createTestTask(
                id = "recent-skipped",
                status = TaskStatus.SKIPPED,
                completedAt = now.minusSeconds(3 * 24 * 60 * 60) // 3 天前
            )

            taskQueueStore.enqueue(oldSkipped)
            taskQueueStore.enqueue(recentSkipped)

            taskQueueStore.cleanup(retentionDays = 7)

            val queue = taskQueueStore.load()
            assertEquals(1, queue.tasks.size)
            assertEquals("recent-skipped", queue.tasks.first().id)
        }
    }

    // ========== 白名单校验测试 ==========

    @Nested
    @DisplayName("白名单校验测试")
    inner class ValidationTests {

        @Test
        @DisplayName("enqueue - 空 id 应抛出异常")
        fun `enqueue should throw when id is blank`() {
            val task = createTestTask(id = "")

            assertThrows<IllegalArgumentException> {
                taskQueueStore.enqueue(task)
            }
        }

        @Test
        @DisplayName("enqueue - priority 超出范围应抛出异常")
        fun `enqueue should throw when priority out of range`() {
            val task = createTestTask(priority = 1.5)

            assertThrows<IllegalArgumentException> {
                taskQueueStore.enqueue(task)
            }
        }

        @Test
        @DisplayName("enqueue - retryCount 为负数应抛出异常")
        fun `enqueue should throw when retryCount is negative`() {
            val task = createTestTask(retryCount = -1)

            assertThrows<IllegalArgumentException> {
                taskQueueStore.enqueue(task)
            }
        }
    }

    // ========== 辅助方法 ==========

    private fun createTestTask(
        id: String = "test-task",
        type: TaskType = TaskType.ANALYZE_API,
        target: String = "src/Test.kt",
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
