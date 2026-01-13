package com.smancode.smanagent.model;

import java.util.List;
import java.util.Map;

/**
 * 业务分析结果
 *
 * 针对用户问题的业务理解和代码映射
 */
public class BusinessAnalysis {
    private String userQuestion;           // 用户问题
    private List<String> identifiedTerms;  // 识别出的业务术语
    private Map<String, String> termExplanations; // 术语解释
    private List<TermRelation> relations;  // 术语关系
    private List<CodeElement> relevantCode; // 相关代码
    private List<String> missingInfo;      // 缺失信息（需要进一步查看代码）

    public BusinessAnalysis() {}

    public String getUserQuestion() {
        return userQuestion;
    }

    public void setUserQuestion(String userQuestion) {
        this.userQuestion = userQuestion;
    }

    public List<String> getIdentifiedTerms() {
        return identifiedTerms;
    }

    public void setIdentifiedTerms(List<String> identifiedTerms) {
        this.identifiedTerms = identifiedTerms;
    }

    public Map<String, String> getTermExplanations() {
        return termExplanations;
    }

    public void setTermExplanations(Map<String, String> termExplanations) {
        this.termExplanations = termExplanations;
    }

    public List<TermRelation> getRelations() {
        return relations;
    }

    public void setRelations(List<TermRelation> relations) {
        this.relations = relations;
    }

    public List<CodeElement> getRelevantCode() {
        return relevantCode;
    }

    public void setRelevantCode(List<CodeElement> relevantCode) {
        this.relevantCode = relevantCode;
    }

    public List<String> getMissingInfo() {
        return missingInfo;
    }

    public void setMissingInfo(List<String> missingInfo) {
        this.missingInfo = missingInfo;
    }
}
