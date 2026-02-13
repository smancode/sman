package com.smancode.sman.analysis.database

import com.smancode.sman.analysis.config.JVectorConfig
import com.smancode.sman.analysis.config.VectorDatabaseConfig
import com.smancode.sman.analysis.config.VectorDbType
import com.smancode.sman.analysis.paths.ProjectPaths
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * 向量存储管理器（单例）
 *
 * 解决 H2 数据库连接池冲突问题：
 * - 每个项目路径只创建一个 TieredVectorStore 实例
 * - 使用 ConcurrentHashMap 保证线程安全
 * - 支持延迟初始化和自动关闭
 *
 * 问题根因：
 * 之前每次 CodeVectorizationCoordinator 创建时都会创建新的 TieredVectorStore，
 * 导致多个 HikariPool 同时访问同一个 H2 数据库文件，产生连接池冲突：
 * ```
 * Failed to initialize pool: Database may be already in use: "Server is running"
 * ```
 *
 * 解决方案：
 * 通过 VectorStoreManager 单例管理所有 TieredVectorStore 实例，
 * 确保每个项目路径只有一个实例，避免连接池冲突。
 */
object VectorStoreManager {

    private val logger = LoggerFactory.getLogger(VectorStoreManager::class.java)

    // 按项目路径缓存的 TieredVectorStore 实例
    private val storeCache = ConcurrentHashMap<String, TieredVectorStore>()

    // 引用计数（用于跟踪使用情况）
    private val refCount = ConcurrentHashMap<String, Int>()

    /**
     * 获取或创建 TieredVectorStore 实例
     *
     * @param projectKey 项目标识
     * @param projectPath 项目路径
     * @return TieredVectorStore 实例
     */
    fun getOrCreate(projectKey: String, projectPath: Path): TieredVectorStore {
        val cacheKey = projectPath.toString()

        // 检查是否已存在
        val existing = storeCache[cacheKey]
        if (existing != null) {
            // 已存在，增加引用计数
            refCount.merge(cacheKey, 1) { old, delta -> old + delta }
            logger.debug("TieredVectorStore 引用计数增加（复用）: path={}, count={}", cacheKey, refCount[cacheKey])
            return existing
        }

        // 不存在，创建新实例
        logger.info("创建新的 TieredVectorStore: projectKey={}, path={}", projectKey, cacheKey)

        val paths = ProjectPaths.forProject(projectPath)
        val config = VectorDatabaseConfig(
            projectKey = projectKey,
            type = VectorDbType.JVECTOR,
            jvector = JVectorConfig(),
            databasePath = paths.databaseFile.toString()
        )

        val newStore = TieredVectorStore(config)
        storeCache[cacheKey] = newStore
        refCount[cacheKey] = 1

        logger.info("TieredVectorStore 创建成功: projectKey={}, refCount=1", projectKey)
        return newStore
    }

    /**
     * 释放 TieredVectorStore 实例
     *
     * 注意：只有引用计数归零时才真正关闭
     *
     * @param projectPath 项目路径
     */
    fun release(projectPath: Path) {
        val cacheKey = projectPath.toString()

        val count = refCount.merge(cacheKey, -1) { old, delta ->
            maxOf(0, old + delta)
        } ?: 0

        logger.debug("TieredVectorStore 引用计数减少: path={}, count={}", cacheKey, count)

        // 只有引用计数归零时才关闭
        if (count <= 0) {
            storeCache.remove(cacheKey)?.let { store ->
                try {
                    store.close()
                    logger.info("TieredVectorStore 已关闭: path={}", cacheKey)
                } catch (e: Exception) {
                    logger.error("关闭 TieredVectorStore 失败: path={}", cacheKey, e)
                }
            }
            refCount.remove(cacheKey)
        }
    }

    /**
     * 强制关闭所有实例
     *
     * 用于插件卸载时清理资源
     */
    fun closeAll() {
        logger.info("关闭所有 TieredVectorStore 实例: count={}", storeCache.size)

        storeCache.forEach { (path, store) ->
            try {
                store.close()
                logger.info("TieredVectorStore 已关闭: path={}", path)
            } catch (e: Exception) {
                logger.error("关闭 TieredVectorStore 失败: path={}", path, e)
            }
        }

        storeCache.clear()
        refCount.clear()
    }

    /**
     * 获取缓存的实例数量
     */
    fun cacheSize(): Int = storeCache.size

    /**
     * 检查指定路径是否有缓存的实例
     */
    fun hasInstance(projectPath: Path): Boolean = storeCache.containsKey(projectPath.toString())

    /**
     * 获取指定路径的引用计数
     */
    fun getRefCount(projectPath: Path): Int = refCount[projectPath.toString()] ?: 0
}
