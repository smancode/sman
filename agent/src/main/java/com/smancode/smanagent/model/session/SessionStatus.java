package com.smancode.smanagent.model.session;

/**
 * 会话状态
 */
public enum SessionStatus {
    /**
     * 空闲
     */
    IDLE,

    /**
     * 工作中
     */
    WORKING,

    /**
     * 已完成
     */
    DONE,

    /**
     * 已取消
     */
    CANCELLED
}
