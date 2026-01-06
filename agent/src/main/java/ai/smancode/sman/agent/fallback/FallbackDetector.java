package ai.smancode.sman.agent.fallback;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 降级检测器
 *
 * 功能：
 * - 检测 Claude Code CLI 是否可用
 * - 自动触发/恢复降级模式
 * - 提供手动控制降级模式的接口
 *
 * @author SiliconMan Team
 * @since 2.0
 */
@Component
public class FallbackDetector {

    private static final Logger log = LoggerFactory.getLogger(FallbackDetector.class);

    @Value("${claude-code.path:claude-code}")
    private String claudeCodePath;

    @Value("${agent.fallback.enabled:true}")
    private boolean fallbackEnabled;

    @Value("${agent.fallback.auto-detect:true}")
    private boolean autoDetect;

    @Value("${agent.fallback.duration-minutes:5}")
    private int fallbackDurationMinutes;

    // 降级状态
    private volatile boolean inFallbackMode = false;
    private volatile long fallbackStartTime = 0;

    // 统计信息
    private volatile long lastCheckTime = 0;
    private volatile boolean lastCheckResult = true;

    /**
     * 检查是否应该启用降级模式
     */
    public boolean shouldEnableFallback() {
        if (!fallbackEnabled || !autoDetect) {
            return false;
        }

        // 如果已经在降级模式，检查是否应该恢复
        if (inFallbackMode) {
            return shouldContinueFallback();
        }

        // 检查 Claude Code 是否可用
        boolean available = isClaudeCodeAvailable();
        lastCheckTime = System.currentTimeMillis();
        lastCheckResult = available;

        if (!available) {
            // Claude Code 不可用，启用降级
            log.warn("🔴 检测到 Claude Code 不可用，启用降级模式");
            enableFallback();
            return true;
        }

        return false;
    }

