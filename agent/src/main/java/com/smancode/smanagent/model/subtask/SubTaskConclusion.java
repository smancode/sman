package com.smancode.smanagent.model.subtask;

import java.time.Instant;
import java.util.Collections;
import java.util.List;

/**
 * SubTask 结论
 * <p>
 * 记录 SubTask 的执行结果，包括结论、证据、迭代次数等。
 */
public class SubTaskConclusion {

    /**
     * SubTask ID
     */
    private String subTaskId;

    /**
     * 目标对象
     */
    private String target;

    /**
     * 要回答的问题
     */
    private String question;

    /**
     * 结论（LLM 生成）
     */
    private String conclusion;

    /**
     * 支持证据（文件路径、知识 ID 等）
     */
    private List<String> evidence;

    /**
     * 实际内部迭代次数
     */
    private Integer internalIterations;

    /**
     * 完成时间
     */
    private Instant completedAt;

    public SubTaskConclusion() {
        this.evidence = Collections.emptyList();
        this.internalIterations = 0;
    }

    public SubTaskConclusion(String subTaskId, String target, String question) {
        this.subTaskId = subTaskId;
        this.target = target;
        this.question = question;
        this.evidence = Collections.emptyList();
        this.internalIterations = 0;
        this.completedAt = Instant.now();
    }

    public String getSubTaskId() {
        return subTaskId;
    }

    public void setSubTaskId(String subTaskId) {
        this.subTaskId = subTaskId;
    }

    public String getTarget() {
        return target;
    }

    public void setTarget(String target) {
        this.target = target;
    }

    public String getQuestion() {
        return question;
    }

    public void setQuestion(String question) {
        this.question = question;
    }

    public String getConclusion() {
        return conclusion;
    }

    public void setConclusion(String conclusion) {
        this.conclusion = conclusion;
    }

    public List<String> getEvidence() {
        return evidence;
    }

    public void setEvidence(List<String> evidence) {
        this.evidence = evidence != null ? evidence : Collections.emptyList();
    }

    public Integer getInternalIterations() {
        return internalIterations;
    }

    public void setInternalIterations(Integer internalIterations) {
        this.internalIterations = internalIterations != null ? internalIterations : 0;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(Instant completedAt) {
        this.completedAt = completedAt;
    }

    /**
     * 判断是否有结论
     */
    public boolean hasConclusion() {
        return conclusion != null && !conclusion.isEmpty();
    }

    /**
     * 判断是否有证据
     */
    public boolean hasEvidence() {
        return evidence != null && !evidence.isEmpty();
    }

    /**
     * 获取证据数量
     */
    public int getEvidenceCount() {
        return evidence != null ? evidence.size() : 0;
    }

    /**
     * 添加证据
     */
    public void addEvidence(String evidenceItem) {
        if (evidenceItem != null && !evidenceItem.isEmpty()) {
            if (this.evidence == null || this.evidence.isEmpty()) {
                this.evidence = Collections.singletonList(evidenceItem);
            } else {
                // 如果是不可变列表，创建新列表
                if (this.evidence.size() == 1) {
                    this.evidence = new java.util.ArrayList<>(this.evidence);
                }
                this.evidence.add(evidenceItem);
            }
        }
    }
}
