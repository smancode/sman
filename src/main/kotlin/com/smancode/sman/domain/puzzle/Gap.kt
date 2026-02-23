package com.smancode.sman.domain.puzzle

import com.smancode.sman.shared.model.PuzzleType
import java.time.Instant

/**
 * 空白数据类
 *
 * 表示 Puzzle 知识库中发现的空白（需要补充的知识）
 *
 * @property type 空白类型
 * @property puzzleType 关联的 Puzzle 类型
 * @property description 空白描述
 * @property priority 优先级（0.0-1.0，越高越优先）
 * @property relatedFiles 相关文件路径列表
 * @property detectedAt 发现时间
 */
data class Gap(
    val type: GapType,
    val puzzleType: PuzzleType,
    val description: String,
    val priority: Double,
    val relatedFiles: List<String>,
    val detectedAt: Instant
)
