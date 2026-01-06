package ai.smancode.sman.agent.models;

import java.util.HashMap;
import java.util.Map;

/**
 * 文件快照
 * 
 * 用于记录项目Java文件的状态，支持智能变化检测。
 * 包含文件路径、修改时间、MD5哈希值等信息。
 * 
 * @businessDomain code.analysis.cache
 * @businessFunction file.change.detection
 * @codeType model
 * @riskLevel low
 * @since 3.7.0
 */
public class FileSnapshot {
    
    /**
     * 快照时间戳
     */
    private long timestamp;
    
    /**
     * 项目根路径
     */
    private String projectPath;
    
    /**
     * 文件总数
     */
    private int fileCount;
    
    /**
     * 文件元数据映射
     * Key: 文件相对路径（相对于项目根目录）
     * Value: 文件元数据
     */
    private Map<String, FileMetadata> fileMetadataMap;
    
    /**
     * 文件元数据
     */
    public static class FileMetadata {
        /**
         * 文件绝对路径
         */
        private String absolutePath;
        
        /**
         * 文件相对路径（相对于项目根目录）
         */
        private String relativePath;
        
        /**
         * 文件大小（字节）
         */
        private long size;
        
        /**
         * 最后修改时间（毫秒时间戳）
         */
        private long lastModified;
        
        /**
         * 文件MD5哈希值（可选，按需计算）
         */
        private String md5;
        
        public FileMetadata() {
        }
        
        public FileMetadata(String absolutePath, String relativePath, long size, long lastModified) {
            this.absolutePath = absolutePath;
            this.relativePath = relativePath;
            this.size = size;
            this.lastModified = lastModified;
        }
        
        // Getters and Setters
        
        public String getAbsolutePath() {
            return absolutePath;
        }
        
        public void setAbsolutePath(String absolutePath) {
            this.absolutePath = absolutePath;
        }
        
        public String getRelativePath() {
            return relativePath;
        }
        
        public void setRelativePath(String relativePath) {
            this.relativePath = relativePath;
        }
        
        public long getSize() {
            return size;
        }
        
        public void setSize(long size) {
            this.size = size;
        }
        
        public long getLastModified() {
            return lastModified;
        }
        
        public void setLastModified(long lastModified) {
            this.lastModified = lastModified;
        }
        
        public String getMd5() {
            return md5;
        }
        
        public void setMd5(String md5) {
            this.md5 = md5;
        }
    }
    
    public FileSnapshot() {
        this.fileMetadataMap = new HashMap<>();
        this.timestamp = System.currentTimeMillis();
    }
    
    public FileSnapshot(String projectPath) {
        this();
        this.projectPath = projectPath;
    }
    
    /**
     * 添加文件元数据
     */
    public void addFile(String relativePath, FileMetadata metadata) {
        fileMetadataMap.put(relativePath, metadata);
        this.fileCount = fileMetadataMap.size();
    }
    
    /**
     * 获取文件元数据
     */
    public FileMetadata getFile(String relativePath) {
        return fileMetadataMap.get(relativePath);
    }
    
    /**
     * 检查文件是否存在
     */
    public boolean containsFile(String relativePath) {
        return fileMetadataMap.containsKey(relativePath);
    }
    
    // Getters and Setters
    
    public long getTimestamp() {
        return timestamp;
    }
    
    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }
    
    public String getProjectPath() {
        return projectPath;
    }
    
    public void setProjectPath(String projectPath) {
        this.projectPath = projectPath;
    }
    
    public int getFileCount() {
        return fileCount;
    }
    
    public void setFileCount(int fileCount) {
        this.fileCount = fileCount;
    }
    
    public Map<String, FileMetadata> getFileMetadataMap() {
        return fileMetadataMap;
    }
    
    public void setFileMetadataMap(Map<String, FileMetadata> fileMetadataMap) {
        this.fileMetadataMap = fileMetadataMap;
        this.fileCount = fileMetadataMap != null ? fileMetadataMap.size() : 0;
    }
}

