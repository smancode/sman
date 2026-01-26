package com.smancode.smanagent.common.service_discovery.model;

/**
 * 服务注销请求
 */
public class UnregisterRequest {
    private String projectKey;

    public String getProjectKey() {
        return projectKey;
    }

    public void setProjectKey(String projectKey) {
        this.projectKey = projectKey;
    }
}
