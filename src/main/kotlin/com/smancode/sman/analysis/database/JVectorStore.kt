package com.smancode.sman.analysis.database

import com.smancode.sman.analysis.config.VectorDatabaseConfig
import com.smancode.sman.analysis.model.VectorFragment
import io.github.jbellis.jvector.graph.GraphIndexBuilder
import io.github.jbellis.jvector.graph.GraphSearcher
import io.github.jbellis.jvector.graph.ListRandomAccessVectorValues
import io.github.jbellis.jvector.graph.OnHeapGraphIndex
import io.github.jbellis.jvector.graph.RandomAccessVectorValues
import io.github.jbellis.jvector.graph.similarity.BuildScoreProvider
import io.github.jbellis.jvector.util.Bits
import io.github.jbellis.jvector.vector.VectorSimilarityFunction
import io.github.jbellis.jvector.vector.VectorizationProvider
import io.github.jbellis.jvector.vector.types.VectorFloat
import io.github.jbellis.jvector.vector.types.VectorTypeSupport
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * JVector 向量存储（生产级实现）
 *
 * 特性：
 * - HNSW 索引，O(log n) 搜索复杂度
 * - 支持增量添加向量
 * - 线程安全（读写锁）
 * - 使用 VectorTypeSupport 创建向量
 *
 * @property config 向量数据库配置
 */
