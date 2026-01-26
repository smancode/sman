package com.smancode.smanagent.common.service_discovery.api;

import com.smancode.smanagent.common.service_discovery.model.RegisterResult;
import com.smancode.smanagent.common.service_discovery.model.ServiceInstance;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 服务注册表接口
 *
 * 核心功能：
 * 1. 探测服务健康状态
 * 2. 注册/注销服务
 * 3. 判断 expert_consult 是否可用
 */
public interface ServiceRegistry {

    /**
     * 启动时探测所有配置的服务
     *
     * @return 探测结果（projectKey -> 是否健康）
     */
    Map<String, Boolean> probeAllServices();

    /**
     * 注册服务
     *
     * @param instance 服务实例
     * @return 注册结果
     */
    RegisterResult register(ServiceInstance instance);

    /**
     * 注销服务
     *
     * @param projectKey 项目标识
     */
    void unregister(String projectKey);

    /**
     * 获取服务实例
     *
     * @param projectKey 项目标识
     * @return 服务实例（如果存在）
     */
    Optional<ServiceInstance> getService(String projectKey);

    /**
     * 判断 expert_consult 是否可用
     *
     * @param projectKey 项目标识
     * @return true=可用, false=不可用
     */
    boolean isExpertConsultAvailable(String projectKey);

    /**
     * 获取所有服务
     *
     * @return 所有已注册的服务
     */
    List<ServiceInstance> getAllServices();
}
