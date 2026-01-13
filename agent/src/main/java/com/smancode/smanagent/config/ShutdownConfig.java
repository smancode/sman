package com.smancode.smanagent.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import com.smancode.smanagent.cache.ProjectCacheService;

import jakarta.annotation.PreDestroy;

/**
 * 应用优雅关闭配置
 *
 * 功能：
 * - 监听 JVM 关闭事件
 * - 持久化项目缓存到 data/
 * - 记录关闭日志
 *
 * @since 1.0.0
 */
@Configuration
public class ShutdownConfig {

    private static final Logger logger = LoggerFactory.getLogger(ShutdownConfig.class);

    @Autowired(required = false)
    private ProjectCacheService projectCacheService;

    /**
     * 项目路径
     */
    @Value("${project.path:}")
    private String projectPath;

    /**
     * 应用关闭前的清理工作
     * 由 Spring 容器在关闭时自动调用
     */
    @PreDestroy
    public void onShutdown() {
        logger.info("========================================");
        logger.info("🛑 SmanAgent 正在关闭...");
        logger.info("========================================");

        // 持久化项目缓存
        if (projectCacheService != null && projectPath != null && !projectPath.isEmpty()) {
            try {
                logger.info("💾 持久化项目缓存: projectPath={}", projectPath);
                projectCacheService.persistCache(projectPath);
                projectCacheService.shutdown();
            } catch (Exception e) {
                logger.warn("⚠️ 持久化项目缓存失败: {}", e.getMessage());
            }
        }

        logger.info("========================================");
        logger.info("✅ SmanAgent 已关闭");
        logger.info("========================================");
    }
}
