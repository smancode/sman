package ai.smancode.smanagent.model;

/**
 * 代码元素
 */
public class CodeElement {
    private String type;      // CLASS, METHOD, FIELD
    private String name;      // 元素名称
    private String signature; // 完整签名（方法）或限定名（类）
    private String filePath;  // 文件路径
    private Integer startLine;// 起始行
    private Integer endLine;  // 结束行
    private String code;      // 代码片段

    public CodeElement() {}

    public CodeElement(String type, String name, String signature, String filePath, Integer startLine) {
        this.type = type;
        this.name = name;
        this.signature = signature;
        this.filePath = filePath;
        this.startLine = startLine;
    }

    // Getters and Setters
    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getSignature() {
        return signature;
    }

    public void setSignature(String signature) {
        this.signature = signature;
    }

    public String getFilePath() {
        return filePath;
    }

    public void setFilePath(String filePath) {
        this.filePath = filePath;
    }

    public Integer getStartLine() {
        return startLine;
    }

    public void setStartLine(Integer startLine) {
        this.startLine = startLine;
    }

    public Integer getEndLine() {
        return endLine;
    }

    public void setEndLine(Integer endLine) {
        this.endLine = endLine;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }
}
