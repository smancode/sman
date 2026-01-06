package ai.smancode.sman.agent.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * 项目配置控制器
 *
 * 提供 REST API 用于：
 * - 查询所有项目配置
 * - 查询单个项目配置
 * - 添加/更新项目配置
 * - 删除项目配置
 *
 * @author SiliconMan Team
 * @since 2.0
 */
@RestController
@RequestMapping("/api/config/projects")
public class ProjectConfigController {

    private static final Logger log = LoggerFactory.getLogger(ProjectConfigController.class);

    @Autowired
    private ProjectConfigService projectConfigService;

    /**
     * 获取所有项目配置
     */
    @GetMapping
    public Map<String, Object> getAllProjects() {
        log.debug("📋 查询所有项目配置");

        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("projects", projectConfigService.getProjects());
        response.put("count", projectConfigService.getAllProjectKeys().size());
        response.put("projectKeys", projectConfigService.getAllProjectKeys());

        return response;
    }

    /**
     * 获取单个项目配置
     */
    @GetMapping("/{projectKey}")
    public Map<String, Object> getProject(@PathVariable String projectKey) {
        log.debug("📋 查询项目配置: projectKey={}", projectKey);

        ProjectConfigService.ProjectConfig config = projectConfigService.getProjectConfig(projectKey);

        if (config == null) {
            Map<String, Object> response = new HashMap<>();
            response.put("success", false);
            response.put("error", "未找到项目: " + projectKey);
            return response;
        }

        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("projectKey", projectKey);
        response.put("config", config);

        return response;
    }

    /**
     * 添加/更新项目配置
     */
    @PostMapping("/{projectKey}")
    public Map<String, Object> upsertProject(
        @PathVariable String projectKey,
        @RequestBody ProjectConfigService.ProjectConfig config
    ) {
        log.info("💾 保存项目配置: projectKey={}, projectPath={}",
            projectKey, config.getProjectPath());

        // 验证配置
        if (config.getProjectPath() == null || config.getProjectPath().isEmpty()) {
            Map<String, Object> response = new HashMap<>();
            response.put("success", false);
            response.put("error", "projectPath 不能为空");
            return response;
        }

        // 保存配置（注意：这只是内存操作，重启后会丢失）
        projectConfigService.addOrUpdateProject(projectKey, config);

        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("message", "项目配置已更新（内存保存，重启后丢失）");
        response.put("projectKey", projectKey);
        response.put("config", config);

        return response;
    }

    /**
     * 删除项目配置
     */
    @DeleteMapping("/{projectKey}")
    public Map<String, Object> deleteProject(@PathVariable String projectKey) {
        log.info("🗑️  删除项目配置: projectKey={}", projectKey);

        if (!projectConfigService.hasProject(projectKey)) {
            Map<String, Object> response = new HashMap<>();
            response.put("success", false);
            response.put("error", "未找到项目: " + projectKey);
            return response;
        }

        projectConfigService.removeProject(projectKey);

        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("message", "项目配置已删除（内存删除，重启后恢复）");
        response.put("projectKey", projectKey);

        return response;
    }
}
