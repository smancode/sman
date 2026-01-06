package ai.smancode.sman.agent.vector;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;

/**
 * 向量索引读写锁管理器（类级锁）
 *
 * 核心特性：
 * 1. 按类名隔离读写锁（projectKey + "." + className）
 * 2. 读锁：支持多个用户同时搜索同一类
 * 3. 写锁：刷新时独占该类，其他类的搜索不受影响
 * 4. 超时机制：防止死锁
 *
 * @author SiliconMan Team
 * @since 1.0.0
 */
@Component
public class VectorIndexLockManager {

    private static final Logger log = LoggerFactory.getLogger(VectorIndexLockManager.class);

    /** 类级锁：Key = projectKey + "." + className */
    private final Map<String, ReentrantReadWriteLock> locks = new ConcurrentHashMap<>();

    /** 默认读锁超时时间（秒） */
    private static final long READ_LOCK_TIMEOUT_SECONDS = 5;

    /** 默认写锁超时时间（秒） */
    private static final long WRITE_LOCK_TIMEOUT_SECONDS = 30;

    /**
     * 执行类级读操作（用户搜索）
     *
     * 特性：
     * - 多个线程可以同时获取读锁
     * - 如果有写锁在执行，读锁会等待
     * - 超时后降级：使用旧数据继续搜索
     *
     * @param projectKey 项目键
     * @param className 类名
     * @param operation 读操作
     * @return 操作结果
     */
    public <T> T readClass(String projectKey, String className, Supplier<T> operation) {
        String lockKey = buildLockKey(projectKey, className);
        ReentrantReadWriteLock lock = locks.computeIfAbsent(
            lockKey,
            k -> new ReentrantReadWriteLock()
        );

        Lock readLock = lock.readLock();

        try {
            // 尝试获取读锁（带超时）
            boolean acquired = readLock.tryLock(READ_LOCK_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            if (!acquired) {
                log.warn("⚠️ 获取读锁超时: projectKey={}, className={}", projectKey, className);
                // 降级：使用旧数据继续搜索
                return operation.get();
            }

            log.debug("🔓 获取读锁成功: projectKey={}, className={}", projectKey, className);

            try {
                // 执行读操作
                return operation.get();

            } finally {
                readLock.unlock();
                log.debug("🔒 释放读锁: projectKey={}, className={}", projectKey, className);
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("❌ 获取读锁被中断: projectKey={}, className={}", projectKey, className);
            // 降级：使用旧数据继续搜索
            return operation.get();
        }
    }

    /**
     * 执行类级写操作（定时刷新、手动刷新）
     *
     * 特性：
     * - 写锁独占，阻塞所有读锁
     * - 只影响当前被刷新的类
     * - 超时抛异常
     *
     * @param projectKey 项目键
     * @param className 类名
     * @param operation 写操作
     * @return 操作结果
     */
    public <T> T writeClass(String projectKey, String className, Supplier<T> operation) {
        String lockKey = buildLockKey(projectKey, className);
        ReentrantReadWriteLock lock = locks.computeIfAbsent(
            lockKey,
            k -> new ReentrantReadWriteLock()
        );

        Lock writeLock = lock.writeLock();

        try {
            // 尝试获取写锁（带超时）
            boolean acquired = writeLock.tryLock(WRITE_LOCK_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            if (!acquired) {
                log.warn("⚠️ 获取写锁超时: projectKey={}, className={}", projectKey, className);
                throw new RuntimeException("获取写锁超时: " + lockKey);
            }

            try {
                // 执行写操作
                return operation.get();

            } finally {
                writeLock.unlock();
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("❌ 获取写锁被中断: projectKey={}, className={}", projectKey, className);
            throw new RuntimeException("获取写锁失败: " + lockKey, e);
        }
    }

    /**
     * 构建锁键
     */
    private String buildLockKey(String projectKey, String className) {
        return projectKey + "." + className;
    }

    /**
     * 获取锁状态（用于监控）
     *
     * @param projectKey 项目键
     * @param className 类名
     * @return 锁状态
     */
    public Map<String, Object> getLockStatus(String projectKey, String className) {
        String lockKey = buildLockKey(projectKey, className);
        ReentrantReadWriteLock lock = locks.get(lockKey);

        if (lock == null) {
            return Map.of(
                "exists", false,
                "lockKey", lockKey
            );
        }

        return Map.of(
            "exists", true,
            "lockKey", lockKey,
            "readLockCount", lock.getReadLockCount(),
            "writeLocked", lock.isWriteLocked(),
            "queuedThreads", lock.getQueueLength(),
            "fair", lock.isFair()
        );
    }

    /**
     * 获取所有锁的状态（用于监控）
     */
    public Map<String, Object> getAllLocksStatus() {
        Map<String, Object> status = new ConcurrentHashMap<>();

        for (Map.Entry<String, ReentrantReadWriteLock> entry : locks.entrySet()) {
            String lockKey = entry.getKey();
            ReentrantReadWriteLock lock = entry.getValue();

            status.put(lockKey, Map.of(
                "readLockCount", lock.getReadLockCount(),
                "writeLocked", lock.isWriteLocked(),
                "queuedThreads", lock.getQueueLength()
            ));
        }

        return Map.of(
            "totalLocks", locks.size(),
            "locks", status
        );
    }

    /**
     * 清理未使用的锁（释放内存）
     *
     * @return 清理的锁数量
     */
    public int cleanupUnusedLocks() {
        int beforeSize = locks.size();

        locks.entrySet().removeIf(entry -> {
            ReentrantReadWriteLock lock = entry.getValue();
            // 如果没有线程等待，且没有线程持有锁
            return lock.getQueueLength() == 0
                && lock.getReadLockCount() == 0
                && !lock.isWriteLocked();
        });

        int cleanedCount = beforeSize - locks.size();

        if (cleanedCount > 0) {
            log.info("🧹 清理未使用的锁: count={}", cleanedCount);
        }

        return cleanedCount;
    }
}
