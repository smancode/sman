package com.smancode.sman.model.part

import java.time.Duration
import java.time.Instant

/**
 * 思考过程 Part
 *
 * 用于显示 AI 的思考过程，支持流式追加。
 */
class ReasoningPart : Part {

    /**
     * 思考内容
     */
    var text: String = ""

    /**
     * 结束时间（完成时设置）
     */
    var endTime: Instant? = null

    constructor() : super() {
        this.type = PartType.REASONING
        this.text = ""
    }

    constructor(id: String, messageId: String, sessionId: String) : super(id, messageId, sessionId, PartType.REASONING) {
        this.text = ""
    }

    /**
     * 追加思考内容（支持流式输出）
     *
     * @param delta 追加的文本
     */
    fun appendText(delta: String?) {
        if (delta.isNullOrEmpty()) return
        this.text += delta
        touch()
    }

    /**
     * 完成思考
     */
    fun complete() {
        this.endTime = Instant.now()
        touch()
    }

    /**
     * 获取思考时长（毫秒）
     */
    fun getDurationMs(): Long {
        val end = endTime ?: Instant.now()
        return Duration.between(createdTime, end).toMillis()
    }

    /**
     * 检查是否完成
     */
    fun isCompleted(): Boolean = endTime != null
}
