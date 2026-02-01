package com.smancode.smanagent.verification.service

import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service

/**
 * H2 数据查询服务
 *
 * 功能：
 * - 查询分析结果（ANALYSIS_STEP 表）
 * - 查询向量数据（存储在 TieredVectorStore，不在 H2）
 * - 查询项目列表（PROJECT_ANALYSIS 表）
 * - 执行安全 SQL
 *
 * 白名单机制：参数不满足直接抛异常
 *
 * 注意：此服务需要 JdbcTemplate Bean，如果没有配置数据源，则此服务不可用
 */
@Service
class H2QueryService(
    private val jdbcTemplate: JdbcTemplate?
) {

    private val logger = LoggerFactory.getLogger(H2QueryService::class.java)

    companion object {
        val DANGEROUS_KEYWORDS = setOf(
            "DROP", "DELETE", "TRUNCATE", "ALTER", "CREATE",
            "INSERT", "UPDATE", "GRANT", "REVOKE", "EXEC"
        )

        val INVALID_PARAM_CHARS = Regex("[;'\"]")
    }

    private fun getJdbcTemplate(): JdbcTemplate = jdbcTemplate
        ?: throw IllegalStateException("H2 数据库未配置，无法执行查询。请检查数据源配置。")

    private fun validatePagination(page: Int, size: Int) {
        require(page >= 0) { "page 必须大于等于 0，当前值: $page" }
        require(size > 0) { "size 必须大于 0，当前值: $size" }
    }

    fun queryAnalysisResults(
        module: String,
        projectKey: String,
        page: Int,
        size: Int
    ): Map<String, Any> {
        logger.info("查询分析结果: module={}, projectKey={}, page={}, size={}",
            module, projectKey, page, size)

        // 白名单参数校验
        require(module.isNotBlank()) { "module 不能为空" }
        require(projectKey.isNotBlank()) { "projectKey 不能为空" }
        validatePagination(page, size)

        val offset = page * size

        // 查询数据 - 使用实际的表名 ANALYSIS_STEP
        val sql = """
            SELECT DATA FROM ANALYSIS_STEP
            WHERE PROJECT_KEY = ? AND STEP_NAME = ?
            ORDER BY CREATED_AT DESC
            LIMIT ? OFFSET ?
        """.trimIndent()

        val data = getJdbcTemplate().queryForList(sql, projectKey, module, size, offset)

        // 查询总数
        val countSql = """
            SELECT COUNT(*) FROM ANALYSIS_STEP
            WHERE PROJECT_KEY = ? AND STEP_NAME = ?
        """.trimIndent()

        val total = getJdbcTemplate().queryForObject(countSql, Integer::class.java, projectKey, module) ?: 0

        logger.info("查询完成: module={}, total={}", module, total)

        return mapOf("data" to data, "total" to total)
    }

    fun queryVectors(page: Int, size: Int): Map<String, Any> {
        logger.info("查询向量数据: page={}, size={}", page, size)

        validatePagination(page, size)

        // 向量数据存储在 TieredVectorStore 中，不在 H2 数据库
        // 返回空结果
        logger.info("向量数据存储在 TieredVectorStore 中，H2 中无数据")
        return mapOf("data" to emptyList<Map<String, Any>>(), "total" to 0)
    }

    fun queryProjects(page: Int, size: Int): Map<String, Any> {
        logger.info("查询项目列表: page={}, size={}", page, size)

        validatePagination(page, size)

        val offset = page * size

        // 查询 PROJECT_ANALYSIS 表
        val sql = """
            SELECT PROJECT_KEY, PROJECT_MD5, STATUS, START_TIME, END_TIME, CREATED_AT, UPDATED_AT
            FROM PROJECT_ANALYSIS
            ORDER BY CREATED_AT DESC
            LIMIT ? OFFSET ?
        """.trimIndent()

        val data = getJdbcTemplate().queryForList(sql, size, offset)

        // 查询总数
        val total = getJdbcTemplate().queryForObject(
            "SELECT COUNT(*) FROM PROJECT_ANALYSIS",
            Integer::class.java
        ) ?: 0

        logger.info("查询项目列表完成: total={}", total)

        return mapOf("data" to data, "total" to total)
    }

    fun executeSafeSql(sql: String, params: Map<String, Any>): List<Map<String, Any>> {
        logger.info("执行安全 SQL: sql={}, params={}", sql.take(100), params.keys)

        // 检查 SQL 是否包含危险关键字
        val upperSql = sql.uppercase()
        for (keyword in DANGEROUS_KEYWORDS) {
            if (keyword in upperSql) {
                throw IllegalArgumentException(
                    "危险 SQL：包含关键字 $keyword，只允许 SELECT 查询"
                )
            }
        }

        // 检查参数是否包含非法字符
        for ((key, value) in params) {
            if (value is String && INVALID_PARAM_CHARS.containsMatchIn(value)) {
                throw IllegalArgumentException(
                    "非法参数：$key 包含非法字符（分号、引号等）"
                )
            }
        }

        // 执行查询
        @Suppress("UNCHECKED_CAST")
        val result = getJdbcTemplate().queryForList(sql, *params.values.toTypedArray()) as List<Map<String, Any>>

        logger.info("执行安全 SQL 完成: 结果数={}", result.size)

        return result
    }
}
