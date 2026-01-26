package com.smancode.smanagent.common.service_discovery.model;

import java.time.Instant;

/**
 * 服务实例
 */
public class ServiceInstance {
    private String projectKey;
    private String host;
    private int port;
    private String description;
    private Status status;
    private Instant registeredAt;
    private Instant lastHeartbeat;

    public enum Status {
        UP,    // 可用（expert_consult 可用）
        DOWN   // 不可用（expert_consult 不可用）
    }

    public String getProjectKey() {
        return projectKey;
    }

    public void setProjectKey(String projectKey) {
        this.projectKey = projectKey;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public Instant getRegisteredAt() {
        return registeredAt;
    }

    public void setRegisteredAt(Instant registeredAt) {
        this.registeredAt = registeredAt;
    }

    public Instant getLastHeartbeat() {
        return lastHeartbeat;
    }

    public void setLastHeartbeat(Instant lastHeartbeat) {
        this.lastHeartbeat = lastHeartbeat;
    }

    /**
     * 判断 expert_consult 是否可用
     */
    public boolean isExpertConsultAvailable() {
        return status == Status.UP;
    }
}
