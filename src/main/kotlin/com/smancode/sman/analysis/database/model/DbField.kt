package com.smancode.sman.analysis.database.model

import kotlinx.serialization.Serializable

/**
 * 数据库字段
 *
 * @property fieldName 字段名：loanId
 * @property columnName 列名：loan_id
 * @property fieldType 字段类型：String
 * @property columnType 列类型（从 @Column(columnDefinition)）：VARCHAR(32)
 * @property nullable 是否可空
 * @property isPrimaryKey 是否主键
 * @property hasColumnAnnotation 是否有 @Column 注解
 */
@Serializable
data class DbField(
    val fieldName: String,
    val columnName: String,
    val fieldType: String,
    val columnType: String? = null,
    val nullable: Boolean = true,
    val isPrimaryKey: Boolean = false,
    val hasColumnAnnotation: Boolean = false
)