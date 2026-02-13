package com.smancode.sman.evolution.recall

import com.smancode.sman.analysis.config.VectorDatabaseConfig
import com.smancode.sman.analysis.database.TieredVectorStore
import com.smancode.sman.analysis.model.VectorFragment
import com.smancode.sman.analysis.vectorization.BgeM3Client
import com.smancode.sman.analysis.vectorization.RerankerClient
import com.smancode.sman.evolution.memory.LearningRecordRepository
import com.smancode.sman.evolution.model.LearningRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

/**
 * 用户意图（简化版）
 *
 * 用于多路召回的输入参数
 *
 * @property originalQuery 原始用户查询
 * @property domains 领域列表
 * @property keywords 关键词列表
 */
data class UserIntent(
    val originalQuery: String,
    val domains: List<String> = emptyList(),
    val keywords: List<String> = emptyList()
)

/**
 * 学习记录带分数
 *
 * @property record 学习记录
 * @property score 相似度分数 (0.0 - 1.0)
 */
data class LearningRecordWithScore(
    val record: LearningRecord,
    val score: Double
)

/**
 * 代码片段带分数
 *
 * @property fragment 向量片段
 * @property score 相似度分数 (0.0 - 1.0)
 */
data class CodeFragmentWithScore(
    val fragment: VectorFragment,
    val score: Double
)

/**
 * 领域知识（简化版）
 *
 * @property domain 领域名称
 * @property summary 领域知识摘要
 * @property keyFiles 关键文件列表
 */
data class DomainKnowledge(
    val domain: String,
    val summary: String,
    val keyFiles: List<String> = emptyList()
)

/**
 * 召回结果
 *
 * @property learningRecords 召回的学习记录（带分数）
 * @property domainKnowledge 领域知识列表
 * @property codeFragments 代码片段列表（带分数）
 */
data class RecallResult(
    val learningRecords: List<LearningRecordWithScore> = emptyList(),
    val domainKnowledge: List<DomainKnowledge> = emptyList(),
    val codeFragments: List<CodeFragmentWithScore> = emptyList()
)

/**
 * 多路召回器
 *
 * 职责：
 * - 向量语义搜索（主路）：使用 BGE-M3 进行语义召回
 * - 关键词匹配（兜底）：使用数据库 LIKE 查询
 * - 合并去重：合并多路结果，去除重复
 * - Rerank 精排：使用 Reranker 提升相关性
 *
 * 简化实现：
 * - 向量搜索：调用已有的 TieredVectorStore
 * - 关键词搜索：调用已有的 LearningRecordRepository
 */
