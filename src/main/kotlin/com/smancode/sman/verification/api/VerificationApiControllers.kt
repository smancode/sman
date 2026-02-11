package com.smancode.sman.verification.api

import com.smancode.sman.analysis.database.TieredVectorStore
import com.smancode.sman.analysis.vectorization.BgeM3Client
import com.smancode.sman.verification.model.AnalysisQueryRequest
import com.smancode.sman.verification.model.ExpertConsultRequest
import com.smancode.sman.verification.model.ExpertConsultResponse
import com.smancode.sman.verification.service.AnalysisQueryService
import com.smancode.sman.verification.service.ExpertConsultService
import com.smancode.sman.verification.service.H2QueryService
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
    private val vectorStore: TieredVectorStore,
    private val bgeClient: BgeM3Client,
    private val h2QueryService: H2QueryService
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
    ): com.smancode.sman.analysis.coordination.VectorizationResult =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val startTime = System.currentTimeMillis()
            val errors = mutableListOf<com.smancode.sman.analysis.coordination.VectorizationResult.FileError>()

            // 清理旧向量
            try {
                val deletedCount = cleanupMdVectors()
                logger.info("清理旧的 .md 向量: 删除 {} 条", deletedCount)
            } catch (e: Exception) {
                logger.warn("清理旧向量失败: {}", e.message)
            }

            // 扫描 .md 文件
            val mdDir = projectPath.resolve(".sman/md")
            if (!java.nio.file.Files.exists(mdDir)) {
                return@withContext createEmptyResult(startTime)
            }

            val mdFiles = java.nio.file.Files.walk(mdDir)
                .filter { it.toFile().isFile && it.fileName.toString().endsWith(".md") }
                .toList()

            logger.info("找到 .md 文件: {} 个", mdFiles.size)

            var processedCount = 0
            var totalVectors = 0

            val parser = com.smancode.sman.analysis.parser.MarkdownParser()

            for (mdFile in mdFiles) {
                try {
                    val mdContent = mdFile.toFile().readText()
                    if (mdContent.isBlank()) {
                        continue
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
                    errors.add(com.smancode.sman.analysis.coordination.VectorizationResult.FileError(mdFile, e.message ?: "未知错误"))
                }
            }

            val elapsedTime = System.currentTimeMillis() - startTime

            com.smancode.sman.analysis.coordination.VectorizationResult(
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
        val deleted = mutableSetOf<String>()

        val allVectorIds = kotlinx.coroutines.runBlocking {
            val h2Service = com.smancode.sman.analysis.database.H2DatabaseService(
                com.smancode.sman.analysis.config.VectorDatabaseConfig.create(
                    projectKey = "autoloop",
                    type = com.smancode.sman.analysis.config.VectorDbType.JVECTOR,
                    jvector = com.smancode.sman.analysis.config.JVectorConfig()
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

    private fun createEmptyResult(startTime: Long): com.smancode.sman.analysis.coordination.VectorizationResult {
        return com.smancode.sman.analysis.coordination.VectorizationResult(
            totalFiles = 0,
            processedFiles = 0,
            skippedFiles = 0,
            totalVectors = 0,
            errors = emptyList(),
            elapsedTimeMs = System.currentTimeMillis() - startTime
        )
    }

    /**
     * 向量化分析结果
     *
     * 将 H2 数据库中的 12 个分析模块结果向量化到向量数据库
     * 使得可以通过语义搜索查询项目结构、技术栈、API 入口等信息
     */
    @PostMapping("/vectorize_analysis_results")
    open fun vectorizeAnalysisResults(@RequestBody request: Map<String, Any>): ResponseEntity<Map<String, Any>> {
        val projectKey = request["projectKey"] as? String
            ?: throw IllegalArgumentException("缺少 projectKey 参数")

        logger.info("开始向量化分析结果: projectKey={}", projectKey)

        val startTime = System.currentTimeMillis()

        // 12 个分析模块的元信息
        val analysisModules = listOf(
            "project_structure" to "项目结构",
            "tech_stack_detection" to "技术栈",
            "ast_scanning" to "AST 扫描",
            "db_entity_detection" to "数据库实体",
            "api_entry_scanning" to "API 入口",
            "external_api_scanning" to "外调接口",
            "enum_scanning" to "枚举",
            "common_class_scanning" to "公共类",
            "xml_code_scanning" to "XML 代码",
            "case_sop_generation" to "案例 SOP",
            "code_vectorization" to "代码向量化",
            "code_walkthrough" to "代码走读"
        )

        var vectorizedCount = 0
        val errors = mutableListOf<String>()

        for ((stepName, moduleName) in analysisModules) {
            try {
                // 查询分析结果数据
                val result = h2QueryService.queryAnalysisResults(stepName, projectKey, 0, 1)

                @Suppress("UNCHECKED_CAST")
                val dataList = result["data"] as? List<Map<String, Any?>> ?: emptyList()
                if (dataList.isNotEmpty()) {
                    val dataJson = dataList.firstOrNull()?.get("data") as? String

                    if (dataJson != null && dataJson.isNotBlank()) {
                        // 构建中文描述内容
                        val dataContent = buildAnalysisContent(stepName, moduleName, dataJson)

                        // 创建向量片段
                        val fragment = com.smancode.sman.analysis.model.VectorFragment(
                            id = "analysis:$stepName",
                            title = moduleName,
                            content = dataContent,
                            fullContent = dataJson,
                            tags = listOf("analysis", stepName),
                            metadata = mapOf(
                                "type" to "analysis",
                                "stepName" to stepName,
                                "moduleName" to moduleName,
                                "projectKey" to projectKey
                            ),
                            vector = floatArrayOf()
                        )

                        // 生成向量并存储
                        val embedding = bgeClient.embed(dataContent)
                        val vectorWithEmbedding = fragment.copy(vector = embedding)

                        // 删除旧向量
                        vectorStore.delete(fragment.id)

                        // 添加新向量
                        vectorStore.add(vectorWithEmbedding)

                        vectorizedCount++
                        logger.info("向量化分析模块成功: {} ({})", moduleName, stepName)
                    } else {
                        logger.warn("分析模块数据为空: {} ({})", moduleName, stepName)
                    }
                } else {
                    logger.warn("分析模块未找到数据: {} ({})", moduleName, stepName)
                }

            } catch (e: Exception) {
                val errorMsg = "${moduleName} (${stepName}): ${e.message}"
                logger.error("向量化分析模块失败: {}", errorMsg, e)
                errors.add(errorMsg)
            }
        }

        val elapsedTime = System.currentTimeMillis() - startTime

        logger.info("分析结果向量化完成: 成功={}, 总耗时={}ms", vectorizedCount, elapsedTime)

        return ResponseEntity.ok(mapOf(
            "success" to errors.isEmpty(),
            "vectorizedCount" to vectorizedCount,
            "totalCount" to analysisModules.size,
            "errors" to errors,
            "elapsedTimeMs" to elapsedTime
        ))
    }

    /**
     * 构建分析结果的中文描述内容
     */
    private fun buildAnalysisContent(stepName: String, moduleName: String, dataJson: String): String {
        return when (stepName) {
            "project_structure" -> buildProjectStructureContent(dataJson)
            "tech_stack_detection" -> buildTechStackContent(dataJson)
            "api_entry_scanning" -> buildApiEntryContent(dataJson)
            "enum_scanning" -> buildEnumScanningContent(dataJson)
            "db_entity_detection" -> buildDbEntityContent(dataJson)
            "external_api_scanning" -> buildExternalApiContent(dataJson)
            else -> buildGenericContent(moduleName, dataJson)
        }
    }

    /**
     * 构建项目结构中文描述
     */
    private fun buildProjectStructureContent(dataJson: String): String {
        try {
            val data = com.fasterxml.jackson.databind.ObjectMapper().readTree(dataJson)
            val modules = data.path("modules")
            val sb = StringBuilder("项目结构分析\n")

            if (modules.isArray) {
                sb.append("模块列表: ")
                val moduleNames = mutableListOf<String>()
                modules.forEach { module ->
                    moduleNames.add(module.path("name").asText())
                }
                sb.append(moduleNames.joinToString(", "))
                sb.append("\n")

                val layers = data.path("layers")
                if (layers.isArray) {
                    sb.append("分层架构: ")
                    sb.append(layers.joinToString(", ") { it.asText() })
                }
            } else {
                sb.append("项目包含多个模块和分层架构")
            }
            return sb.toString()
        } catch (e: Exception) {
            logger.warn("解析项目结构数据失败: {}", e.message)
            return "项目结构分析结果，包含模块列表和分层架构信息"
        }
    }

    /**
     * 构建技术栈中文描述
     */
    private fun buildTechStackContent(dataJson: String): String {
        try {
            val data = com.fasterxml.jackson.databind.ObjectMapper().readTree(dataJson)
            val sb = StringBuilder("技术栈分析\n")

            val frameworks = data.path("frameworks")
            if (frameworks.isArray) {
                sb.append("框架: ").append(frameworks.joinToString(", ") { it.asText() }).append("\n")
            }

            val languages = data.path("languages")
            if (languages.isArray) {
                sb.append("语言: ").append(languages.joinToString(", ") { it.asText() }).append("\n")
            }

            val databases = data.path("databases")
            if (databases.isArray) {
                sb.append("数据库: ").append(databases.joinToString(", ") { it.asText() }).append("\n")
            }

            val middlewares = data.path("middlewares")
            if (middlewares.isArray) {
                sb.append("中间件: ").append(middlewares.joinToString(", ") { it.asText() })
            }

            if (sb.length <= "技术栈分析\n".length) {
                return "技术栈分析结果，包含项目使用的框架、语言、数据库和中间件信息"
            }
            return sb.toString().trimEnd()
        } catch (e: Exception) {
            logger.warn("解析技术栈数据失败: {}", e.message)
            return "技术栈分析结果，包含项目使用的框架、语言、数据库和中间件信息"
        }
    }

    /**
     * 构建 API 入口中文描述
     */
    private fun buildApiEntryContent(dataJson: String): String {
        try {
            val data = com.fasterxml.jackson.databind.ObjectMapper().readTree(dataJson)
            val sb = StringBuilder("API 入口分析\n")

            val apis = data.path("apis")
            if (apis.isArray) {
                val count = apis.size()
                sb.append("发现 ").append(count).append(" 个 API 入口\n")

                // 列出前5个 API
                val sampleApis = apis.take(5)
                sampleApis.forEach { api ->
                    val path = api.path("path").asText()
                    val method = api.path("method").asText()
                    sb.append("- ").append(method).append(" ").append(path).append("\n")
                }

                if (count > 5) {
                    sb.append("... 还有 ").append(count - 5).append(" 个 API")
                }
            }
            return sb.toString().trimEnd()
        } catch (e: Exception) {
            logger.warn("解析 API 入口数据失败: {}", e.message)
            return "API 入口分析结果，包含项目中所有的 HTTP API 端点信息"
        }
    }

    /**
     * 构建枚举扫描中文描述
     */
    private fun buildEnumScanningContent(dataJson: String): String {
        try {
            val data = com.fasterxml.jackson.databind.ObjectMapper().readTree(dataJson)
            val sb = StringBuilder("枚举类分析\n")

            val enums = data.path("enums")
            if (enums.isArray) {
                val count = enums.size()
                sb.append("发现 ").append(count).append(" 个枚举类\n")

                // 列出前5个枚举
                val sampleEnums = enums.take(5)
                sampleEnums.forEach { enumNode ->
                    val name = enumNode.path("name").asText()
                    val desc = enumNode.path("description").asText()
                    sb.append("- ").append(name)
                    if (desc.isNotEmpty()) {
                        sb.append(": ").append(desc)
                    }
                    sb.append("\n")
                }

                if (count > 5) {
                    sb.append("... 还有 ").append(count - 5).append(" 个枚举")
                }
            }
            return sb.toString().trimEnd()
        } catch (e: Exception) {
            logger.warn("解析枚举数据失败: {}", e.message)
            return "枚举类分析结果，包含项目中所有枚举定义及其业务含义"
        }
    }

    /**
     * 构建数据库实体中文描述
     */
    private fun buildDbEntityContent(dataJson: String): String {
        try {
            val data = com.fasterxml.jackson.databind.ObjectMapper().readTree(dataJson)
            val sb = StringBuilder("数据库实体分析\n")

            val entities = data.path("entities")
            if (entities.isArray) {
                val count = entities.size()
                sb.append("发现 ").append(count).append(" 个数据库实体\n")

                // 列出前5个实体
                val sampleEntities = entities.take(5)
                sampleEntities.forEach { entity ->
                    val name = entity.path("name").asText()
                    val table = entity.path("table").asText()
                    sb.append("- ").append(name).append(" (表: ").append(table).append(")\n")
                }

                if (count > 5) {
                    sb.append("... 还有 ").append(count - 5).append(" 个实体")
                }
            }
            return sb.toString().trimEnd()
        } catch (e: Exception) {
            logger.warn("解析数据库实体数据失败: {}", e.message)
            return "数据库实体分析结果，包含项目中所有数据库实体和表映射信息"
        }
    }

    /**
     * 构建外调接口中文描述
     */
    private fun buildExternalApiContent(dataJson: String): String {
        try {
            val data = com.fasterxml.jackson.databind.ObjectMapper().readTree(dataJson)
            val sb = StringBuilder("外调接口分析\n")

            val apis = data.path("externalApis")
            if (apis.isArray) {
                val count = apis.size()
                sb.append("发现 ").append(count).append(" 个外部接口调用\n")

                // 列出前5个接口
                val sampleApis = apis.take(5)
                sampleApis.forEach { api ->
                    val name = api.path("name").asText()
                    val target = api.path("target").asText()
                    sb.append("- ").append(name).append(" -> ").append(target).append("\n")
                }

                if (count > 5) {
                    sb.append("... 还有 ").append(count - 5).append(" 个接口")
                }
            }
            return sb.toString().trimEnd()
        } catch (e: Exception) {
            logger.warn("解析外调接口数据失败: {}", e.message)
            return "外调接口分析结果，包含项目中所有外部 API 调用信息"
        }
    }

    /**
     * 构建通用中文描述
     */
    private fun buildGenericContent(moduleName: String, dataJson: String): String {
        return try {
            val data = com.fasterxml.jackson.databind.ObjectMapper().readTree(dataJson)
            val sb = StringBuilder("$moduleName 分析结果\n")

            // 尝试提取常见字段
            val count = data.path("count").asInt()
            if (count > 0) {
                sb.append("包含 ").append(count).append(" 条数据")
            } else {
                sb.append("包含详细的分析数据")
            }
            sb.toString()
        } catch (e: Exception) {
            "$moduleName 分析结果，包含详细的项目分析数据"
        }
    }
}
