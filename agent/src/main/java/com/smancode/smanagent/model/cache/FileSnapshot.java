package com.smancode.smanagent.model.cache;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 文件快照
 *
 * 功能：
 * - 记录项目所有文件的元数据（路径、大小、修改时间、MD5）
 * - 用于变化检测的基准对比
 *
 * @since 1.0.0
 */
public class FileSnapshot implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 项目根路径
     */
    private final String projectPath;

    /**
     * 快照创建时间
     */
    private final long timestamp;

    /**
     * 文件元数据映射 (相对路径 -> 元数据)
     */
    private final Map<String, FileMetadata> fileMetadataMap;

    /**
     * 文件总数
     */
    private int fileCount;

    public FileSnapshot(String projectPath) {
        this.projectPath = projectPath;
        this.timestamp = System.currentTimeMillis();
        this.fileMetadataMap = new ConcurrentHashMap<>();
        this.fileCount = 0;
    }

    public String getProjectPath() {
        return projectPath;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public Map<String, FileMetadata> getFileMetadataMap() {
        return fileMetadataMap;
    }

    public int getFileCount() {
        return fileCount;
    }

    public void setFileCount(int fileCount) {
        this.fileCount = fileCount;
    }

    /**
     * 添加文件元数据
     */
    public void addFile(String relativePath, FileMetadata metadata) {
        fileMetadataMap.put(relativePath, metadata);
        fileCount = fileMetadataMap.size();
    }

    /**
     * 获取文件元数据
     */
    public FileMetadata getFile(String relativePath) {
        return fileMetadataMap.get(relativePath);
    }

    /**
     * 文件元数据
     */
    public static class FileMetadata implements Serializable {

        private static final long serialVersionUID = 1L;

        /**
         * 文件绝对路径
         */
        private final String absolutePath;

        /**
         * 文件相对路径（相对于项目根）
         */
        private final String relativePath;

        /**
         * 文件大小（字节）
         */
        private final long fileSize;

        /**
         * 文件最后修改时间
         */
        private final long lastModified;

        /**
         * 文件MD5值（懒加载）
         */
        private String md5;

        public FileMetadata(String absolutePath, String relativePath, long fileSize, long lastModified) {
            this.absolutePath = absolutePath;
            this.relativePath = relativePath;
            this.fileSize = fileSize;
            this.lastModified = lastModified;
        }

        public String getAbsolutePath() {
            return absolutePath;
        }

        public String getRelativePath() {
            return relativePath;
        }

        public long getFileSize() {
            return fileSize;
        }

        public long getLastModified() {
            return lastModified;
        }

        public String getMd5() {
            return md5;
        }

        public void setMd5(String md5) {
            this.md5 = md5;
        }
    }
}
