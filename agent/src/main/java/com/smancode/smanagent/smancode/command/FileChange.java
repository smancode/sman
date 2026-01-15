package com.smancode.smanagent.smancode.command;

/**
 * 文件变更记录
 * <p>
 * 用于追踪 agent 通过工具修改的文件
 */
public class FileChange {

    /**
     * 文件相对路径
     */
    private String relativePath;

    /**
     * 变更类型
     */
    private ChangeType type;

    /**
     * 变更摘要（从工具执行结果的 displayContent 提取）
     */
    private String changeSummary;

    public FileChange() {
    }

    public FileChange(String relativePath, ChangeType type, String changeSummary) {
        this.relativePath = relativePath;
        this.type = type;
        this.changeSummary = changeSummary;
    }

    public String getRelativePath() {
        return relativePath;
    }

    public void setRelativePath(String relativePath) {
        this.relativePath = relativePath;
    }

    public ChangeType getType() {
        return type;
    }

    public void setType(ChangeType type) {
        this.type = type;
    }

    public String getChangeSummary() {
        return changeSummary;
    }

    public void setChangeSummary(String changeSummary) {
        this.changeSummary = changeSummary;
    }

    /**
     * 变更类型枚举
     */
    public enum ChangeType {
        /**
         * 新增文件 (create_file)
         */
        ADD,

        /**
         * 修改文件 (apply_change)
         */
        MODIFY,

        /**
         * 删除文件 (delete_file)
         */
        DELETE
    }
}
