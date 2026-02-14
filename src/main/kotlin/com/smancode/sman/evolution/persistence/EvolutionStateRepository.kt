package com.smancode.sman.evolution.persistence

import com.smancode.sman.analysis.config.VectorDatabaseConfig
import com.smancode.sman.evolution.loop.EvolutionPhase
import com.smancode.sman.evolution.loop.EvolutionStopReason
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.sql.ResultSet
import javax.sql.DataSource

/**
 * 进化状态实体 - 运行状态
 */
data class LoopStateEntity(
    val projectKey: String,
    val enabled: Boolean = true,
    val currentPhase: EvolutionPhase = EvolutionPhase.IDLE,

    // 统计信息
    val totalIterations: Long = 0,
    val successfulIterations: Long = 0,
    val consecutiveDuplicateCount: Int = 0,

    // ING 状态
    val currentQuestion: String? = null,
    val currentQuestionHash: String? = null,
    val explorationProgress: Int = 0,
    val partialSteps: String? = null, // JSON
    val startedAt: Long? = null,

    // 智能控制
    val lastGeneratedQuestionHash: String? = null,
    val lastProjectMd5: String? = null,
    val stopReason: EvolutionStopReason? = null,

    // 时间戳
    val lastUpdatedAt: Long = System.currentTimeMillis()
)

/**
 * 退避状态实体
 */
data class BackoffStateEntity(
    val projectKey: String,
    val consecutiveErrors: Int = 0,
    val lastErrorTime: Long? = null,
    val backoffUntil: Long? = null,
    val lastUpdatedAt: Long = System.currentTimeMillis()
)

/**
 * 每日配额实体
 */
data class DailyQuotaEntity(
    val projectKey: String,
    val questionsToday: Int = 0,
    val explorationsToday: Int = 0,
    val lastResetDate: String = "",
    val lastUpdatedAt: Long = System.currentTimeMillis()
)

/**
 * 进化状态持久化仓储
 *
 * 负责进化循环状态、退避状态和每日配额的持久化。
 */
