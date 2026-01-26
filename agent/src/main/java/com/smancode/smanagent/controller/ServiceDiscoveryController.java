package com.smancode.smanagent.controller;

import com.smancode.smanagent.common.service_discovery.api.ServiceRegistry;
import com.smancode.smanagent.common.service_discovery.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * 服务发现 Controller
 *
 * 提供 Knowledge 服务注册、注销、查询 API
 */
@RestController
@RequestMapping("/api/agent")
public class ServiceDiscoveryController {

    private final Logger logger = LoggerFactory.getLogger(ServiceDiscoveryController.class);

    @Autowired
    private ServiceRegistry serviceRegistry;

    /**
     * 注册服务
     *
     * POST /api/agent/register
     */
    @PostMapping("/register")
    public ResponseEntity<Map<String, Object>> register(@RequestBody RegisterRequest request) {
        logger.info("收到服务注册请求: projectKey={}, host={}, port={}",
            request.getProjectKey(), request.getHost(), request.getPort());

        ServiceInstance instance = new ServiceInstance();
        instance.setProjectKey(request.getProjectKey());
        instance.setHost(request.getHost());
        instance.setPort(request.getPort());
        instance.setDescription(request.getDescription());

        RegisterResult result = serviceRegistry.register(instance);

        Map<String, Object> response = new HashMap<>();
        response.put("success", result.isSuccess());
        response.put("message", result.getMessage());

        if (result.isSuccess()) {
            Map<String, Object> data = new HashMap<>();
            data.put("projectKey", result.getData().getProjectKey());
            data.put("status", result.getData().getStatus());
            data.put("registeredAt", result.getData().getRegisteredAt());
            response.put("data", data);
        } else {
            response.put("errorCode", "REGISTRATION_FAILED");
        }

        return ResponseEntity.ok(response);
    }

    /**
     * 注销服务
     *
     * POST /api/agent/unregister
     */
    @PostMapping("/unregister")
    public ResponseEntity<Map<String, Object>> unregister(@RequestBody UnregisterRequest request) {
        logger.info("收到服务注销请求: projectKey={}", request.getProjectKey());

        serviceRegistry.unregister(request.getProjectKey());

        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("message", "服务注销成功");

        return ResponseEntity.ok(response);
    }

    /**
     * 查询服务
     *
     * GET /api/agent/services/{projectKey}
     */
    @GetMapping("/services/{projectKey}")
    public ResponseEntity<Map<String, Object>> getService(@PathVariable String projectKey) {
        logger.debug("查询服务: projectKey={}", projectKey);

        return serviceRegistry.getService(projectKey)
            .map(instance -> {
                Map<String, Object> data = new HashMap<>();
                data.put("projectKey", instance.getProjectKey());
                data.put("host", instance.getHost());
                data.put("port", instance.getPort());
                data.put("status", instance.getStatus());
                data.put("lastHeartbeat", instance.getLastHeartbeat());
                data.put("expertConsultAvailable", instance.isExpertConsultAvailable());

                Map<String, Object> response = new HashMap<>();
                response.put("success", true);
                response.put("data", data);

                return ResponseEntity.ok(response);
            })
            .orElseGet(() -> {
                Map<String, Object> response = new HashMap<>();
                response.put("success", false);
                response.put("message", "服务不存在: " + projectKey);
                response.put("errorCode", "SERVICE_NOT_FOUND");

                return ResponseEntity.status(404).body(response);
            });
    }

    /**
     * 列出所有服务
     *
     * GET /api/agent/services
     */
    @GetMapping("/services")
    public ResponseEntity<Map<String, Object>> getAllServices() {
        logger.debug("查询所有服务");

        var services = serviceRegistry.getAllServices().stream()
            .map(instance -> {
                Map<String, Object> data = new HashMap<>();
                data.put("projectKey", instance.getProjectKey());
                data.put("host", instance.getHost());
                data.put("port", instance.getPort());
                data.put("status", instance.getStatus());
                data.put("expertConsultAvailable", instance.isExpertConsultAvailable());
                return data;
            })
            .toList();

        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("data", services);

        return ResponseEntity.ok(response);
    }

    /**
     * Knowledge 服务健康检查
     *
     * GET /api/agent/knowledge-health
     */
    @GetMapping("/knowledge-health")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> health = new HashMap<>();
        health.put("status", "UP");
        health.put("serviceCount", serviceRegistry.getAllServices().size());

        long availableCount = serviceRegistry.getAllServices().stream()
            .filter(ServiceInstance::isExpertConsultAvailable)
            .count();

        health.put("availableCount", (int) availableCount);

        return ResponseEntity.ok(health);
    }
}
