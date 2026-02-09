package com.smancode.smanagent.analysis.retry

import com.smancode.smanagent.analysis.database.H2DatabaseService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * 失败记录实体
 */
data class FailureRecord(
    val id: String = UUID.randomUUID().toString(),
    val projectKey: String,
    val operationType: OperationType,
    val itemIdentifier: String,
    val itemData: String,
    val originalError: String,
    val retryCount: Int = 0,
    val maxRetries: Int = 3,
    val createdAt: Long = System.currentTimeMillis(),
    val nextRetryAt: Long = System.currentTimeMillis() + 1000,  // 默认1秒后重试
    val status: FailureStatus = FailureStatus.PENDING
)

/**
 * 操作类型
 */
enum class OperationType {
    VECTORIZATION_BGE,
    VECTORIZATION_LLM,
    ANALYSIS_STEP,
    LLM_CALL,
    BGE_BATCH
}

/**
 * 失败状态
 */
enum class FailureStatus {
    PENDING,    // 等待重试
    RETRYING,   // 重试中
    SUCCESS,    // 重试成功
    FAILED      // 彻底失败
}

/**
 * 失败记录服务（H2 持久化）
 *
 * 用途：
 * - 持久化失败的操作记录
 * - 支持离线重试
 * - 记录重试历史
 *
 * @param databaseService H2 数据库服务
 */
