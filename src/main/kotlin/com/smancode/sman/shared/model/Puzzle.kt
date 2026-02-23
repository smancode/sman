package com.smancode.sman.shared.model

import java.time.Instant

/**
 * 拼图块模型
 * 代表项目知识图谱中的一个拼图块
 *
 * @property id 拼图唯一标识符
 * @property type 拼图类型（结构/技术/入口/数据/流程/规则）
 * @property status 拼图状态（待分析/分析中/已完成/失败）
 * @property content Markdown 格式的拼图内容
 * @property completeness 完成度（0.0-1.0）
 * @property confidence 置信度（0.0-1.0）
 * @property lastUpdated 最后更新时间
 * @property filePath 拼图存储路径
 */
data class Puzzle(
    val id: String,
    val type: PuzzleType,
    val status: PuzzleStatus,
    val content: String,
    val completeness: Double,
    val confidence: Double,
    val lastUpdated: Instant,
    val filePath: String
)
