package com.smancode.smanagent.analysis.database

import com.smancode.smanagent.analysis.config.VectorDatabaseConfig
import com.smancode.smanagent.analysis.model.VectorFragment
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

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
        require(query.isNotEmpty()) {
            "查询向量不能为空"
        }
        require(topK > 0) {
            "topK 必须大于 0，当前值: $topK"
        }

        return vectorMap.values
            .mapNotNull { frag ->
                frag.vector?.let { vector ->
                    frag to query.cosineSimilarity(vector)
                }
            }
            .sortedByDescending { it.second }
            .take(topK)
            .map { it.first }
    }

    override fun contains(id: String): Boolean {
        return id in vectorMap
    }

    override fun delete(id: String) {
        val keysToDelete = if (id.contains(":")) {
            vectorMap.keys.filter { it.startsWith(id) }
        } else {
            listOf(id)
        }
        keysToDelete.forEach { vectorMap.remove(it) }
        logger.info("删除向量片段: id={}, count={}", id, keysToDelete.size)
    }

    override fun close() {
        vectorMap.clear()
        logger.info("SimpleVectorStore 已关闭")
    }

    fun getStats(): Map<String, Any> = mapOf(
        "totalVectors" to vectorMap.size,
        "dimension" to config.vectorDimension
    )
}
