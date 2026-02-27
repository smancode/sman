package com.smancode.sman.domain.puzzle

import com.smancode.sman.shared.model.PuzzleType
import java.time.Instant
import java.util.UUID

/**
 * Gap 与 Task 之间的类型映射器
 *
 * 职责：封装 Gap、Task、TaskType、PuzzleType 之间的转换逻辑
 */
object TaskGapMapper {

    /**
     * 将 Gap 转换为 Task
     */
    fun gapToTask(gap: Gap): AnalysisTask {
        return AnalysisTask.create(
            id = UUID.randomUUID().toString(),
            type = mapGapTypeToTaskType(gap.type),
            target = gap.relatedFiles.firstOrNull() ?: "unknown",
            puzzleId = "${gap.puzzleType.name.lowercase()}-${gap.relatedFiles.firstOrNull()?.hashCode() ?: 0}",
            priority = gap.priority,
            relatedFiles = gap.relatedFiles
        )
    }

    /**
     * 将 Task 转换为 Gap
     */
    fun gapFromTask(task: AnalysisTask): Gap {
        return Gap(
            type = GapType.INCOMPLETE,
            puzzleType = mapTaskTypeToPuzzleType(task.type),
            description = task.target,
            priority = task.priority,
            relatedFiles = task.relatedFiles,
            detectedAt = Instant.now()
        )
    }

    /**
     * 将 GapType 映射到 TaskType
     */
    fun mapGapTypeToTaskType(gapType: GapType): TaskType {
        return when (gapType) {
            GapType.LOW_COMPLETENESS, GapType.INCOMPLETE -> TaskType.UPDATE_PUZZLE
            GapType.FILE_CHANGE_TRIGGERED -> TaskType.UPDATE_PUZZLE
            GapType.USER_QUERY_TRIGGERED -> TaskType.UPDATE_PUZZLE
            else -> TaskType.ANALYZE_API
        }
    }

    /**
     * 将 TaskType 映射到 PuzzleType
     */
    fun mapTaskTypeToPuzzleType(taskType: TaskType): PuzzleType {
        return when (taskType) {
            TaskType.ANALYZE_STRUCTURE -> PuzzleType.STRUCTURE
            TaskType.ANALYZE_API -> PuzzleType.API
            TaskType.ANALYZE_DATA -> PuzzleType.DATA
            TaskType.ANALYZE_FLOW -> PuzzleType.FLOW
            TaskType.ANALYZE_RULE -> PuzzleType.RULE
            TaskType.UPDATE_PUZZLE -> PuzzleType.API
        }
    }
}
