package com.smancode.sman.analysis.database

import com.smancode.sman.analysis.config.VectorDatabaseConfig
import com.smancode.sman.analysis.model.VectorFragment
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * 向量存储服务接口
 */
interface VectorStoreService {
    /**
     * 添加向量片段
     *
     * @param fragment 向量片段
     */
    fun add(fragment: VectorFragment)

    /**
     * 获取向量片段
     *
     * @param id 片段 ID
     * @return 向量片段，如果不存在返回 null
     */
    fun get(id: String): VectorFragment?

    /**
     * 搜索向量
     *
     * @param query 查询向量
     * @param topK 返回 top K
     * @return 搜索结果列表
     */
    fun search(query: FloatArray, topK: Int): List<VectorFragment>

    /**
     * 检查片段是否存在
     *
     * @param id 片段 ID
     * @return 如果存在返回 true
     */
    fun contains(id: String): Boolean

    /**
     * 删除向量片段
     *
     * @param id 片段 ID（支持前缀匹配，如 "autoloop:api_entry" 会删除所有以该前缀开头的向量）
     */
    fun delete(id: String)

    /**
     * 关闭存储
     */
    fun close()
}

/**
 * 内存向量存储实现（用于测试）
 */
class MemoryVectorStore(
    private val config: VectorDatabaseConfig
) : VectorStoreService {

    private val logger = LoggerFactory.getLogger(MemoryVectorStore::class.java)
    private val store: MutableMap<String, VectorFragment> = ConcurrentHashMap()

    override fun add(fragment: VectorFragment) {
        // 白名单校验
        require(fragment.id.isNotBlank()) {
            "向量片段 id 不能为空"
        }
        require(fragment.title.isNotBlank()) {
            "向量片段 title 不能为空"
        }
        require(fragment.content.isNotBlank()) {
            "向量片段 content 不能为空"
        }

        store[fragment.id] = fragment
        logger.debug("Added vector fragment: id=${fragment.id}, title=${fragment.title}")
    }

    override fun get(id: String): VectorFragment? {
        return store[id]
    }

    override fun search(query: FloatArray, topK: Int): List<VectorFragment> {
        // 白名单校验
        require(query.size > 0) {
            "查询向量不能为空"
        }
        require(topK > 0) {
            "topK 必须大于 0，当前值: $topK"
        }

        // 简单实现：返回所有片段（实际应该使用向量相似度）
        return store.values
            .take(topK)
            .also {
                logger.debug("Search returned ${it.size} fragments")
            }
    }

    override fun contains(id: String): Boolean {
        return store.containsKey(id)
    }

    override fun close() {
        store.clear()
        logger.info("MemoryVectorStore closed")
    }

    override fun delete(id: String) {
        // 支持前缀匹配删除
        val keysToDelete = if (id.endsWith(":")) {
            // 精确匹配
            listOf(id)
        } else {
            // 前缀匹配（如 "autoloop:api_entry" 会删除所有以该前缀开头的向量）
            store.keys.filter { it.startsWith(id) }
        }

        keysToDelete.forEach { store.remove(it) }
        logger.info("Deleted {} vector fragments with prefix: {}", keysToDelete.size, id)
    }
}
