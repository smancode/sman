package com.smancode.smanagent.service;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.smancode.smanagent.cache.ProjectCacheService;
import com.smancode.smanagent.model.cache.ChangeDetectionResult;

/**
 * 语义搜索服务
 *
 * 功能：
 * - 语义搜索接口
 * - 集成项目缓存自动刷新
 *
 * @since 1.0.0
 */
@Service
public class SemanticSearchService {

    private static final Logger logger = LoggerFactory.getLogger(SemanticSearchService.class);

    @Autowired(required = false)
    private ProjectCacheService projectCacheService;

    @Value("${project.path:}")
    private String projectPath;

    @Value("${project.key:default}")
    private String projectKey;

    /**
     * 初始化（启动时调用）
     */
    public void initialize() {
        logger.info("🚀 初始化语义搜索服务");

        if (projectCacheService != null && !projectPath.isEmpty()) {
            projectCacheService.initialize(projectPath, projectKey);
        }

        logger.info("✅ 语义搜索服务初始化完成");
    }

    /**
     * 语义搜索
     *
     * @param query 搜索查询
     * @param topK 返回结果数量
     * @return 搜索结果
     */
    public List<String> semanticSearch(String query, int topK) {
        logger.debug("🔍 语义搜索: query={}, topK={}", query, topK);

        // TODO: 实现实际的语义搜索逻辑
        // 这里只是示例，实际需要调用向量搜索引擎

        return List.of("result1", "result2");
    }

    /**
     * 获取缓存统计信息
     */
    public Object getCacheStatistics() {
        if (projectCacheService != null) {
            return projectCacheService.getCacheStatistics(projectPath);
        }
        return "缓存服务未启用";
    }

    /**
     * 手动刷新缓存
     */
    public ChangeDetectionResult refreshCache() {
        if (projectCacheService != null) {
            return projectCacheService.detectAndRefresh(projectPath);
        }
        return createNoChangeResult();
    }

    /**
     * 强制刷新缓存
     */
    public ChangeDetectionResult forceRefreshCache() {
        if (projectCacheService != null) {
            return projectCacheService.forceRefresh(projectPath);
        }
        return createNoChangeResult();
    }

    private ChangeDetectionResult createNoChangeResult() {
        ChangeDetectionResult result = new ChangeDetectionResult();
        result.setHasChanges(false);
        result.setSummary("缓存服务未启用");
        result.buildSummary();
        return result;
    }
}
