package com.smancode.sman.evolution.memory

import com.smancode.sman.analysis.config.VectorDatabaseConfig
import com.smancode.sman.evolution.model.LearningRecord
import com.smancode.sman.evolution.model.QuestionType
import com.smancode.sman.evolution.model.ToolCallStep
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
 * 学习记录数据库访问层
 *
 * 负责 LearningRecord 的 CRUD 操作，使用 H2 数据库存储。
 *
 * 职责：
 * - 学习记录的持久化存储
 * - 按项目、时间等条件查询
 * - 简单的关键词搜索（LIKE 查询）
 */
class LearningRecordRepository(
    private val config: VectorDatabaseConfig
) {

    private val logger = LoggerFactory.getLogger(LearningRecordRepository::class.java)
    private val dataSource: DataSource = createDataSource()
    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        private const val TABLE_NAME = "learning_records"

        // SQL 语句
        private const val SQL_INSERT = """
            INSERT INTO $TABLE_NAME (
                id, project_key, created_at, question, question_type, answer,
                exploration_path, confidence, source_files, tags, domain
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """

        private const val SQL_UPDATE = """
            UPDATE $TABLE_NAME SET
                project_key = ?, created_at = ?, question = ?, question_type = ?,
                answer = ?, exploration_path = ?, confidence = ?, source_files = ?,
                tags = ?, domain = ?
            WHERE id = ?
        """

        private const val SQL_FIND_BY_ID = """
            SELECT * FROM $TABLE_NAME WHERE id = ?
        """

        private const val SQL_FIND_BY_PROJECT_KEY = """
            SELECT * FROM $TABLE_NAME WHERE project_key = ? ORDER BY created_at DESC
        """

        private const val SQL_FIND_RECENT = """
            SELECT * FROM $TABLE_NAME ORDER BY created_at DESC LIMIT ?
        """

        private const val SQL_SEARCH = """
            SELECT * FROM $TABLE_NAME
            WHERE (question LIKE ? OR answer LIKE ? OR tags LIKE ?)
            ORDER BY created_at DESC
            LIMIT ?
        """

        private const val SQL_DELETE_BY_ID = """
            DELETE FROM $TABLE_NAME WHERE id = ?
        """

        private const val SQL_DELETE_BY_PROJECT_KEY = """
            DELETE FROM $TABLE_NAME WHERE project_key = ?
        """

        private const val SQL_COUNT_BY_PROJECT_KEY = """
            SELECT COUNT(*) FROM $TABLE_NAME WHERE project_key = ?
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

    /**
     * 保存学习记录
     *
     * 如果记录已存在（根据 id 判断），则更新；否则插入新记录。
     *
     * @param record 学习记录
     */
    suspend fun save(record: LearningRecord) = withContext(Dispatchers.IO) {
        require(record.id.isNotBlank()) { "学习记录 id 不能为空" }
        require(record.projectKey.isNotBlank()) { "学习记录 projectKey 不能为空" }
        require(record.question.isNotBlank()) { "学习记录 question 不能为空" }
        require(record.answer.isNotBlank()) { "学习记录 answer 不能为空" }
        require(record.confidence in 0.0..1.0) { "confidence 必须在 0.0 到 1.0 之间" }

        val existingRecord = findById(record.id)

        dataSource.connection.use { connection ->
            if (existingRecord != null) {
                // 更新现有记录
                connection.prepareStatement(SQL_UPDATE).use { stmt ->
                    stmt.setString(1, record.projectKey)
                    stmt.setLong(2, record.createdAt)
                    stmt.setString(3, record.question)
                    stmt.setString(4, record.questionType.name)
                    stmt.setString(5, record.answer)
                    stmt.setString(6, json.encodeToString(record.explorationPath))
                    stmt.setDouble(7, record.confidence)
                    stmt.setString(8, json.encodeToString(record.sourceFiles))
                    stmt.setString(9, json.encodeToString(record.tags))
                    stmt.setString(10, record.domain)
                    stmt.setString(11, record.id)
                    stmt.executeUpdate()
                }
                logger.debug("更新学习记录: id={}", record.id)
            } else {
                // 插入新记录
                connection.prepareStatement(SQL_INSERT).use { stmt ->
                    stmt.setString(1, record.id)
                    stmt.setString(2, record.projectKey)
                    stmt.setLong(3, record.createdAt)
                    stmt.setString(4, record.question)
                    stmt.setString(5, record.questionType.name)
                    stmt.setString(6, record.answer)
                    stmt.setString(7, json.encodeToString(record.explorationPath))
                    stmt.setDouble(8, record.confidence)
                    stmt.setString(9, json.encodeToString(record.sourceFiles))
                    stmt.setString(10, json.encodeToString(record.tags))
                    stmt.setString(11, record.domain)
                    stmt.executeUpdate()
                }
                logger.debug("保存学习记录: id={}", record.id)
            }
        }
    }

    /**
     * 根据 ID 查找学习记录
     *
     * @param id 学习记录 ID
     * @return 学习记录，如果不存在则返回 null
     */
    suspend fun findById(id: String): LearningRecord? = withContext(Dispatchers.IO) {
        require(id.isNotBlank()) { "id 不能为空" }

        dataSource.connection.use { connection ->
            connection.prepareStatement(SQL_FIND_BY_ID).use { stmt ->
                stmt.setString(1, id)
                val rs = stmt.executeQuery()
                if (rs.next()) {
                    rs.toLearningRecord()
                } else {
                    null
                }
            }
        }
    }

    /**
     * 根据 ID 列表批量查找学习记录
     *
     * @param ids 学习记录 ID 列表
     * @return 学习记录列表
     */
    suspend fun findByIds(ids: List<String>): List<LearningRecord> = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) {
            return@withContext emptyList()
        }

        val placeholders = ids.joinToString(",") { "?" }
        val sql = "SELECT * FROM $TABLE_NAME WHERE id IN ($placeholders)"

        dataSource.connection.use { connection ->
            connection.prepareStatement(sql).use { stmt ->
                ids.forEachIndexed { index, id ->
                    stmt.setString(index + 1, id)
                }
                val rs = stmt.executeQuery()
                val records = mutableListOf<LearningRecord>()
                while (rs.next()) {
                    records.add(rs.toLearningRecord())
                }
                records
            }
        }
    }

    /**
     * 根据项目 key 查找所有学习记录
     *
     * @param projectKey 项目 key
     * @return 学习记录列表，按创建时间倒序排列
     */
    suspend fun findByProjectKey(projectKey: String): List<LearningRecord> = withContext(Dispatchers.IO) {
        require(projectKey.isNotBlank()) { "projectKey 不能为空" }

        dataSource.connection.use { connection ->
            connection.prepareStatement(SQL_FIND_BY_PROJECT_KEY).use { stmt ->
                stmt.setString(1, projectKey)
                val rs = stmt.executeQuery()
                val records = mutableListOf<LearningRecord>()
                while (rs.next()) {
                    records.add(rs.toLearningRecord())
                }
                records
            }
        }
    }

    /**
     * 查找最近的学习记录
     *
     * @param limit 返回记录数量上限
     * @return 学习记录列表，按创建时间倒序排列
     */
    suspend fun findRecent(limit: Int): List<LearningRecord> = withContext(Dispatchers.IO) {
        require(limit > 0) { "limit 必须大于 0" }

        dataSource.connection.use { connection ->
            connection.prepareStatement(SQL_FIND_RECENT).use { stmt ->
                stmt.setInt(1, limit)
                val rs = stmt.executeQuery()
                val records = mutableListOf<LearningRecord>()
                while (rs.next()) {
                    records.add(rs.toLearningRecord())
                }
                records
            }
        }
    }

    /**
     * 按关键词搜索学习记录
     *
     * 在问题、答案和标签中进行 LIKE 搜索。
     * 注意：这是简单的文本搜索，向量搜索将在后续实现。
     *
     * @param keyword 搜索关键词
     * @param limit 返回记录数量上限
     * @return 匹配的学习记录列表
     */
    suspend fun search(keyword: String, limit: Int = 10): List<LearningRecord> = withContext(Dispatchers.IO) {
        require(keyword.isNotBlank()) { "keyword 不能为空" }
        require(limit > 0) { "limit 必须大于 0" }

        val searchPattern = "%$keyword%"

        dataSource.connection.use { connection ->
            connection.prepareStatement(SQL_SEARCH).use { stmt ->
                stmt.setString(1, searchPattern)
                stmt.setString(2, searchPattern)
                stmt.setString(3, searchPattern)
                stmt.setInt(4, limit)
                val rs = stmt.executeQuery()
                val records = mutableListOf<LearningRecord>()
                while (rs.next()) {
                    records.add(rs.toLearningRecord())
                }
                logger.debug("搜索学习记录: keyword={}, 结果数量={}", keyword, records.size)
                records
            }
        }
    }

    /**
     * 按领域搜索学习记录
     *
     * @param domain 领域名称
     * @param projectKey 可选的项目 key 过滤
     * @param limit 返回记录数量上限
     * @return 匹配的学习记录列表
     */
    suspend fun findByDomain(
        domain: String,
        projectKey: String? = null,
        limit: Int = 10
    ): List<LearningRecord> = withContext(Dispatchers.IO) {
        require(domain.isNotBlank()) { "domain 不能为空" }
        require(limit > 0) { "limit 必须大于 0" }

        val sql = if (projectKey != null) {
            "SELECT * FROM $TABLE_NAME WHERE domain = ? AND project_key = ? ORDER BY created_at DESC LIMIT ?"
        } else {
            "SELECT * FROM $TABLE_NAME WHERE domain = ? ORDER BY created_at DESC LIMIT ?"
        }

        dataSource.connection.use { connection ->
            connection.prepareStatement(sql).use { stmt ->
                stmt.setString(1, domain)
                if (projectKey != null) {
                    stmt.setString(2, projectKey)
                    stmt.setInt(3, limit)
                } else {
                    stmt.setInt(2, limit)
                }
                val rs = stmt.executeQuery()
                val records = mutableListOf<LearningRecord>()
                while (rs.next()) {
                    records.add(rs.toLearningRecord())
                }
                records
            }
        }
    }

    /**
     * 按问题类型搜索学习记录
     *
     * @param questionType 问题类型
     * @param projectKey 可选的项目 key 过滤
     * @param limit 返回记录数量上限
     * @return 匹配的学习记录列表
     */
    suspend fun findByQuestionType(
        questionType: QuestionType,
        projectKey: String? = null,
        limit: Int = 10
    ): List<LearningRecord> = withContext(Dispatchers.IO) {
        require(limit > 0) { "limit 必须大于 0" }

        val sql = if (projectKey != null) {
            "SELECT * FROM $TABLE_NAME WHERE question_type = ? AND project_key = ? ORDER BY created_at DESC LIMIT ?"
        } else {
            "SELECT * FROM $TABLE_NAME WHERE question_type = ? ORDER BY created_at DESC LIMIT ?"
        }

        dataSource.connection.use { connection ->
            connection.prepareStatement(sql).use { stmt ->
                stmt.setString(1, questionType.name)
                if (projectKey != null) {
                    stmt.setString(2, projectKey)
                    stmt.setInt(3, limit)
                } else {
                    stmt.setInt(2, limit)
                }
                val rs = stmt.executeQuery()
                val records = mutableListOf<LearningRecord>()
                while (rs.next()) {
                    records.add(rs.toLearningRecord())
                }
                records
            }
        }
    }

    /**
     * 删除学习记录
     *
     * @param id 学习记录 ID
     * @return 是否删除成功
     */
    suspend fun deleteById(id: String): Boolean = withContext(Dispatchers.IO) {
        require(id.isNotBlank()) { "id 不能为空" }

        dataSource.connection.use { connection ->
            connection.prepareStatement(SQL_DELETE_BY_ID).use { stmt ->
                stmt.setString(1, id)
                val count = stmt.executeUpdate()
                logger.debug("删除学习记录: id={}, 删除数量={}", id, count)
                count > 0
            }
        }
    }

    /**
     * 删除指定项目的所有学习记录
     *
     * @param projectKey 项目 key
     * @return 删除的记录数量
     */
    suspend fun deleteByProjectKey(projectKey: String): Int = withContext(Dispatchers.IO) {
        require(projectKey.isNotBlank()) { "projectKey 不能为空" }

        dataSource.connection.use { connection ->
            connection.prepareStatement(SQL_DELETE_BY_PROJECT_KEY).use { stmt ->
                stmt.setString(1, projectKey)
                val count = stmt.executeUpdate()
                logger.info("删除项目学习记录: projectKey={}, 删除数量={}", projectKey, count)
                count
            }
        }
    }

    /**
     * 统计指定项目的学习记录数量
     *
     * @param projectKey 项目 key
     * @return 记录数量
     */
    suspend fun countByProjectKey(projectKey: String): Int = withContext(Dispatchers.IO) {
        require(projectKey.isNotBlank()) { "projectKey 不能为空" }

        dataSource.connection.use { connection ->
            connection.prepareStatement(SQL_COUNT_BY_PROJECT_KEY).use { stmt ->
                stmt.setString(1, projectKey)
                val rs = stmt.executeQuery()
                if (rs.next()) {
                    rs.getInt(1)
                } else {
                    0
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
        logger.info("LearningRecordRepository 数据库连接已关闭")
    }

    // ========== 辅助方法 ==========

    /**
     * 将 ResultSet 转换为 LearningRecord
     */
    private fun ResultSet.toLearningRecord(): LearningRecord {
        val explorationPathJson = getString("exploration_path")
        val explorationPath: List<ToolCallStep> = if (explorationPathJson.isNullOrBlank()) {
            emptyList()
        } else {
            try {
                json.decodeFromString(explorationPathJson)
            } catch (e: Exception) {
                logger.warn("解析 explorationPath 失败: ${e.message}")
                emptyList()
            }
        }

        val sourceFilesJson = getString("source_files")
        val sourceFiles: List<String> = if (sourceFilesJson.isNullOrBlank()) {
            emptyList()
        } else {
            try {
                json.decodeFromString(sourceFilesJson)
            } catch (e: Exception) {
                logger.warn("解析 sourceFiles 失败: ${e.message}")
                emptyList()
            }
        }

        val tagsJson = getString("tags")
        val tags: List<String> = if (tagsJson.isNullOrBlank()) {
            emptyList()
        } else {
            try {
                json.decodeFromString(tagsJson)
            } catch (e: Exception) {
                logger.warn("解析 tags 失败: ${e.message}")
                emptyList()
            }
        }

        val questionTypeStr = getString("question_type")
        val questionType = try {
            QuestionType.valueOf(questionTypeStr)
        } catch (e: Exception) {
            logger.warn("解析 questionType 失败: $questionTypeStr, 使用默认值 CODE_STRUCTURE")
            QuestionType.CODE_STRUCTURE
        }

        return LearningRecord(
            id = getString("id"),
            projectKey = getString("project_key"),
            createdAt = getLong("created_at"),
            question = getString("question"),
            questionType = questionType,
            answer = getString("answer"),
            explorationPath = explorationPath,
            confidence = getDouble("confidence"),
            sourceFiles = sourceFiles,
            relatedRecords = emptyList(), // 数据库表结构中未存储，需要后续扩展
            tags = tags,
            domain = getString("domain"),
            metadata = emptyMap() // 数据库表结构中未存储，需要后续扩展
        )
    }
}
