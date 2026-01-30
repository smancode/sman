package com.smancode.smanagent.analysis.database

import com.smancode.smanagent.analysis.config.VectorDatabaseConfig
import com.smancode.smanagent.analysis.model.VectorFragment
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
    private val vts: VectorTypeSupport = VectorizationProvider.getInstance().vectorTypeSupport

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
    private val M: Int = config.jvector.M
    private val efConstruction: Int = config.jvector.efConstruction
    private val rerankerThreshold: Double = config.jvector.rerankerThreshold

    // 索引构建标志
    @Volatile
    private var indexBuilt: Boolean = false

    init {
        logger.info(
            "JVectorStore 初始化: dimension={}, M={}, efConstruction={}, rerankerThreshold={}",
            dimension, M, efConstruction, rerankerThreshold
        )
    }

    /**
     * 添加向量片段
     */
    override fun add(fragment: VectorFragment) {
        require(fragment.id.isNotBlank()) {
            "向量片段 id 不能为空"
        }
        require(fragment.vector != null && fragment.vector!!.size == dimension) {
            "向量维度不匹配: 期望 $dimension, 实际 ${fragment.vector!!.size}"
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
            if (size < M) {
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
     * 确保索引已构建
     */
    private fun ensureIndexBuilt() {
        // 双重检查锁定模式
        if (indexBuilt && graphIndex != null) {
            return
        }

        lock.write {
            if (indexBuilt && graphIndex != null) {
                return
            }

            buildIndex()
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
            // 创建向量列表（按 ordinal 排序）
            val vectorList: MutableList<VectorFloat<*>> = ArrayList()
            (0 until size).forEach { ordinal ->
                val id = ordinalToId[ordinal]
                    ?: throw IllegalStateException("Missing ID for ordinal: $ordinal")
                val fragment = vectors[id]
                    ?: throw IllegalStateException("Missing vector for ID: $id")
                val vectorArray = fragment.vector!!
                vectorList.add(createVectorFloat(vectorArray))
            }

            // 创建 RandomAccessVectorValues
            val ravv = ListRandomAccessVectorValues(vectorList, dimension)

            // 创建 BuildScoreProvider
            val bsp = BuildScoreProvider.randomAccessScoreProvider(
                ravv,
                VectorSimilarityFunction.DOT_PRODUCT
            )

            // 创建 GraphIndexBuilder
            val builder = GraphIndexBuilder(
                bsp,           // BuildScoreProvider
                dimension,     // dimension
                M,             // maxDegree
                efConstruction, // searchConcurrency
                1.2f,          // overflowFactor
                1.2f           // neighborOverlap
            )

            // 构建索引
            graphIndex = builder.build(ravv)
            builder.close()

            indexBuilt = true

            logger.info("HNSW 索引构建完成: 节点数量={}", size)
        } catch (e: Exception) {
            logger.error("HNSW 索引构建失败", e)
            throw e
        }
    }

    /**
     * 创建 VectorFloat（使用 VectorTypeSupport）
     */
    private fun createVectorFloat(floatArray: FloatArray): VectorFloat<*> {
        val vector = vts.createFloatVector(floatArray.size)
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
        val vectorList: MutableList<VectorFloat<*>> = ArrayList()
        (0 until vectors.size).forEach { ordinal ->
            val id = ordinalToId[ordinal]
                ?: throw IllegalStateException("Missing ID for ordinal: $ordinal")
            val fragment = vectors[id]
                ?: throw IllegalStateException("Missing vector for ID: $id")
            val vectorArray = fragment.vector!!
            vectorList.add(createVectorFloat(vectorArray))
        }

        return ListRandomAccessVectorValues(vectorList, dimension)
    }

    /**
     * 获取统计信息
     */
    fun getStats(): Map<String, Any> = mapOf(
        "totalVectors" to vectors.size,
        "dimension" to dimension,
        "M" to M,
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
