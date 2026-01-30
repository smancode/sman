package com.smancode.smanagent.analysis.database

import com.smancode.smanagent.analysis.config.VectorDatabaseConfig
import com.smancode.smanagent.analysis.model.VectorFragment
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.sql.ResultSet
import javax.sql.DataSource

/**
 * H2 数据库服务
 *
 * 存储配置、元数据、SOP 等结构化数据
 */
class H2DatabaseService(
    private val config: VectorDatabaseConfig
) {

    private val logger = LoggerFactory.getLogger(H2DatabaseService::class.java)
    private val dataSource: DataSource = createDataSource()

    /**
     * 创建数据源
     */
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
     * 初始化数据库表结构
     */
    suspend fun init() = withContext(Dispatchers.IO) {
        val connection = dataSource.connection
        try {
            // 创建配置表
            connection.createStatement().executeUpdate("""
                CREATE TABLE IF NOT EXISTS config (
                    key VARCHAR(255) PRIMARY KEY,
                    value TEXT NOT NULL,
                    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
            """.trimIndent())

            // 创建元数据表
            connection.createStatement().executeUpdate("""
                CREATE TABLE IF NOT EXISTS metadata (
                    id VARCHAR(255) PRIMARY KEY,
                    type VARCHAR(50) NOT NULL,
                    title VARCHAR(255),
                    content TEXT,
                    metadata TEXT,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
            """.trimIndent())

            // 创建 SOP 表
            connection.createStatement().executeUpdate("""
                CREATE TABLE IF NOT EXISTS sop (
                    id VARCHAR(255) PRIMARY KEY,
                    title VARCHAR(255) NOT NULL,
                    description TEXT,
                    category VARCHAR(50),
                    preconditions TEXT,
                    steps TEXT,
                    expected_results TEXT,
                    related_classes TEXT,
                    tags TEXT,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
            """.trimIndent())

            // 创建向量片段表（用于冷数据存储）
            connection.createStatement().executeUpdate("""
                CREATE TABLE IF NOT EXISTS vector_fragments (
                    id VARCHAR(255) PRIMARY KEY,
                    title VARCHAR(255) NOT NULL,
                    content TEXT NOT NULL,
                    full_content TEXT,
                    tags TEXT,
                    metadata TEXT,
                    vector_data BLOB,
                    cache_level VARCHAR(20) DEFAULT 'cold',
                    access_count INT DEFAULT 0,
                    last_accessed TIMESTAMP,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
            """.trimIndent())

            // 创建索引
            connection.createStatement().executeUpdate("""
                CREATE INDEX IF NOT EXISTS idx_metadata_type ON metadata(type)
            """.trimIndent())

            connection.createStatement().executeUpdate("""
                CREATE INDEX IF NOT EXISTS idx_sop_category ON sop(category)
            """.trimIndent())

            connection.createStatement().executeUpdate("""
                CREATE INDEX IF NOT EXISTS idx_vector_cache_level ON vector_fragments(cache_level)
            """.trimIndent())

            connection.createStatement().executeUpdate("""
                CREATE INDEX IF NOT EXISTS idx_vector_last_accessed ON vector_fragments(last_accessed)
            """.trimIndent())

            logger.info("H2 数据库表结构初始化完成")
        } finally {
            connection.close()
        }
    }

    /**
     * 保存配置
     */
    suspend fun saveConfig(key: String, value: String) = withContext(Dispatchers.IO) {
        val connection = dataSource.connection
        try {
            val sql = """
                MERGE INTO config (key, value, updated_at)
                VALUES (?, ?, CURRENT_TIMESTAMP)
            """.trimIndent()

            connection.prepareStatement(sql).use { stmt ->
                stmt.setString(1, key)
                stmt.setString(2, value)
                stmt.executeUpdate()
            }

            logger.debug("配置已保存: key={}", key)
        } finally {
            connection.close()
        }
    }

    /**
     * 获取配置
     */
    suspend fun getConfig(key: String): String? = withContext(Dispatchers.IO) {
        val connection = dataSource.connection
        try {
            val sql = "SELECT value FROM config WHERE key = ?"
            connection.prepareStatement(sql).use { stmt ->
                stmt.setString(1, key)
                val rs = stmt.executeQuery()
                if (rs.next()) {
                    rs.getString("value")
                } else {
                    null
                }
            }
        } finally {
            connection.close()
        }
    }

    /**
     * 保存向量片段（冷数据）
     */
    suspend fun saveVectorFragment(fragment: VectorFragment) = withContext(Dispatchers.IO) {
        val connection = dataSource.connection
        try {
            val sql = """
                MERGE INTO vector_fragments (
                    id, title, content, full_content, tags, metadata,
                    vector_data, cache_level, access_count, last_accessed
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
            """.trimIndent()

            connection.prepareStatement(sql).use { stmt ->
                stmt.setString(1, fragment.id)
                stmt.setString(2, fragment.title)
                stmt.setString(3, fragment.content)
                stmt.setString(4, fragment.fullContent)
                stmt.setString(5, fragment.tags.joinToString(","))
                stmt.setString(6, serializeMetadata(fragment.metadata))
                stmt.setBytes(7, serializeVector(fragment.vector) ?: byteArrayOf())
                stmt.setString(8, "cold")
                stmt.setInt(9, 0)
                stmt.executeUpdate()
            }

            logger.debug("向量片段已保存到冷存储: id={}", fragment.id)
        } finally {
            connection.close()
        }
    }

    /**
     * 获取向量片段
     */
    suspend fun getVectorFragment(id: String): VectorFragment? = withContext(Dispatchers.IO) {
        val connection = dataSource.connection
        try {
            val sql = "SELECT * FROM vector_fragments WHERE id = ?"
            connection.prepareStatement(sql).use { stmt ->
                stmt.setString(1, id)
                val rs = stmt.executeQuery()
                if (rs.next()) {
                    // 更新访问计数和最后访问时间
                    updateAccessInfo(id)

                    mapToVectorFragment(rs)
                } else {
                    null
                }
            }
        } finally {
            connection.close()
        }
    }

    /**
     * 获取所有冷数据向量片段
     */
    suspend fun getAllColdFragments(): List<VectorFragment> = withContext(Dispatchers.IO) {
        val connection = dataSource.connection
        try {
            val sql = "SELECT * FROM vector_fragments WHERE cache_level = 'cold'"
            connection.prepareStatement(sql).use { stmt ->
                val rs = stmt.executeQuery()
                val fragments = mutableListOf<VectorFragment>()
                while (rs.next()) {
                    fragments.add(mapToVectorFragment(rs))
                }
                fragments
            }
        } finally {
            connection.close()
        }
    }

    /**
     * 更新访问信息
     */
    private suspend fun updateAccessInfo(id: String) = withContext(Dispatchers.IO) {
        val connection = dataSource.connection
        try {
            val sql = """
                UPDATE vector_fragments
                SET access_count = access_count + 1,
                    last_accessed = CURRENT_TIMESTAMP
                WHERE id = ?
            """.trimIndent()
            connection.prepareStatement(sql).use { stmt ->
                stmt.setString(1, id)
                stmt.executeUpdate()
            }
        } finally {
            connection.close()
        }
    }

    /**
     * 将 ResultSet 映射为 VectorFragment
     */
    private fun mapToVectorFragment(rs: ResultSet): VectorFragment {
        val vectorData = rs.getBytes("vector_data")
        val vector = if (vectorData != null && vectorData.isNotEmpty()) {
            deserializeVector(vectorData)
        } else {
            FloatArray(config.vectorDimension)
        }

        return VectorFragment(
            id = rs.getString("id"),
            title = rs.getString("title"),
            content = rs.getString("content"),
            fullContent = rs.getString("full_content"),
            tags = rs.getString("tags").split(","),
            metadata = deserializeMetadata(rs.getString("metadata")),
            vector = vector
        )
    }

    /**
     * 序列化元数据
     */
    private fun serializeMetadata(metadata: Map<String, String>): String {
        return metadata.entries.joinToString(";") { "${it.key}=${it.value}" }
    }

    /**
     * 反序列化元数据
     */
    private fun deserializeMetadata(data: String?): Map<String, String> {
        if (data.isNullOrBlank()) return emptyMap()
        return data.split(";").mapNotNull { entry ->
            val parts = entry.split("=", limit = 2)
            if (parts.size == 2) parts[0] to parts[1] else null
        }.toMap()
    }

    /**
     * 序列化向量
     */
    private fun serializeVector(vector: FloatArray?): ByteArray? {
        if (vector == null) return null

        val result = ByteArray(vector.size * 4)
        for (i in vector.indices) {
            val bytes = floatToBytes(vector[i])
            val offset = i * 4
            result[offset] = bytes[0]
            result[offset + 1] = bytes[1]
            result[offset + 2] = bytes[2]
            result[offset + 3] = bytes[3]
        }
        return result
    }

    /**
     * 反序列化向量
     */
    private fun deserializeVector(data: ByteArray?): FloatArray {
        if (data == null || data.isEmpty()) return FloatArray(config.vectorDimension)

        val floatCount = data.size / 4
        val result = FloatArray(floatCount)
        for (i in 0 until floatCount) {
            val offset = i * 4
            val bytes = byteArrayOf(
                data[offset],
                data[offset + 1],
                data[offset + 2],
                data[offset + 3]
            )
            result[i] = bytesToFloat(bytes)
        }
        return result
    }

    /**
     * Float 转 4 字节数组
     */
    private fun floatToBytes(value: Float): List<Byte> {
        val bits = java.lang.Float.floatToIntBits(value)
        return listOf(
            (bits shr 24).toByte(),
            (bits shr 16).toByte(),
            (bits shr 8).toByte(),
            bits.toByte()
        )
    }

    /**
     * 4 字节数组转 Float
     */
    private fun bytesToFloat(bytes: ByteArray): Float {
        val bits = ((bytes[0].toInt() and 0xFF) shl 24) or
                   ((bytes[1].toInt() and 0xFF) shl 16) or
                   ((bytes[2].toInt() and 0xFF) shl 8) or
                   (bytes[3].toInt() and 0xFF)
        return java.lang.Float.intBitsToFloat(bits)
    }

    /**
     * 清理过期数据
     */
    suspend fun cleanupOldData(beforeDays: Int = 30) = withContext(Dispatchers.IO) {
        val connection = dataSource.connection
        try {
            // H2 不支持 DATEADD，使用 DATEADD 替代
            val sql = """
                DELETE FROM vector_fragments
                WHERE cache_level = 'cold'
                  AND last_accessed < DATEADD('DAY', -?, CURRENT_TIMESTAMP)
                  AND access_count < 5
            """.trimIndent()

            connection.prepareStatement(sql).use { stmt ->
                stmt.setInt(1, beforeDays)
                val deleted = stmt.executeUpdate()
                logger.info("清理了 {} 个过期的向量片段", deleted)
            }
        } finally {
            connection.close()
        }
    }

    /**
     * 初始化 H2 数据库
     */
    suspend fun initDatabase() = withContext(Dispatchers.IO) {
        init()
    }

    /**
     * 关闭数据库连接
     */
    fun close() {
        if (dataSource is HikariDataSource) {
            dataSource.close()
        }
        logger.info("H2 数据库连接已关闭")
    }
}
