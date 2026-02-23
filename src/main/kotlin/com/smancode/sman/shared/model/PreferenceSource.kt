package com.smancode.sman.shared.model

/**
 * 偏好来源枚举
 * 定义偏好数据的来源渠道
 */
enum class PreferenceSource {
    EXPLICIT,           // 用户明确指定
    IMPLICIT,           // 从行为推断
    CORRECTION          // 从修正中学习
}
