package com.smancode.smanagent.model;

import java.time.Instant;

/**
 * 领域知识
 * <p>
 * 存储领域知识（业务规则、SOP、代码映射关系等），支持向量相似度搜索。
 * <p>
 * 简化设计：只存储核心字段，content 为纯文本，embedding 为 BGE-M3 向量（Base64 编码）。
 */
public class DomainKnowledge {

    /**
     * 主键 ID（UUID）
     */
    private String id;

    /**
     * 项目标识
     */
    private String projectKey;

    /**
     * 知识标题
     */
    private String title;

    /**
     * 知识内容（纯文本）
     */
    private String content;

    /**
     * BGE-M3 向量（Base64 编码）
     */
    private String embedding;

    /**
     * 创建时间
     */
    private Instant createdAt;

    /**
     * 更新时间
     */
    private Instant updatedAt;

    public DomainKnowledge() {
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public DomainKnowledge(String id, String projectKey, String title, String content) {
        this.id = id;
        this.projectKey = projectKey;
        this.title = title;
        this.content = content;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getProjectKey() {
        return projectKey;
    }

    public void setProjectKey(String projectKey) {
        this.projectKey = projectKey;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getEmbedding() {
        return embedding;
    }

    public void setEmbedding(String embedding) {
        this.embedding = embedding;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    /**
     * 更新时间戳
     */
    public void touch() {
        this.updatedAt = Instant.now();
    }

    /**
     * 判断是否有向量
     */
    public boolean hasEmbedding() {
        return embedding != null && !embedding.isEmpty();
    }
}
