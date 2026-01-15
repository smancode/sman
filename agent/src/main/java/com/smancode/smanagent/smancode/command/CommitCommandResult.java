package com.smancode.smanagent.smancode.command;

import java.util.List;

/**
 * Commit 命令执行结果
 * <p>
 * 返回给前端的结构化数据
 */
public class CommitCommandResult {

    /**
     * commit message（格式：#AI commit# ${message}）
     */
    private String commitMessage;

    /**
     * 新增文件列表
     */
    private List<String> addFiles;

    /**
     * 修改文件列表
     */
    private List<String> modifyFiles;

    /**
     * 删除文件列表
     */
    private List<String> deleteFiles;

    public CommitCommandResult() {
    }

    public CommitCommandResult(String commitMessage, List<String> addFiles, List<String> modifyFiles, List<String> deleteFiles) {
        this.commitMessage = commitMessage;
        this.addFiles = addFiles;
        this.modifyFiles = modifyFiles;
        this.deleteFiles = deleteFiles;
    }

    public String getCommitMessage() {
        return commitMessage;
    }

    public void setCommitMessage(String commitMessage) {
        this.commitMessage = commitMessage;
    }

    public List<String> getAddFiles() {
        return addFiles;
    }

    public void setAddFiles(List<String> addFiles) {
        this.addFiles = addFiles;
    }

    public List<String> getModifyFiles() {
        return modifyFiles;
    }

    public void setModifyFiles(List<String> modifyFiles) {
        this.modifyFiles = modifyFiles;
    }

    public List<String> getDeleteFiles() {
        return deleteFiles;
    }

    public void setDeleteFiles(List<String> deleteFiles) {
        this.deleteFiles = deleteFiles;
    }
}
