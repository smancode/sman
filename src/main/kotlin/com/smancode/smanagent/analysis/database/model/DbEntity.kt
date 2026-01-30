package com.smancode.smanagent.analysis.database.model

import kotlinx.serialization.Serializable

/**
 * 数据库实体（从 PSI 提取）
 *
 * @property className 简单类名：LoanEntity
 * @property qualifiedName 全限定名：com.smancode.entity.LoanEntity
 * @property packageName 包名：com.smancode.entity
 * @property tableName 表名：t_loan
 * @property hasTableAnnotation 是否有 @Table 注解
 * @property fields 字段列表
 * @property primaryKey 主键字段名：loan_id
 * @property relations 关联关系
 * @property stage1Confidence 阶段1置信度
 */
@Serializable
data class DbEntity(
    val className: String,
    val qualifiedName: String,
    val packageName: String,
    val tableName: String,
    val hasTableAnnotation: Boolean,
    val fields: List<DbField>,
    val primaryKey: String? = null,
    val relations: List<DbRelation> = emptyList(),
    val stage1Confidence: Double = 0.5
)