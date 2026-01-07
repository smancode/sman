package ai.smancode.sman.agent.config;

import ai.smancode.sman.agent.utils.PathUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 项目配置服务
 *
 * 功能：
 * - 管理 projectKey → projectPath 映射
 * - 提供项目配置查询接口
 * - 支持动态配置更新
 *
 * @author SiliconMan Team
 * @since 2.0
 */
@Service
@ConfigurationProperties(prefix = "agent")
public class ProjectConfigService {

    private static final Logger log = LoggerFactory.getLogger(ProjectConfigService.class);

    private Map<String, ProjectConfig> projects;

    /**
     * 获取 projectPath (自动规范化路径以支持 Windows Git Bash)
     */
    public String getProjectPath(String projectKey) {
        ProjectConfig config = projects.get(projectKey);

        if (config == null) {
            throw new IllegalArgumentException(
                "未找到 projectKey 映射: " + projectKey + "\n" +
                "请检查 application.yml 中的 agent.projects 配置\n" +
                "可用的 projectKeys: " + getAllProjectKeys()
            );
        }

        String originalPath = config.getProjectPath();

        // 🔥 调试：检查原始路径
        log.info("📋 [ProjectConfigService] 查询 projectPath");
        log.info("   projectKey: \"{}\"", projectKey);
        log.info("   originalPath: \"{}\"", originalPath);
        log.info("   originalPath.length: {}", originalPath != null ? originalPath.length() : "null");
        log.info("   originalPath.bytes: {}", originalPath != null ? java.util.Arrays.toString(originalPath.getBytes(java.nio.charset.StandardCharsets.UTF_8)) : "null");

        String normalizedPath = PathUtils.normalizePath(originalPath);

        log.info("   normalizedPath: \"{}\"", normalizedPath);
        log.info("   当前系统: os.name=\"{}\"", System.getProperty("os.name"));

        // 🔥 尝试直接检测
        File testFile = new File(normalizedPath);
        log.info("   File.exists(): {}", testFile.exists());
        log.info("   File.getAbsolutePath(): {}", testFile.getAbsolutePath());

        return normalizedPath;
    }

    /**
     * 获取项目配置
     */
    public ProjectConfig getProjectConfig(String projectKey) {
        return projects.get(projectKey);
    }

    /**
     * 检查项目是否存在
     */
    public boolean hasProject(String projectKey) {
        return projects.containsKey(projectKey);
    }

    /**
     * 获取所有 projectKey
     */
    public List<String> getAllProjectKeys() {
        if (projects == null) {
            return new ArrayList<>();
        }
        return new ArrayList<>(projects.keySet());
    }

    /**
     * 添加或更新项目配置
     */
    public void addOrUpdateProject(String projectKey, ProjectConfig config) {
        if (projects == null) {
            projects = new java.util.HashMap<>();
        }
        projects.put(projectKey, config);
        log.info("✅ 项目配置已更新: projectKey={}, projectPath={}",
            projectKey, config.getProjectPath());
    }

    /**
     * 删除项目配置
     */
    public void removeProject(String projectKey) {
        if (projects != null) {
            ProjectConfig removed = projects.remove(projectKey);
            if (removed != null) {
                log.info("✅ 项目配置已删除: projectKey={}", projectKey);
            }
        }
    }

    // Getters and Setters
    public Map<String, ProjectConfig> getProjects() {
        return projects;
    }

    public void setProjects(Map<String, ProjectConfig> projects) {
        this.projects = projects;
        log.info("✅ 项目配置已加载: {} 个项目", projects.size());
    }

    /**
     * 项目配置
     */
    public static class ProjectConfig {
        private String projectPath;
        private String description;
        private String language;
        private String version;

        // Getters and Setters
        public String getProjectPath() { return projectPath; }
        public void setProjectPath(String projectPath) { this.projectPath = projectPath; }

        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }

        public String getLanguage() { return language; }
        public void setLanguage(String language) { this.language = language; }

        public String getVersion() { return version; }
        public void setVersion(String version) { this.version = version; }

        @Override
        public String toString() {
            return "ProjectConfig{" +
                "projectPath='" + projectPath + '\'' +
                ", description='" + description + '\'' +
                ", language='" + language + '\'' +
                ", version='" + version + '\'' +
                '}';
        }
    }
}
