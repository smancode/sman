package com.smancode.smanagent.analysis.database

import com.smancode.smanagent.analysis.database.model.DbEntity
import com.smancode.smanagent.analysis.database.model.DbField

/**
 * 伪 DDL 生成器
 *
 * 从 DbEntity 生成伪 DDL 语句
 */
class PseudoDdlGenerator {

    /**
     * 生成伪 DDL
     *
     * @param dbEntity 数据库实体
     * @return DDL 语句
     */
    fun generate(dbEntity: DbEntity): String {
        val sb = StringBuilder()

        sb.append("CREATE TABLE ${dbEntity.tableName} (\n")

        // 生成字段定义
        val fields = dbEntity.fields.map { field ->
            generateFieldDefinition(field)
        }.joinToString(",\n")

        sb.append("    $fields\n")

        // 生成主键约束
        if (dbEntity.primaryKey != null) {
            sb.append("    PRIMARY KEY (${dbEntity.primaryKey})\n")
        }

        sb.append(");")

        return sb.toString()
    }

    /**
     * 生成字段定义
     *
     * @param field 数据库字段
     * @return 字段定义
     */
    private fun generateFieldDefinition(field: DbField): String {
        val sb = StringBuilder()

        // 列名
        sb.append(field.columnName)

        // 列类型
        val columnType = if (field.columnType != null) {
            field.columnType
        } else {
            mapFieldTypeToColumnType(field.fieldType)
        }
        sb.append(" $columnType")

        // 是否可空
        if (!field.nullable) {
            sb.append(" NOT NULL")
        }

        return sb.toString()
    }

    /**
     * 映射字段类型到列类型
     *
     * @param fieldType 字段类型
     * @return 列类型
     */
    private fun mapFieldTypeToColumnType(fieldType: String): String {
        return when {
            fieldType == "String" -> "VARCHAR"
            fieldType == "Integer" || fieldType == "int" -> "INT"
            fieldType == "Long" || fieldType == "long" -> "BIGINT"
            fieldType == "BigDecimal" -> "DECIMAL"
            fieldType == "Boolean" || fieldType == "boolean" -> "BOOLEAN"
            fieldType == "Date" || fieldType == "LocalDate" || fieldType == "LocalDateTime" -> "TIMESTAMP"
            fieldType.contains("List") -> "TEXT"
            else -> "VARCHAR"
        }
    }
}
