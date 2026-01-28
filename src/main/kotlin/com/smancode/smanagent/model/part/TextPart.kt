package com.smancode.smanagent.model.part

import com.fasterxml.jackson.annotation.JsonIgnore

/**
 * 文本内容 Part
 *
 * 用于存储 Markdown 格式的文本内容，支持流式追加。
 */
class TextPart : Part {

    /**
     * 文本内容（Markdown 格式）
     */
    var text: String = ""

    constructor() : super() {
        this.type = PartType.TEXT
        this.text = ""
    }

    constructor(id: String, messageId: String, sessionId: String) : super(id, messageId, sessionId, PartType.TEXT) {
        this.text = ""
    }

    /**
     * 追加文本内容（支持流式输出）
     *
     * @param delta 追加的文本
     */
    fun appendText(delta: String?) {
        if (delta.isNullOrEmpty()) return
        this.text += delta
        touch()
    }

    /**
     * 清空文本内容
     */
    fun clear() {
        this.text = ""
        touch()
    }

    /**
     * 获取文本长度
     */
    @JsonIgnore
    fun getLength(): Int = text?.length ?: 0

    /**
     * 检查是否为空
     */
    @JsonIgnore
    fun isEmpty(): Boolean = text.isNullOrEmpty()
}
