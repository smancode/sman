package com.smancode.smanagent.analysis.database.model

import kotlinx.serialization.Serializable

/**
 * 关联关系
 *
 * @property relationType 关联类型
 * @property targetEntity 目标实体类名：com.smancode.entity.Customer
 * @property fieldName 字段名：customer
 * @property cascade 级联操作
 */
@Serializable
data class DbRelation(
    val relationType: RelationType,
    val targetEntity: String,
    val fieldName: String,
    val cascade: List<String> = emptyList()
)

/**
 * 关联类型枚举
 */
@Serializable
enum class RelationType {
    ONE_TO_MANY,   // 一对多
    MANY_TO_ONE,   // 多对一
    MANY_TO_MANY   // 多对多
}