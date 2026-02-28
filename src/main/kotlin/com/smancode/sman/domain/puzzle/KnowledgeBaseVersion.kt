package com.smancode.sman.domain.puzzle

import com.smancode.sman.shared.model.Puzzle
import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * 版本触发类型
 */
enum class VersionTrigger {
    /** 手动触发 */
    MANUAL,
    /** 自动触发（定时） */
    AUTO,
    /** 迭代触发（知识进化） */
    ITERATION
}

/**
 * 知识库版本模型
 *
 * @property id 版本唯一标识符，格式: v{number}-{timestamp}
 * @property versionNumber 版本号（1, 2, 3...）
 * @property createdAt 创建时间
 * @property puzzleCount 拼图数量
 * @property checksum 所有 Puzzle 内容的聚合 MD5
 * @property description 版本描述
 * @property trigger 触发类型
 */
data class KnowledgeBaseVersion(
    val id: String,
    val versionNumber: Int,
    val createdAt: Instant,
    val puzzleCount: Int,
    val checksum: String,
    val description: String?,
    val trigger: VersionTrigger
) {
    companion object {
        private val TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
            .withZone(java.time.ZoneId.systemDefault())

        /**
         * 生成版本 ID
         */
        fun generateId(versionNumber: Int, timestamp: Instant = Instant.now()): String {
            val timestampStr = TIMESTAMP_FORMAT.format(timestamp)
            return "v$versionNumber-$timestampStr"
        }
    }
}

/**
 * 版本索引条目
 */
data class VersionEntry(
    val id: String,
    val versionNumber: Int,
    val createdAt: Instant,
    val puzzleCount: Int,
    val checksum: String
)

/**
 * 版本索引
 *
 * @property currentVersion 当前版本号
 * @property versions 版本列表
 */
data class VersionIndex(
    val currentVersion: Int = 0,
    val versions: List<VersionEntry> = emptyList()
)

/**
 * 知识库快照
 *
 * @property version 版本信息
 * @property puzzles 拼图列表
 */
data class KnowledgeBaseSnapshot(
    val version: KnowledgeBaseVersion,
    val puzzles: List<Puzzle>
)
