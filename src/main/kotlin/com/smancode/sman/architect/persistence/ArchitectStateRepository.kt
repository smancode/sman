package com.smancode.sman.architect.persistence

import com.smancode.sman.analysis.config.VectorDatabaseConfig
import com.smancode.sman.analysis.model.AnalysisType
import com.smancode.sman.architect.model.ArchitectPhase
import com.smancode.sman.architect.model.ArchitectStopReason
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import javax.sql.DataSource

/**
 * 架构师 Agent 状态实体
 *
 * 用于断点续传
 */
data class ArchitectStateEntity(
    val projectKey: String,
    val enabled: Boolean = true,
    val currentPhase: ArchitectPhase = ArchitectPhase.IDLE,

    // 统计信息
    val totalIterations: Long = 0,
    val successfulIterations: Long = 0,

    // 当前目标状态
    val currentGoalType: String? = null,
    val currentIterationCount: Int = 0,
    val currentGoalTodos: String? = null, // JSON 格式的 TODO 列表

    // 本轮已处理的目标
    val processedGoals: String? = null, // JSON 格式的 AnalysisType key 列表

    // 停止原因
    val stopReason: ArchitectStopReason? = null,

    // 时间戳
    val lastUpdatedAt: Long = System.currentTimeMillis()
)

/**
 * 架构师 Agent 状态持久化仓储
 *
 * 负责断点续传：
 * 1. 保存当前执行状态
 * 2. 恢复未完成的任务
 */
