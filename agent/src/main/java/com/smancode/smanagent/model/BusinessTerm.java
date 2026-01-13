package com.smancode.smanagent.model;

import java.util.List;
import java.util.Map;

/**
 * 业务术语
 */
public class BusinessTerm {
    private String name;              // 术语名称
    private String category;          // 分类：ENTITY(实体), ACTION(动作), RULE(规则)
    private String description;       // 描述
    private Double confidence;        // 置信度
    private List<CodeElement> codeMappings; // 映射的代码元素
    private Map<String, Object> attributes; // 额外属性

    public BusinessTerm() {}

    public BusinessTerm(String name, String category) {
        this.name = name;
        this.category = category;
    }

    // Getters and Setters
    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Double getConfidence() {
        return confidence;
    }

    public void setConfidence(Double confidence) {
        this.confidence = confidence;
    }

    public List<CodeElement> getCodeMappings() {
        return codeMappings;
    }

    public void setCodeMappings(List<CodeElement> codeMappings) {
        this.codeMappings = codeMappings;
    }

    public Map<String, Object> getAttributes() {
        return attributes;
    }

    public void setAttributes(Map<String, Object> attributes) {
        this.attributes = attributes;
    }
}
