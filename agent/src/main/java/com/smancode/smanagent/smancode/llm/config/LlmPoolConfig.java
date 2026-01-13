package com.smancode.smanagent.smancode.llm.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * LLM 端点池配置
 */
@Component
@ConfigurationProperties(prefix = "llm.pool")
public class LlmPoolConfig {

    /**
     * 端点列表
     */
    private List<LlmEndpoint> endpoints = new ArrayList<>();

    /**
     * 重试策略
     */
    private LlmRetryPolicy retry = new LlmRetryPolicy();

    /**
     * Round-Robin 轮询索引
     */
    private final AtomicInteger roundRobinIndex = new AtomicInteger(0);

    public List<LlmEndpoint> getEndpoints() {
        return endpoints;
    }

    public void setEndpoints(List<LlmEndpoint> endpoints) {
        this.endpoints = endpoints;
    }

    public LlmRetryPolicy getRetry() {
        return retry;
    }

    public void setRetry(LlmRetryPolicy retry) {
        this.retry = retry;
    }

    /**
     * 获取下一个可用端点（Round-Robin + 故障过滤）
     *
     * @return 可用端点，如果全部不可用返回 null
     */
    public LlmEndpoint getNextAvailableEndpoint() {
        List<LlmEndpoint> enabledEndpoints = endpoints.stream()
                .filter(LlmEndpoint::isEnabled)
                .toList();

        if (enabledEndpoints.isEmpty()) {
            return null;
        }

        int attempts = 0;
        int totalEndpoints = enabledEndpoints.size();

        while (attempts < totalEndpoints) {
            int index = Math.abs(roundRobinIndex.getAndIncrement() % totalEndpoints);
            LlmEndpoint endpoint = enabledEndpoints.get(index);

            if (endpoint.isAvailable()) {
                return endpoint;
            }

            // 检查是否已过冷却期（使用重试策略的 base-delay）
            if (endpoint.isCooldownOver(retry.getBaseDelay())) {
                // 冷却期已过，尝试重置可用状态
                endpoint.markSuccess();
                return endpoint;
            }

            attempts++;
        }

        // 所有端点都不可用
        return null;
    }

    /**
     * 获取所有启用的端点
     */
    public List<LlmEndpoint> getEnabledEndpoints() {
        return endpoints.stream()
                .filter(LlmEndpoint::isEnabled)
                .toList();
    }

    /**
     * 标记所有端点为可用（用于服务恢复）
     */
    public void resetAllEndpoints() {
        endpoints.forEach(LlmEndpoint::markSuccess);
    }
}
