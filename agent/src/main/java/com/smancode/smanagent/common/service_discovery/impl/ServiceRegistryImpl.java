package com.smancode.smanagent.common.service_discovery.impl;

import com.smancode.smanagent.common.service_discovery.api.HealthCheckService;
import com.smancode.smanagent.common.service_discovery.api.ServiceRegistry;
import com.smancode.smanagent.common.service_discovery.model.ProjectConfig;
import com.smancode.smanagent.common.service_discovery.model.RegisterResult;
import com.smancode.smanagent.common.service_discovery.model.ServiceInstance;
import com.smancode.smanagent.config.KnowledgeGraphDiscoveryConfig;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Knowledge Graph 服务注册表实现
 * <p>
 * 负责管理多个 Knowledge Graph 服务实例的注册、健康检查和状态维护
 */
@Service
public class ServiceRegistryImpl implements ServiceRegistry {

    private static final Logger logger = LoggerFactory.getLogger(ServiceRegistryImpl.class);

    private final Map<String, ServiceInstance> registry = new ConcurrentHashMap<>();

    @Autowired
    private KnowledgeGraphDiscoveryConfig config;

    @Autowired
    private HealthCheckService healthCheckService;

    /**
     * 启动时探测所有配置的服务
     */
    @PostConstruct
    public Map<String, Boolean> probeAllServices() {
        if (!config.isEnabled()) {
            logger.info("Knowledge Graph 服务发现未启用，跳过启动探测");
            return Collections.emptyMap();
        }

        logger.info("开始启动探测 Knowledge Graph 服务...");
        Map<String, Boolean> results = new HashMap<>();

        for (ProjectConfig project : config.getKnowledgeGraphServices()) {
            boolean healthy = healthCheckService.checkHealth(project.getHost(), project.getPort());
            ServiceInstance instance = createServiceInstance(project, healthy);
            registry.put(project.getKey(), instance);
            results.put(project.getKey(), healthy);

            logProbeResult(project, healthy);
        }

        logProbeSummary(results);
        return results;
    }

    @Override
    public RegisterResult register(ServiceInstance instance) {
        if (!config.isProjectDefined(instance.getProjectKey())) {
            return RegisterResult.failure("Knowledge Graph 项目未在配置文件中定义");
        }

        instance.setStatus(ServiceInstance.Status.UP);
        instance.setRegisteredAt(Instant.now());
        instance.setLastHeartbeat(Instant.now());
        registry.put(instance.getProjectKey(), instance);

        logger.info("Knowledge Graph 服务注册成功: projectKey={}, host={}, port={}",
            instance.getProjectKey(), instance.getHost(), instance.getPort());

        return RegisterResult.success(instance);
    }

    @Override
    public void unregister(String projectKey) {
        ServiceInstance removed = registry.remove(projectKey);
        if (removed != null) {
            logger.info("Knowledge Graph 服务注销成功: projectKey={}", projectKey);
        }
    }

    @Override
    public Optional<ServiceInstance> getService(String projectKey) {
        return Optional.ofNullable(registry.get(projectKey));
    }

    @Override
    public boolean isExpertConsultAvailable(String projectKey) {
        ServiceInstance instance = registry.get(projectKey);
        return instance != null && instance.getStatus() == ServiceInstance.Status.UP;
    }

    @Override
    public List<ServiceInstance> getAllServices() {
        return new ArrayList<>(registry.values());
    }

    /**
     * 定时心跳探测（每5分钟）
     */
    @Scheduled(fixedDelay = 300000)
    public void heartbeatCheck() {
        if (!config.isEnabled()) {
            return;
        }

        for (ServiceInstance instance : registry.values()) {
            boolean healthy = healthCheckService.checkHealth(instance.getHost(), instance.getPort());
            updateServiceStatus(instance, healthy);
        }
    }

    // ==================== 私有辅助方法 ====================

    /**
     * 创建服务实例
     */
    private ServiceInstance createServiceInstance(ProjectConfig project, boolean healthy) {
        ServiceInstance instance = new ServiceInstance();
        instance.setProjectKey(project.getKey());
        instance.setHost(project.getHost());
        instance.setPort(project.getPort());
        instance.setDescription(project.getDescription());
        instance.setStatus(healthy ? ServiceInstance.Status.UP : ServiceInstance.Status.DOWN);
        instance.setRegisteredAt(Instant.now());
        instance.setLastHeartbeat(Instant.now());
        return instance;
    }

    /**
     * 更新服务状态
     */
    private void updateServiceStatus(ServiceInstance instance, boolean healthy) {
        ServiceInstance.Status oldStatus = instance.getStatus();
        ServiceInstance.Status newStatus = healthy ? ServiceInstance.Status.UP : ServiceInstance.Status.DOWN;

        instance.setStatus(newStatus);
        instance.setLastHeartbeat(Instant.now());

        if (oldStatus != newStatus) {
            logger.info("Knowledge Graph 服务状态变更: projectKey={}, {} → {}, expert_consult={}",
                instance.getProjectKey(), oldStatus, newStatus,
                newStatus == ServiceInstance.Status.UP ? "可用" : "不可用");
        }
    }

    /**
     * 记录探测结果
     */
    private void logProbeResult(ProjectConfig project, boolean healthy) {
        if (healthy) {
            logger.info("启动探测成功: projectKey={}, host={}, port={}",
                project.getKey(), project.getHost(), project.getPort());
        } else {
            logger.warn("启动探测失败: projectKey={}, host={}, port={}, expert_consult 不可用",
                project.getKey(), project.getHost(), project.getPort());
        }
    }

    /**
     * 记录探测汇总
     */
    private void logProbeSummary(Map<String, Boolean> results) {
        int successCount = (int) results.values().stream().filter(b -> b).count();
        int failCount = results.size() - successCount;

        logger.info("启动探测完成: 总数={}, 成功={}, 失败={}",
            results.size(), successCount, failCount);
    }
}
