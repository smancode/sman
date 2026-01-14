package com.smancode.smanagent.model.part;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * 文本内容 Part
 * <p>
 * 用于存储 Markdown 格式的文本内容，支持流式追加。
 */
public class TextPart extends Part {

    /**
     * 文本内容（Markdown 格式）
     */
    private String text;

    public TextPart() {
        super();
        this.type = PartType.TEXT;
        this.text = "";
    }

    public TextPart(String id, String messageId, String sessionId) {
        super(id, messageId, sessionId, PartType.TEXT);
        this.text = "";
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
        touch();
    }

    /**
     * 追加文本内容（支持流式输出）
     *
     * @param delta 追加的文本
     */
    public void appendText(String delta) {
        if (delta == null || delta.isEmpty()) {
            return;
        }
        this.text += delta;
        touch();
    }

    /**
     * 清空文本内容
     */
    public void clear() {
        this.text = "";
        touch();
    }

    /**
     * 获取文本长度
     */
    @JsonIgnore
    public int getLength() {
        return text != null ? text.length() : 0;
    }

    /**
     * 检查是否为空
     */
    @JsonIgnore
    public boolean isEmpty() {
        return text == null || text.isEmpty();
    }
}
