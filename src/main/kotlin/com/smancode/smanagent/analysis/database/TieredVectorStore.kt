package com.smancode.smanagent.analysis.database

import com.smancode.smanagent.analysis.config.VectorDatabaseConfig
import com.smancode.smanagent.analysis.model.VectorFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.sqrt

/**
 * 分层向量存储服务
 *
 * 三层缓存架构：
 * - L1 (Hot): 内存 LRU 缓存，快速访问
 * - L2 (Warm): 向量索引，中等访问速度
 * - L3 (Cold): H2 数据库，持久化存储，慢速访问
 *
 * 防止内存爆炸的核心设计！
 */
class TieredVectorStore(
    private val config: VectorDatabaseConfig
) : VectorStoreService {

    private val logger = LoggerFactory.getLogger(TieredVectorStore::class.java)

    // L1: 热数据缓存（内存 LRU）
    private val l1Cache: LRUCache<String, VectorFragment>

    // L2: 温数据存储（向量索引）
    private val l2Store: SimpleVectorStore

    // L3: 冷数据存储（H2）
    private val l3Store: H2DatabaseService

    // 缓存层级配置
    private val l1MaxSize: Int = config.l1CacheSize
    private val l1AccessThreshold: Int = config.l1AccessThreshold
    private val l2AccessThreshold: Int = config.l2AccessThreshold

    // 访问计数
    private val accessCounter: ConcurrentHashMap<String, AtomicLong> = ConcurrentHashMap()

    // 后台写入队列
    private val writeQueue: LinkedBlockingQueue<VectorFragment> = LinkedBlockingQueue()

    init {
        l1Cache = LRUCache(l1MaxSize)
        l2Store = SimpleVectorStore(config)
        l3Store = H2DatabaseService(config)

        // 启动后台写入线程
        startBackgroundWriter()

        logger.info("分层向量存储初始化完成: L1={}, L2=SimpleVector, L3=H2", l1MaxSize)
    }

    /**
     * 添加向量片段
     */
    override fun add(fragment: VectorFragment) {
        require(fragment.id.isNotBlank()) {
            "向量片段 id 不能为空"
        }

        // 初始化访问计数
        accessCounter.putIfAbsent(fragment.id, AtomicLong(0))

        // L1: 写入热缓存
        l1Cache.put(fragment.id, fragment)

        // L2: 加入后台写入队列
        writeQueue.offer(fragment)

        logger.debug("向量片段已添加到 L1: id={}", fragment.id)
    }

    /**
     * 获取向量片段
     */
    override fun get(id: String): VectorFragment? {
        require(id.isNotBlank()) {
            "向量片段 id 不能为空"
        }

        // 增加访问计数
        accessCounter.getOrPut(id) { AtomicLong(0) }.incrementAndGet()

        // L1: 查询热缓存
        var fragment = l1Cache.get(id)
        if (fragment != null) {
            logger.debug("L1 命中: id={}", id)
            return fragment
        }

        // L2: 查询温数据
        fragment = l2Store.get(id)
        if (fragment != null) {
            logger.debug("L2 命中，升级到 L1: id={}", id)
            l1Cache.put(id, fragment)
            return fragment
        }

        // L3: 查询冷数据（异步）
        logger.debug("L3 查询: id={}", id)
        val l3Fragment = kotlinx.coroutines.runBlocking {
            l3Store.getVectorFragment(id)
        }

        if (l3Fragment != null) {
            logger.debug("L3 命中，升级到 L2: id={}", id)
            // L3 → L2: 加入写入队列
            writeQueue.offer(l3Fragment)
            l1Cache.put(id, l3Fragment)
        }

        return l3Fragment
    }

    /**
     * 搜索向量
     */
    override fun search(query: FloatArray, topK: Int): List<VectorFragment> {
        require(query.size > 0) {
            "查询向量不能为空"
        }
        require(topK > 0) {
            "topK 必须大于 0，当前值: $topK"
        }

        val results = mutableListOf<VectorFragment>()

        // L1: 热缓存搜索
        val l1Results = l1Cache.values()
            .map { it to cosineSimilarity(query, it.vector) }
            .sortedByDescending { it.second }
            .take(topK)
            .map { it.first }

        results.addAll(l1Results)

        // 如果 L1 结果不足，从 L2 搜索
        if (results.size < topK) {
            val remaining = topK - results.size
            val existingIds = results.map { it.id }.toSet()

            val l2Results = l2Store.search(query, topK)
                .filter { it.id !in existingIds }

            results.addAll(l2Results)
        }

        logger.debug("搜索完成: 请求={}, 返回={}", topK, results.size)
        return results
    }

    /**
     * 检查片段是否存在
     */
    override fun contains(id: String): Boolean {
        return l1Cache.contains(id) || l2Store.contains(id)
    }

    /**
     * 计算余弦相似度
     */
    private fun cosineSimilarity(v1: FloatArray, v2: FloatArray?): Float {
        if (v2 == null || v1.size != v2.size) {
            return 0f
        }

        var dotProduct = 0f
        var norm1 = 0f
        var norm2 = 0f

        for (i in v1.indices) {
            dotProduct += v1[i] * v2[i]
            norm1 += v1[i] * v1[i]
            norm2 += v2[i] * v2[i]
        }

        return if (norm1 == 0f || norm2 == 0f) {
            0f
        } else {
            dotProduct / (sqrt(norm1) * sqrt(norm2))
        }
    }

    /**
     * 启动后台写入线程
     */
    private fun startBackgroundWriter() {
        val thread = Thread {
            while (!Thread.currentThread().isInterrupted) {
                try {
                    val fragment = writeQueue.take()
                    // 简化：直接写入 L2 和 L3
                    l2Store.add(fragment)
                    kotlinx.coroutines.runBlocking {
                        l3Store.saveVectorFragment(fragment)
                    }
                } catch (e: InterruptedException) {
                    logger.info("后台写入线程被中断")
                    break
                } catch (e: Exception) {
                    logger.error("后台写入失败", e)
                }
            }
        }
        thread.name = "TieredVectorStore-Writer"
        thread.isDaemon = true
        thread.start()
    }

    /**
     * 获取缓存统计信息
     */
    fun getCacheStats(): Map<String, Any> {
        return mapOf(
            "L1_size" to l1Cache.size(),
            "L1_maxSize" to l1MaxSize,
            "L2_stats" to l2Store.getStats(),
            "writeQueue_size" to writeQueue.size,
            "accessCounter_size" to accessCounter.size
        )
    }

    /**
     * 关闭存储
     */
    override fun close() {
        // 停止后台写入线程
        writeQueue.clear()

        // 关闭各层存储
        l1Cache.clear()
        l2Store.close()
        l3Store.close()

        logger.info("分层向量存储已关闭")
    }
}

/**
 * LRU 缓存实现
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

    @Synchronized
    fun put(key: K, value: V) {
        cache[key] = value
    }

    @Synchronized
    fun get(key: K): V? {
        return cache[key]
    }

    @Synchronized
    fun contains(key: K): Boolean {
        return cache.containsKey(key)
    }

    @Synchronized
    fun values(): Collection<V> {
        return cache.values.toList()
    }

    @Synchronized
    fun size(): Int {
        return cache.size
    }

    @Synchronized
    fun clear() {
        cache.clear()
    }
}

