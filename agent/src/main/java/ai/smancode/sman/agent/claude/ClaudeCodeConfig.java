package ai.smancode.sman.agent.claude;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Configuration;

/**
 * Claude Code 进程池自动初始化配置
 *
 * 功能：
 * - 在 Spring Boot 启动完成后自动初始化 Claude Code 进程池
 * - 在应用关闭时自动清理进程池
 *
 * @author SiliconMan Team
 * @since 1.0.0
 */
@Configuration
public class ClaudeCodeConfig implements ApplicationListener<ApplicationReadyEvent> {

    private static final Logger log = LoggerFactory.getLogger(ClaudeCodeConfig.class);

    @Autowired
    private ClaudeCodeProcessPool processPool;

    /**
     * 应用启动完成后的回调
     * 在此时初始化 Claude Code 进程池
     */
    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        log.info("========================================");
        log.info("  SiliconMan Agent 启动完成");
        log.info("  初始化 Claude Code 进程池...");
        log.info("========================================");

        try {
            // 初始化进程池（包括预热）
            processPool.initialize();

            log.info("========================================");
            log.info("  ✅ Claude Code 进程池初始化完成");
            log.info("  📊 进程池状态: {}", getPoolStatusSummary());
            log.info("========================================");

        } catch (Exception e) {
            log.error("========================================");
            log.error("  ❌ Claude Code 进程池初始化失败");
            log.error("  错误: {}", e.getMessage(), e);
            log.error("========================================");
            log.warn("⚠️ Agent 将继续运行，但 Claude Code 功能不可用");
        }
    }

    /**
     * 获取进程执行器状态摘要
     */
    private String getPoolStatusSummary() {
        try {
            ClaudeCodeProcessPool.PoolStatus status = processPool.getStatus();
            return String.format("并发限制=%d, 活跃进程=%d, 总请求数=%d, 可用许可=%d",
                    status.getConcurrentLimit(),
                    status.getActiveProcesses(),
                    status.getTotalRequests(),
                    status.getAvailablePermits());
        } catch (Exception e) {
            return "状态获取失败: " + e.getMessage();
        }
    }

    /**
     * 应用关闭时的清理（可选）
     * 使用 DisposableBean 接口确保在应用关闭时调用
     */
    public void shutdown() {
        log.info("🛑 SiliconMan Agent 关闭中，清理 Claude Code 进程池...");
        try {
            if (processPool != null) {
                processPool.shutdown();
            }
        } catch (Exception e) {
            log.error("清理进程池失败: {}", e.getMessage(), e);
        }
    }
}
