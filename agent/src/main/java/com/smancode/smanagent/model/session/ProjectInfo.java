package com.smancode.smanagent.model.session;

/**
 * 项目信息
 */
public class ProjectInfo {

    /**
     * 项目 Key
     */
    private String projectKey;

    /**
     * 项目路径
     */
    private String projectPath;

    /**
     * 项目描述
     */
    private String description;

    public ProjectInfo() {
    }

    public ProjectInfo(String projectKey, String projectPath, String description) {
        this.projectKey = projectKey;
        this.projectPath = projectPath;
        this.description = description;
    }

    public String getProjectKey() {
        return projectKey;
    }

    public void setProjectKey(String projectKey) {
        this.projectKey = projectKey;
    }

    public String getProjectPath() {
        return projectPath;
    }

    public void setProjectPath(String projectPath) {
        this.projectPath = projectPath;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }
}
