package com.smancode.smanagent.verification.service

import com.smancode.smanagent.analysis.config.JVectorConfig
import com.smancode.smanagent.analysis.config.VectorDatabaseConfig
import com.smancode.smanagent.analysis.config.VectorDbType
import com.smancode.smanagent.analysis.database.TieredVectorStore
import com.smancode.smanagent.analysis.vectorization.BgeM3Client
import com.smancode.smanagent.analysis.vectorization.RerankerClient
import com.smancode.smanagent.smancode.llm.LlmService
import com.smancode.smanagent.verification.model.ExpertConsultRequest
import com.smancode.smanagent.verification.model.ExpertConsultResponse
import com.smancode.smanagent.verification.model.SourceInfo
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.stereotype.Service

/**
 * 专家咨询服务
 *
 * 直接使用 LLM 查询，不走 ReAct 循环
 * 集成向量检索（BGE-M3 + TieredVectorStore + Reranker）
 *
 * 只在 LlmService Bean 存在时创建
 */
@Service
@ConditionalOnBean(LlmService::class)
class ExpertConsultService(
    private val llmService: LlmService,
    private val bgeM3Client: BgeM3Client,
    private val rerankerClient: RerankerClient
) {

    private val logger = LoggerFactory.getLogger(ExpertConsultService::class.java)

    fun consult(request: ExpertConsultRequest): ExpertConsultResponse {
        val startTime = System.currentTimeMillis()

        logger.info("专家咨询: question={}, projectKey={}", request.question, request.projectKey)

        // 白名单参数校验
        require(request.question.isNotBlank()) { "question 不能为空" }
        require(request.projectKey.isNotBlank()) { "projectKey 不能为空" }
        require(request.topK > 0) { "topK 必须大于 0" }

        // 1. 为请求的项目创建 VectorStore
        val config = VectorDatabaseConfig.create(
            projectKey = request.projectKey,
            type = VectorDbType.JVECTOR,
            jvector = JVectorConfig(dimension = 1024),
            vectorDimension = 1024
        )
        val vectorStore = TieredVectorStore(config)

        // 2. 向量检索相关代码片段
        val searchRequest = com.smancode.smanagent.verification.model.VectorSearchRequest(
            query = request.question,
            projectKey = request.projectKey,
            topK = request.topK,
            enableRerank = request.enableRerank,
            rerankTopN = request.rerankTopN
        )

        val searchService = VectorSearchService(bgeM3Client, vectorStore, rerankerClient)
        val searchResult = searchService.semanticSearch(searchRequest)

        // 3. 构建上下文
        val context = buildContext(searchResult)

        // 4. 调用 LLM
        val systemPrompt = """你是一个代码专家。请根据以下代码片段回答问题。
            |代码片段来自项目 ${request.projectKey} 的语义搜索结果。
            |请引用相关代码位置，给出准确、简洁的答案。""".trimMargin().replace("\n", " ")
        val userPrompt = buildPrompt(request.question, context)
        val answer = llmService.simpleRequest(systemPrompt, userPrompt)

        // 5. 构造响应
        val processingTime = System.currentTimeMillis() - startTime
        val sources = searchResult.rerankResults?.take(10)?.map {
            SourceInfo(
                filePath = it.fileName,
                className = "",
                methodName = "",
                score = it.score
            )
        } ?: emptyList()

        logger.info("专家咨询完成: sources={}, time={}ms", sources.size, processingTime)

        return ExpertConsultResponse(
            answer = answer,
            sources = sources,
            confidence = if (sources.isNotEmpty()) 0.8 else 0.3,
            processingTimeMs = processingTime
        )
    }

    private fun buildContext(result: com.smancode.smanagent.verification.model.VectorSearchResponse): String {
        val results = result.rerankResults ?: result.recallResults
        if (results.isEmpty()) {
            return "未找到相关代码片段。"
        }

        return buildString {
            appendLine("找到 ${results.size} 个相关代码片段：\n")
            results.forEachIndexed { index, searchResult ->
                appendLine("【片段 ${index + 1}】")
                appendLine("文件: ${searchResult.fileName}")
                appendLine("片段ID: ${searchResult.fragmentId}")
                appendLine("相似度: ${"%.2f".format(searchResult.score)}")
                appendLine("内容:")
                appendLine(searchResult.content.take(500))
                if (searchResult.content.length > 500) appendLine("...")
                appendLine()
            }
        }
    }

    private fun buildPrompt(question: String, context: String): String =
        """
        |你是一个代码专家。请根据以下上下文回答问题。
        |
        |$context
        |
        |问题：$question
        |
        |请给出准确、简洁的答案，并引用相关代码位置（使用【片段 X】的形式）。
        |如果上下文不足，请明确说明。
        """.trimMargin()
}
