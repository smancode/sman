package com.smancode.smanagent.model.part;

import java.time.Instant;
import java.time.Duration;

/**
 * 思考过程 Part
 * <p>
 * 用于显示 AI 的思考过程，支持流式追加。
 */
public class ReasoningPart extends Part {

    /**
     * 思考内容
     */
    private String text;

    /**
     * 结束时间（完成时设置）
     */
    private Instant endTime;

    public ReasoningPart() {
        super();
        this.type = PartType.REASONING;
        this.text = "";
    }

    public ReasoningPart(String id, String messageId, String sessionId) {
        super(id, messageId, sessionId, PartType.REASONING);
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
     * 追加思考内容（支持流式输出）
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
     * 完成思考
     */
    public void complete() {
        this.endTime = Instant.now();
        touch();
    }

    public Instant getEndTime() {
        return endTime;
    }

    public void setEndTime(Instant endTime) {
        this.endTime = endTime;
        touch();
    }

    /**
     * 获取思考时长（毫秒）
     */
    public long getDurationMs() {
        if (endTime == null) {
            return Duration.between(createdTime, Instant.now()).toMillis();
        }
        return Duration.between(createdTime, endTime).toMillis();
    }

    /**
     * 检查是否完成
     */
    public boolean isCompleted() {
        return endTime != null;
    }
}
