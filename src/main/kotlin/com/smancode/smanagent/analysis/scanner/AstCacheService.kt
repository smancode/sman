package com.smancode.smanagent.analysis.scanner

import com.smancode.smanagent.analysis.config.VectorDatabaseConfig
import com.smancode.smanagent.analysis.model.ClassAstInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.File
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * AST 分层缓存服务
 *
 * 三层缓存架构：
 * - L1 (Hot): 内存 LRU 缓存，最新解析的 AST
 * - L2 (Warm): 磁盘文件缓存，序列化的 AST 对象
 * - L3 (Cold): 实时解析，从源代码重新解析
 *
 * 防止内存爆炸，同时保持良好的性能！
 */
class AstCacheService(
    private val psiScanner: PsiAstScanner,
    private val config: AstCacheConfig = AstCacheConfig()
) {

    private val logger = LoggerFactory.getLogger(AstCacheService::class.java)

    // L1: 内存缓存（热数据）
    private val l1Cache: LRUCache<String, CachedAst>

    // L2: 磁盘缓存目录
    private val l2CacheDir: File

    // 访问统计
    private val accessCounter: ConcurrentHashMap<String, AtomicLong> = ConcurrentHashMap()
    private val hitCounter: AtomicInteger = AtomicInteger(0)
    private val missCounter: AtomicInteger = AtomicInteger(0)

    init {
        l1Cache = LRUCache(config.l1MaxSize)
        l2CacheDir = File(config.l2CachePath).apply { mkdirs() }

        logger.info("AST 缓存服务初始化完成: L1={}, L2={}", config.l1MaxSize, l2CacheDir.absolutePath)
    }

    /**
     * 获取 AST 信息
     *
     * 读取策略：
     * 1. L1: 内存缓存（最新访问）
     * 2. L2: 磁盘缓存（序列化对象）
     * 3. L3: 实时解析（从源代码）
     */
    suspend fun getAst(file: File): ClassAstInfo? {
        val cacheKey = file.absolutePath

        // 增加访问计数
        accessCounter.getOrPut(cacheKey) { AtomicLong(0) }.incrementAndGet()

        // L1: 查询内存缓存
        var cached = l1Cache.get(cacheKey)
        if (cached != null && !cached.isExpired()) {
            hitCounter.incrementAndGet()
            logger.debug("L1 命中: file={}", file.name)
            return cached.ast
        }

        // L2: 查询磁盘缓存
        cached = loadFromL2(cacheKey)
        if (cached != null && !cached.isExpired()) {
            hitCounter.incrementAndGet()
            logger.debug("L2 命中，升级到 L1: file={}", file.name)
            l1Cache.put(cacheKey, cached)
            return cached.ast
        }

        // L3: 实时解析
        missCounter.incrementAndGet()
        logger.debug("L3 解析: file={}", file.name)

        val ast = psiScanner.scanFile(file.toPath())
        if (ast != null) {
            // 写入缓存
            val newCached = CachedAst(
                ast = ast,
                fileModified = file.lastModified(),
                cachedAt = System.currentTimeMillis(),
                ttl = config.defaultTtl
            )

            // 异步写入 L1 和 L2
            l1Cache.put(cacheKey, newCached)
            saveToL2(cacheKey, newCached)
        }

        return ast
    }

    /**
     * 预加载 AST 到缓存
     */
    suspend fun preload(file: File) {
        getAst(file)
    }

    /**
     * 批量预加载
     */
    suspend fun preloadBatch(files: List<File>) = withContext(Dispatchers.IO) {
        files.forEach { file ->
            try {
                preload(file)
            } catch (e: Exception) {
                logger.warn("预加载失败: file={}", file.name, e)
            }
        }
    }

    /**
     * 从 L2 加载缓存
     */
    private fun loadFromL2(key: String): CachedAst? {
        return try {
            val cacheFile = getL2CacheFile(key)
            if (!cacheFile.exists()) {
                return null
            }

            ObjectInputStream(cacheFile.inputStream()).use { ois ->
                @Suppress("UNCHECKED_CAST")
                ois.readObject() as? CachedAst
            }
        } catch (e: Exception) {
            logger.debug("L2 缓存加载失败: key={}", key, e)
            null
        }
    }

    /**
     * 保存到 L2 缓存
     */
    private fun saveToL2(key: String, cached: CachedAst) {
        try {
            val cacheFile = getL2CacheFile(key)
            ObjectOutputStream(cacheFile.outputStream()).use { oos ->
                oos.writeObject(cached)
            }
        } catch (e: Exception) {
            logger.warn("L2 缓存保存失败: key={}", key, e)
        }
    }

    /**
     * 获取 L2 缓存文件
     */
    private fun getL2CacheFile(key: String): File {
        // 使用文件名的哈希作为缓存文件名，避免路径过长
        val hash = key.hashCode().toString(16)
        return File(l2CacheDir, "ast_$hash.cache")
    }

    /**
     * 使缓存失效
     */
    fun invalidate(file: File) {
        val key = file.absolutePath
        l1Cache.remove(key)

        val cacheFile = getL2CacheFile(key)
        if (cacheFile.exists()) {
            cacheFile.delete()
        }

        accessCounter.remove(key)

        logger.debug("缓存已失效: file={}", file.name)
    }

    /**
     * 清空所有缓存
     */
    fun clear() {
        l1Cache.clear()

        // 清空 L2 缓存目录
        l2CacheDir.listFiles()?.forEach { it.delete() }

        accessCounter.clear()

        logger.info("所有 AST 缓存已清空")
    }

    /**
     * 清理过期缓存
     */
    suspend fun cleanupExpired() = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()

        // 清理 L1 中的过期缓存
        l1Cache.entries.removeIf { (_, cached) ->
            cached.isExpired(now)
        }

        // 清理 L2 中的过期缓存
        l2CacheDir.listFiles()?.forEach { file ->
            try {
                val cached = loadFromL2(file.absolutePath.removePrefix(l2CacheDir.absolutePath).removePrefix("/").removeSuffix(".cache").replace("ast_", "").toInt(16).toString())
                // 简化实现：直接删除所有 L2 缓存
                file.delete()
            } catch (e: Exception) {
                logger.debug("清理 L2 缓存失败: file={}", file.name, e)
            }
        }

        logger.info("过期 AST 缓存已清理")
    }

    /**
     * 获取缓存统计
     */
    fun getStats(): Map<String, Any> {
        val total = hitCounter.get() + missCounter.get()
        val hitRate = if (total > 0) hitCounter.get().toDouble() / total else 0.0

        return mapOf(
            "L1_size" to l1Cache.size(),
            "L1_maxSize" to config.l1MaxSize,
            "L2_cache_dir" to l2CacheDir.absolutePath,
            "L2_file_count" to (l2CacheDir.listFiles()?.size ?: 0),
            "total_requests" to total,
            "hits" to hitCounter.get(),
            "misses" to missCounter.get(),
            "hit_rate" to hitRate
        )
    }

    /**
     * 热点分析
     */
    fun getHotFiles(limit: Int = 10): List<String> {
        return accessCounter.entries
            .sortedByDescending { it.value.get() }
            .take(limit)
            .map { it.key }
    }
}

