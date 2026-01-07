package ai.smancode.sman.agent.config;

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
 * - 确保线程池优雅关闭（由 AsyncConfig 处理）
 *
 * @author SiliconMan Team
 * @since 1.0.0
 */
@Configuration
public class ShutdownConfig {

    private static final Logger log = LoggerFactory.getLogger(ShutdownConfig.class);

    /**
     * 应用关闭前的清理工作
     * 由 Spring 容器在关闭时自动调用
     *
     * 注意：
     * - 线程池的优雅关闭由 AsyncConfig 中的 setWaitForTasksToCompleteOnShutdown(true) 处理
     * - Claude Code 进程池的优雅关闭由 ClaudeCodeProcessPool.shutdown() 处理
     */
    @PreDestroy
    public void onShutdown() {
        log.info("========================================");
        log.info("🛑 SiliconMan Agent 正在关闭...");
        log.info("========================================");

        // 注意：实际的清理工作由各自的 @PreDestroy 方法处理
        // - AsyncConfig: 线程池优雅关闭
        // - ClaudeCodeConfig: 进程池关闭
        // - VectorCacheManager: 缓存持久化

        log.info("========================================");
        log.info("✅ SiliconMan Agent 已关闭");
        log.info("========================================");
    }
}
