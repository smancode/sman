package com.smancode.smanagent.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PreDestroy;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * WebSocket 线程池配置
 * <p>
 * 固定大小线程池，满了就拒绝请求
 */
@Configuration
public class ThreadPoolConfig {

    private static final Logger logger = LoggerFactory.getLogger(ThreadPoolConfig.class);

    @Value("${websocket.thread-pool.core-size:32}")
    private int coreSize;

    @Value("${websocket.thread-pool.max-size:32}")
    private int maxSize;

    @Value("${websocket.thread-pool.queue-capacity:0}")
    private int queueCapacity;

    @Bean(name = "webSocketExecutorService", destroyMethod = "shutdown")
    public ExecutorService webSocketExecutorService() {
        logger.info("初始化 WebSocket 线程池: coreSize={}, maxSize={}, queueCapacity={}", coreSize, maxSize, queueCapacity);

        java.util.concurrent.ThreadPoolExecutor executor = new java.util.concurrent.ThreadPoolExecutor(
                coreSize,
                maxSize,
                60L, java.util.concurrent.TimeUnit.SECONDS,
                queueCapacity > 0 ? new java.util.concurrent.LinkedBlockingQueue<>(queueCapacity)
                                  : new java.util.concurrent.SynchronousQueue<>(),
                new java.util.concurrent.ThreadPoolExecutor.AbortPolicy()  // 满了就拒绝
        );

        // 允许核心线程超时，便于停机
        executor.allowCoreThreadTimeOut(true);

        return executor;
    }

    @Value("${subtask.thread-pool.core-size:8}")
    private int subTaskCoreSize;

    @Value("${subtask.thread-pool.max-size:16}")
    private int subTaskMaxSize;

    @Value("${subtask.thread-pool.queue-capacity:0}")
    private int subTaskQueueCapacity;

    /**
     * SubTask 并行执行线程池
     * <p>
     * 用于并行执行无依赖关系的 SubTask。
     * 配置：
     * - core-size: 核心线程数（默认 32）
     * - max-size: 最大线程数（默认 32）
     * - queue-capacity: 队列容量（默认 0，0 表示直接拒绝，不排队）
     */
    @Bean(name = "subTaskExecutorService", destroyMethod = "shutdown")
    public ExecutorService subTaskExecutorService() {
        logger.info("初始化 SubTask 线程池: coreSize={}, maxSize={}, queueCapacity={}",
                subTaskCoreSize, subTaskMaxSize, subTaskQueueCapacity);

        java.util.concurrent.ThreadPoolExecutor executor = new java.util.concurrent.ThreadPoolExecutor(
                subTaskCoreSize,
                subTaskMaxSize,
                60L, java.util.concurrent.TimeUnit.SECONDS,
                subTaskQueueCapacity > 0 ? new java.util.concurrent.LinkedBlockingQueue<>(subTaskQueueCapacity)
                                         : new java.util.concurrent.SynchronousQueue<>(),
                new java.util.concurrent.ThreadPoolExecutor.AbortPolicy()  // 满了就拒绝
        );

        // 允许核心线程超时，便于停机
        executor.allowCoreThreadTimeOut(true);

        return executor;
    }

    /**
     * 等待线程池优雅关闭
     * <p>
     * 此方法由 GracefulShutdownManager 在停机时调用
     */
    public void waitForExecutorTermination() throws InterruptedException {
        ExecutorService webSocketExec = webSocketExecutorService();
        ExecutorService subTaskExec = subTaskExecutorService();

        logger.info("开始优雅关闭线程池...");

        // 1. 先拒绝新任务
        webSocketExec.shutdown();
        subTaskExec.shutdown();

        // 2. 等待现有任务完成（最多 30 秒）
        long timeoutMs = 30000;

        boolean webSocketTerminated = webSocketExec.awaitTermination(timeoutMs, TimeUnit.MILLISECONDS);
        if (!webSocketTerminated) {
            logger.warn("WebSocket 线程池未在 {} ms 内完成，强制关闭", timeoutMs);
            shutdownNow(webSocketExec);
        } else {
            logger.info("✅ WebSocket 线程池已优雅关闭");
        }

        boolean subTaskTerminated = subTaskExec.awaitTermination(timeoutMs, TimeUnit.MILLISECONDS);
        if (!subTaskTerminated) {
            logger.warn("SubTask 线程池未在 {} ms 内完成，强制关闭", timeoutMs);
            shutdownNow(subTaskExec);
        } else {
            logger.info("✅ SubTask 线程池已优雅关闭");
        }
    }

    /**
     * 强制关闭线程池
     */
    private void shutdownNow(ExecutorService executor) {
        try {
            java.util.List<Runnable> remainingTasks = executor.shutdownNow();
            if (!remainingTasks.isEmpty()) {
                logger.warn("强制关闭线程池，剩余 {} 个任务未执行", remainingTasks.size());
            }
        } catch (Exception e) {
            logger.error("强制关闭线程池失败", e);
        }
    }
}