class EvolutionStateRepository(
    private val config: VectorDatabaseConfig
) {
    private val logger = LoggerFactory.getLogger(EvolutionStateRepository::class.java)
    private val dataSource: DataSource = createDataSource()
    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        // ========== 进化循环状态 SQL ==========
        private const val SQL_INSERT_LOOP_STATE = """
            INSERT INTO evolution_loop_state (
                project_key, enabled, current_phase,
                total_iterations, successful_iterations, consecutive_duplicate_count,
                current_question, current_question_hash, exploration_progress, partial_steps, started_at,
                last_generated_question_hash, last_project_md5, stop_reason,
                last_updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """

        private const val SQL_UPDATE_LOOP_STATE = """
            UPDATE evolution_loop_state SET
                enabled = ?, current_phase = ?,
                total_iterations = ?, successful_iterations = ?, consecutive_duplicate_count = ?,
                current_question = ?, current_question_hash = ?, exploration_progress = ?, partial_steps = ?, started_at = ?,
                last_generated_question_hash = ?, last_project_md5 = ?, stop_reason = ?,
                last_updated_at = ?
            WHERE project_key = ?
        """

        private const val SQL_GET_LOOP_STATE = """
            SELECT * FROM evolution_loop_state WHERE project_key = ?
        """

        private const val SQL_CLEAR_ING_STATE = """
            UPDATE evolution_loop_state SET
                current_phase = 'IDLE',
                current_question = NULL,
                current_question_hash = NULL,
                exploration_progress = 0,
                partial_steps = NULL,
                started_at = NULL,
                last_updated_at = ?
            WHERE project_key = ?
        """

        // ========== 退避状态 SQL ==========
        private const val SQL_INSERT_BACKOFF = """
            INSERT INTO backoff_state (
                project_key, consecutive_errors, last_error_time, backoff_until, last_updated_at
            ) VALUES (?, ?, ?, ?, ?)
        """

        private const val SQL_UPDATE_BACKOFF = """
            UPDATE backoff_state SET
                consecutive_errors = ?, last_error_time = ?, backoff_until = ?, last_updated_at = ?
            WHERE project_key = ?
        """

        private const val SQL_GET_BACKOFF = """
            SELECT * FROM backoff_state WHERE project_key = ?
        """

        // ========== 每日配额 SQL ==========
        private const val SQL_INSERT_QUOTA = """
            INSERT INTO daily_quota (
                project_key, questions_today, explorations_today, last_reset_date, last_updated_at
            ) VALUES (?, ?, ?, ?, ?)
        """

        private const val SQL_UPDATE_QUOTA = """
            UPDATE daily_quota SET
                questions_today = ?, explorations_today = ?, last_reset_date = ?, last_updated_at = ?
            WHERE project_key = ?
        """

        private const val SQL_GET_QUOTA = """
            SELECT * FROM daily_quota WHERE project_key = ?
        """
    }

    private fun createDataSource(): DataSource {
        val hikariConfig = HikariConfig().apply {
            jdbcUrl = "jdbc:h2:${config.databasePath};MODE=PostgreSQL;AUTO_SERVER=TRUE"
            driverClassName = "org.h2.Driver"
            username = "sa"
            password = ""
            maximumPoolSize = 10
            minimumIdle = 2
            connectionTimeout = 30000
            idleTimeout = 600000
            maxLifetime = 1800000
        }
        return HikariDataSource(hikariConfig)
    }

    // ========== 进化循环状态方法 ==========

    /**
     * 保存/更新进化循环状态
     */
    suspend fun saveLoopState(state: LoopStateEntity) = withContext(Dispatchers.IO) {
        require(state.projectKey.isNotBlank()) { "projectKey 不能为空" }

        val existing = getLoopState(state.projectKey)

        dataSource.connection.use { connection ->
            if (existing != null) {
                // 更新
                connection.prepareStatement(SQL_UPDATE_LOOP_STATE).use { stmt ->
                    stmt.setBoolean(1, state.enabled)
                    stmt.setString(2, state.currentPhase.name)
                    stmt.setLong(3, state.totalIterations)
                    stmt.setLong(4, state.successfulIterations)
                    stmt.setInt(5, state.consecutiveDuplicateCount)
                    stmt.setString(6, state.currentQuestion)
                    stmt.setString(7, state.currentQuestionHash)
                    stmt.setInt(8, state.explorationProgress)
                    stmt.setString(9, state.partialSteps)
                    stmt.setObject(10, state.startedAt)
                    stmt.setString(11, state.lastGeneratedQuestionHash)
                    stmt.setString(12, state.lastProjectMd5)
                    stmt.setString(13, state.stopReason?.name)
                    stmt.setLong(14, System.currentTimeMillis())
                    stmt.setString(15, state.projectKey)
                    stmt.executeUpdate()
                }
                logger.debug("更新进化循环状态: projectKey={}", state.projectKey)
            } else {
                // 插入
                connection.prepareStatement(SQL_INSERT_LOOP_STATE).use { stmt ->
                    stmt.setString(1, state.projectKey)
                    stmt.setBoolean(2, state.enabled)
                    stmt.setString(3, state.currentPhase.name)
                    stmt.setLong(4, state.totalIterations)
                    stmt.setLong(5, state.successfulIterations)
                    stmt.setInt(6, state.consecutiveDuplicateCount)
                    stmt.setString(7, state.currentQuestion)
                    stmt.setString(8, state.currentQuestionHash)
                    stmt.setInt(9, state.explorationProgress)
                    stmt.setString(10, state.partialSteps)
                    stmt.setObject(11, state.startedAt)
                    stmt.setString(12, state.lastGeneratedQuestionHash)
                    stmt.setString(13, state.lastProjectMd5)
                    stmt.setString(14, state.stopReason?.name)
                    stmt.setLong(15, System.currentTimeMillis())
                    stmt.executeUpdate()
                }
                logger.debug("保存进化循环状态: projectKey={}", state.projectKey)
            }
        }
    }

    /**
     * 获取进化循环状态
     */
    suspend fun getLoopState(projectKey: String): LoopStateEntity? = withContext(Dispatchers.IO) {
        require(projectKey.isNotBlank()) { "projectKey 不能为空" }

        dataSource.connection.use { connection ->
            connection.prepareStatement(SQL_GET_LOOP_STATE).use { stmt ->
                stmt.setString(1, projectKey)
                val rs = stmt.executeQuery()
                if (rs.next()) {
                    rs.toLoopStateEntity()
                } else {
                    null
                }
            }
        }
    }

    /**
     * 清除 ING 状态（操作完成后）
     */
    suspend fun clearIngState(projectKey: String) = withContext(Dispatchers.IO) {
        require(projectKey.isNotBlank()) { "projectKey 不能为空" }

        dataSource.connection.use { connection ->
            connection.prepareStatement(SQL_CLEAR_ING_STATE).use { stmt ->
                stmt.setLong(1, System.currentTimeMillis())
                stmt.setString(2, projectKey)
                stmt.executeUpdate()
            }
        }
        logger.debug("清除 ING 状态: projectKey={}", projectKey)
    }

    // ========== 退避状态方法 ==========

    /**
     * 保存退避状态
     */
    suspend fun saveBackoffState(state: BackoffStateEntity) = withContext(Dispatchers.IO) {
        require(state.projectKey.isNotBlank()) { "projectKey 不能为空" }

        val existing = getBackoffState(state.projectKey)

        dataSource.connection.use { connection ->
            if (existing != null) {
                // 更新
                connection.prepareStatement(SQL_UPDATE_BACKOFF).use { stmt ->
                    stmt.setInt(1, state.consecutiveErrors)
                    stmt.setObject(2, state.lastErrorTime)
                    stmt.setObject(3, state.backoffUntil)
                    stmt.setLong(4, System.currentTimeMillis())
                    stmt.setString(5, state.projectKey)
                    stmt.executeUpdate()
                }
            } else {
                // 插入
                connection.prepareStatement(SQL_INSERT_BACKOFF).use { stmt ->
                    stmt.setString(1, state.projectKey)
                    stmt.setInt(2, state.consecutiveErrors)
                    stmt.setObject(3, state.lastErrorTime)
                    stmt.setObject(4, state.backoffUntil)
                    stmt.setLong(5, System.currentTimeMillis())
                    stmt.executeUpdate()
                }
            }
        }
        logger.debug("保存退避状态: projectKey={}, errors={}", state.projectKey, state.consecutiveErrors)
    }

    /**
     * 获取退避状态
     */
    suspend fun getBackoffState(projectKey: String): BackoffStateEntity? = withContext(Dispatchers.IO) {
        getBackoffStateInternal(projectKey)
    }

    /**
     * 获取退避状态（内部同步版本）
     */
    private fun getBackoffStateInternal(projectKey: String): BackoffStateEntity? {
        require(projectKey.isNotBlank()) { "projectKey 不能为空" }

        dataSource.connection.use { connection ->
            connection.prepareStatement(SQL_GET_BACKOFF).use { stmt ->
                stmt.setString(1, projectKey)
                val rs = stmt.executeQuery()
                if (rs.next()) {
                    return rs.toBackoffStateEntity()
                } else {
                    return null
                }
            }
        }
    }

    // ========== 每日配额方法 ==========

    /**
     * 保存每日配额
     */
    suspend fun saveDailyQuota(quota: DailyQuotaEntity) = withContext(Dispatchers.IO) {
        require(quota.projectKey.isNotBlank()) { "projectKey 不能为空" }

        val existing = getDailyQuota(quota.projectKey)

        dataSource.connection.use { connection ->
            if (existing != null) {
                // 更新
                connection.prepareStatement(SQL_UPDATE_QUOTA).use { stmt ->
                    stmt.setInt(1, quota.questionsToday)
                    stmt.setInt(2, quota.explorationsToday)
                    stmt.setString(3, quota.lastResetDate)
                    stmt.setLong(4, System.currentTimeMillis())
                    stmt.setString(5, quota.projectKey)
                    stmt.executeUpdate()
                }
            } else {
                // 插入
                connection.prepareStatement(SQL_INSERT_QUOTA).use { stmt ->
                    stmt.setString(1, quota.projectKey)
                    stmt.setInt(2, quota.questionsToday)
                    stmt.setInt(3, quota.explorationsToday)
                    stmt.setString(4, quota.lastResetDate)
                    stmt.setLong(5, System.currentTimeMillis())
                    stmt.executeUpdate()
                }
            }
        }
        logger.debug("保存每日配额: projectKey={}, questions={}", quota.projectKey, quota.questionsToday)
    }

    /**
     * 获取每日配额
     */
    suspend fun getDailyQuota(projectKey: String): DailyQuotaEntity? = withContext(Dispatchers.IO) {
        getDailyQuotaInternal(projectKey)
    }

    /**
     * 获取每日配额（内部同步版本）
     */
    private fun getDailyQuotaInternal(projectKey: String): DailyQuotaEntity? {
        require(projectKey.isNotBlank()) { "projectKey 不能为空" }

        dataSource.connection.use { connection ->
            connection.prepareStatement(SQL_GET_QUOTA).use { stmt ->
                stmt.setString(1, projectKey)
                val rs = stmt.executeQuery()
                if (rs.next()) {
                    return rs.toDailyQuotaEntity()
                } else {
                    return null
                }
            }
        }
    }

    /**
     * 关闭数据库连接
     */
    fun close() {
        if (dataSource is HikariDataSource) {
            dataSource.close()
        }
        logger.info("EvolutionStateRepository 数据库连接已关闭")
    }

    // ========== 辅助方法 ==========

    private fun ResultSet.toLoopStateEntity(): LoopStateEntity {
        val phaseStr = getString("current_phase")
        val phase = try {
            EvolutionPhase.valueOf(phaseStr)
        } catch (e: Exception) {
            EvolutionPhase.IDLE
        }

        val stopReasonStr = getString("stop_reason")
        val stopReason = if (stopReasonStr != null) {
            try {
                EvolutionStopReason.valueOf(stopReasonStr)
            } catch (e: Exception) {
                null
            }
        } else null

        return LoopStateEntity(
            projectKey = getString("project_key"),
            enabled = getBoolean("enabled"),
            currentPhase = phase,
            totalIterations = getLong("total_iterations"),
            successfulIterations = getLong("successful_iterations"),
            consecutiveDuplicateCount = getInt("consecutive_duplicate_count"),
            currentQuestion = getString("current_question"),
            currentQuestionHash = getString("current_question_hash"),
            explorationProgress = getInt("exploration_progress"),
            partialSteps = getString("partial_steps"),
            startedAt = getObject("started_at") as? Long,
            lastGeneratedQuestionHash = getString("last_generated_question_hash"),
            lastProjectMd5 = getString("last_project_md5"),
            stopReason = stopReason,
            lastUpdatedAt = getLong("last_updated_at")
        )
    }

    private fun ResultSet.toBackoffStateEntity(): BackoffStateEntity {
        return BackoffStateEntity(
            projectKey = getString("project_key"),
            consecutiveErrors = getInt("consecutive_errors"),
            lastErrorTime = getObject("last_error_time") as? Long,
            backoffUntil = getObject("backoff_until") as? Long,
            lastUpdatedAt = getLong("last_updated_at")
        )
    }

    private fun ResultSet.toDailyQuotaEntity(): DailyQuotaEntity {
        return DailyQuotaEntity(
            projectKey = getString("project_key"),
            questionsToday = getInt("questions_today"),
            explorationsToday = getInt("explorations_today"),
            lastResetDate = getString("last_reset_date") ?: "",
            lastUpdatedAt = getLong("last_updated_at")
        )
    }
}
