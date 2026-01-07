package ai.smancode.sman.agent.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 异步线程池配置
 *
 * 功能：
 * - 统一管理所有异步任务的线程池
 * - 支持优雅关闭，确保任务完成后再终止
 * - 提供专用线程池：通用异步、向量索引刷新、WebSocket 消息处理
 *
 * @author SiliconMan Team
 * @since 1.0.0
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    private static final Logger log = LoggerFactory.getLogger(AsyncConfig.class);

    // ==================== 通用异步线程池 ====================

    @Value("${async.executor.core-size:10}")
    private int asyncCoreSize;

    @Value("${async.executor.max-size:50}")
    private int asyncMaxSize;

    @Value("${async.executor.queue-capacity:1000}")
    private int asyncQueueCapacity;

    @Value("${async.executor.thread-name-prefix:sman-async-}")
    private String asyncThreadNamePrefix;

    /**
     * 通用异步线程池
     * 用于：@Async 注解的方法、CompletableFuture 默认异步任务
     */
    @Bean(name = "taskExecutor")
    public Executor taskExecutor() {
        log.info("========================================");
        log.info("  初始化通用异步线程池");
        log.info("  核心线程数: {}", asyncCoreSize);
        log.info("  最大线程数: {}", asyncMaxSize);
        log.info("  队列容量: {}", asyncQueueCapacity);
        log.info("  线程名前缀: {}", asyncThreadNamePrefix);
        log.info("========================================");

        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(asyncCoreSize);
        executor.setMaxPoolSize(asyncMaxSize);
        executor.setQueueCapacity(asyncQueueCapacity);
        executor.setThreadNamePrefix(asyncThreadNamePrefix);

        // 拒绝策略：调用者运行（保证不丢任务）
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());

        // 等待任务完成后才关闭
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);

        executor.initialize();

        log.info("✅ 通用异步线程池初始化完成");
        return executor;
    }

    // ==================== 向量索引刷新线程池 ====================

    @Value("${async.executor.vector-refresh.core-size:1}")
    private int vectorRefreshCoreSize;

    @Value("${async.executor.vector-refresh.max-size:1}")
    private int vectorRefreshMaxSize;

    @Value("${async.executor.vector-refresh.queue-capacity:10}")
    private int vectorRefreshQueueCapacity;

    @Value("${async.executor.vector-refresh.thread-name-prefix:vector-refresh-}")
    private String vectorRefreshThreadNamePrefix;

    /**
     * 向量索引刷新专用线程池
     * 特点：单线程顺序执行，避免并发冲突
     */
    @Bean(name = "vectorRefreshExecutor")
    public ExecutorService vectorRefreshExecutor() {
        log.info("========================================");
        log.info("  初始化向量索引刷新线程池");
        log.info("  核心线程数: {}", vectorRefreshCoreSize);
        log.info("  最大线程数: {}", vectorRefreshMaxSize);
        log.info("  队列容量: {}", vectorRefreshQueueCapacity);
        log.info("  线程名前缀: {}", vectorRefreshThreadNamePrefix);
        log.info("========================================");

        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(vectorRefreshCoreSize);
        executor.setMaxPoolSize(vectorRefreshMaxSize);
        executor.setQueueCapacity(vectorRefreshQueueCapacity);
        executor.setThreadNamePrefix(vectorRefreshThreadNamePrefix);

        // 拒绝策略：调用者运行
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());

        // 等待任务完成后才关闭
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(120);  // 刷新任务可能较慢，等待 2 分钟

        executor.initialize();

        log.info("✅ 向量索引刷新线程池初始化完成");
        return executor.getThreadPoolExecutor();
    }

    // ==================== WebSocket 消息处理线程池 ====================

    @Value("${async.executor.websocket.core-size:5}")
    private int webSocketCoreSize;

    @Value("${async.executor.websocket.max-size:20}")
    private int webSocketMaxSize;

    @Value("${async.executor.websocket.queue-capacity:500}")
    private int webSocketQueueCapacity;

    @Value("${async.executor.websocket.thread-name-prefix:websocket-async-}")
    private String webSocketThreadNamePrefix;

    /**
     * WebSocket 消息处理专用线程池
     * 特点：独立线程池，避免阻塞主业务线程池
     */
    @Bean(name = "webSocketExecutor")
    public Executor webSocketExecutor() {
        log.info("========================================");
        log.info("  初始化 WebSocket 消息处理线程池");
        log.info("  核心线程数: {}", webSocketCoreSize);
        log.info("  最大线程数: {}", webSocketMaxSize);
        log.info("  队列容量: {}", webSocketQueueCapacity);
        log.info("  线程名前缀: {}", webSocketThreadNamePrefix);
        log.info("========================================");

        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(webSocketCoreSize);
        executor.setMaxPoolSize(webSocketMaxSize);
        executor.setQueueCapacity(webSocketQueueCapacity);
        executor.setThreadNamePrefix(webSocketThreadNamePrefix);

        // 拒绝策略：调用者运行
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());

        // 等待任务完成后才关闭
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);

        executor.initialize();

        log.info("✅ WebSocket 消息处理线程池初始化完成");
        return executor;
    }

    // ==================== 线程池监控 ====================

    /**
     * 获取线程池状态统计
     */
    public String getPoolStatus(Executor executor) {
        if (executor instanceof ThreadPoolTaskExecutor) {
            ThreadPoolTaskExecutor taskExecutor = (ThreadPoolTaskExecutor) executor;
            ThreadPoolExecutor pool = taskExecutor.getThreadPoolExecutor();

            return String.format(
                    "活跃线程=%d, 池大小=%d, 队列大小=%d, 完成任务=%d",
                    pool.getActiveCount(),
                    pool.getPoolSize(),
                    pool.getQueue().size(),
                    pool.getCompletedTaskCount()
            );
        }
        return "未知类型";
    }
}
