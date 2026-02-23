package com.smancode.sman.shared.model

import java.time.Instant

/**
 * 反馈数据类
 * 存储用户对 AI 输出的反馈信息
 *
 * @param id 反馈唯一标识
 * @param type 反馈类型
 * @param content 反馈内容
 * @param context 触发反馈的上下文
 * @param timestamp 反馈时间
 */
data class Feedback(
    val id: String,
    val type: FeedbackType,
    val content: String,
    val context: String,
    val timestamp: Instant
)
