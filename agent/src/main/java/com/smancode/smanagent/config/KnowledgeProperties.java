package com.smancode.smanagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Knowledge 服务配置属性
 */
@Component
@ConfigurationProperties(prefix = "knowledge.service")
public class KnowledgeProperties {

    /**
     * Knowledge 服务基础 URL
     */
    private String baseUrl = "http://localhost:8081";

    /**
     * 搜索接口路径
     */
    private String searchPath = "/api/knowledge/search";

    /**
     * 超时时间（毫秒）
     */
    private int timeout = 30000;

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getSearchPath() {
        return searchPath;
    }

    public void setSearchPath(String searchPath) {
        this.searchPath = searchPath;
    }

    public int getTimeout() {
        return timeout;
    }

    public void setTimeout(int timeout) {
        this.timeout = timeout;
    }

    /**
     * 获取完整的搜索 URL
     */
    public String getSearchUrl() {
        return baseUrl + searchPath;
    }
}
