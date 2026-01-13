package com.smancode.smanagent.model.session;

/**
 * 会话状态（极简设计）
 * <p>
 * 参考 OpenCode，只有 3 种状态：
 * - IDLE: 空闲
 * - BUSY: 忙碌（正在处理）
 * - RETRY: 重试中
 */
public enum SessionStatus {
    /**
     * 空闲
     */
    IDLE,

    /**
     * 忙碌（正在处理）
     */
    BUSY,

    /**
     * 重试中
     */
    RETRY
}