    /**
     * 检查 Claude Code 是否可用
     */
    private boolean isClaudeCodeAvailable() {
        try {
            // 1. 检查 CLI 是否安装
            ProcessBuilder pb = new ProcessBuilder(claudeCodePath, "--version");
            pb.redirectErrorStream(true);

            Process process = pb.start();

            // 等待最多 10 秒
            boolean finished = process.waitFor(10, TimeUnit.SECONDS);

            if (!finished) {
                log.warn("⚠️ Claude Code --version 命令超时");
                process.destroyForcibly();
                return false;
            }

            int exitCode = process.exitValue();

            if (exitCode != 0) {
                log.warn("⚠️ Claude Code CLI 返回非零退出码: {}", exitCode);
                return false;
            }

            // 2. 检查资源是否充足
            if (!hasSufficientResources()) {
                log.warn("⚠️ 系统资源不足");
                return false;
            }

            log.debug("✅ Claude Code 可用性检查通过");
            return true;

        } catch (Exception e) {
            log.warn("⚠️ Claude Code 可用性检查失败: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 检查是否有足够的系统资源
     */
    private boolean hasSufficientResources() {
        // 检查可用内存（需要至少 500MB）
        long availableMemory = getAvailableMemoryMB();
        if (availableMemory < 500) {
            log.warn("⚠️ 可用内存不足: {} MB", availableMemory);
            return false;
        }

        // 检查磁盘空间（需要至少 1GB）
        long availableDisk = getAvailableDiskSpaceMB();
        if (availableDisk < 1024) {
            log.warn("⚠️ 可用磁盘空间不足: {} MB", availableDisk);
            return false;
        }

        return true;
    }

    /**
     * 获取可用内存（MB）
     */
    private long getAvailableMemoryMB() {
        try {
            Runtime runtime = Runtime.getRuntime();
            long maxMemory = runtime.maxMemory(); // JVM 最大内存
            long totalMemory = runtime.totalMemory(); // JVM 已分配内存
            long freeMemory = runtime.freeMemory(); // JVM 空闲内存
            long usedMemory = totalMemory - freeMemory; // JVM 已使用内存

            return (maxMemory - usedMemory) / (1024 * 1024);
        } catch (Exception e) {
            log.warn("⚠️ 获取内存信息失败: {}", e.getMessage());
            return 0;
        }
    }

    /**
     * 获取可用磁盘空间（MB）
     */
    private long getAvailableDiskSpaceMB() {
        try {
            File tempDir = new File(System.getProperty("java.io.tmpdir"));
            long freeSpace = tempDir.getFreeSpace();
            return freeSpace / (1024 * 1024);
        } catch (Exception e) {
            log.warn("⚠️ 获取磁盘空间失败: {}", e.getMessage());
            return 0;
        }
    }

    /**
     * 检查是否应该继续降级模式
     */
    private boolean shouldContinueFallback() {
        long elapsed = System.currentTimeMillis() - fallbackStartTime;
        long fallbackDuration = fallbackDurationMinutes * 60 * 1000L;

        // 降级时间未到，继续降级
        if (elapsed < fallbackDuration) {
            log.debug("⏳ 降级模式持续中 ({}/{} 分钟)",
                elapsed / 60000, fallbackDurationMinutes);
            return true;
        }

        // 尝试恢复（检查 Claude Code 是否恢复）
        log.info("🔄 降级时间已到，尝试恢复...");
        boolean recovered = isClaudeCodeAvailable();

        if (recovered) {
            log.info("✅ Claude Code 已恢复，退出降级模式");
            inFallbackMode = false;
            fallbackStartTime = 0;
            return false;
        }

        // 未恢复，延长降级时间
        log.info("⏳ Claude Code 仍未恢复，继续降级模式（延长 {} 分钟）", fallbackDurationMinutes);
        fallbackStartTime = System.currentTimeMillis();
        return true;
    }

    /**
     * 手动启用降级
     */
    public void enableFallback() {
        if (!inFallbackMode) {
            log.warn("🔴 手动启用降级模式");
            inFallbackMode = true;
            fallbackStartTime = System.currentTimeMillis();
        } else {
            log.debug("⚠️  降级模式已启用，无需重复操作");
        }
    }

    /**
     * 手动恢复
     */
    public void disableFallback() {
        if (inFallbackMode) {
            log.info("🟢 手动退出降级模式");
            inFallbackMode = false;
            fallbackStartTime = 0;
        } else {
            log.debug("⚠️  当前未在降级模式");
        }
    }

    /**
     * 获取降级状态信息
     */
    public FallbackStatus getStatus() {
        FallbackStatus status = new FallbackStatus();
        status.setInFallbackMode(inFallbackMode);
        status.setClaudeCodeAvailable(isClaudeCodeAvailable());
        status.setFallbackDuration(fallbackDurationMinutes);
        status.setLastCheckTime(lastCheckTime);
        status.setLastCheckResult(lastCheckResult);

        if (inFallbackMode) {
            long elapsed = System.currentTimeMillis() - fallbackStartTime;
            status.setElapsedMinutes((int) (elapsed / 60000));
            status.setRemainingMinutes(Math.max(0, fallbackDurationMinutes - (int) (elapsed / 60000)));
        }

        return status;
    }

    /**
     * 降级状态
     */
    public static class FallbackStatus {
        private boolean inFallbackMode;
        private boolean claudeCodeAvailable;
        private int fallbackDuration;
        private int elapsedMinutes;
        private int remainingMinutes;
        private long lastCheckTime;
        private boolean lastCheckResult;

        public Map<String, Object> toMap() {
            Map<String, Object> map = new java.util.HashMap<>();
            map.put("inFallbackMode", inFallbackMode);
            map.put("claudeCodeAvailable", claudeCodeAvailable);
            map.put("fallbackDuration", fallbackDuration);
            map.put("elapsedMinutes", elapsedMinutes);
            map.put("remainingMinutes", remainingMinutes);
            map.put("lastCheckTime", lastCheckTime);
            map.put("lastCheckResult", lastCheckResult);
            return map;
        }

        // Getters and Setters
        public boolean isInFallbackMode() { return inFallbackMode; }
        public void setInFallbackMode(boolean inFallbackMode) { this.inFallbackMode = inFallbackMode; }

        public boolean isClaudeCodeAvailable() { return claudeCodeAvailable; }
        public void setClaudeCodeAvailable(boolean claudeCodeAvailable) { this.claudeCodeAvailable = claudeCodeAvailable; }

        public int getFallbackDuration() { return fallbackDuration; }
        public void setFallbackDuration(int fallbackDuration) { this.fallbackDuration = fallbackDuration; }

        public int getElapsedMinutes() { return elapsedMinutes; }
        public void setElapsedMinutes(int elapsedMinutes) { this.elapsedMinutes = elapsedMinutes; }

        public int getRemainingMinutes() { return remainingMinutes; }
        public void setRemainingMinutes(int remainingMinutes) { this.remainingMinutes = remainingMinutes; }

        public long getLastCheckTime() { return lastCheckTime; }
        public void setLastCheckTime(long lastCheckTime) { this.lastCheckTime = lastCheckTime; }

        public boolean isLastCheckResult() { return lastCheckResult; }
        public void setLastCheckResult(boolean lastCheckResult) { this.lastCheckResult = lastCheckResult; }
    }
}
