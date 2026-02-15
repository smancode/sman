package com.smancode.sman.architect.model

import com.smancode.sman.analysis.model.AnalysisType

/**
 * 文件变更影响分析结果
 *
 * 用于增量更新检测，判断文件变更是否需要触发 MD 重新分析
 *
 * @property analysisType 分析类型
 * @property changedFiles 变更的文件列表（相对路径）
 * @property needsUpdate 是否需要更新
 * @property impactLevel 影响级别
 * @property affectedSections 受影响的 MD 章节
 * @property reason 原因说明
 */
data class FileChangeImpact(
    val analysisType: AnalysisType,
    val changedFiles: List<String>,
    val needsUpdate: Boolean,
    val impactLevel: ImpactLevel,
    val affectedSections: List<String> = emptyList(),
    val reason: String = ""
) {
    /**
     * 影响级别
     */
    enum class ImpactLevel {
        /** 高影响 - 需要完全重新分析 */
        HIGH,
        /** 中等影响 - 需要增量更新 */
        MEDIUM,
        /** 低影响 - 可以忽略 */
        LOW;

        companion object {
            /**
             * 从字符串解析
             */
            fun fromString(value: String): ImpactLevel {
                return values().firstOrNull { it.name.equals(value, ignoreCase = true) } ?: LOW
            }
        }
    }

    /**
     * 格式化为可读字符串
     */
    fun formatSummary(): String {
        return buildString {
            appendLine("文件变更影响分析:")
            appendLine("  - 分析类型: ${analysisType.displayName}")
            appendLine("  - 变更文件数: ${changedFiles.size}")
            appendLine("  - 影响级别: $impactLevel")
            appendLine("  - 需要更新: ${if (needsUpdate) "是" else "否"}")
            if (reason.isNotEmpty()) {
                appendLine("  - 原因: $reason")
            }
            if (affectedSections.isNotEmpty()) {
                appendLine("  - 受影响章节: ${affectedSections.joinToString(", ")}")
            }
        }
    }

    companion object {
        /**
         * 无变更
         */
        fun noChange(type: AnalysisType): FileChangeImpact = FileChangeImpact(
            analysisType = type,
            changedFiles = emptyList(),
            needsUpdate = false,
            impactLevel = ImpactLevel.LOW,
            affectedSections = emptyList(),
            reason = "无文件变更"
        )

        /**
         * 高影响变更
         */
        fun highImpact(
            type: AnalysisType,
            files: List<String>,
            sections: List<String> = emptyList(),
            reason: String = ""
        ): FileChangeImpact = FileChangeImpact(
            analysisType = type,
            changedFiles = files,
            needsUpdate = true,
            impactLevel = ImpactLevel.HIGH,
            affectedSections = sections,
            reason = reason.ifEmpty { "核心文件发生重大变更" }
        )

        /**
         * 中等影响变更
         */
        fun mediumImpact(
            type: AnalysisType,
            files: List<String>,
            sections: List<String> = emptyList(),
            reason: String = ""
        ): FileChangeImpact = FileChangeImpact(
            analysisType = type,
            changedFiles = files,
            needsUpdate = true,
            impactLevel = ImpactLevel.MEDIUM,
            affectedSections = sections,
            reason = reason.ifEmpty { "部分文件发生变更" }
        )

        /**
         * 低影响变更
         */
        fun lowImpact(
            type: AnalysisType,
            files: List<String>,
            reason: String = ""
        ): FileChangeImpact = FileChangeImpact(
            analysisType = type,
            changedFiles = files,
            needsUpdate = false,
            impactLevel = ImpactLevel.LOW,
            affectedSections = emptyList(),
            reason = reason.ifEmpty { "变更不影响分析结果" }
        )
    }
}
