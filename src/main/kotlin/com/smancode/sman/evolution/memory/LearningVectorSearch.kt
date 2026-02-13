package com.smancode.sman.evolution.memory

import com.smancode.sman.analysis.database.TieredVectorStore
import com.smancode.sman.analysis.database.cosineSimilarity
import com.smancode.sman.evolution.model.LearningRecord
import com.smancode.sman.analysis.vectorization.BgeM3Client
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

/**
 * 学习记录向量搜索服务
 *
 * 提供基于向量相似度的学习记录搜索功能
 */
class LearningVectorSearch(
    private val vectorStore: TieredVectorStore,
    private val recordRepository: LearningRecordRepository,
    private val bgeM3Client: BgeM3Client
) {
    private val logger = LoggerFactory.getLogger(LearningVectorSearch::class.java)

    companion object {
        /** 学习记录向量 ID 前缀 */
        const val LEARNING_RECORD_ID_PREFIX = "learning_record:"
    }

    /**
     * 向量相似度搜索
     *
     * @param queryVector 查询向量
     * @param projectKey 项目标识（可选，用于过滤）
     * @param topK 返回数量
     * @return 带分数的学习记录列表
     * @throws IllegalArgumentException 如果参数不合法
     */
    suspend fun searchByVector(
        queryVector: FloatArray,
        projectKey: String?,
        topK: Int
    ): List<LearningRecordWithScore> = withContext(Dispatchers.IO) {
        require(queryVector.isNotEmpty()) {
            "查询向量不能为空"
        }
        require(topK > 0) {
            "topK 必须大于 0，当前值: $topK"
        }

        logger.debug("开始向量搜索: projectKey={}, topK={}", projectKey, topK)

        // 从向量存储搜索
        val vectorResults = vectorStore.search(queryVector, topK * 2)

        // 过滤学习记录类型的向量
        val learningRecordIds = vectorResults
            .filter { it.id.startsWith(LEARNING_RECORD_ID_PREFIX) }
            .map { it.id.removePrefix(LEARNING_RECORD_ID_PREFIX) }

        // 批量获取学习记录
        val records = recordRepository.findByIds(learningRecordIds)

        // 按 projectKey 过滤并计算分数
        val results = records
            .filter { projectKey == null || it.projectKey == projectKey }
            .mapNotNull { record ->
                val fragment = vectorResults.find {
                    it.id == "$LEARNING_RECORD_ID_PREFIX${record.id}"
                }
                val score = fragment?.vector?.let { queryVector.cosineSimilarity(it) }
                if (score != null && score > 0f) {
                    LearningRecordWithScore(record, score.toDouble())
                } else {
                    null
                }
            }
            .sortedByDescending { it.score }
            .take(topK)

        logger.debug("向量搜索完成: 返回 {} 条记录", results.size)
        results
    }

    /**
     * 语义搜索（文本 -> 向量 -> 搜索）
     *
     * @param query 查询文本
     * @param projectKey 项目标识（可选，用于过滤）
     * @param topK 返回数量
     * @return 带分数的学习记录列表
     * @throws IllegalArgumentException 如果参数不合法
     */
    suspend fun searchSemantic(
        query: String,
        projectKey: String?,
        topK: Int
    ): List<LearningRecordWithScore> {
        require(query.isNotBlank()) {
            "查询文本不能为空"
        }
        require(topK > 0) {
            "topK 必须大于 0，当前值: $topK"
        }

        logger.debug("开始语义搜索: query={}, projectKey={}, topK={}", query, projectKey, topK)

        // 文本向量化
        val queryVector = bgeM3Client.embed(query)

        // 执行向量搜索
        return searchByVector(queryVector, projectKey, topK)
    }
}

/**
 * 带分数的学习记录
 */
data class LearningRecordWithScore(
    val record: LearningRecord,
    val score: Double
)
