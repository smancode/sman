package com.smancode.smanagent.smancode.llm.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * LLM 重试策略配置
 */
@Component
@ConfigurationProperties(prefix = "llm.pool.retry")
public class LlmRetryPolicy {

    /**
     * 最大重试次数
     */
    private int maxRetries = 3;

    /**
     * 基础延迟时间（毫秒）
     */
    private long baseDelay = 10000; // 10秒

    public int getMaxRetries() {
        return maxRetries;
    }

    public void setMaxRetries(int maxRetries) {
        this.maxRetries = maxRetries;
    }

    public long getBaseDelay() {
        return baseDelay;
    }

    public void setBaseDelay(long baseDelay) {
        this.baseDelay = baseDelay;
    }

    /**
     * 计算指数退避延迟时间
     *
     * @param retryAttempt 重试次数（从 0 开始）
     * @return 延迟时间（毫秒）
     */
    public long calculateDelay(int retryAttempt) {
        // 指数退避：10s, 20s, 30s
        return baseDelay * (retryAttempt + 1);
    }
}
