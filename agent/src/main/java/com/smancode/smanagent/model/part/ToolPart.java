package com.smancode.smanagent.model.part;

import java.util.Map;

/**
 * 工具调用 Part
 * <p>
 * 极简设计（参考 OpenCode）：
 * - 简单的枚举状态（而非复杂的状态类继承）
 * - 状态流转：PENDING → RUNNING → COMPLETED/ERROR
 * - 包含工具名称、参数、结果
 */
public class ToolPart extends Part {

    /**
     * 工具名称
     */
    private String toolName;

    /**
     * 工具参数
     */
    private Map<String, Object> parameters;

    /**
     * 工具执行结果
     */
    private com.smancode.smanagent.tools.ToolResult result;

    /**
     * 当前状态（极简枚举）
     */
    private ToolState state;

    public ToolPart() {
        super();
        this.type = PartType.TOOL;
        this.state = ToolState.PENDING;
    }

    public ToolPart(String id, String messageId, String sessionId, String toolName) {
        super(id, messageId, sessionId, PartType.TOOL);
        this.toolName = toolName;
        this.state = ToolState.PENDING;
    }

    public String getToolName() {
        return toolName;
    }

    public void setToolName(String toolName) {
        this.toolName = toolName;
        touch();
    }

    public Map<String, Object> getParameters() {
        return parameters;
    }

    public void setParameters(Map<String, Object> parameters) {
        this.parameters = parameters;
        touch();
    }

    public com.smancode.smanagent.tools.ToolResult getResult() {
        return result;
    }

    public void setResult(com.smancode.smanagent.tools.ToolResult result) {
        this.result = result;
        touch();
    }

    public ToolState getState() {
        return state;
    }

    public void setState(ToolState state) {
        // 状态转换校验
        if (this.state == ToolState.COMPLETED || this.state == ToolState.ERROR) {
            throw new IllegalStateException("已完成或错误的工具不能转换状态");
        }
        this.state = state;
        touch();
    }

    /**
     * 工具状态枚举（极简设计）
     */
    public enum ToolState {
        /**
         * 等待执行
         */
        PENDING,

        /**
         * 执行中
         */
        RUNNING,

        /**
         * 已完成
         */
        COMPLETED,

        /**
         * 错误
         */
        ERROR
    }
}
