package com.smancode.smanagent.model.search;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 搜索结果
 * <p>
 * 统一代码搜索和知识搜索的结果格式。
 */
public class SearchResult {

    /**
     * 结果类型：code 或 knowledge
     */
    @JsonProperty("type")
    private String type;

    /**
     * ID（知识 ID 或文件路径）
     */
    @JsonProperty("id")
    private String id;

    /**
     * 标题或文件名
     */
    @JsonProperty("title")
    private String title;

    /**
     * 内容片段
     */
    @JsonProperty("content")
    private String content;

    /**
     * 相似度分数（0-1）
     */
    @JsonProperty("score")
    private double score;

    /**
     * 额外元数据（JSON 字符串）
     */
    @JsonProperty("metadata")
    private String metadata;

    public SearchResult() {
    }

    public SearchResult(String type, String id, String title, String content, double score) {
        this.type = type;
        this.id = id;
        this.title = title;
        this.content = content;
        this.score = score;
    }

    public SearchResult(String type, String id, String title, String content, double score, String metadata) {
        this.type = type;
        this.id = id;
        this.title = title;
        this.content = content;
        this.score = score;
        this.metadata = metadata;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
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

    public double getScore() {
        return score;
    }

    public void setScore(double score) {
        this.score = score;
    }

    public String getMetadata() {
        return metadata;
    }

    public void setMetadata(String metadata) {
        this.metadata = metadata;
    }

    /**
     * 判断是否为代码搜索结果
     */
    public boolean isCodeResult() {
        return "code".equals(type);
    }

    /**
     * 判断是否为知识搜索结果
     */
    public boolean isKnowledgeResult() {
        return "knowledge".equals(type);
    }
}