class FailureRecordService(
    private val databaseService: H2DatabaseService
) {
    private val logger = LoggerFactory.getLogger(this::class.java)

    init {
        initializeTable()
    }

    /**
     * 初始化失败记录表
     */
    private fun initializeTable() {
        try {
            // 创建失败记录表
            databaseService.executeSql(
                """
                CREATE TABLE IF NOT EXISTS vectorization_failures (
                    id VARCHAR(36) PRIMARY KEY,
                    project_key VARCHAR(100) NOT NULL,
                    operation_type VARCHAR(50) NOT NULL,
                    item_identifier VARCHAR(500) NOT NULL,
                    item_data CLOB NOT NULL,
                    original_error CLOB NOT NULL,
                    retry_count INT DEFAULT 0,
                    max_retries INT DEFAULT 3,
                    created_at BIGINT NOT NULL,
                    next_retry_at BIGINT NOT NULL,
                    status VARCHAR(20) NOT NULL,
                    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
                """.trimIndent()
            )

            // 创建索引
            databaseService.executeSql(
                "CREATE INDEX IF NOT EXISTS idx_failures_project_status ON vectorization_failures(project_key, status)"
            )
            databaseService.executeSql(
                "CREATE INDEX IF NOT EXISTS idx_failures_next_retry ON vectorization_failures(next_retry_at)"
            )
            databaseService.executeSql(
                "CREATE INDEX IF NOT EXISTS idx_failures_operation_type ON vectorization_failures(operation_type)"
            )

            logger.info("失败记录表初始化完成")
        } catch (e: Exception) {
            logger.error("初始化失败记录表失败: ${e.message}", e)
            throw e
        }
    }

    /**
     * 添加失败记录
     *
     * @param projectKey 项目键
     * @param operationType 操作类型
     * @param itemIdentifier 项标识符
     * @param itemData 项数据（序列化）
     * @param error 异常对象
     */
    fun addFailure(
        projectKey: String,
        operationType: OperationType,
        itemIdentifier: String,
        itemData: String,
        error: Exception
    ) {
        try {
            val sql = """
                INSERT INTO vectorization_failures
                (id, project_key, operation_type, item_identifier, item_data,
                 original_error, retry_count, max_retries, created_at, next_retry_at, status)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()

            databaseService.executeSql(
                sql,
                listOf(
                    UUID.randomUUID().toString(),
                    projectKey,
                    operationType.name,
                    itemIdentifier,
                    itemData,
                    error.stackTraceToString(),
                    0,
                    3,
                    System.currentTimeMillis(),
                    calculateNextRetryStatic(0),
                    FailureStatus.PENDING.name
                )
            )

            logger.warn("添加失败记录: operation={}, item={}", operationType, itemIdentifier)
        } catch (e: Exception) {
            logger.error("添加失败记录失败: ${e.message}", e)
        }
    }

    /**
     * 获取待重试的记录
     *
     * @param projectKey 项目键（可选）
     * @param limit 限制数量
     * @return 待重试的记录列表
     */
    suspend fun getPendingRetry(projectKey: String? = null, limit: Int = 10): List<FailureRecord> =
        withContext(Dispatchers.IO) {
            try {
                val sql = if (projectKey != null) {
                    """
                    SELECT * FROM vectorization_failures
                    WHERE project_key = ? AND status = 'PENDING' AND next_retry_at <= ?
                    ORDER BY next_retry_at ASC
                    LIMIT ?
                    """.trimIndent()
                } else {
                    """
                    SELECT * FROM vectorization_failures
                    WHERE status = 'PENDING' AND next_retry_at <= ?
                    ORDER BY next_retry_at ASC
                    LIMIT ?
                    """.trimIndent()
                }

                val params = if (projectKey != null) {
                    listOf(projectKey, System.currentTimeMillis(), limit)
                } else {
                    listOf(System.currentTimeMillis(), limit)
                }

                databaseService.querySuspend(sql, params) { rs ->
                    generateSequence {
                        if (rs.next()) {
                            FailureRecord(
                                id = rs.getString("id"),
                                projectKey = rs.getString("project_key"),
                                operationType = OperationType.valueOf(rs.getString("operation_type")),
                                itemIdentifier = rs.getString("item_identifier"),
                                itemData = rs.getString("item_data"),
                                originalError = rs.getString("original_error"),
                                retryCount = rs.getInt("retry_count"),
                                maxRetries = rs.getInt("max_retries"),
                                createdAt = rs.getLong("created_at"),
                                nextRetryAt = rs.getLong("next_retry_at"),
                                status = FailureStatus.valueOf(rs.getString("status"))
                            )
                        } else null
                    }.toList()
                }
            } catch (e: Exception) {
                logger.error("获取待重试记录失败: ${e.message}", e)
                emptyList()
            }
        }

    /**
     * 更新重试状态
     *
     * @param id 记录 ID
     * @param success 是否成功
     * @param newRetryCount 新的重试次数
     * @param nextRetryAt 下次重试时间（可选）
     */
    fun updateRetryStatus(
        id: String,
        success: Boolean,
        newRetryCount: Int,
        nextRetryAt: Long? = null
    ) {
        try {
            val status = if (success) FailureStatus.SUCCESS else FailureStatus.PENDING
            val sql = """
                UPDATE vectorization_failures
                SET status = ?, retry_count = ?, next_retry_at = ?, updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
            """.trimIndent()

            val params = mutableListOf<Any>(status.name, newRetryCount)
            if (nextRetryAt != null) {
                params.add(nextRetryAt)
            } else {
                params.add(System.currentTimeMillis())
            }
            params.add(id)

            databaseService.executeSql(sql, params)
            logger.info("更新重试状态: id={}, success={}, retryCount={}", id, success, newRetryCount)
        } catch (e: Exception) {
            logger.error("更新重试状态失败: ${e.message}", e)
        }
    }

    /**
     * 标记为彻底失败
     *
     * @param id 记录 ID
     */
    fun markAsFailed(id: String) {
        try {
            val sql = """
                UPDATE vectorization_failures
                SET status = 'FAILED', updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
            """.trimIndent()

            databaseService.executeSql(sql, listOf(id))
            logger.warn("标记为彻底失败: id={}", id)
        } catch (e: Exception) {
            logger.error("标记失败状态失败: ${e.message}", e)
        }
    }

    /**
     * 清理成功的记录
     *
     * @param olderThanMs 保留时间（毫秒）
     */
    fun cleanupSuccessRecords(olderThanMs: Long = 7 * 24 * 60 * 60 * 1000L) { // 7天
        try {
            val threshold = System.currentTimeMillis() - olderThanMs
            val sql = "DELETE FROM vectorization_failures WHERE status = 'SUCCESS' AND created_at < ?"

            val deleted = databaseService.executeSql(sql, listOf(threshold))
            logger.info("清理成功记录: 删除 {} 条", deleted)
        } catch (e: Exception) {
            logger.error("清理成功记录失败: ${e.message}", e)
        }
    }

    /**
     * 清理失败的记录
     *
     * @param olderThanMs 保留时间（毫秒）
     */
    fun cleanupFailedRecords(olderThanMs: Long = 30 * 24 * 60 * 60 * 1000L) { // 30天
        try {
            val threshold = System.currentTimeMillis() - olderThanMs
            val sql = "DELETE FROM vectorization_failures WHERE status = 'FAILED' AND created_at < ?"

            val deleted = databaseService.executeSql(sql, listOf(threshold))
            logger.info("清理失败记录: 删除 {} 条", deleted)
        } catch (e: Exception) {
            logger.error("清理失败记录失败: ${e.message}", e)
        }
    }

    /**
     * 获取统计信息
     *
     * @param projectKey 项目键（可选）
     * @return 统计信息
     */
    suspend fun getStatistics(projectKey: String? = null): FailureStatistics = withContext(Dispatchers.IO) {
        try {
            val whereClause = if (projectKey != null) "WHERE project_key = ?" else ""

            val total = databaseService.querySuspend(
                "SELECT COUNT(*) as count FROM vectorization_failures $whereClause",
                if (projectKey != null) listOf(projectKey) else emptyList()
            ) { rs ->
                if (rs.next()) rs.getInt("count") else 0
            }

            val pending = databaseService.querySuspend(
                "SELECT COUNT(*) as count FROM vectorization_failures $whereClause AND status = 'PENDING'",
                if (projectKey != null) listOf(projectKey) else emptyList()
            ) { rs ->
                if (rs.next()) rs.getInt("count") else 0
            }

            val success = databaseService.querySuspend(
                "SELECT COUNT(*) as count FROM vectorization_failures $whereClause AND status = 'SUCCESS'",
                if (projectKey != null) listOf(projectKey) else emptyList()
            ) { rs ->
                if (rs.next()) rs.getInt("count") else 0
            }

            val failed = databaseService.querySuspend(
                "SELECT COUNT(*) as count FROM vectorization_failures $whereClause AND status = 'FAILED'",
                if (projectKey != null) listOf(projectKey) else emptyList()
            ) { rs ->
                if (rs.next()) rs.getInt("count") else 0
            }

            FailureStatistics(
                totalRecords = total,
                pendingRecords = pending,
                successRecords = success,
                failedRecords = failed
            )
        } catch (e: Exception) {
            logger.error("获取统计信息失败: ${e.message}", e)
            FailureStatistics(0, 0, 0, 0)
        }
    }

    companion object {
        /**
         * 计算下次重试时间（指数退避）
         *
         * @param retryCount 当前重试次数
         * @return 下次重试时间戳
         */
        fun calculateNextRetryStatic(retryCount: Int): Long {
            val delayMs = 1000L * Math.pow(2.0, retryCount.toDouble()).toLong()
            return System.currentTimeMillis() + minOf(delayMs, 3600000L) // 最多延迟1小时
        }
    }
}

/**
 * 失败统计信息
 */
data class FailureStatistics(
    val totalRecords: Int,
    val pendingRecords: Int,
    val successRecords: Int,
    val failedRecords: Int
) {
    override fun toString(): String {
        return """
            |失败记录统计:
            |  总记录数: $totalRecords
            |  待重试: $pendingRecords
            |  成功: $successRecords
            |  失败: $failedRecords
        """.trimMargin()
    }
}
