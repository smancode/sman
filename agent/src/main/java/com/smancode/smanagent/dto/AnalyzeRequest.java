package com.smancode.smanagent.dto;

/**
 * 分析项目请求
 */
public class AnalyzeRequest {

    /**
     * 项目路径
     */
    private String projectPath;

    public AnalyzeRequest() {
    }

    public String getProjectPath() {
        return projectPath;
    }

    public void setProjectPath(String projectPath) {
        this.projectPath = projectPath;
    }
}
