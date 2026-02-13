package com.smancode.sman.tools.ide

import com.intellij.openapi.project.Project
import com.smancode.sman.analysis.database.TieredVectorStore
import com.smancode.sman.analysis.database.cosineSimilarity
import com.smancode.sman.analysis.model.VectorFragment
import com.smancode.sman.analysis.vectorization.BgeM3Client
import com.smancode.sman.analysis.vectorization.RerankerClient
import com.smancode.sman.config.SmanConfig
import org.slf4j.LoggerFactory

/**
 * 本地专家咨询服务（插件环境直接调用）
 *
 * 核心逻辑：
 * 1. BGE-M3 向量召回
 * 2. Reranker 重排序
 * 3. 直接返回召回结果（不调用 LLM）
 *
 * 设计原则：
 * - 语义搜索交给 BGE + Reranker，不做 LLM 二次处理
 * - 返回原始搜索结果，让调用方决定如何使用
 */
class LocalExpertConsultService(
    private val project: Project
) {
    private val logger = LoggerFactory.getLogger(LocalExpertConsultService::class.java)

    private val bgeClient by lazy {
        val config = SmanConfig.bgeM3Config
        if (config == null) {
            throw IllegalStateException("BGE-M3 未配置，无法使用 expert_consult 工具。请在设置中配置 bge.endpoint")
        }
        BgeM3Client(config)
    }

    private val rerankerClient by lazy {
        val config = SmanConfig.rerankerConfig
        RerankerClient(config)
    }

    private val vectorStore by lazy {
        val projectKey = project.name // 使用项目名作为 key
        val projectPath = project.basePath?.toString()
            ?: throw IllegalStateException("项目基础路径为空，无法创建向量数据库配置")
        val dbConfig = SmanConfig.createVectorDbConfig(projectKey, projectPath)
        TieredVectorStore(dbConfig)
    }

    /**
     * 咨询请求
     */
    data class ConsultRequest(
        val question: String,
        val projectKey: String = "",
        val topK: Int = 10,
        val enableRerank: Boolean = true,
        val rerankTopN: Int = 5
    )

    /**
     * 咨询响应
     */
    data class ConsultResponse(
        val answer: String,
        val sources: List<SourceInfo>,
        val confidence: Double,
        val processingTimeMs: Long
    )

    /**
     * 来源信息
     */
    data class SourceInfo(
        val filePath: String,
        val className: String = "",
        val methodName: String = "",
        val content: String = "",
        val score: Double
    )

    /**
     * 执行专家咨询
     *
     * 只做 BGE 召回 + Reranker 重排，不调用 LLM
     */
    fun consult(request: ConsultRequest): ConsultResponse {
        val startTime = System.currentTimeMillis()

        val actualProjectKey = if (request.projectKey.isEmpty()) project.name else request.projectKey

        logger.info("本地专家咨询: question={}, projectKey={}",
            request.question, actualProjectKey)

        // 白名单参数校验
        require(request.question.isNotBlank()) { "question 不能为空" }
        require(request.topK > 0) { "topK 必须大于 0" }

        // 1. 向量检索相关代码片段（BGE 召回 + Reranker 重排）
        val searchResults = semanticSearch(
            query = request.question,
            topK = request.topK,
            enableRerank = request.enableRerank,
            rerankTopN = request.rerankTopN
        )

        // 2. 构造响应（不调用 LLM，直接返回搜索结果）
        val processingTime = System.currentTimeMillis() - startTime
        val sources = searchResults.map { result ->
            SourceInfo(
                filePath = result.fileName,
                className = result.className,
                methodName = result.methodName,
                content = result.content,
                score = result.score
            )
        }

        // 3. 构建答案（直接展示搜索结果）
        val answer = buildAnswer(request.question, searchResults)

        logger.info("本地专家咨询完成: sources={}, time={}ms",
            sources.size, processingTime)

        return ConsultResponse(
            answer = answer,
            sources = sources,
            confidence = if (sources.isNotEmpty()) 0.8 else 0.3,
            processingTimeMs = processingTime
        )
    }

    /**
     * 构建答案（直接展示搜索结果，不做 LLM 处理）
     */
    private fun buildAnswer(@Suppress("UNUSED_PARAMETER") question: String, results: List<SearchResultWithMetadata>): String {
        if (results.isEmpty()) {
            return "未找到相关代码片段。"
        }

        return buildString {
            appendLine("找到 ${results.size} 个相关代码片段：\n")
            results.forEachIndexed { index, result ->
                appendLine("【片段 ${index + 1}】 ${result.fileName}")
                if (result.className.isNotEmpty() && result.className != result.fileName) {
                    appendLine("类名: ${result.className}")
                }
                if (result.methodName.isNotEmpty()) {
                    appendLine("方法: ${result.methodName}")
                }
                appendLine("相似度: ${"%.2f".format(result.score)}")
                appendLine()
                // 限制内容长度
                val maxLength = 800
                if (result.content.length > maxLength) {
                    appendLine(result.content.take(maxLength))
                    appendLine("...(内容已截断)")
                } else {
                    appendLine(result.content)
                }
                appendLine()
            }
        }
    }

    /**
     * 带元数据的搜索结果
     */
    private data class SearchResultWithMetadata(
        val fileName: String,
        val className: String,
        val methodName: String,
        val content: String,
        val score: Double
    )

    /**
     * 语义搜索（BGE 召回 + Reranker 重排）
     */
    private fun semanticSearch(
        query: String,
        topK: Int,
        enableRerank: Boolean,
        rerankTopN: Int
    ): List<SearchResultWithMetadata> {
        return try {
            // 1. 将查询转换为向量
            val queryVector = bgeClient.embed(query)

            // 2. 向量召回
            val recallResults = vectorStore.search(
                query = queryVector,
                topK = if (enableRerank) topK * 2 else topK  // 召回更多结果用于重排
            )

            if (recallResults.isEmpty()) {
                return emptyList()
            }

            logger.info("向量召回完成: query={}, recallCount={}", query, recallResults.size)

            // 3. 计算相似度分数
            val recallResultsWithScores = recallResults.map { fragment ->
                val score = queryVector.cosineSimilarity(fragment.vector!!).toDouble()
                Pair(fragment, score)
            }

            // 4. 可选：重排序
            val finalResults = if (enableRerank && recallResultsWithScores.isNotEmpty()) {
                performReranking(query, recallResultsWithScores.map { it.first }, rerankTopN)
            } else {
                recallResultsWithScores.take(topK)
            }

            // 5. 转换为带元数据的结果
            finalResults.map { (fragment, score) ->
                SearchResultWithMetadata(
                    fileName = fragment.getMetadata("sourceFile")
                        ?.let { java.nio.file.Paths.get(it).fileName.toString() }
                        ?: fragment.title,
                    className = fragment.getMetadata("className") ?: "",
                    methodName = fragment.getMetadata("methodName") ?: "",
                    content = fragment.content,
                    score = score
                )
            }
        } catch (e: IllegalStateException) {
            logger.warn("向量搜索失败: ${e.message}")
            emptyList()
        } catch (e: Exception) {
            logger.error("向量搜索失败", e)
            emptyList()
        }
    }

    /**
     * 执行重排序
     */
    private fun performReranking(
        query: String,
        recallResults: List<VectorFragment>,
        rerankTopN: Int
    ): List<Pair<VectorFragment, Double>> {
        return try {
            val documents = recallResults.map { it.content }
            val rerankResults = rerankerClient.rerankWithScores(
                query = query,
                documents = documents,
                topK = rerankTopN
            )

            // rerankResults 是 List<Pair<Int, Double>>，Int 是原始文档列表中的索引
            rerankResults.map { (documentIndex, score) ->
                recallResults[documentIndex] to score
            }
        } catch (e: Exception) {
            logger.warn("重排序失败: ${e.message}，使用原始顺序")
            emptyList()
        }
    }
}