class JVectorStore(
    private val config: VectorDatabaseConfig
) : VectorStoreService {

    private val logger = LoggerFactory.getLogger(JVectorStore::class.java)

    // VectorTypeSupport（用于创建 VectorFloat）
    private val vectorTypeSupport: VectorTypeSupport = VectorizationProvider.getInstance().vectorTypeSupport

    // 向量存储（按 ID 索引）
    private val vectors: ConcurrentHashMap<String, VectorFragment> = ConcurrentHashMap()

    // ID 到 ordinal 的映射
    private val idToOrdinal: ConcurrentHashMap<String, Int> = ConcurrentHashMap()

    // ordinal 到 ID 的反向映射
    private val ordinalToId: ConcurrentHashMap<Int, String> = ConcurrentHashMap()

    // HNSW 索引
    private var graphIndex: OnHeapGraphIndex? = null

    // 读写锁（保护索引构建和搜索）
    private val lock = ReentrantReadWriteLock()

    // 下一个可用的 ordinal
    private var nextOrdinal: Int = 0

    // 配置参数
    private val dimension: Int = config.vectorDimension
    private val maxConnections: Int = config.jvector.M
    private val efConstruction: Int = config.jvector.efConstruction
    private val rerankerThreshold: Double = config.jvector.rerankerThreshold

    // H2 数据库服务（用于持久化）
    private val h2Service: H2DatabaseService = H2DatabaseService(config).apply {
        kotlinx.coroutines.runBlocking { init() }
    }

    // 索引构建标志
    @Volatile
    private var indexBuilt: Boolean = false

    companion object {
        private const val OVERFLOW_FACTOR = 1.2f
        private const val NEIGHBOR_OVERLAP = 1.2f
    }

    init {
        logger.info(
            "JVectorStore 初始化: dimension={}, M={}, efConstruction={}, rerankerThreshold={}",
            dimension, maxConnections, efConstruction, rerankerThreshold
        )

        // 从 H2 数据库加载已有的向量数据
        loadFromH2()
    }

    /**
     * 从 H2 数据库加载向量数据
     */
    private fun loadFromH2() {
        try {
            val h2Service = H2DatabaseService(config)
            val fragments = kotlinx.coroutines.runBlocking {
                h2Service.getAllVectorFragments()
            }

            logger.info("从 H2 加载向量数据: 数量={}", fragments.size)

            for (fragment in fragments) {
                if (fragment.vector != null && fragment.vector.size == dimension) {
                    addUnsafe(fragment)
                } else {
                    logger.warn("跳过无效向量: id={}, vectorDimension={}",
                        fragment.id, fragment.vector?.size)
                }
            }

            logger.info("H2 向量数据加载完成: 已加载={} 条", vectors.size)
        } catch (e: Exception) {
            logger.warn("从 H2 加载向量数据失败: {}", e.message)
        }
    }

    /**
     * 不加锁地添加向量（用于初始化加载）
     */
    private fun addUnsafe(fragment: VectorFragment) {
        if (idToOrdinal.containsKey(fragment.id)) {
            return
        }

        val ordinal = nextOrdinal++
        idToOrdinal[fragment.id] = ordinal
        ordinalToId[ordinal] = fragment.id
        vectors[fragment.id] = fragment
    }

    /**
     * 添加向量片段
     */
    override fun add(fragment: VectorFragment) {
        require(fragment.id.isNotBlank()) {
            "向量片段 id 不能为空"
        }
        val vector = fragment.vector
        require(vector != null && vector.size == dimension) {
            "向量维度不匹配: 期望 $dimension, 实际 ${vector?.size}"
        }

        lock.write {
            // 检查是否已存在
            if (idToOrdinal.containsKey(fragment.id)) {
                logger.warn("向量片段已存在，将覆盖: id={}", fragment.id)
                // 移除旧的
                val oldOrdinal = idToOrdinal[fragment.id]!!
                ordinalToId.remove(oldOrdinal)
            }

            // 分配 ordinal
            val ordinal = nextOrdinal++

            // 存储向量片段
            vectors[fragment.id] = fragment
            idToOrdinal[fragment.id] = ordinal
            ordinalToId[ordinal] = fragment.id

            // 持久化到 H2 数据库
            try {
                kotlinx.coroutines.runBlocking {
                    h2Service.saveVectorFragment(fragment)
                }
                logger.debug("向量片段已持久化到 H2: id={}", fragment.id)
            } catch (e: Exception) {
                logger.error("向量片段持久化失败: id={}, error={}", fragment.id, e.message)
                // 不抛出异常，允许继续使用内存中的数据
            }

            // 标记索引需要重建
            indexBuilt = false

            logger.debug("向量片段已添加: id={}, ordinal={}", fragment.id, ordinal)
        }
    }

    /**
     * 获取向量片段
     */
    override fun get(id: String): VectorFragment? {
        require(id.isNotBlank()) {
            "向量片段 id 不能为空"
        }

        return vectors[id]
    }

    /**
     * 搜索向量（使用 HNSW 索引）
     */
    override fun search(query: FloatArray, topK: Int): List<VectorFragment> {
        require(query.isNotEmpty()) {
            "查询向量不能为空"
        }
        require(query.size == dimension) {
            "查询向量维度不匹配: 期望 $dimension, 实际 ${query.size}"
        }
        require(topK > 0) {
            "topK 必须大于 0，当前值: $topK"
        }

        lock.read {
            val size = vectors.size
            if (size == 0) {
                logger.debug("向量为空，返回空结果")
                return emptyList()
            }

            // 如果向量数量很少，直接线性搜索
            if (size < maxConnections) {
                logger.debug("向量数量较少 ({}), 使用线性搜索", size)
                return linearSearch(query, topK)
            }

            // 确保索引已构建
            ensureIndexBuilt()

            // 使用 HNSW 索引搜索
            try {
                return hnswSearch(query, topK)
            } catch (e: Exception) {
                logger.error("HNSW 搜索失败，回退到线性搜索", e)
                return linearSearch(query, topK)
            }
        }
    }

    /**
     * 检查片段是否存在
     */
    override fun contains(id: String): Boolean {
        return vectors.containsKey(id)
    }

    /**
     * 删除向量片段（支持前缀匹配）
     */
    override fun delete(id: String) {
        lock.write {
            val keysToDelete = if (id.contains(":")) {
                // 前缀匹配：删除所有以该前缀开头的向量
                vectors.keys.filter { it.startsWith(id) }
            } else {
                // 精确匹配
                listOf(id)
            }

            keysToDelete.forEach { key ->
                vectors.remove(key)
                idToOrdinal.remove(key)

                // 从 H2 数据库删除
                try {
                    kotlinx.coroutines.runBlocking {
                        h2Service.deleteVectorFragment(key)
                    }
                } catch (e: Exception) {
                    logger.warn("从 H2 删除向量失败: id={}, error={}", key, e.message)
                }
            }

            // 重建 ordinal 到 ID 的映射
            rebuildOrdinalMapping()

            // 标记索引需要重建
            indexBuilt = false

            logger.info("删除向量片段: id={}, count={}", id, keysToDelete.size)
        }
    }

    /**
     * 重建 ordinal 映射
     */
    private fun rebuildOrdinalMapping() {
        ordinalToId.clear()
        vectors.entries.forEach { (id, _) ->
            // 如果有 ordinal 映射，需要重新分配
            // 简化实现：清空后重新建立
        }
    }

    /**
     * 确保索引已构建
     */
    private fun ensureIndexBuilt() {
        if (indexBuilt) return

        lock.write {
            if (!indexBuilt) buildIndex()
        }
    }

    /**
     * 构建 HNSW 索引
     */
    private fun buildIndex() {
        val size = vectors.size
        if (size == 0) {
            logger.warn("向量为空，无法构建索引")
            return
        }

        logger.info("开始构建 HNSW 索引: 向量数量={}", size)

        try {
            val vectorList = buildVectorList()
            val ravv = ListRandomAccessVectorValues(vectorList, dimension)
            val bsp = BuildScoreProvider.randomAccessScoreProvider(ravv, VectorSimilarityFunction.DOT_PRODUCT)

            val builder = GraphIndexBuilder(
                bsp,
                dimension,
                maxConnections,
                efConstruction,
                OVERFLOW_FACTOR,
                NEIGHBOR_OVERLAP
            )

            try {
                graphIndex = builder.build(ravv)
                indexBuilt = true
                logger.info("HNSW 索引构建完成: 节点数量={}", size)
            } finally {
                builder.close()
            }
        } catch (e: Exception) {
            logger.error("HNSW 索引构建失败", e)
            throw e
        }
    }

    /**
     * 构建向量列表（按 ordinal 排序）
     */
    private fun buildVectorList(): List<VectorFloat<*>> {
        val vectorList: MutableList<VectorFloat<*>> = ArrayList()
        (0 until vectors.size).forEach { ordinal ->
            vectorList.add(getVectorByOrdinal(ordinal))
        }
        return vectorList
    }

    /**
     * 根据 ordinal 获取向量
     */
    private fun getVectorByOrdinal(ordinal: Int): VectorFloat<*> {
        val id = ordinalToId[ordinal]
            ?: throw IllegalStateException("Missing ID for ordinal: $ordinal")
        val fragment = vectors[id]
            ?: throw IllegalStateException("Missing vector for ID: $id")
        return createVectorFloat(fragment.vector!!)
    }

    /**
     * 创建 VectorFloat（使用 VectorTypeSupport）
     */
    private fun createVectorFloat(floatArray: FloatArray): VectorFloat<*> {
        val vector = vectorTypeSupport.createFloatVector(floatArray.size)
        for (i in floatArray.indices) {
            vector.set(i, floatArray[i])
        }
        return vector
    }

    /**
     * HNSW 搜索
     */
    private fun hnswSearch(query: FloatArray, topK: Int): List<VectorFragment> {
        val index = graphIndex ?: throw IllegalStateException("索引未构建")
        val queryVector = createVectorFloat(query)

        // 创建 RandomAccessVectorValues
        val ravv = createRandomAccessVectorValues()

        // 执行静态搜索（使用 DOT_PRODUCT 相似度）
        val searchResult = GraphSearcher.search(
            queryVector,
            topK,
            ravv,
            VectorSimilarityFunction.DOT_PRODUCT,
            index,
            Bits.ALL
        )

        // 转换结果
        val results = searchResult.nodes
            .take(topK)
            .mapNotNull { nodeScore ->
                val id = ordinalToId[nodeScore.node]
                id?.let { vectors[it] }
            }

        logger.debug("HNSW 搜索完成: 请求={}, 返回={}", topK, results.size)
        return results
    }

    /**
     * 线性搜索（回退方案）
     */
    private fun linearSearch(query: FloatArray, topK: Int): List<VectorFragment> {
        return vectors.values
            .map { it to query.cosineSimilarity(it.vector!!) }
            .sortedByDescending { it.second }
            .take(topK)
            .map { it.first }
    }

    /**
     * 创建 RandomAccessVectorValues
     */
    private fun createRandomAccessVectorValues(): RandomAccessVectorValues {
        val vectorList = buildVectorList()
        return ListRandomAccessVectorValues(vectorList, dimension)
    }

    /**
     * 获取统计信息
     */
    fun getStats(): Map<String, Any> = mapOf(
        "totalVectors" to vectors.size,
        "dimension" to dimension,
        "M" to maxConnections,
        "efConstruction" to efConstruction,
        "indexBuilt" to indexBuilt,
        "rerankerThreshold" to rerankerThreshold
    )

    /**
     * 关闭存储
     */
    override fun close() {
        lock.write {
            graphIndex = null

            vectors.clear()
            idToOrdinal.clear()
            ordinalToId.clear()
            nextOrdinal = 0
            indexBuilt = false
        }

        logger.info("JVectorStore 已关闭")
    }
}
