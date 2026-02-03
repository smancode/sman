package com.smancode.smanagent.verification.service

import com.smancode.smanagent.analysis.database.TieredVectorStore
import com.smancode.smanagent.analysis.database.cosineSimilarity
import com.smancode.smanagent.analysis.model.VectorFragment
import com.smancode.smanagent.analysis.vectorization.BgeM3Client
import com.smancode.smanagent.analysis.vectorization.RerankerClient
import com.smancode.smanagent.verification.model.SearchResult
import com.smancode.smanagent.verification.model.VectorSearchRequest
import com.smancode.smanagent.verification.model.VectorSearchResponse
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * 向量搜索服务
 *
 * 功能：
 * - 使用 BGE-M3 将查询转换为向量
 * - 从 TieredVectorStore 召回 topK 结果
 * - 可选：使用 Reranker 重排序（带 threshold 过滤）
 *
 * 白名单机制：参数不满足直接抛异常
 */
@Service
class VectorSearchService(
    private val bgeClient: BgeM3Client,
    private val vectorStore: TieredVectorStore,
    private val rerankerClient: RerankerClient
) {

    private val logger = LoggerFactory.getLogger(VectorSearchService::class.java)

    fun semanticSearch(request: VectorSearchRequest): VectorSearchResponse {
        val startTime = System.currentTimeMillis()

        logger.info("语义搜索: query={}, projectKey={}, topK={}, enableRerank={}",
            request.query, request.projectKey, request.topK, request.enableRerank)

        // 白名单参数校验
        validateRequest(request)

        // 查询向量化
        val queryVector = bgeClient.embed(request.query)

        // 向量召回
        val recallResults = vectorStore.search(queryVector, request.topK)

        logger.info("向量召回完成: query={}, recallCount={}", request.query, recallResults.size)

        // 计算相似度分数
        val recallResultsWithScores = recallResults.map { fragment ->
            fragment to queryVector.cosineSimilarity(fragment.vector!!).toDouble()
        }

        // 可选：重排序（带分数过滤）
        val rerankResults = if (request.enableRerank && recallResults.isNotEmpty()) {
            performRerankingWithScores(request.query, recallResults, request.rerankTopN)
        } else {
            null
        }

        // 构造响应
        val processingTime = System.currentTimeMillis() - startTime

        val response = VectorSearchResponse(
            query = request.query,
            recallResults = recallResultsWithScores.toSearchResultsWithScores(),
            rerankResults = rerankResults?.toSearchResultsWithScores(),
            processingTimeMs = processingTime
        )

        logger.info("语义搜索完成: query={}, recall={}, rerank={}, time={}ms",
            request.query, response.recallResults.size,
            response.rerankResults?.size ?: 0, processingTime)

        return response
    }

    private fun validateRequest(request: VectorSearchRequest) {
        require(request.query.isNotBlank()) { "query 不能为空" }
        require(request.topK > 0) { "topK 必须大于 0，当前值: ${request.topK}" }
        if (request.enableRerank) {
            require(request.rerankTopN > 0) { "rerankTopN 必须大于 0，当前值: ${request.rerankTopN}" }
        }
    }

    /**
     * 重排序（带分数过滤）
     */
    private fun performRerankingWithScores(
        query: String,
        recallResults: List<VectorFragment>,
        rerankTopN: Int
    ): List<Pair<VectorFragment, Double>> = try {
        val documents = recallResults.map { it.content }
        val rerankResults = rerankerClient.rerankWithScores(query, documents, rerankTopN)

        logger.debug("重排序完成: original={}, reranked={}", recallResults.size, rerankResults.size)

        rerankResults.map { (index, score) -> recallResults[index] to score }
    } catch (e: Exception) {
        logger.warn("重排序失败，使用原始顺序: {}", e.message)
        recallResults.take(rerankTopN).map { it to 1.0 }
    }

    private fun List<Pair<VectorFragment, Double>>.toSearchResultsWithScores(): List<SearchResult> =
        mapIndexed { index, (fragment, score) ->
            SearchResult(
                fragmentId = fragment.id,
                fileName = fragment.getMetadata("fileName") ?: fragment.title,
                content = fragment.content,
                score = score,
                rank = index + 1
            )
        }
}
