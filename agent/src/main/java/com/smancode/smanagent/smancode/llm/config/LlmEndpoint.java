package com.smancode.smanagent.smancode.llm.config;

/**
 * 单个 LLM 端点配置
 */
public class LlmEndpoint {

    /**
     * API 基础 URL
     */
    private String baseUrl;

    /**
     * API 密钥
     */
    private String apiKey;

    /**
     * 模型名称
     */
    private String model;

    /**
     * 最大 token 数
     */
    private int maxTokens = 8192;

    /**
     * 超时时间（毫秒）
     */
    private long timeout = 60000;

    /**
     * 是否启用
     */
    private boolean enabled = true;

    /**
     * 当前是否可用（用于故障检测）
     */
    private transient volatile boolean available = true;

    /**
     * 上次失败时间（用于冷却）
     */
    private transient volatile long lastFailureTime = 0;

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public int getMaxTokens() {
        return maxTokens;
    }

    public void setMaxTokens(int maxTokens) {
        this.maxTokens = maxTokens;
    }

    public long getTimeout() {
        return timeout;
    }

    public void setTimeout(long timeout) {
        this.timeout = timeout;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isAvailable() {
        return available && enabled;
    }

    public void setAvailable(boolean available) {
        this.available = available;
        if (!available) {
            this.lastFailureTime = System.currentTimeMillis();
        }
    }

    public long getLastFailureTime() {
        return lastFailureTime;
    }

    public void setLastFailureTime(long lastFailureTime) {
        this.lastFailureTime = lastFailureTime;
    }

    /**
     * 标记端点失败
     */
    public void markFailed() {
        this.available = false;
        this.lastFailureTime = System.currentTimeMillis();
    }

    /**
     * 标记端点成功
     */
    public void markSuccess() {
        this.available = true;
        this.lastFailureTime = 0;
    }

    /**
     * 检查端点是否已过冷却期
     *
     * @param cooldownMs 冷却时间（毫秒）
     * @return 是否可重试
     */
    public boolean isCooldownOver(long cooldownMs) {
        if (available) {
            return true;
        }
        return System.currentTimeMillis() - lastFailureTime >= cooldownMs;
    }

    @Override
    public String toString() {
        return "LlmEndpoint{" +
                "baseUrl='" + baseUrl + '\'' +
                ", model='" + model + '\'' +
                ", enabled=" + enabled +
                ", available=" + available +
                '}';
    }
}
