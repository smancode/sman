package com.smancode.smanagent.analysis.vectorization

import com.smancode.smanagent.analysis.config.BgeM3Config
import com.smancode.smanagent.analysis.database.JVectorStore
import com.smancode.smanagent.analysis.model.VectorFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

/**
 * 项目信息向量化服务
 *
 * 将项目分析各步骤的结果向量化并存入 JVector
 */
class ProjectInfoVectorizationService(
    private val projectKey: String,
    private val vectorStore: JVectorStore,
    bgeEndpoint: String
) {

    private val logger = LoggerFactory.getLogger(ProjectInfoVectorizationService::class.java)
    private val bgeClient = BgeM3Client(BgeM3Config(
        endpoint = bgeEndpoint,
        timeoutSeconds = 30,
        dimension = 1024
    ))

    /**
     * 向量化配置
     * 定义每种数据类型的向量化元数据
     */
    private data class VectorizationConfig(
        val type: String,
        val title: String,
        val description: String,
        val contentPrefix: String,
        val tags: List<String>
    )

    /**
     * 向量化配置映射
     */
    private val vectorizationConfigs = mapOf(
        "project_structure" to VectorizationConfig(
            type = "project_structure",
            title = "项目结构",
            description = "项目目录结构和文件组织方式",
            contentPrefix = "项目结构:",
            tags = listOf("project", "structure")
        ),
        "tech_stack" to VectorizationConfig(
            type = "tech_stack",
            title = "技术栈",
            description = "项目使用的技术框架和工具",
            contentPrefix = "技术栈:",
            tags = listOf("project", "tech_stack")
        ),
        "db_entities" to VectorizationConfig(
            type = "db_entities",
            title = "数据库实体",
            description = "数据库表和实体映射关系",
            contentPrefix = "数据库实体:",
            tags = listOf("database", "entity")
        ),
        "api_entries" to VectorizationConfig(
            type = "api_entries",
            title = "外部API接口",
            description = "外部接口调用和集成点",
            contentPrefix = "外部API:",
            tags = listOf("api", "external")
        ),
        "enums" to VectorizationConfig(
            type = "enums",
            title = "枚举类",
            description = "业务常量和状态定义",
            contentPrefix = "枚举:",
            tags = listOf("enum", "constant")
        ),
        "common_classes" to VectorizationConfig(
            type = "common_classes",
            title = "公共类",
            description = "通用工具类和帮助类",
            contentPrefix = "公共类:",
            tags = listOf("class", "common", "utility")
        ),
        "xml_configs" to VectorizationConfig(
            type = "xml_configs",
            title = "XML配置",
            description = "配置文件和映射文件",
            contentPrefix = "XML配置:",
            tags = listOf("xml", "config", "mapper")
        )
    )

    /**
     * 向量化项目结构
     */
    suspend fun vectorizeProjectStructure(data: String): Int =
        vectorizeData("project_structure", data)

    /**
     * 向量化技术栈
     */
    suspend fun vectorizeTechStack(data: String): Int =
        vectorizeData("tech_stack", data)

    /**
     * 向量化数据库实体
     */
    suspend fun vectorizeDbEntities(data: String): Int =
        vectorizeData("db_entities", data)

    /**
     * 向量化 API 入口
     */
    suspend fun vectorizeApiEntries(data: String): Int =
        vectorizeData("api_entries", data)

    /**
     * 向量化枚举
     */
    suspend fun vectorizeEnums(data: String): Int =
        vectorizeData("enums", data)

    /**
     * 向量化公共类
     */
    suspend fun vectorizeCommonClasses(data: String): Int =
        vectorizeData("common_classes", data)

    /**
     * 向量化 XML 代码
     */
    suspend fun vectorizeXmlCodes(data: String): Int =
        vectorizeData("xml_configs", data)

    /**
     * 通用向量化方法
     */
    private suspend fun vectorizeData(configKey: String, data: String): Int = withContext(Dispatchers.IO) {
        try {
            val config = vectorizationConfigs[configKey]
                ?: throw IllegalArgumentException("未知的向量化配置: $configKey")

            val content = buildContentPrefix(config, data)
            val vector = bgeClient.embed(content)

            val fragment = VectorFragment(
                id = "$projectKey:${config.type}",
                title = config.title,
                content = config.description,
                fullContent = data,
                tags = config.tags + projectKey,
                metadata = mapOf(
                    "type" to config.type,
                    "projectKey" to projectKey
                ),
                vector = vector
            )

            vectorStore.add(fragment)
            logger.info("已向量化${config.title}: projectKey={}", projectKey)
            1
        } catch (e: Exception) {
            logger.error("向量化失败: configKey={}", configKey, e)
            0
        }
    }

    /**
     * 构建内容前缀
     */
    private fun buildContentPrefix(config: VectorizationConfig, data: String): String {
        return if (config.type == "project_structure") {
            "${config.contentPrefix} $data"
        } else {
            "${config.contentPrefix} $data, 项目: $projectKey"
        }
    }

    /**
     * 关闭服务
     */
    fun close() {
        bgeClient.close()
        logger.info("项目信息向量化服务已关闭: projectKey={}", projectKey)
    }
}
