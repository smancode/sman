package com.smancode.smanagent.config;

import com.smancode.smanagent.common.service_discovery.model.ProjectConfig;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Knowledge Graph 服务发现配置
 * <p>
 * 用于配置多个 Knowledge Graph 实例的服务发现和健康检查
 */
@Component
@ConfigurationProperties(prefix = "service-discovery")
public class KnowledgeGraphDiscoveryConfig {

    // ==================== 常量 ====================

    private static final String DEFAULT_PROJECT_KEY = "autoloop";
    private static final String DEFAULT_HOST = "localhost";
    private static final int DEFAULT_PORT = 8081;
    private static final String DEFAULT_DESCRIPTION = "项目分析智能体";

    // ==================== 配置属性 ====================

    private boolean enabled = true;
    private long heartbeatInterval = 300000;  // 5分钟
    private int healthCheckTimeout = 5000;     // 5秒
    private int registrationTimeout = 5000;     // 5秒
    private int searchTimeout = 300000;         // 5分钟
    private String searchPath = "/api/ai/tool-use";

    /**
     * Knowledge Graph 服务配置列表（从 application.yml 读取）
     * key: projectKey
     * value: {host, port, description}
     */
    private Map<String, Map<String, Object>> knowledgeGraphs;

    // ==================== Getter/Setter ====================

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public long getHeartbeatInterval() {
        return heartbeatInterval;
    }

    public void setHeartbeatInterval(long heartbeatInterval) {
        this.heartbeatInterval = heartbeatInterval;
    }

    public int getHealthCheckTimeout() {
        return healthCheckTimeout;
    }

    public void setHealthCheckTimeout(int healthCheckTimeout) {
        this.healthCheckTimeout = healthCheckTimeout;
    }

    public int getRegistrationTimeout() {
        return registrationTimeout;
    }

    public void setRegistrationTimeout(int registrationTimeout) {
        this.registrationTimeout = registrationTimeout;
    }

    public int getSearchTimeout() {
        return searchTimeout;
    }

    public void setSearchTimeout(int searchTimeout) {
        this.searchTimeout = searchTimeout;
    }

    public String getSearchPath() {
        return searchPath;
    }

    public void setSearchPath(String searchPath) {
        this.searchPath = searchPath;
    }

    public Map<String, Map<String, Object>> getKnowledgeGraphs() {
        return knowledgeGraphs;
    }

    public void setKnowledgeGraphs(Map<String, Map<String, Object>> knowledgeGraphs) {
        this.knowledgeGraphs = knowledgeGraphs;
    }

    // ==================== 业务方法 ====================

    /**
     * 获取所有配置的 Knowledge Graph 服务
     */
    public List<ProjectConfig> getKnowledgeGraphServices() {
        if (knowledgeGraphs == null || knowledgeGraphs.isEmpty()) {
            return List.of(createDefaultConfig());
        }

        List<ProjectConfig> projectConfigs = new ArrayList<>(knowledgeGraphs.size());
        for (Map.Entry<String, Map<String, Object>> entry : knowledgeGraphs.entrySet()) {
            projectConfigs.add(createProjectConfig(entry.getKey(), entry.getValue()));
        }
        return projectConfigs;
    }

    /**
     * 判断项目是否在配置文件中定义
     */
    public boolean isProjectDefined(String projectKey) {
        if (knowledgeGraphs == null || knowledgeGraphs.isEmpty()) {
            return DEFAULT_PROJECT_KEY.equals(projectKey);
        }
        return knowledgeGraphs.containsKey(projectKey);
    }

    /**
     * 获取项目配置
     */
    public ProjectConfig getProjectConfig(String projectKey) {
        return getKnowledgeGraphServices().stream()
            .filter(config -> config.getKey().equals(projectKey))
            .findFirst()
            .orElse(null);
    }

    /**
     * 获取指定 projectKey 的 Knowledge Graph 服务 URL
     *
     * @param projectKey 项目标识
     * @return 完整的搜索 URL（例如：http://localhost:8088/api/ai/tool-use）
     * @throws IllegalArgumentException 如果项目未配置
     */
    public String getKnowledgeGraphServiceUrl(String projectKey) {
        ProjectConfig config = getProjectConfig(projectKey);
        if (config == null) {
            throw new IllegalArgumentException("Knowledge Graph 项目未配置: " + projectKey);
        }
        return String.format("http://%s:%d%s", config.getHost(), config.getPort(), searchPath);
    }

    // ==================== 私有辅助方法 ====================

    /**
     * 创建默认配置
     */
    private ProjectConfig createDefaultConfig() {
        ProjectConfig config = new ProjectConfig();
        config.setKey(DEFAULT_PROJECT_KEY);
        config.setHost(DEFAULT_HOST);
        config.setPort(DEFAULT_PORT);
        config.setDescription(DEFAULT_DESCRIPTION);
        return config;
    }

    /**
     * 从配置创建 ProjectConfig
     */
    private ProjectConfig createProjectConfig(String projectKey, Map<String, Object> configData) {
        ProjectConfig config = new ProjectConfig();
        config.setKey(projectKey);
        config.setHost((String) configData.get("host"));
        config.setPort(((Number) configData.get("port")).intValue());
        config.setDescription((String) configData.get("description"));
        return config;
    }
}
