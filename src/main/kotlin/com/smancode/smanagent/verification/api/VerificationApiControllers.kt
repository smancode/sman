package com.smancode.smanagent.verification.api

import com.smancode.smanagent.analysis.config.JVectorConfig
import com.smancode.smanagent.analysis.config.VectorDatabaseConfig
import com.smancode.smanagent.analysis.config.VectorDbType
import com.smancode.smanagent.analysis.database.TieredVectorStore
import com.smancode.smanagent.analysis.vectorization.BgeM3Client
import com.smancode.smanagent.analysis.vectorization.RerankerClient
import com.smancode.smanagent.verification.model.AnalysisQueryRequest
import com.smancode.smanagent.verification.model.ExpertConsultRequest
import com.smancode.smanagent.verification.model.ExpertConsultResponse
import com.smancode.smanagent.verification.model.VectorSearchRequest
import com.smancode.smanagent.verification.model.VectorSearchResponse
import com.smancode.smanagent.verification.service.AnalysisQueryService
import com.smancode.smanagent.verification.service.ExpertConsultService
import com.smancode.smanagent.verification.service.H2QueryService
import com.smancode.smanagent.verification.service.VectorSearchService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 专家咨询 API
 *
 * 直接使用 LLM 查询，不走 ReAct 循环
 * 只在 ExpertConsultService Bean 存在时创建
 */
@RestController
@RequestMapping("/api/verify")
@ConditionalOnBean(ExpertConsultService::class)
open class ExpertConsultApi(@Autowired(required = false) private val expertConsultService: ExpertConsultService?) {

    @PostMapping("/expert_consult")
    open fun expertConsult(@RequestBody request: ExpertConsultRequest): ResponseEntity<ExpertConsultResponse> {
        val service = expertConsultService ?: throw IllegalStateException("专家咨询服务不可用，请设置 LLM_API_KEY 环境变量")
        return ResponseEntity.ok(service.consult(request))
    }
}

/**
 * 向量搜索 API
 *
 * 支持 BGE 召回 + Reranker 重排
 */
@RestController
@RequestMapping("/api/verify")
open class VectorSearchApi(
    @Autowired private val bgeM3Client: BgeM3Client,
    @Autowired private val rerankerClient: RerankerClient
) {

    @PostMapping("/semantic_search")
    open fun semanticSearch(@RequestBody request: VectorSearchRequest): ResponseEntity<VectorSearchResponse> {
        // 为每个请求创建特定项目的向量存储
        val projectKey = request.projectKey ?: "autoloop"
        val config = VectorDatabaseConfig.create(
            projectKey = projectKey,
            type = VectorDbType.JVECTOR,
            jvector = JVectorConfig(dimension = 1024),
            vectorDimension = 1024
        )
        val vectorStore = TieredVectorStore(config)
        val searchService = VectorSearchService(bgeM3Client, vectorStore, rerankerClient)

        return ResponseEntity.ok(searchService.semanticSearch(request))
    }
}

/**
 * 分析结果查询 API
 *
 * 查询 12 个分析模块的结果
 */
@RestController
@RequestMapping("/api/verify")
open class AnalysisQueryApi {

    @Autowired(required = false)
    private lateinit var h2QueryService: H2QueryService

    private val analysisQueryService by lazy {
        require(::h2QueryService.isInitialized) {
            "H2 数据库未配置，无法查询分析结果"
        }
        AnalysisQueryService(h2QueryService)
    }

    @PostMapping("/analysis_results")
    open fun queryResults(@RequestBody request: AnalysisQueryRequest): ResponseEntity<Map<String, Any>> {
        val response = analysisQueryService.queryResults<Map<String, Any>>(request)
        return ResponseEntity.ok(
            mapOf(
                "module" to response.module,
                "projectKey" to response.projectKey,
                "data" to response.data,
                "total" to response.total,
                "page" to response.page,
                "size" to response.size
            )
        )
    }
}

/**
 * H2 数据查询 API
 *
 * 查询 H2 数据库中的原始数据
 */
@RestController
@RequestMapping("/api/verify")
open class H2QueryApi {

    @Autowired(required = false)
    private lateinit var h2QueryService: H2QueryService

    private fun requireH2QueryService() {
        require(::h2QueryService.isInitialized) {
            "H2 数据库未配置，无法执行查询"
        }
    }

    @PostMapping("/query_vectors")
    open fun queryVectors(@RequestBody request: Map<String, Any>): ResponseEntity<Map<String, Any>> {
        requireH2QueryService()

        val page = (request["page"] as? Number)?.toInt() ?: 0
        val size = (request["size"] as? Number)?.toInt() ?: 20

        val result = h2QueryService.queryVectors(page, size)
        return ResponseEntity.ok(result)
    }

    @PostMapping("/query_projects")
    open fun queryProjects(@RequestBody request: Map<String, Any>): ResponseEntity<Map<String, Any>> {
        requireH2QueryService()

        val page = (request["page"] as? Number)?.toInt() ?: 0
        val size = (request["size"] as? Number)?.toInt() ?: 20

        val result = h2QueryService.queryProjects(page, size)
        return ResponseEntity.ok(result)
    }

    @PostMapping("/execute_sql")
    open fun executeSql(@RequestBody request: Map<String, Any>): ResponseEntity<Map<String, Any>> {
        requireH2QueryService()

        val sql = request["sql"] as? String
            ?: throw IllegalArgumentException("缺少 sql 参数")
        val params = (request["params"] as? Map<String, Any>) ?: emptyMap()

        val result = h2QueryService.executeSafeSql(sql, params)
        return ResponseEntity.ok(mapOf("data" to result))
    }
}
