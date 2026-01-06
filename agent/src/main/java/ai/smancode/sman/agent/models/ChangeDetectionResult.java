package ai.smancode.sman.agent.models;

import java.util.ArrayList;
import java.util.List;

/**
 * 文件变化检测结果
 * 
 * 记录文件增删改的详细信息，用于判断是否需要刷新Spoon模型。
 * 
 * @businessDomain code.analysis.cache
 * @businessFunction file.change.detection
 * @codeType model
 * @riskLevel low
 * @since 3.7.0
 */
public class ChangeDetectionResult {
    
    /**
     * 是否有变化
     */
    private boolean hasChanges;
    
    /**
     * 检测级别
     * - LEVEL1_FILE_LIST: 文件列表变化（增删）
     * - LEVEL2_MODIFY_TIME: 修改时间变化
     * - LEVEL3_MD5: MD5变化
     * - NO_CHANGE: 无变化
     */
    private DetectionLevel detectionLevel;
    
    /**
     * 变化前文件数
     */
    private int fileCountBefore;
    
    /**
     * 变化后文件数
     */
    private int fileCountAfter;
    
    /**
     * 新增的文件（相对路径）
     */
    private List<String> addedFiles;
    
    /**
     * 删除的文件（相对路径）
     */
    private List<String> deletedFiles;
    
    /**
     * 修改时间变化的文件（相对路径）
     */
    private List<String> modifiedTimeFiles;
    
    /**
     * MD5变化的文件（相对路径）
     */
    private List<String> md5ChangedFiles;
    
    /**
     * 变化摘要
     */
    private String summary;
    
    /**
     * 检测耗时（毫秒）
     */
    private long detectionDuration;
    
    /**
     * 检测级别枚举
     */
    public enum DetectionLevel {
        NO_CHANGE("无变化"),
        LEVEL1_FILE_LIST("文件列表变化"),
        LEVEL2_MODIFY_TIME("修改时间变化"),
        LEVEL3_MD5("MD5变化");
        
        private final String description;
        
        DetectionLevel(String description) {
            this.description = description;
        }
        
        public String getDescription() {
            return description;
        }
    }
    
    public ChangeDetectionResult() {
        this.addedFiles = new ArrayList<>();
        this.deletedFiles = new ArrayList<>();
        this.modifiedTimeFiles = new ArrayList<>();
        this.md5ChangedFiles = new ArrayList<>();
        this.detectionLevel = DetectionLevel.NO_CHANGE;
    }
    
    /**
     * 构建变化摘要
     */
    public void buildSummary() {
        if (summary != null && summary.contains("首次快照")) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        
        if (!hasChanges) {
            sb.append("无变化");
        } else {
            List<String> parts = new ArrayList<>();
            
            if (!addedFiles.isEmpty()) {
                parts.add(String.format("新增%d个", addedFiles.size()));
            }
            if (!deletedFiles.isEmpty()) {
                parts.add(String.format("删除%d个", deletedFiles.size()));
            }
            if (!md5ChangedFiles.isEmpty()) {
                parts.add(String.format("修改%d个", md5ChangedFiles.size()));
            } else if (!modifiedTimeFiles.isEmpty()) {
                parts.add(String.format("时间变化%d个(内容未变)", modifiedTimeFiles.size()));
            }
            
            sb.append(String.join(", ", parts));
        }
        
        sb.append(String.format(" [%s, 耗时%dms]", detectionLevel.getDescription(), detectionDuration));
        
        this.summary = sb.toString();
    }
    
    // Getters and Setters
    
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
}

