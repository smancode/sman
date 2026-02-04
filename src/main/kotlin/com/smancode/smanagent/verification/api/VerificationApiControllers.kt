package com.smancode.smanagent.verification.api

import com.smancode.smanagent.verification.model.AnalysisQueryRequest
import com.smancode.smanagent.verification.model.ExpertConsultRequest
import com.smancode.smanagent.verification.model.ExpertConsultResponse
import com.smancode.smanagent.verification.service.AnalysisQueryService
import com.smancode.smanagent.verification.service.ExpertConsultService
import com.smancode.smanagent.verification.service.H2QueryService
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
 * 这是唯一的对外语义查询接口
 * 内部实现：BGE 向量召回 + Reranker 重排 + LLM 答案生成
 *
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

/**
 * 向量化恢复 API
 *
 * 从已有的 .md 文件重新向量化到 H2 数据库
 */
@RestController
@RequestMapping("/api/verify")
open class VectorRecoveryApi {

    private val logger = org.slf4j.LoggerFactory.getLogger(VectorRecoveryApi::class.java)

    @PostMapping("/vectorize_from_md")
    open fun vectorizeFromMd(@RequestBody request: Map<String, Any>): ResponseEntity<Map<String, Any>> {
        val projectKey = request["projectKey"] as? String
            ?: throw IllegalArgumentException("缺少 projectKey 参数")
        val projectPath = request["projectPath"] as? String
            ?: throw IllegalArgumentException("缺少 projectPath 参数")

        logger.info("开始从已有 .md 文件向量化: projectKey={}, path={}", projectKey, projectPath)

        // 创建向量化服务
        val service = com.smancode.smanagent.analysis.service.VectorizationService(
            projectKey = projectKey,
            projectPath = java.nio.file.Paths.get(projectPath),
            llmService = com.smancode.smanagent.config.SmanAgentConfig.createLlmService(),
            bgeEndpoint = com.smancode.smanagent.config.SmanAgentConfig.bgeM3Config?.endpoint
                ?: throw IllegalStateException("BGE 端点未配置")
        )

        // 执行向量化（自动清理旧向量）
        val result = kotlinx.coroutines.runBlocking {
            service.vectorizeFromExistingMd()
        }

        // 关闭服务
        service.close()

        logger.info("向量化完成: 处理={}, 向量数={}", result.processedFiles, result.totalVectors)

        return ResponseEntity.ok(mapOf(
            "success" to result.isSuccess,
            "totalFiles" to result.totalFiles,
            "processedFiles" to result.processedFiles,
            "skippedFiles" to result.skippedFiles,
            "totalVectors" to result.totalVectors,
            "errors" to result.errors.map { "${it.file.fileName}: ${it.error}" },
            "elapsedTimeMs" to result.elapsedTimeMs
        ))
    }

    // TODO: 添加清理旧向量的端点（需要添加必要的 import）
    // @PostMapping("/cleanup_md_vectors")
    // open fun cleanupMdVectors(...): ResponseEntity<Map<String, Any>> { ... }
}
