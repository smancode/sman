package ai.smancode.smanagent.model;

/**
 * 术语关系
 */
public class TermRelation {
    private String fromTerm;      // 源术语
    private String relation;      // 关系类型：HAS_A, IS_A, BELONGS_TO, CALLS, IMPLEMENTS
    private String toTerm;        // 目标术语
    private Double confidence;    // 置信度
    private CodeElement evidence; // 证据（代码元素）

    public TermRelation() {}

    public TermRelation(String fromTerm, String relation, String toTerm) {
        this.fromTerm = fromTerm;
        this.relation = relation;
        this.toTerm = toTerm;
    }

    // Getters and Setters
    public String getFromTerm() {
        return fromTerm;
    }

    public void setFromTerm(String fromTerm) {
        this.fromTerm = fromTerm;
    }

    public String getRelation() {
        return relation;
    }

    public void setRelation(String relation) {
        this.relation = relation;
    }

    public String getToTerm() {
        return toTerm;
    }

    public void setToTerm(String toTerm) {
        this.toTerm = toTerm;
    }

    public Double getConfidence() {
        return confidence;
    }

    public void setConfidence(Double confidence) {
        this.confidence = confidence;
    }

    public CodeElement getEvidence() {
        return evidence;
    }

    public void setEvidence(CodeElement evidence) {
        this.evidence = evidence;
    }
}