class MultiPathRecaller(
    private val bgeM3Client: BgeM3Client,
    private val vectorStore: TieredVectorStore,
    private val recordRepository: LearningRecordRepository,
    private val rerankerClient: RerankerClient?,
    private val config: VectorDatabaseConfig
) {
    private val logger = LoggerFactory.getLogger(MultiPathRecaller::class.java)

    companion object {
        // 默认召回参数
        private const val DEFAULT_TOP_K = 10
        private const val VECTOR_RECALL_MULTIPLIER = 2  // 向量召回 2 倍，后续 Rerank
        private const val KEYWORD_SEARCH_LIMIT = 10
    }

    /**
     * 多路召回并合并
     *
     * @param projectKey 项目标识
     * @param intent 用户意图
     * @param topK 返回数量（默认 10）
     * @return 召回结果
     */
    suspend fun recall(
        projectKey: String,
        intent: UserIntent,
        topK: Int = DEFAULT_TOP_K
    ): RecallResult {
        require(projectKey.isNotBlank()) { "projectKey 不能为空" }
        require(intent.originalQuery.isNotBlank()) { "originalQuery 不能为空" }
        require(topK > 0) { "topK 必须大于 0" }

        logger.info("开始多路召回: projectKey={}, query={}, topK={}",
            projectKey, intent.originalQuery.take(50), topK)

        return try {
            coroutineScope {
                // 并行执行向量搜索和关键词搜索
                val deferredVector = async { searchByVector(intent.originalQuery, topK * VECTOR_RECALL_MULTIPLIER) }
                val deferredKeyword = async { searchByKeywords(projectKey, intent.keywords) }
                val deferredCode = async { searchCodeFragments(intent.originalQuery, topK) }

                // 等待所有召回完成
                val vectorResults = deferredVector.await()
                val keywordResults = deferredKeyword.await()
                val codeResults = deferredCode.await()

                logger.debug("召回完成: 向量={}, 关键词={}, 代码={}",
                    vectorResults.size, keywordResults.size, codeResults.size)

                // 合并去重学习记录
                val merged = mergeAndDeduplicate(vectorResults, keywordResults)

                // Rerank 精排
                val reranked = rerank(intent.originalQuery, merged, topK)

                // 构建领域知识（简化实现：从学习记录中提取）
                val domainKnowledge = buildDomainKnowledge(reranked, intent.domains)

                RecallResult(
                    learningRecords = reranked,
                    domainKnowledge = domainKnowledge,
                    codeFragments = codeResults
                )
            }
        } catch (e: Exception) {
            logger.error("多路召回失败: {}", e.message, e)
            RecallResult()  // 返回空结果
        }
    }

    /**
     * 向量语义搜索
     *
     * @param query 查询文本
     * @param topK 返回数量
     * @return 带分数的学习记录列表
     */
    private suspend fun searchByVector(query: String, topK: Int): List<LearningRecordWithScore> =
        withContext(Dispatchers.IO) {
            try {
                // 1. 向量化查询
                val queryVector = bgeM3Client.embed(query, "recall-query")

                // 2. 向量检索
                val fragments = vectorStore.search(queryVector, topK)

                logger.debug("向量搜索返回 {} 个结果", fragments.size)

                // 3. 转换为学习记录（简化实现：从向量片段 ID 中提取记录 ID）
                val results = mutableListOf<LearningRecordWithScore>()
                for (fragment in fragments) {
                    // 检查是否是学习记录类型的向量
                    if (fragment.metadata["type"] == "learning_record") {
                        val recordId = fragment.metadata["recordId"]
                        if (recordId != null) {
                            val record = recordRepository.findById(recordId)
                            if (record != null) {
                                // 计算相似度分数（简化：使用余弦相似度）
                                val score = calculateSimilarity(queryVector, fragment.vector)
                                results.add(LearningRecordWithScore(record, score))
                            }
                        }
                    } else {
                        // 非学习记录类型，也作为候选（但分数较低）
                        val score = calculateSimilarity(queryVector, fragment.vector) * 0.5
                        // 创建一个简化的学习记录表示
                        val pseudoRecord = LearningRecord(
                            id = fragment.id,
                            projectKey = fragment.metadata["projectKey"] ?: "",
                            createdAt = System.currentTimeMillis(),
                            question = fragment.title,
                            questionType = com.smancode.sman.evolution.model.QuestionType.DOMAIN_KNOWLEDGE,
                            answer = fragment.content,
                            explorationPath = emptyList(),
                            confidence = score,
                            sourceFiles = emptyList(),
                            relatedRecords = emptyList()
                        )
                        results.add(LearningRecordWithScore(pseudoRecord, score))
                    }
                }

                results
            } catch (e: Exception) {
                logger.error("向量搜索失败: {}", e.message)
                emptyList()
            }
        }

    /**
     * 关键词搜索
     *
     * @param projectKey 项目标识
     * @param keywords 关键词列表
     * @return 学习记录列表
     */
    private suspend fun searchByKeywords(projectKey: String, keywords: List<String>): List<LearningRecord> =
        withContext(Dispatchers.IO) {
            if (keywords.isEmpty()) {
                return@withContext emptyList()
            }

            try {
                // 使用第一个关键词进行搜索（简化实现）
                val keyword = keywords.first()
                recordRepository.search(keyword, KEYWORD_SEARCH_LIMIT)
                    .filter { it.projectKey == projectKey }
            } catch (e: Exception) {
                logger.error("关键词搜索失败: {}", e.message)
                emptyList()
            }
        }

    /**
     * 搜索代码片段
     *
     * @param query 查询文本
     * @param topK 返回数量
     * @return 代码片段列表
     */
    private suspend fun searchCodeFragments(query: String, topK: Int): List<CodeFragmentWithScore> =
        withContext(Dispatchers.IO) {
            try {
                // 1. 向量化查询
                val queryVector = bgeM3Client.embed(query, "code-search")

                // 2. 向量检索
                val fragments = vectorStore.search(queryVector, topK)

                // 3. 过滤代码类型的片段
                fragments
                    .filter { it.metadata["type"] == "code" || it.metadata["filePath"] != null }
                    .map { fragment ->
                        val score = calculateSimilarity(queryVector, fragment.vector)
                        CodeFragmentWithScore(fragment, score)
                    }
            } catch (e: Exception) {
                logger.error("代码片段搜索失败: {}", e.message)
                emptyList()
            }
        }

    /**
     * 合并去重
     *
     * @param vectorResults 向量搜索结果
     * @param keywordResults 关键词搜索结果
     * @return 合并后的结果
     */
    private fun mergeAndDeduplicate(
        vectorResults: List<LearningRecordWithScore>,
        keywordResults: List<LearningRecord>
    ): List<LearningRecordWithScore> {
        val seen = mutableSetOf<String>()
        val merged = mutableListOf<LearningRecordWithScore>()

        // 向量结果优先
        vectorResults.forEach { item ->
            if (seen.add(item.record.id)) {
                merged.add(item)
            }
        }

        // 关键词结果补充（给一个默认分数）
        keywordResults.forEach { record ->
            if (seen.add(record.id)) {
                merged.add(LearningRecordWithScore(record, 0.5))
            }
        }

        logger.debug("合并去重完成: 向量={}, 关键词={}, 合并后={}",
            vectorResults.size, keywordResults.size, merged.size)

        return merged
    }

    /**
     * Rerank 精排
     *
     * @param query 查询文本
     * @param candidates 候选列表
     * @param topK 返回数量
     * @return 重排后的结果
     */
    private fun rerank(
        query: String,
        candidates: List<LearningRecordWithScore>,
        topK: Int
    ): List<LearningRecordWithScore> {
        if (candidates.isEmpty()) {
            return emptyList()
        }

        // 如果没有 Reranker 或者候选数量少，直接按分数排序返回
        if (rerankerClient == null || candidates.size <= topK) {
            return candidates
                .sortedByDescending { it.score }
                .take(topK)
        }

        return try {
            // 准备文档列表（使用问题和答案的组合）
            val documents = candidates.map { "${it.record.question}\n${it.record.answer}" }

            // 调用 Reranker
            val rerankResults = rerankerClient.rerankWithScores(query, documents, topK)

            // 按重排结果重新排序
            rerankResults.mapNotNull { (index, rerankScore) ->
                candidates.getOrNull(index)?.copy(score = rerankScore)
            }
        } catch (e: Exception) {
            logger.warn("Rerank 失败，使用原始排序: {}", e.message)
            candidates.sortedByDescending { it.score }.take(topK)
        }
    }

    /**
     * 构建领域知识
     *
     * 从学习记录中提取领域知识
     *
     * @param records 学习记录列表
     * @param domains 领域列表
     * @return 领域知识列表
     */
    private fun buildDomainKnowledge(
        records: List<LearningRecordWithScore>,
        domains: List<String>
    ): List<DomainKnowledge> {
        val knowledgeMap = mutableMapOf<String, MutableList<LearningRecord>>()

        // 按领域分组
        for (item in records) {
            val domain = item.record.domain ?: continue
            if (domains.isEmpty() || domains.contains(domain)) {
                knowledgeMap.getOrPut(domain) { mutableListOf() }.add(item.record)
            }
        }

        // 构建领域知识
        return knowledgeMap.map { (domain, records) ->
            val summary = records
                .take(3)
                .joinToString("\n") { "- ${it.answer.take(200)}" }
            val keyFiles = records
                .flatMap { it.sourceFiles }
                .distinct()
                .take(10)

            DomainKnowledge(
                domain = domain,
                summary = summary,
                keyFiles = keyFiles
            )
        }
    }

    /**
     * 计算向量相似度（余弦相似度）
     *
     * @param a 向量 a
     * @param b 向量 b
     * @return 相似度分数 (0.0 - 1.0)
     */
    private fun calculateSimilarity(a: FloatArray?, b: FloatArray?): Double {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) {
            return 0.0
        }

        var dotProduct = 0.0
        var normA = 0.0
        var normB = 0.0

        val minLen = minOf(a.size, b.size)
        for (i in 0 until minLen) {
            dotProduct += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }

        val denominator = kotlin.math.sqrt(normA) * kotlin.math.sqrt(normB)
        return if (denominator > 0) {
            (dotProduct / denominator).coerceIn(0.0, 1.0)
        } else {
            0.0
        }
    }
}
