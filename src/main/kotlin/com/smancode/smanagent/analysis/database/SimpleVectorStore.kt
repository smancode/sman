package com.smancode.smanagent.analysis.database

import com.smancode.smanagent.analysis.config.VectorDatabaseConfig
import com.smancode.smanagent.analysis.model.VectorFragment
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.sqrt

/**
 * 简化的向量存储实现（临时方案）
 *
 * TODO: 替换为完整的 JVector 实现
 */
class SimpleVectorStore(
    private val config: VectorDatabaseConfig
) : VectorStoreService {

    private val logger = LoggerFactory.getLogger(SimpleVectorStore::class.java)
    private val vectorMap: MutableMap<String, VectorFragment> = ConcurrentHashMap()

    override fun add(fragment: VectorFragment) {
        require(fragment.id.isNotBlank()) {
            "向量片段 id 不能为空"
        }
        vectorMap[fragment.id] = fragment
        logger.debug("向量片段已添加: id={}", fragment.id)
    }

    override fun get(id: String): VectorFragment? {
        return vectorMap[id]
    }

    override fun search(query: FloatArray, topK: Int): List<VectorFragment> {
        require(query.size > 0) {
            "查询向量不能为空"
        }
        require(topK > 0) {
            "topK 必须大于 0，当前值: $topK"
        }

        // 简单实现：计算余弦相似度并返回 topK
        return vectorMap.values
            .mapNotNull { frag ->
                val vector = frag.vector
                if (vector != null) {
                    val similarity = cosineSimilarity(query, vector)
                    frag to similarity
                } else {
                    null
                }
            }
            .sortedByDescending { it.second }
            .take(topK)
            .map { it.first }
    }

    override fun contains(id: String): Boolean {
        return vectorMap.containsKey(id)
    }

    override fun close() {
        vectorMap.clear()
        logger.info("SimpleVectorStore 已关闭")
    }

    /**
     * 计算余弦相似度
     */
    private fun cosineSimilarity(v1: FloatArray, v2: FloatArray): Float {
        if (v1.size != v2.size) {
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

    fun getStats(): Map<String, Any> {
        return mapOf(
            "totalVectors" to vectorMap.size,
            "dimension" to config.vectorDimension
        )
    }
}