class ArchitectStateRepository(
    private val config: VectorDatabaseConfig
) {
    private val logger = LoggerFactory.getLogger(ArchitectStateRepository::class.java)
    private val dataSource: DataSource = createDataSource()

    companion object {
        private const val SQL_CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS architect_state (
                project_key VARCHAR(255) PRIMARY KEY,
                enabled BOOLEAN DEFAULT TRUE,
                current_phase VARCHAR(50) DEFAULT 'IDLE',
                total_iterations BIGINT DEFAULT 0,
                successful_iterations BIGINT DEFAULT 0,
                current_goal_type VARCHAR(50),
                current_iteration_count INT DEFAULT 0,
                current_goal_todos CLOB,
                processed_goals CLOB,
                stop_reason VARCHAR(50),
                last_updated_at BIGINT
            )
        """

        private const val SQL_INSERT = """
            INSERT INTO architect_state (
                project_key, enabled, current_phase,
                total_iterations, successful_iterations,
                current_goal_type, current_iteration_count, current_goal_todos,
                processed_goals, stop_reason, last_updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """

        private const val SQL_UPDATE = """
            UPDATE architect_state SET
                enabled = ?, current_phase = ?,
                total_iterations = ?, successful_iterations = ?,
                current_goal_type = ?, current_iteration_count = ?, current_goal_todos = ?,
                processed_goals = ?, stop_reason = ?, last_updated_at = ?
            WHERE project_key = ?
        """

        private const val SQL_SELECT = """
            SELECT project_key, enabled, current_phase,
                   total_iterations, successful_iterations,
                   current_goal_type, current_iteration_count, current_goal_todos,
                   processed_goals, stop_reason, last_updated_at
            FROM architect_state WHERE project_key = ?
        """

        private const val SQL_DELETE = """
            DELETE FROM architect_state WHERE project_key = ?
        """
    }

    init {
        initTable()
    }

    private fun createDataSource(): DataSource {
        val hikariConfig = HikariConfig().apply {
            // 使用 databasePath 作为 H2 数据库路径
            jdbcUrl = "jdbc:h2:${config.databasePath};AUTO_SERVER=TRUE"
            driverClassName = "org.h2.Driver"
            username = "sa"
            password = ""
            maximumPoolSize = 3
            minimumIdle = 1
            isAutoCommit = true
        }
        return HikariDataSource(hikariConfig)
    }

    private fun initTable() {
        try {
            dataSource.connection.use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.execute(SQL_CREATE_TABLE)
                    logger.debug("architect_state 表初始化完成")
                }
            }
        } catch (e: Exception) {
            logger.error("初始化 architect_state 表失败", e)
        }
    }

    /**
     * 保存状态
     */
    suspend fun saveState(state: ArchitectStateEntity) {
        withContext(Dispatchers.IO) {
            try {
                val existing = loadState(state.projectKey)

                dataSource.connection.use { conn ->
                    if (existing != null) {
                        // 更新
                        conn.prepareStatement(SQL_UPDATE).use { stmt ->
                            stmt.setBoolean(1, state.enabled)
                            stmt.setString(2, state.currentPhase.name)
                            stmt.setLong(3, state.totalIterations)
                            stmt.setLong(4, state.successfulIterations)
                            stmt.setString(5, state.currentGoalType)
                            stmt.setInt(6, state.currentIterationCount)
                            stmt.setString(7, state.currentGoalTodos)
                            stmt.setString(8, state.processedGoals)
                            stmt.setString(9, state.stopReason?.name)
                            stmt.setLong(10, state.lastUpdatedAt)
                            stmt.setString(11, state.projectKey)
                            stmt.executeUpdate()
                        }
                    } else {
                        // 插入
                        conn.prepareStatement(SQL_INSERT).use { stmt ->
                            stmt.setString(1, state.projectKey)
                            stmt.setBoolean(2, state.enabled)
                            stmt.setString(3, state.currentPhase.name)
                            stmt.setLong(4, state.totalIterations)
                            stmt.setLong(5, state.successfulIterations)
                            stmt.setString(6, state.currentGoalType)
                            stmt.setInt(7, state.currentIterationCount)
                            stmt.setString(8, state.currentGoalTodos)
                            stmt.setString(9, state.processedGoals)
                            stmt.setString(10, state.stopReason?.name)
                            stmt.setLong(11, state.lastUpdatedAt)
                            stmt.executeUpdate()
                        }
                    }
                }

                logger.debug("保存架构师状态: projectKey={}, phase={}, goal={}",
                    state.projectKey, state.currentPhase, state.currentGoalType)

            } catch (e: Exception) {
                logger.error("保存架构师状态失败: projectKey={}", state.projectKey, e)
            }
        }
    }

    /**
     * 加载状态
     */
    suspend fun loadState(projectKey: String): ArchitectStateEntity? {
        return withContext(Dispatchers.IO) {
            try {
                dataSource.connection.use { conn ->
                    conn.prepareStatement(SQL_SELECT).use { stmt ->
                        stmt.setString(1, projectKey)
                        val rs = stmt.executeQuery()

                        if (rs.next()) {
                            ArchitectStateEntity(
                                projectKey = rs.getString("project_key"),
                                enabled = rs.getBoolean("enabled"),
                                currentPhase = ArchitectPhase.valueOf(rs.getString("current_phase")),
                                totalIterations = rs.getLong("total_iterations"),
                                successfulIterations = rs.getLong("successful_iterations"),
                                currentGoalType = rs.getString("current_goal_type"),
                                currentIterationCount = rs.getInt("current_iteration_count"),
                                currentGoalTodos = rs.getString("current_goal_todos"),
                                processedGoals = rs.getString("processed_goals"),
                                stopReason = rs.getString("stop_reason")?.let {
                                    try { ArchitectStopReason.valueOf(it) } catch (e: Exception) { null }
                                },
                                lastUpdatedAt = rs.getLong("last_updated_at")
                            )
                        } else {
                            null
                        }
                    }
                }
            } catch (e: Exception) {
                logger.error("加载架构师状态失败: projectKey={}", projectKey, e)
                null
            }
        }
    }

    /**
     * 清除状态
     */
    suspend fun clearState(projectKey: String) {
        withContext(Dispatchers.IO) {
            try {
                dataSource.connection.use { conn ->
                    conn.prepareStatement(SQL_DELETE).use { stmt ->
                        stmt.setString(1, projectKey)
                        stmt.executeUpdate()
                    }
                }
                logger.debug("清除架构师状态: projectKey={}", projectKey)
            } catch (e: Exception) {
                logger.error("清除架构师状态失败: projectKey={}", projectKey, e)
            }
        }
    }

    /**
     * 检查是否有未完成的任务
     */
    suspend fun hasUnfinishedTask(projectKey: String): Boolean {
        val state = loadState(projectKey) ?: return false

        // 如果有正在执行的目标，说明有未完成任务
        return state.currentGoalType != null &&
               state.currentPhase != ArchitectPhase.IDLE &&
               state.currentPhase != ArchitectPhase.WAITING
    }
}