/**
 * 缓存的 AST 数据
 */
@Suppress("SERIALIZABLE")
data class CachedAst(
    val ast: ClassAstInfo,
    val fileModified: Long,
    val cachedAt: Long,
    val ttl: Long
) : java.io.Serializable {
    /**
     * 检查是否过期
     */
    fun isExpired(now: Long = System.currentTimeMillis()): Boolean {
        return (now - cachedAt) > ttl
    }
}

/**
 * LRU 缓存实现（用于 AST）
 */
class LRUCache<K, V>(
    private val maxSize: Int
) {
    private val cache: MutableMap<K, V> =
        object : LinkedHashMap<K, V>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>): Boolean {
                return size > maxSize
            }
        }

    fun put(key: K, value: V) {
        synchronized(cache) {
            cache[key] = value
        }
    }

    fun get(key: K): V? {
        synchronized(cache) {
            return cache[key]
        }
    }

    fun remove(key: K) {
        synchronized(cache) {
            cache.remove(key)
        }
    }

    fun contains(key: K): Boolean {
        synchronized(cache) {
            return cache.containsKey(key)
        }
    }

    fun values(): Collection<V> {
        synchronized(cache) {
            return cache.values.toList()
        }
    }

    val entries: MutableSet<Map.Entry<K, V>>
        get() = synchronized(cache) {
            cache.entries.toMutableSet()
        }

    fun size(): Int {
        synchronized(cache) {
            return cache.size
        }
    }

    fun clear() {
        synchronized(cache) {
            cache.clear()
        }
    }
}

/**
 * AST 缓存配置
 */
data class AstCacheConfig(
    val l1MaxSize: Int = 100,
    val l2CachePath: String = "${System.getProperty("user.home")}/.smanunion/ast_cache",
    val defaultTtl: Long = 30 * 60 * 1000 // 30 分钟
)
