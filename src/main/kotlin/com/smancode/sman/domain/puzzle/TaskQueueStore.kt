package com.smancode.sman.domain.puzzle

import org.slf4j.LoggerFactory
import java.io.File
import java.time.Instant

/**
 * 任务队列存储
 *
 * 负责任务队列的持久化到 JSON 文件
 * 文件路径：{projectPath}/.sman/queue/pending.json
 */
class TaskQueueStore(private val projectPath: String) {

    private val logger = LoggerFactory.getLogger(TaskQueueStore::class.java)

    companion object {
        private const val QUEUE_DIR = ".sman/queue"
        private const val QUEUE_FILE = "pending.json"
    }

    @Volatile
    private var cache: TaskQueue? = null

    /** 加载任务队列 */
    fun load(): TaskQueue {
        cache?.let { return it }

        val file = getQueueFile()
        val queue = if (file.exists()) {
            TaskQueueJson.deserialize(file.readText())
        } else {
            TaskQueue(
                version = TaskQueue.CURRENT_VERSION,
                lastUpdated = Instant.now(),
                tasks = emptyList()
            )
        }
        cache = queue
        return queue
    }

    /** 添加任务到队列 */
    fun enqueue(task: AnalysisTask) {
        validate(task)

        val queue = load()
        if (queue.tasks.any { it.id == task.id }) {
            logger.debug("任务已存在，跳过: {}", task.id)
            return
        }

        save(queue.copy(tasks = queue.tasks + task, lastUpdated = Instant.now()))
        logger.info("任务已入队: id={}, type={}, priority={}", task.id, task.type, task.priority)
    }

    /** 移除并返回最高优先级的 PENDING 任务 */
    fun dequeue(): AnalysisTask? {
        val queue = load()

        val task = queue.tasks
            .filter { it.status == TaskStatus.PENDING }
            .maxByOrNull { it.priority }

        if (task != null) {
            save(queue.copy(
                tasks = queue.tasks.filter { it.id != task.id },
                lastUpdated = Instant.now()
            ))
            logger.info("任务已出队: id={}", task.id)
        }

        return task
    }

    /** 更新任务 */
    fun update(task: AnalysisTask) {
        val queue = load()
        if (!queue.tasks.any { it.id == task.id }) {
            throw IllegalArgumentException("任务不存在: ${task.id}")
        }

        save(queue.copy(
            tasks = queue.tasks.map { if (it.id == task.id) task else it },
            lastUpdated = Instant.now()
        ))
        logger.debug("任务已更新: id={}, status={}", task.id, task.status)
    }

    /** 按 ID 查找任务 */
    fun findById(id: String): AnalysisTask? = load().tasks.find { it.id == id }

    /** 按状态查找任务 */
    fun findByStatus(status: TaskStatus): List<AnalysisTask> = load().tasks.filter { it.status == status }

    /** 查找可执行的任务（PENDING 且重试次数未超限） */
    fun findRunnable(maxRetry: Int): List<AnalysisTask> = load().tasks.filter {
        it.status == TaskStatus.PENDING && it.retryCount < maxRetry
    }

    /** 查找超时的 RUNNING 任务 */
    fun findStaleRunning(timeoutSeconds: Long): List<AnalysisTask> {
        val now = Instant.now()
        return load().tasks.filter { task ->
            task.status == TaskStatus.RUNNING &&
                task.startedAt != null &&
                now.minusSeconds(timeoutSeconds).isAfter(task.startedAt)
        }
    }

    /** 重置任务为 PENDING 状态（用于重试） */
    fun resetTask(id: String) {
        val task = findById(id) ?: throw IllegalArgumentException("任务不存在: $id")
        val reset = task.copy(
            status = TaskStatus.PENDING,
            startedAt = null,
            retryCount = task.retryCount + 1
        )
        update(reset)
        logger.info("任务已重置: id={}, retryCount={}", id, reset.retryCount)
    }

    /** 标记任务为失败 */
    fun markFailed(id: String, errorMessage: String) {
        val task = findById(id) ?: throw IllegalArgumentException("任务不存在: $id")
        val failed = task.copy(
            status = TaskStatus.FAILED,
            completedAt = Instant.now(),
            errorMessage = errorMessage
        )
        update(failed)
        logger.warn("任务已标记失败: id={}, error={}", id, errorMessage)
    }

    /** 清理已完成/过期的任务 */
    fun cleanup(retentionDays: Int = 7) {
        val queue = load()
        val cutoff = Instant.now().minusSeconds(retentionDays * 24 * 60 * 60L)

        val before = queue.tasks.size
        val remaining = queue.tasks.filter { task ->
            when (task.status) {
                TaskStatus.COMPLETED -> false
                TaskStatus.SKIPPED -> task.completedAt?.isAfter(cutoff) ?: true
                else -> true
            }
        }
        save(queue.copy(tasks = remaining, lastUpdated = Instant.now()))

        val removed = before - remaining.size
        if (removed > 0) {
            logger.info("清理了 {} 个已完成/过期的任务", removed)
        }
    }

    private fun validate(task: AnalysisTask) {
        require(task.id.isNotBlank()) { "任务 id 不能为空" }
        require(task.priority in 0.0..1.0) {
            "priority 必须在 0-1 之间，当前值: ${task.priority}"
        }
        require(task.retryCount >= 0) { "retryCount 不能为负数" }
    }

    private fun getQueueFile(): File {
        val dir = File(projectPath, QUEUE_DIR)
        dir.mkdirs()
        return File(dir, QUEUE_FILE)
    }

    private fun save(queue: TaskQueue) {
        val file = getQueueFile()
        file.writeText(TaskQueueJson.serialize(queue))
        cache = queue
    }
}
