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
 *
 * 注意：使用 Spring Bean 的 vectorStore，确保向量数据对搜索服务可见
 */
@RestController
@RequestMapping("/api/verify")
open class VectorRecoveryApi(
    private val vectorStore: com.smancode.smanagent.analysis.database.TieredVectorStore,
    private val bgeClient: com.smancode.smanagent.analysis.vectorization.BgeM3Client
) {

    private val logger = org.slf4j.LoggerFactory.getLogger(VectorRecoveryApi::class.java)

    @PostMapping("/vectorize_from_md")
    open fun vectorizeFromMd(@RequestBody request: Map<String, Any>): ResponseEntity<Map<String, Any>> {
        val projectKey = request["projectKey"] as? String
            ?: throw IllegalArgumentException("缺少 projectKey 参数")
        val projectPath = request["projectPath"] as? String
            ?: throw IllegalArgumentException("缺少 projectPath 参数")

        logger.info("开始从已有 .md 文件向量化: projectKey={}, path={}", projectKey, projectPath)

        // 使用共享的 vectorStore 和 bgeClient 直接向量化
        val result = kotlinx.coroutines.runBlocking {
            vectorizeMdFiles(projectKey, java.nio.file.Paths.get(projectPath))
        }

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

    /**
     * 向量化 MD 文件（使用共享的 vectorStore）
     */
    private suspend fun vectorizeMdFiles(
        projectKey: String,
        projectPath: java.nio.file.Path
    ): com.smancode.smanagent.analysis.coordination.VectorizationResult =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val startTime = System.currentTimeMillis()
            val errors = mutableListOf<com.smancode.smanagent.analysis.coordination.VectorizationResult.FileError>()

            // 清理旧向量
            try {
                val deletedCount = cleanupMdVectors()
                logger.info("清理旧的 .md 向量: 删除 {} 条", deletedCount)
            } catch (e: Exception) {
                logger.warn("清理旧向量失败: {}", e.message)
            }

            // 扫描 .md 文件
            val mdDir = projectPath.resolve(".smanunion/md")
            if (!java.nio.file.Files.exists(mdDir)) {
                return@withContext createEmptyResult(startTime)
            }

            val mdFiles = java.nio.file.Files.walk(mdDir)
                .filter { it.toFile().isFile && it.fileName.toString().endsWith(".md") }
                .toList()

            logger.info("找到 .md 文件: {} 个", mdFiles.size)

            var processedCount = 0
            var totalVectors = 0

            // 解析并向量化
            val parser = com.smancode.smanagent.analysis.parser.MarkdownParser()

            for (mdFile in mdFiles) {
                try {
                    val mdContent = mdFile.toFile().readText()
                    if (mdContent.isBlank()) {
                        continue
                    }

                    // DEBUG: 检查是否是 Enum 文件
                    val isEnum = parser.isEnumMarkdown(mdContent)
                    if (isEnum) {
                        logger.info("检测到 Enum 文件: {}", mdFile.fileName)
                    }

                    val vectors = parser.parseAll(mdFile, mdContent)
                    if (vectors.isEmpty()) {
                        logger.warn("文件解析出 0 个向量: {}", mdFile.fileName)
                        continue
                    }

                    logger.debug("文件解析成功: file={}, vectors={}", mdFile.fileName, vectors.size)

                    for (vector in vectors) {
                        val embedding = bgeClient.embed(vector.content)
                        val vectorWithEmbedding = vector.copy(vector = embedding)

                        // 删除旧向量
                        vectorStore.delete(vector.id)

                        // 添加新向量（会写入 H2 和 L2）
                        vectorStore.add(vectorWithEmbedding)
                    }

                    processedCount++
                    totalVectors += vectors.size

                } catch (e: Exception) {
                    logger.error("处理文件失败: file={}, error={}", mdFile.fileName, e.message)
                    errors.add(com.smancode.smanagent.analysis.coordination.VectorizationResult.FileError(mdFile, e.message ?: "未知错误"))
                }
            }

            val elapsedTime = System.currentTimeMillis() - startTime

            com.smancode.smanagent.analysis.coordination.VectorizationResult(
                totalFiles = mdFiles.size,
                processedFiles = processedCount,
                skippedFiles = mdFiles.size - processedCount,
                totalVectors = totalVectors,
                errors = errors,
                elapsedTimeMs = elapsedTime
            )
        }

    /**
     * 清理所有 .md 向量
     */
    private fun cleanupMdVectors(): Int {
        // 删除所有包含 .md 的向量（通过模式匹配）
        val deleted = mutableSetOf<String>()

        // 从 H2 获取所有向量 ID
        val allVectorIds = kotlinx.coroutines.runBlocking {
            val h2Service = com.smancode.smanagent.analysis.database.H2DatabaseService(
                com.smancode.smanagent.analysis.config.VectorDatabaseConfig.create(
                    projectKey = "autoloop",
                    type = com.smancode.smanagent.analysis.config.VectorDbType.JVECTOR,
                    jvector = com.smancode.smanagent.analysis.config.JVectorConfig()
                )
            )
            h2Service.getAllVectorFragments().map { it.id }
        }

        val regex = Regex(".*\\.md.*")
        val idsToDelete = allVectorIds.filter { regex.containsMatchIn(it) }

        for (id in idsToDelete) {
            try {
                vectorStore.delete(id)
                deleted.add(id)
            } catch (e: Exception) {
                logger.warn("删除向量失败: id={}, error={}", id, e.message)
            }
        }

        return deleted.size
    }

    private fun createEmptyResult(startTime: Long): com.smancode.smanagent.analysis.coordination.VectorizationResult {
        return com.smancode.smanagent.analysis.coordination.VectorizationResult(
            totalFiles = 0,
            processedFiles = 0,
            skippedFiles = 0,
            totalVectors = 0,
            errors = emptyList(),
            elapsedTimeMs = System.currentTimeMillis() - startTime
        )
    }

    // TODO: 添加清理旧向量的端点（需要添加必要的 import）
    // @PostMapping("/cleanup_md_vectors")
    // open fun cleanupMdVectors(...): ResponseEntity<Map<String, Any>> { ... }
}
