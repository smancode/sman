package com.smancode.smanagent.analysis.database.model

import kotlinx.serialization.Serializable

/**
 * Queen 表（核心业务表）
 *
 * @property tableName 表名
 * @property className 类名
 * @property businessName 业务名称：借据
 * @property tableType 表类型：QUEEN
 * @property llmReasoning LLM 推理过程
 * @property confidence 最终置信度
 * @property stage1Confidence 阶段1置信度
 * @property stage2Confidence 阶段2置信度（LLM）
 * @property stage3Confidence 阶段3置信度（调整后）
 * @property xmlReferenceCount XML 引用次数
 * @property columnCount 列数
 * @property primaryKey 主键
 * @property pseudoDdl 伪 DDL
 */
@Serializable
data class QueenTable(
    val tableName: String,
    val className: String,
    val businessName: String,
    val tableType: TableType,
    val llmReasoning: String? = null,
    val confidence: Double,
    val stage1Confidence: Double,
    val stage2Confidence: Double,
    val stage3Confidence: Double,
    val xmlReferenceCount: Int = 0,
    val columnCount: Int,
    val primaryKey: String?,
    val pseudoDdl: String
)

/**
 * 表类型枚举
 */
@Serializable
enum class TableType {
    QUEEN,      // 核心业务表（高置信度 > 0.7）
    COMMON,     // 普通业务表
    SYSTEM,     // 系统配置表
    LOOKUP      // 字典表
}