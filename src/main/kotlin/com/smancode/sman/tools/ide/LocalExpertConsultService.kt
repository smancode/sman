package com.smancode.sman.tools.ide

import com.intellij.openapi.project.Project
import com.smancode.sman.analysis.database.TieredVectorStore
import com.smancode.sman.analysis.database.cosineSimilarity
import com.smancode.sman.analysis.model.VectorFragment
import com.smancode.sman.analysis.vectorization.BgeM3Client
import com.smancode.sman.analysis.vectorization.RerankerClient
import com.smancode.sman.config.SmanConfig
import com.smancode.sman.smancode.llm.LlmService
import org.slf4j.LoggerFactory

/**
 * 本地专家咨询服务（插件环境直接调用）
 *
 * 不再依赖 HTTP 验证服务，直接在插件进程内完成
 * 复用 ReAct Loop 中已有的服务：LlmService、BgeM3Client、RerankerClient、TieredVectorStore
 */
class LocalExpertConsultService(
    private val project: Project
) {
    private val logger = LoggerFactory.getLogger(LocalExpertConsultService::class.java)

    // 懒加载依赖服务（按需创建）
    private val llmService by lazy { SmanConfig.createLlmService() }

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
        val score: Double
    )

    /**
     * 执行专家咨询
     */
    fun consult(request: ConsultRequest): ConsultResponse {
        val startTime = System.currentTimeMillis()

        val actualProjectKey = if (request.projectKey.isEmpty()) project.name else request.projectKey

        logger.info("本地专家咨询: question={}, projectKey={}",
            request.question, actualProjectKey)

        // 白名单参数校验
        require(request.question.isNotBlank()) { "question 不能为空" }
        require(request.topK > 0) { "topK 必须大于 0" }

        // 1. 向量检索相关代码片段
        val searchResults = semanticSearch(
            query = request.question,
            topK = request.topK,
            enableRerank = request.enableRerank,
            rerankTopN = request.rerankTopN
        )

        if (searchResults.isEmpty()) {
            // 没有搜索到任何结果，仍然调用 LLM
            val context = "未找到相关代码片段。"
            val answer = callLlm(request.question, context)
            val processingTime = System.currentTimeMillis() - startTime

            return ConsultResponse(
                answer = answer,
                sources = emptyList(),
                confidence = 0.3,
                processingTimeMs = processingTime
            )
        }

        // 2. 构建上下文
        val context = buildContext(searchResults)

        // 3. 调用 LLM
        val answer = callLlm(request.question, context)

        // 4. 构造响应
        val processingTime = System.currentTimeMillis() - startTime
        val sources = searchResults.map {
            SourceInfo(
                filePath = it.fileName,
                score = it.score
            )
        }

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
     * 调用 LLM 获取答案
     */
    private fun callLlm(question: String, context: String): String {
        val systemPrompt = """你是一个代码专家。请根据以下代码片段回答问题。
            |代码片段来自项目的语义搜索结果。
            |请引用相关代码位置，给出准确、简洁的答案。""".trimMargin().replace("\n", " ")

        val userPrompt = buildString {
            appendLine("你是一个代码专家。请根据以下上下文回答问题。")
            appendLine()
            append(context)
            appendLine()
            appendLine("问题：$question")
            appendLine()
            appendLine("请给出准确、简洁的答案，并引用相关代码位置（使用【片段 X】的形式）。")
            appendLine("如果上下文不足，请明确说明。")
        }

        return llmService.simpleRequest(systemPrompt, userPrompt)
    }

    /**
     * 搜索结果
     */
    private data class SearchResult(
        val fileName: String,
        val content: String,
        val score: Double
    )

    /**
     * 语义搜索
     */
    private fun semanticSearch(
        query: String,
        topK: Int,
        enableRerank: Boolean,
        rerankTopN: Int
    ): List<SearchResult> {
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

            finalResults.map { (fragment, score) ->
                SearchResult(
                    fileName = fragment.title,  // VectorFragment.title 存储文件路径
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
     * rerankerClient.rerankWithScores 返回 List<Pair<Int, Double>>，其中 Int 是文档索引
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

    /**
     * 构建上下文
     */
    private fun buildContext(results: List<SearchResult>): String =
        buildString {
            if (results.isEmpty()) {
                appendLine("未找到相关代码片段。")
                return@buildString
            }

            appendLine("找到 ${results.size} 个相关代码片段：\n")
            results.forEachIndexed { index, result ->
                appendLine("【片段 ${index + 1}】")
                appendLine("文件: ${result.fileName}")
                appendLine("相似度: ${"%.2f".format(result.score)}")
                appendLine("内容:")
                // 限制内容长度，避免 token 过多
                val maxLength = 1000
                if (result.content.length > maxLength) {
                    appendLine(result.content.take(maxLength))
                    appendLine("...(内容已截断，共 ${result.content.length} 字符)")
                } else {
                    appendLine(result.content)
                }
                appendLine()
            }
        }
}
