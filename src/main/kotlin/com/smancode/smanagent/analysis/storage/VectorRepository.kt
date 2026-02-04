package com.smancode.smanagent.analysis.storage

import com.smancode.smanagent.analysis.config.VectorDatabaseConfig
import com.smancode.smanagent.analysis.database.H2DatabaseService
import com.smancode.smanagent.analysis.database.TieredVectorStore
import com.smancode.smanagent.analysis.model.VectorFragment
import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlinx.coroutines.runBlocking

/**
 * 向量存储仓库接口
 *
 * 职责：
 * - 统一的向量 CRUD 接口
 * - 自动处理 H2 持久化
 * - 支持通配符删除
 * - 清理旧向量
 */
interface VectorRepository {
    /**
     * 添加向量片段
     */
    fun add(fragment: VectorFragment)

    /**
     * 获取向量片段
     */
    fun get(id: String): VectorFragment?

    /**
     * 删除向量片段（支持前缀匹配）
     */
    fun delete(id: String)

    /**
     * 按通配符模式删除向量
     * @param pattern 通配符模式，如 "%.md%" 删除所有包含 .md 的向量
     * @return 删除的数量
     */
    fun deleteByPattern(pattern: String): Int

    /**
     * 清理所有包含 .md 后缀的旧向量
     * @return 删除的数量
     */
    fun cleanupMdVectors(): Int

    /**
     * 搜索向量
     */
    fun search(query: FloatArray, topK: Int): List<VectorFragment>

    /**
     * 关闭存储
     */
    fun close()
}

/**
 * 分层向量存储仓库实现
 *
 * 基于 TieredVectorStore 实现，增加 H2 查询能力
 */
class TieredVectorRepository(
    private val projectKey: String,
    private val projectPath: Path,
    private val config: VectorDatabaseConfig
) : VectorRepository {

    private val logger = LoggerFactory.getLogger(TieredVectorRepository::class.java)

    private val vectorStore: TieredVectorStore = TieredVectorStore(config)

    private val h2Service: H2DatabaseService = H2DatabaseService(config)

    companion object {
        // MD 向量删除模式常量
        private const val MD_VECTOR_PATTERN = "%.md%"
    }

    init {
        logger.info("TieredVectorRepository 初始化完成: projectKey={}", projectKey)
    }

    override fun add(fragment: VectorFragment) {
        validateFragment(fragment)

        // 添加到向量存储（会异步持久化到 H2）
        vectorStore.add(fragment)

        // 立即持久化到 H2（确保 deleteByPattern 能查到）
        runBlocking {
            try {
                h2Service.saveVectorFragment(fragment)
            } catch (e: Exception) {
                logger.warn("保存向量到 H2 失败: id={}, error={}", fragment.id, e.message)
            }
        }

        logger.debug("添加向量: id={}", fragment.id)
    }

    override fun get(id: String): VectorFragment? {
        return vectorStore.get(id)
    }

    override fun delete(id: String) {
        vectorStore.delete(id)
        logger.debug("删除向量: id={}", id)
    }

    override fun deleteByPattern(pattern: String): Int {
        logger.info("按模式删除向量: pattern={}", pattern)

        val regexPattern = convertSqlLikeToRegex(pattern)

        // 从 H2 获取所有向量 ID（包括尚未持久化到 L2/L3 的）
        val allVectorIds = runBlocking {
            h2Service.getAllVectorFragments().map { it.id }
        }

        val idsToDelete = allVectorIds
            .filter { regexPattern.containsMatchIn(it) }

        logger.info("找到 {} 个匹配的向量", idsToDelete.size)

        var deletedCount = 0
        for (id in idsToDelete) {
            try {
                vectorStore.delete(id)
                deletedCount++
            } catch (e: Exception) {
                logger.warn("删除向量失败: id={}, error={}", id, e.message)
            }
        }

        logger.info("按模式删除完成: pattern={}, deletedCount={}", pattern, deletedCount)
        return deletedCount
    }

    override fun cleanupMdVectors(): Int {
        logger.info("清理所有 .md 向量")
        return deleteByPattern(MD_VECTOR_PATTERN)
    }

    override fun search(query: FloatArray, topK: Int): List<VectorFragment> {
        require(query.isNotEmpty()) { "查询向量不能为空" }
        require(topK > 0) { "topK 必须大于 0，当前值: $topK" }

        return vectorStore.search(query, topK)
    }

    override fun close() {
        closeQuietly(vectorStore, "向量存储")
        closeQuietly(h2Service, "H2 服务")
        logger.info("TieredVectorRepository 已关闭")
    }

    // ========== 辅助方法 ==========

    /**
     * 验证向量片段
     */
    private fun validateFragment(fragment: VectorFragment) {
        require(fragment.id.isNotBlank()) { "向量片段 id 不能为空" }
        require(fragment.title.isNotBlank()) { "向量片段 title 不能为空" }
        require(fragment.content.isNotBlank()) { "向量片段 content 不能为空" }
    }

    /**
     * 将 SQL LIKE 模式转换为正则表达式
     *
     * 转换规则：
     * - %.md% -> .*\.md.* (包含 .md 的任何地方)
     * - % -> .*
     * - . -> \\. (转义点号)
     */
    private fun convertSqlLikeToRegex(pattern: String): Regex {
        val regexPattern = pattern
            .replace(".", "\\.")
            .replace("%", ".*")

        return Regex(regexPattern)
    }

    /**
     * 安静地关闭资源
     */
    private fun closeQuietly(closeable: Any, name: String) {
        try {
            when (closeable) {
                is AutoCloseable -> closeable.close()
                is java.io.Closeable -> closeable.close()
            }
        } catch (e: Exception) {
            logger.warn("关闭 {} 失败: {}", name, e.message)
        }
    }
}
