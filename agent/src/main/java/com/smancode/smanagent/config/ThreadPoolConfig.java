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
}
