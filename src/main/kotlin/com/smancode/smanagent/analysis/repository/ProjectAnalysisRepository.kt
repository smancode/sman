package com.smancode.smanagent.analysis.repository

import com.smancode.smanagent.analysis.model.AnalysisStatus
import com.smancode.smanagent.analysis.model.ProjectAnalysisResult
import com.smancode.smanagent.analysis.model.StepResult
import com.smancode.smanagent.analysis.model.StepStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.sql.ResultSet
import java.sql.Types
import java.sql.Connection

/**
 * 项目分析结果仓储
 *
 * 负责：
 * - 创建数据库表
 * - 保存分析结果
 * - 加载分析结果
 * - 查询分析状态
 */
class ProjectAnalysisRepository(
    private val h2JdbcUrl: String
) {

    private val logger = LoggerFactory.getLogger(ProjectAnalysisRepository::class.java)
    private val jdbcUrl = "$h2JdbcUrl;MODE=PostgreSQL"

    // 数据库连接辅助函数
    private suspend fun <T> useConnection(block: (Connection) -> T): T = withContext(Dispatchers.IO) {
        try {
            Class.forName("org.h2.Driver")
            java.sql.DriverManager.getConnection(jdbcUrl, "sa", "").use(block)
        } catch (e: Exception) {
            logger.error("数据库操作失败", e)
            throw e
        }
    }

    /**
     * 初始化数据库表
     */
    suspend fun initTables() = useConnection { connection ->
        executeUpdate(connection, """
            CREATE TABLE IF NOT EXISTS project_analysis (
                project_key VARCHAR(255) PRIMARY KEY,
                start_time BIGINT NOT NULL,
                end_time BIGINT,
                status VARCHAR(20) NOT NULL,
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
            )
        """)

        executeUpdate(connection, """
            CREATE TABLE IF NOT EXISTS analysis_step (
                id VARCHAR(255) PRIMARY KEY,
                project_key VARCHAR(255) NOT NULL,
                step_name VARCHAR(100) NOT NULL,
                step_description VARCHAR(255),
                status VARCHAR(20) NOT NULL,
                start_time BIGINT NOT NULL,
                end_time BIGINT,
                data TEXT,
                error TEXT,
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                FOREIGN KEY (project_key) REFERENCES project_analysis(project_key)
                    ON DELETE CASCADE
            )
        """)

        executeUpdate(connection, """
            CREATE INDEX IF NOT EXISTS idx_analysis_step_project
            ON analysis_step(project_key)
        """)

        logger.info("项目分析表初始化完成")
    }

    private fun executeUpdate(connection: Connection, sql: String) {
        connection.createStatement().executeUpdate(sql.trimIndent())
    }

    /**
     * 保存项目分析结果
     */
    suspend fun saveAnalysisResult(result: ProjectAnalysisResult) = useConnection { connection ->
        connection.autoCommit = false
        try {
            saveMainRecord(connection, result)
            result.steps.values.forEach { saveStepResult(connection, result.projectKey, it) }
            connection.commit()
            logger.debug("保存分析结果: projectKey={}, status={}", result.projectKey, result.status)
        } catch (e: Exception) {
            connection.rollback()
            logger.error("保存分析结果失败: projectKey={}", result.projectKey, e)
            throw e
        }
    }

    private fun saveMainRecord(connection: Connection, result: ProjectAnalysisResult) {
        connection.prepareStatement("""
            MERGE INTO project_analysis (project_key, start_time, end_time, status, updated_at)
            VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP)
        """.trimIndent()).use { stmt ->
            stmt.setString(1, result.projectKey)
            stmt.setLong(2, result.startTime)
            stmt.setLongOrNull(3, result.endTime)
            stmt.setString(4, result.status.name)
            stmt.executeUpdate()
        }
    }

    /**
     * 保存单个步骤结果
     */
    private fun saveStepResult(connection: Connection, projectKey: String, stepResult: StepResult) {
        connection.prepareStatement("""
            MERGE INTO analysis_step (id, project_key, step_name, step_description, status, start_time, end_time, data, error)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent()).use { stmt ->
            stmt.setString(1, "$projectKey-${stepResult.stepName}")
            stmt.setString(2, projectKey)
            stmt.setString(3, stepResult.stepName)
            stmt.setString(4, stepResult.stepDescription)
            stmt.setString(5, stepResult.status.name)
            stmt.setLong(6, stepResult.startTime)
            stmt.setLongOrNull(7, stepResult.endTime)
            stmt.setString(8, stepResult.data)
            stmt.setString(9, stepResult.error)
            stmt.executeUpdate()
        }
    }

    private fun java.sql.PreparedStatement.setLongOrNull(index: Int, value: Long?) {
        if (value != null) setLong(index, value) else setNull(index, Types.BIGINT)
    }

    /**
     * 加载项目分析结果
     */
    suspend fun loadAnalysisResult(projectKey: String): ProjectAnalysisResult? = useConnection { connection ->
        val mainResult = queryOne(connection, "SELECT * FROM project_analysis WHERE project_key = ?", projectKey) { rs ->
            rs.toProjectAnalysisResult()
        } ?: run {
            logger.debug("未找到分析结果: projectKey={}", projectKey)
            return@useConnection null
        }

        val steps = queryMap(connection, "SELECT * FROM analysis_step WHERE project_key = ?", projectKey) { rs ->
            rs.toStepResult().stepName to rs.toStepResult()
        }

        mainResult.copy(steps = steps.toMap())
    }

    private fun <T> queryOne(connection: Connection, sql: String, param: String, mapper: (ResultSet) -> T): T? {
        connection.prepareStatement(sql).use { stmt ->
            stmt.setString(1, param)
            val rs = stmt.executeQuery()
            return if (rs.next()) mapper(rs) else null
        }
    }

    private fun <K, V> queryMap(connection: Connection, sql: String, param: String, mapper: (ResultSet) -> Pair<K, V>): Map<K, V> {
        connection.prepareStatement(sql).use { stmt ->
            stmt.setString(1, param)
            val rs = stmt.executeQuery()
            val map = mutableMapOf<K, V>()
            while (rs.next()) {
                val (key, value) = mapper(rs)
                map[key] = value
            }
            return map
        }
    }

    /**
     * 查询项目分析状态
     */
    suspend fun getAnalysisStatus(projectKey: String): AnalysisStatus? = useConnection { connection ->
        queryOne(connection, "SELECT status FROM project_analysis WHERE project_key = ?", projectKey) { rs ->
            AnalysisStatus.valueOf(rs.getString("status"))
        }
    }

    /**
     * 删除项目分析结果
     */
    suspend fun deleteAnalysisResult(projectKey: String) = useConnection { connection ->
        connection.prepareStatement("DELETE FROM project_analysis WHERE project_key = ?").use { stmt ->
            stmt.setString(1, projectKey)
            stmt.executeUpdate()
        }
        logger.info("删除分析结果: projectKey={}", projectKey)
    }

    /**
     * ResultSet 转换为 ProjectAnalysisResult
     */
    private fun ResultSet.toProjectAnalysisResult(): ProjectAnalysisResult {
        return ProjectAnalysisResult(
            projectKey = getString("project_key"),
            startTime = getLong("start_time"),
            endTime = getLong("end_time").takeIf { !wasNull() },
            status = AnalysisStatus.valueOf(getString("status"))
            // steps 需要单独查询
        )
    }

    /**
     * ResultSet 转换为 StepResult
     */
    private fun ResultSet.toStepResult(): StepResult {
        return StepResult(
            stepName = getString("step_name"),
            stepDescription = getString("step_description") ?: "",
            status = StepStatus.valueOf(getString("status")),
            startTime = getLong("start_time"),
            endTime = getLong("end_time").takeIf { !wasNull() },
            data = getString("data"),
            error = getString("error")
        )
    }
}
