package com.smancode.smanagent.tools

/**
 * 需要访问 WebSocket Session 的工具接口
 *
 * 工具实现此接口后，在执行时可以通过 {@link #setWebSocketSessionId(String)}
 * 接收 WebSocket Session ID，用于将子工具调用转发到 IDE。
 *
 * 使用场景：批量工具（如 BatchTool）需要将其内部的子工具调用
 * 转发到 IDE 执行时，需要 Session ID 来建立 WebSocket 连接。
 *
 * @see Tool
 * @see ToolExecutor
 */
interface SessionAwareTool {

    /**
     * 设置 WebSocket Session ID
     *
     * 此方法由 {@link ToolExecutor} 在工具执行前调用，
     * 用于传递当前请求的 WebSocket Session ID。
     *
     * @param sessionId WebSocket Session ID，可能为 null（表示无 Session 上下文）
     */
    fun setWebSocketSessionId(sessionId: String?)
}
