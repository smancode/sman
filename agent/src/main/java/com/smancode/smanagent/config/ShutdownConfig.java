package com.smancode.smanagent.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PreDestroy;

/**
 * 应用优雅关闭配置
 *
 * <p>功能：
 * <ul>
 *   <li>监听 JVM 关闭事件</li>
 *   <li>记录关闭日志</li>
 * </ul>
 *
 * <p>注意：实际的优雅停机逻辑由 {@link com.smancode.smanagent.shutdown.GracefulShutdownManager} 处理
 *
 * @since 1.0.0
 */
@Configuration
public class ShutdownConfig {

    private static final Logger logger = LoggerFactory.getLogger(ShutdownConfig.class);

    /**
     * 应用关闭前的日志记录
     * <p>
     * 由 Spring 容器在关闭时自动调用。
     * 实际的清理工作由 GracefulShutdownManager 处理。
     */
    @PreDestroy
    public void onShutdown() {
        // 仅记录日志，实际的优雅停机逻辑由 GracefulShutdownManager.stop() 处理
        logger.info("========================================");
        logger.info("🛑 SmanAgent 正在关闭...");
        logger.info("========================================");
        logger.info("✅ SmanAgent 已关闭");
        logger.info("========================================");
    }
}
