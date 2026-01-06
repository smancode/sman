package ai.smancode.sman.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * SiliconMan Agent 启动类
 *
 * 架构说明：
 * - 基于 Claude Code 的代码分析 Agent 后端
 * - 提供 WebSocket API（IDE Plugin 通信）
 * - 提供 HTTP Tool API（Claude Code 调用）
 * - 核心能力：Spoon AST、调用链分析、向量搜索
 *
 * 技术栈：
 * - Spring Boot 3.2.5
 * - Java 21+
 * - Spoon 11.0.0
 *
 * @author SiliconMan Team
 * @since 1.0.0
 */
@SpringBootApplication(scanBasePackages = {
    "ai.smancode.sman.agent",
    "com.siliconman.agent",
    "com.siliconman.shared"
})
@EnableScheduling  // 启用定时任务
public class SiliconManAgentApplication {

    private static final Logger log = LoggerFactory.getLogger(SiliconManAgentApplication.class);

    public static void main(String[] args) {
        // 禁用 JUL → SLF4J 桥接（修复 ClassNotFoundException: ch.qos.logback.classic.spi.ThrowableProxy）
        System.setProperty("java.util.logging.config.file", "/dev/null");
        java.util.logging.LogManager.getLogManager().reset();

        // 完全禁用 DevTools
        System.setProperty("spring.devtools.restart.enabled", "false");
        System.setProperty("spring.devtools.livereload.enabled", "false");
        System.setProperty("spring.devtools.add-properties", "false");

        log.info("========================================");
        log.info("  SiliconMan Agent 启动中...");
        log.info("  版本: 1.0.0");
        log.info("  基于 Claude Code 的代码分析 Agent");
        log.info("========================================");

        try {
            // 配置 Spring Boot
            SpringApplication app = new SpringApplication(SiliconManAgentApplication.class);
            app.setLogStartupInfo(false);
            app.setRegisterShutdownHook(true);

            // 启动 Spring Boot 应用
            ConfigurableApplicationContext context = app.run(args);

            // 打印启动信息
            printStartupInfo(context.getEnvironment());

            log.info("========================================");
            log.info("  SiliconMan Agent 启动成功！");
            log.info("========================================");

        } catch (Exception e) {
            log.error("========================================");
            log.error("  SiliconMan Agent 启动失败！");
            log.error("  错误: {}", e.getMessage(), e);
            log.error("========================================");
            System.exit(1);
        }
    }

    /**
     * 打印启动信息
     */
    private static void printStartupInfo(Environment env) {
        String protocol = "http";
        String serverPort = env.getProperty("server.port", "8080");
        String contextPath = env.getProperty("server.servlet.context-path", "");

        String hostAddress = "localhost";
        try {
            hostAddress = InetAddress.getLocalHost().getHostAddress();
        } catch (UnknownHostException e) {
            log.warn("无法获取本机IP地址: {}", e.getMessage());
        }

        log.info("");
        log.info("----------------------------------------------------------");
        log.info("  Application '{}' is running!", env.getProperty("spring.application.name"));
        log.info("  Access URLs:");
        log.info("    Local:       {}://localhost:{}{}", protocol, serverPort, contextPath);
        log.info("    External:    {}://{}:{}{}", protocol, hostAddress, serverPort, contextPath);
        log.info("  Profile(s):    {}", (Object) env.getActiveProfiles());
        log.info("");
        log.info("  API Endpoints:");
        log.info("    WebSocket:   ws://localhost:{}{}/ws/analyze", serverPort, contextPath);
        log.info("    HTTP Tool:   {}://localhost:{}{}/api/claude-code/tools/execute", protocol, serverPort, contextPath);
        log.info("    Health:      {}://localhost:{}{}/api/claude-code/health", protocol, serverPort, contextPath);
        log.info("    Pool Status: {}://localhost:{}{}/api/claude-code/pool/status", protocol, serverPort, contextPath);
        log.info("");
        log.info("  Documentation:");
        log.info("    Architecture: ../sman/docs/md/01-architecture.md");
        log.info("    WebSocket:    ../sman/docs/md/02-websocket-api.md");
        log.info("    HTTP API:     ../sman/docs/md/03-claude-code-integration.md");
        log.info("    Data Models:  ../sman/docs/md/04-data-models.md");
        log.info("----------------------------------------------------------");
        log.info("");
    }
}
