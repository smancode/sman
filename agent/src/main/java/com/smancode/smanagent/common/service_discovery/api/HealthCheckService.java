package com.smancode.smanagent.common.service_discovery.api;

/**
 * 健康检查服务接口
 *
 * 核心功能：
 * 1. HTTP 健康检查
 * 2. 超时控制
 * 3. 异常处理
 */
public interface HealthCheckService {

    /**
     * 执行健康检查
     *
     * @param host 主机
     * @param port 端口
     * @param timeoutMs 超时时间（毫秒）
     * @return true=健康, false=不健康
     */
    boolean checkHealth(String host, int port, int timeoutMs);

    /**
     * 执行健康检查（使用默认超时5秒）
     *
     * @param host 主机
     * @param port 端口
     * @return true=健康, false=不健康
     */
    default boolean checkHealth(String host, int port) {
        return checkHealth(host, port, 5000);
    }
}
