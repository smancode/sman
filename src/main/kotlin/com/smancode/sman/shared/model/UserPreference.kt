package com.smancode.sman.shared.model

import java.time.Instant

/**
 * 用户偏好数据类
 * 存储单个用户偏好项的完整信息
 *
 * @param id 偏好唯一标识
 * @param type 偏好类型
 * @param key 偏好键（如 "naming_convention"）
 * @param value 偏好值（如 "camelCase"）
 * @param confidence 置信度（0.0-1.0）
 * @param source 偏好来源
 * @param learnedAt 学习时间
 * @param usageCount 使用次数
 */
data class UserPreference(
    val id: String,
    val type: PreferenceType,
    val key: String,
    val value: String,
    val confidence: Double,
    val source: PreferenceSource,
    val learnedAt: Instant,
    val usageCount: Int
)
