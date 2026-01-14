package com.smancode.smanagent.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;

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

    @Bean(name = "webSocketExecutorService")
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

        return executor;
    }

    @Value("${subtask.thread-pool.core-size:8}")
    private int subTaskCoreSize;

    @Value("${subtask.thread-pool.max-size:16}")
    private int subTaskMaxSize;

    @Value("${subtask.thread-pool.queue-capacity:100}")
    private int subTaskQueueCapacity;

    /**
     * SubTask 并行执行线程池
     * <p>
     * 用于并行执行无依赖关系的 SubTask。
     * 配置：
     * - core-size: 核心线程数（默认 8）
     * - max-size: 最大线程数（默认 16）
     * - queue-capacity: 队列容量（默认 100）
     */
    @Bean(name = "subTaskExecutorService")
    public ExecutorService subTaskExecutorService() {
        logger.info("初始化 SubTask 线程池: coreSize={}, maxSize={}, queueCapacity={}",
                subTaskCoreSize, subTaskMaxSize, subTaskQueueCapacity);

        java.util.concurrent.ThreadPoolExecutor executor = new java.util.concurrent.ThreadPoolExecutor(
                subTaskCoreSize,
                subTaskMaxSize,
                60L, java.util.concurrent.TimeUnit.SECONDS,
                new java.util.concurrent.LinkedBlockingQueue<>(subTaskQueueCapacity),
                new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy()  // 满了由调用线程执行
        );

        return executor;
    }
}
