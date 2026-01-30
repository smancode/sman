package com.smancode.smanagent.analysis.database.model

import kotlinx.serialization.Serializable

/**
 * 表使用情况（从 Mapper XML 提取）
 *
 * @property tableName 表名
 * @property mapperFiles 引用该表的 Mapper 文件
 * @property referenceCount 引用次数
 * @property operations 涉及的 SQL 操作
 */
@Serializable
data class TableUsage(
    val tableName: String,
    val mapperFiles: List<String> = emptyList(),
    val referenceCount: Int = 0,
    val operations: List<SqlOperation> = emptyList()
)

/**
 * SQL 操作
 *
 * @property operationType 操作类型：SELECT/INSERT/UPDATE/DELETE
 * @property mapperFile Mapper 文件
 * @property statementId 语句 ID
 */
@Serializable
data class SqlOperation(
    val operationType: SqlOperationType,
    val mapperFile: String,
    val statementId: String
)

/**
 * SQL 操作类型枚举
 */
@Serializable
enum class SqlOperationType {
    SELECT, INSERT, UPDATE, DELETE
}