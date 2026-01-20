package com.smancode.smanagent.tools;

/**
 * 批量工具的子结果
 * <p>
 * 用于 batch 工具记录每个子工具的执行结果
 */
public class BatchSubResult {
    /**
     * 子工具名称
     */
    private String toolName;

    /**
     * 是否成功
     */
    private boolean success;

    /**
     * 工具结果（复用 ToolResult）
     */
    private ToolResult result;

    /**
     * 错误信息（如果失败）
     */
    private String error;

    public BatchSubResult() {
    }

    public BatchSubResult(String toolName, boolean success, ToolResult result, String error) {
        this.toolName = toolName;
        this.success = success;
        this.result = result;
        this.error = error;
    }

    public String getToolName() {
        return toolName;
    }

    public void setToolName(String toolName) {
        this.toolName = toolName;
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public ToolResult getResult() {
        return result;
    }

    public void setResult(ToolResult result) {
        this.result = result;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }
}
