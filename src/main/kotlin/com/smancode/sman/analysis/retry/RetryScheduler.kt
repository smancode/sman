package com.smancode.sman.analysis.retry

import com.smancode.sman.analysis.vectorization.CodeVectorizationService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import org.slf4j.MarkerFactory

/**
 * 离线重试调度器
 *
 * 用途：
 * - 定时扫描失败记录
 * - 自动重试失败的任务
 * - 支持后台调度
 *
 * @param failureRecordService 失败记录服务
 * @param intervalMs 扫描间隔（毫秒）
 */
class RetryScheduler(
    private val failureRecordService: FailureRecordService,
    private val intervalMs: Long = 60000  // 1分钟
) {
    private val logger = LoggerFactory.getLogger(this::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile
    private var isRunning = false
    private var job: kotlinx.coroutines.Job? = null

    // 重试处理器（由外部注入）
    private val retryHandlers = mutableMapOf<OperationType, suspend (String, String) -> Boolean>()

    init {
        require(intervalMs > 0) { "扫描间隔必须大于 0" }
    }

    /**
     * 注册重试处理器
     *
     * @param operationType 操作类型
     * @param handler 重试处理器函数（参数：项目键、项数据；返回：是否成功）
     */
    fun registerHandler(operationType: OperationType, handler: suspend (String, String) -> Boolean) {
        retryHandlers[operationType] = handler
        logger.info("注册重试处理器: type={}", operationType)
    }

    /**
     * 启动调度器
     *
     * @param projectKey 项目键（可选，如果指定则只处理该项目的失败记录）
     */
    fun start(projectKey: String? = null) {
        if (isRunning) {
            logger.warn("调度器已在运行")
            return
        }

        isRunning = true
        job = scope.launch {
            logger.info("重试调度器已启动，间隔={} ms, projectKey={}", intervalMs, projectKey ?: "全部")

            while (isRunning) {
                try {
                    processPendingRetries(projectKey)
                } catch (e: Exception) {
                    logger.error("重试调度失败", e)
                }
                delay(intervalMs)
            }
        }
    }

    /**
     * 停止调度器
     */
    fun stop() {
        isRunning = false
        job?.cancel()
        logger.info("重试调度器已停止")
    }

    /**
     * 处理待重试的记录
     *
     * @param projectKey 项目键（可选）
     */
    private suspend fun processPendingRetries(projectKey: String? = null) {
        val pendingRecords = failureRecordService.getPendingRetry(projectKey, limit = 10)

        if (pendingRecords.isEmpty()) {
            return
        }

        logger.info("开始处理 {} 条待重试记录", pendingRecords.size)

        for (record in pendingRecords) {
            processRetry(record)
        }

        // 清理旧的成功记录
        failureRecordService.cleanupSuccessRecords()
    }

    /**
     * 处理单条重试
     *
     * @param record 失败记录
     */
    private suspend fun processRetry(record: FailureRecord) {
        val newRetryCount = record.retryCount + 1

        try {
            // 获取重试处理器
            val handler = retryHandlers[record.operationType]
            if (handler == null) {
                logger.warn("未找到重试处理器: type={}, item={}",
                    record.operationType, record.itemIdentifier)
                failureRecordService.markAsFailed(record.id)
                return
            }

            // 执行重试
            logger.info("重试: type={}, item={}, retryCount={}",
                record.operationType, record.itemIdentifier, newRetryCount)

            val success = handler(record.projectKey, record.itemData)

            if (success) {
                failureRecordService.updateRetryStatus(
                    id = record.id,
                    success = true,
                    newRetryCount = newRetryCount
                )
                logger.info("重试成功: type={}, item={}",
                    record.operationType, record.itemIdentifier)
            } else {
                // 重试失败，判断是否继续
                if (newRetryCount >= record.maxRetries) {
                    logger.error("达到最大重试次数: type={}, item={}",
                        record.operationType, record.itemIdentifier)
                    failureRecordService.markAsFailed(record.id)
                } else {
                    val nextRetryAt = FailureRecordService.calculateNextRetryStatic(newRetryCount)
                    failureRecordService.updateRetryStatus(
                        id = record.id,
                        success = false,
                        newRetryCount = newRetryCount,
                        nextRetryAt = nextRetryAt
                    )
                }
            }
        } catch (e: Exception) {
            logger.error("重试异常: type={}, item={}, error={}",
                record.operationType, record.itemIdentifier, e.message)

            if (newRetryCount >= record.maxRetries) {
                failureRecordService.markAsFailed(record.id)
            } else {
                val nextRetryAt = FailureRecordService.calculateNextRetryStatic(newRetryCount)
                failureRecordService.updateRetryStatus(
                    id = record.id,
                    success = false,
                    newRetryCount = newRetryCount,
                    nextRetryAt = nextRetryAt
                )
            }
        }
    }

    /**
     * 获取统计信息
     *
     * @param projectKey 项目键（可选）
     * @return 统计信息
     */
    suspend fun getStatistics(projectKey: String? = null): FailureStatistics {
        return failureRecordService.getStatistics(projectKey)
    }

    /**
     * 是否正在运行
     */
    fun isRunning(): Boolean = isRunning

    companion object {
        /**
         * 创建并启动调度器
         */
        fun start(
            failureRecordService: FailureRecordService,
            intervalMs: Long = 60000,
            projectKey: String? = null
        ): RetryScheduler {
            val scheduler = RetryScheduler(failureRecordService, intervalMs)
            scheduler.start(projectKey)
            return scheduler
        }
    }
}
