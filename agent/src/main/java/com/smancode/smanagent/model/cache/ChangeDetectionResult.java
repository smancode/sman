package com.smancode.smanagent.model.cache;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 变化检测结果
 *
 * 功能：
 * - 记录四级检测的结果
 * - 提供变化摘要和统计
 *
 * @since 1.0.0
 */
public class ChangeDetectionResult implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 检测级别枚举
     */
    public enum DetectionLevel {
        /**
         * 无变化
         */
        NO_CHANGE,
        /**
         * 文件列表变化（增删）
         */
        LEVEL1_FILE_LIST,
        /**
         * 修改时间变化
         */
        LEVEL2_MODIFY_TIME,
        /**
         * MD5变化（内容变化）
         */
        LEVEL3_MD5
    }

    /**
     * 变化前文件数
     */
    private int fileCountBefore;

    /**
     * 变化后文件数
     */
    private int fileCountAfter;

    /**
     * 是否有变化
     */
    private boolean hasChanges;

    /**
     * 检测级别
     */
    private DetectionLevel detectionLevel;

    /**
     * 新增文件列表（相对路径）
     */
    private List<String> addedFiles = new ArrayList<>();

    /**
     * 删除文件列表（相对路径）
     */
    private List<String> deletedFiles = new ArrayList<>();

    /**
     * 修改时间变化的文件列表（相对路径）
     */
    private List<String> modifiedTimeFiles = new ArrayList<>();

    /**
     * MD5变化的文件列表（相对路径）
     */
    private List<String> md5ChangedFiles = new ArrayList<>();

    /**
     * 检测摘要
     */
    private String summary;

    /**
     * 检测耗时（毫秒）
     */
    private long detectionDuration;

    // Getters and Setters

    public int getFileCountBefore() {
        return fileCountBefore;
    }

    public void setFileCountBefore(int fileCountBefore) {
        this.fileCountBefore = fileCountBefore;
    }

    public int getFileCountAfter() {
        return fileCountAfter;
    }

    public void setFileCountAfter(int fileCountAfter) {
        this.fileCountAfter = fileCountAfter;
    }

    public boolean isHasChanges() {
        return hasChanges;
    }

    public void setHasChanges(boolean hasChanges) {
        this.hasChanges = hasChanges;
    }

    public DetectionLevel getDetectionLevel() {
        return detectionLevel;
    }

    public void setDetectionLevel(DetectionLevel detectionLevel) {
        this.detectionLevel = detectionLevel;
    }

    public List<String> getAddedFiles() {
        return addedFiles;
    }

    public void setAddedFiles(List<String> addedFiles) {
        this.addedFiles = addedFiles;
    }

    public List<String> getDeletedFiles() {
        return deletedFiles;
    }

    public void setDeletedFiles(List<String> deletedFiles) {
        this.deletedFiles = deletedFiles;
    }

    public List<String> getModifiedTimeFiles() {
        return modifiedTimeFiles;
    }

    public void setModifiedTimeFiles(List<String> modifiedTimeFiles) {
        this.modifiedTimeFiles = modifiedTimeFiles;
    }

    public List<String> getMd5ChangedFiles() {
        return md5ChangedFiles;
    }

    public void setMd5ChangedFiles(List<String> md5ChangedFiles) {
        this.md5ChangedFiles = md5ChangedFiles;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public long getDetectionDuration() {
        return detectionDuration;
    }

    public void setDetectionDuration(long detectionDuration) {
        this.detectionDuration = detectionDuration;
    }

    /**
     * 构建摘要
     */
    public void buildSummary() {
        StringBuilder sb = new StringBuilder();

        if (!hasChanges) {
            sb.append("无变化");
        } else {
            switch (detectionLevel) {
                case LEVEL1_FILE_LIST:
                    sb.append("文件列表变化: ");
                    sb.append("新增").append(addedFiles.size()).append("个");
                    sb.append(", 删除").append(deletedFiles.size()).append("个");
                    break;
                case LEVEL2_MODIFY_TIME:
                    sb.append("修改时间变化但MD5一致");
                    break;
                case LEVEL3_MD5:
                    sb.append("内容变化: ");
                    sb.append(md5ChangedFiles.size()).append("个文件MD5变化");
                    break;
                default:
                    sb.append("未知变化");
            }
        }

        this.summary = sb.toString();
    }

    /**
     * 获取所有变化的文件（新增+删除+修改）
     */
    public int getTotalChangedFiles() {
        return addedFiles.size() + deletedFiles.size() + md5ChangedFiles.size();
    }
}
