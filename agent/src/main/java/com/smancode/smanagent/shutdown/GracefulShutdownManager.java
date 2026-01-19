package com.smancode.smanagent.shutdown;

import com.smancode.smanagent.config.properties.GracefulShutdownProperties;
import com.smancode.smanagent.config.ThreadPoolConfig;
import com.smancode.smanagent.smancode.core.SessionManager;
import com.smancode.smanagent.websocket.AgentWebSocketHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 优雅停机管理器
 *
 * <p>实现 Spring SmartLifecycle 接口，管理应用的优雅停机流程：
 * <ol>
 *   <li>标记服务不可用，拒绝新请求</li>
 *   <li>等待在途请求完成</li>
 *   <li>关闭所有 WebSocket 连接</li>
 *   <li>优雅关闭线程池</li>
 *   <li>持久化所有会话数据</li>
 * </ol>
 */
@Component
public class GracefulShutdownManager implements SmartLifecycle {

    private static final Logger logger = LoggerFactory.getLogger(GracefulShutdownManager.class);

    private final GracefulShutdownProperties properties;
    private final AgentWebSocketHandler webSocketHandler;
    private final ThreadPoolConfig threadPoolConfig;
    private final SessionManager sessionManager;

    private volatile boolean running = true;
    private volatile boolean shuttingDown = false;

    public GracefulShutdownManager(GracefulShutdownProperties properties,
                                   @Lazy AgentWebSocketHandler webSocketHandler,
                                   ThreadPoolConfig threadPoolConfig,
                                   SessionManager sessionManager) {
        this.properties = properties;
        this.webSocketHandler = webSocketHandler;
        this.threadPoolConfig = threadPoolConfig;
        this.sessionManager = sessionManager;
    }

    @Override
    public void start() {
        // 组件启动时默认就是运行状态
        logger.info("GracefulShutdownManager 已启动");
    }

    @Override
    public void stop() {
        if (!running) {
            logger.info("GracefulShutdownManager 已经停止，跳过");
            return;
        }

        logger.info("========================================");
        logger.info("🛑 开始优雅停机流程...");
        logger.info("========================================");

        long startTime = System.currentTimeMillis();

        try {
            // 阶段 1: 标记停机中，拒绝新请求
            logger.info("【阶段 1/5】标记服务不可用");
            shuttingDown = true;

            // 阶段 2: 等待在途请求完成
            logger.info("【阶段 2/5】等待在途请求完成（最多 {} 秒）",
                    properties.getAwaitTerminationTimeout().toSeconds());
            waitForPendingRequests();

            // 阶段 3: 关闭所有 WebSocket 连接
            logger.info("【阶段 3/5】关闭所有 WebSocket 连接");
            closeAllWebSockets();

            // 阶段 4: 优雅关闭线程池
            logger.info("【阶段 4/5】优雅关闭线程池");
            shutdownExecutors();

            // 阶段 5: 持久化所有会话
            logger.info("【阶段 5/5】持久化所有会话数据");
            persistAllSessions();

            running = false;

            long duration = System.currentTimeMillis() - startTime;
            logger.info("========================================");
            logger.info("✅ 优雅停机完成，耗时: {} ms", duration);
            logger.info("========================================");

        } catch (Exception e) {
            logger.error("优雅停机过程中发生错误", e);
            running = false;
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public boolean isAutoStartup() {
        return true;
    }

    @Override
    public void stop(Runnable callback) {
        stop();
        callback.run();
    }

    @Override
    public int getPhase() {
        // 最晚执行，确保其他组件先停止
        return Integer.MAX_VALUE;
    }

    /**
     * 检查是否正在停机中
     */
    public boolean isShuttingDown() {
        return shuttingDown;
    }

    /**
     * 获取当前待处理的请求数量
     */
    public int getPendingRequests() {
        return webSocketHandler.getProcessingSessionCount();
    }

    /**
     * 获取当前停机阶段
     */
    public String getShutdownPhase() {
        if (!shuttingDown) {
            return "ACCEPTING_REQUESTS";
        }
        if (running) {
            return "DRAINING";
        }
        return "TERMINATED";
    }

    /**
     * 等待在途请求完成
     */
    private void waitForPendingRequests() {
        long timeoutMs = properties.getAwaitTerminationTimeoutMs();
        boolean completed = webSocketHandler.waitForPendingSessions(timeoutMs);

        if (completed) {
            logger.info("✅ 所有在途请求已完成");
        } else {
            int pending = webSocketHandler.getProcessingSessionCount();
            logger.warn("⚠️ 等待超时，仍有 {} 个请求未完成", pending);
        }
    }

    /**
     * 关闭所有 WebSocket 连接
     */
    private void closeAllWebSockets() {
        try {
            int closed = webSocketHandler.closeAllForShutdown();
            logger.info("✅ 已关闭 {} 个 WebSocket 连接", closed);
        } catch (Exception e) {
            logger.error("关闭 WebSocket 连接时发生错误", e);
        }
    }

    /**
     * 优雅关闭线程池
     */
    private void shutdownExecutors() {
        try {
            threadPoolConfig.waitForExecutorTermination();
            logger.info("✅ 线程池已优雅关闭");
        } catch (Exception e) {
            logger.error("关闭线程池时发生错误", e);
        }
    }

    /**
     * 持久化所有会话
     */
    private void persistAllSessions() {
        try {
            int persisted = sessionManager.persistAllSessions();
            logger.info("✅ 已持久化 {} 个会话", persisted);
        } catch (Exception e) {
            logger.error("持久化会话时发生错误", e);
        }
    }
}
