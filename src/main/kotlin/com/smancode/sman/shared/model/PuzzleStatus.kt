package com.smancode.sman.shared.model

/**
 * 拼图状态枚举
 * 定义拼图块的生命周期状态
 */
enum class PuzzleStatus {
    /** 待分析 - 初始状态，尚未开始分析 */
    PENDING,
    /** 分析中 - 正在进行 AI 分析 */
    IN_PROGRESS,
    /** 已完成 - 分析完成，内容可用 */
    COMPLETED,
    /** 失败 - 分析过程中出现错误 */
    FAILED
}
