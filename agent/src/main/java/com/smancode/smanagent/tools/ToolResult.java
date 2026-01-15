package com.smancode.smanagent.tools;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 工具执行结果
 */
public class ToolResult {

    /**
     * 是否成功
     */
    private boolean success;

    /**
     * 返回数据
     */
    private Object data;

    /**
     * 显示标题
     */
    private String displayTitle;

    /**
     * 显示内容
     */
    private String displayContent;

    /**
     * 错误信息
     */
    private String error;

    /**
     * 执行时长（毫秒）
     */
    private long executionTimeMs;

    /**
     * 执行时间
     */
    private Instant executionTime;

    /**
     * 相对路径（用于后续重新读取或修改）
     */
    private String relativePath;

    /**
     * 相关文件路径列表（用于 find_file 等工具）
     * <p>
     * 存储 find_file 找到的所有匹配文件路径
     */
    private List<String> relatedFilePaths;

    /**
     * 元数据（存储扩展信息）
     * <p>
     * 例如: absolutePath, totalLines, startLine, endLine 等
     */
    private Map<String, Object> metadata;

    public ToolResult() {
        this.executionTime = Instant.now();
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public Object getData() {
        return data;
    }

    public void setData(Object data) {
        this.data = data;
    }

    public String getDisplayTitle() {
        return displayTitle;
    }

    public void setDisplayTitle(String displayTitle) {
        this.displayTitle = displayTitle;
    }

    public String getDisplayContent() {
        return displayContent;
    }

    public void setDisplayContent(String displayContent) {
        this.displayContent = displayContent;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }

    public long getExecutionTimeMs() {
        return executionTimeMs;
    }

    public void setExecutionTimeMs(long executionTimeMs) {
        this.executionTimeMs = executionTimeMs;
    }

    /**
     * 创建成功结果
     *
     * @param data         数据
     * @param displayTitle 显示标题
     * @param displayContent 显示内容
     * @return 成功结果
     */
    public static ToolResult success(Object data, String displayTitle, String displayContent) {
        ToolResult result = new ToolResult();
        result.setSuccess(true);
        result.setData(data);
        result.setDisplayTitle(displayTitle);
        result.setDisplayContent(displayContent);
        return result;
    }

    /**
     * 创建失败结果
     *
     * @param error 错误信息
     * @return 失败结果
     */
    public static ToolResult failure(String error) {
        ToolResult result = new ToolResult();
        result.setSuccess(false);
        result.setError(error);
        return result;
    }

    /**
     * 计算执行时长
     */
    public void calculateDuration() {
        if (executionTime != null) {
            this.executionTimeMs = java.time.Duration.between(executionTime, Instant.now()).toMillis();
        }
    }

    public String getRelativePath() {
        return relativePath;
    }

    public void setRelativePath(String relativePath) {
        this.relativePath = relativePath;
    }

    public List<String> getRelatedFilePaths() {
        return relatedFilePaths;
    }

    public void setRelatedFilePaths(List<String> relatedFilePaths) {
        this.relatedFilePaths = relatedFilePaths;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public void setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata;
    }

    /**
     * 创建成功结果（带相对路径）
     *
     * @param data         数据
     * @param relativePath 相对路径
     * @param displayContent 显示内容
     * @return 成功结果
     */
    public static ToolResult successWithPath(Object data, String relativePath, String displayContent) {
        ToolResult result = new ToolResult();
        result.setSuccess(true);
        result.setData(data);
        result.setRelativePath(relativePath);
        result.setDisplayTitle(relativePath);  // 标题使用相对路径
        result.setDisplayContent(displayContent);
        return result;
    }
}
