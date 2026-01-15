package com.smancode.smanagent.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PreDestroy;

/**
 * 应用优雅关闭配置
 *
 * 功能：
 * - 监听 JVM 关闭事件
 * - 记录关闭日志
 *
 * @since 1.0.0
 */
@Configuration
public class ShutdownConfig {

    private static final Logger logger = LoggerFactory.getLogger(ShutdownConfig.class);

    /**
     * 应用关闭前的清理工作
     * 由 Spring 容器在关闭时自动调用
     */
    @PreDestroy
    public void onShutdown() {
        logger.info("========================================");
        logger.info("🛑 SmanAgent 正在关闭...");
        logger.info("========================================");
        logger.info("✅ SmanAgent 已关闭");
        logger.info("========================================");
    }
}
