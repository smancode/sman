package com.smancode.smanagent.analysis.vectorization

import com.smancode.smanagent.analysis.config.BgeM3Config
import com.smancode.smanagent.analysis.database.JVectorStore
import com.smancode.smanagent.analysis.model.VectorFragment
import com.smancode.smanagent.analysis.model.StepResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import org.slf4j.Logger

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

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 向量化项目结构
     */
    suspend fun vectorizeProjectStructure(data: String): Int = withContext(Dispatchers.IO) {
        try {
            val content = "项目结构: $data"
            val vector = bgeClient.embed(content)

            val fragment = VectorFragment(
                id = "$projectKey:project_structure",
                title = "项目结构",
                content = "项目目录结构和文件组织方式",
                fullContent = data,
                tags = listOf("project", "structure", projectKey),
                metadata = mapOf(
                    "type" to "project_structure",
                    "projectKey" to projectKey
                ),
                vector = vector
            )

            vectorStore.add(fragment)
            logger.info("已向量化项目结构: projectKey={}", projectKey)
            1
        } catch (e: Exception) {
            logger.error("向量化项目结构失败", e)
            0
        }
    }

    /**
     * 向量化技术栈
     */
    suspend fun vectorizeTechStack(data: String): Int = withContext(Dispatchers.IO) {
        try {
            val content = "技术栈: $data"
            val vector = bgeClient.embed(content)

            val fragment = VectorFragment(
                id = "$projectKey:tech_stack",
                title = "技术栈",
                content = "项目使用的技术框架和工具",
                fullContent = data,
                tags = listOf("project", "tech_stack", projectKey),
                metadata = mapOf(
                    "type" to "tech_stack",
                    "projectKey" to projectKey
                ),
                vector = vector
            )

            vectorStore.add(fragment)
            logger.info("已向量化技术栈: projectKey={}", projectKey)
            1
        } catch (e: Exception) {
            logger.error("向量化技术栈失败", e)
            0
        }
    }

    /**
     * 向量化数据库实体
     */
    suspend fun vectorizeDbEntities(data: String): Int = withContext(Dispatchers.IO) {
        try {
            // 简化：直接向量化整个数据
            val content = "数据库实体: $data, 项目: $projectKey"
            val vector = bgeClient.embed(content)

            val fragment = VectorFragment(
                id = "$projectKey:db_entities",
                title = "数据库实体",
                content = "数据库表和实体映射关系",
                fullContent = data,
                tags = listOf("database", "entity", projectKey),
                metadata = mapOf(
                    "type" to "db_entities",
                    "projectKey" to projectKey
                ),
                vector = vector
            )

            vectorStore.add(fragment)
            logger.info("已向量化数据库实体: projectKey={}", projectKey)
            1
        } catch (e: Exception) {
            logger.error("向量化数据库实体失败", e)
            0
        }
    }

    /**
     * 向量化 API 入口
     */
    suspend fun vectorizeApiEntries(data: String): Int = withContext(Dispatchers.IO) {
        try {
            val content = "外部API: $data, 项目: $projectKey"
            val vector = bgeClient.embed(content)

            val fragment = VectorFragment(
                id = "$projectKey:api_entries",
                title = "外部API接口",
                content = "外部接口调用和集成点",
                fullContent = data,
                tags = listOf("api", "external", projectKey),
                metadata = mapOf(
                    "type" to "api_entries",
                    "projectKey" to projectKey
                ),
                vector = vector
            )

            vectorStore.add(fragment)
            logger.info("已向量化API入口: projectKey={}", projectKey)
            1
        } catch (e: Exception) {
            logger.error("向量化API入口失败", e)
            0
        }
    }

    /**
     * 向量化枚举
     */
    suspend fun vectorizeEnums(data: String): Int = withContext(Dispatchers.IO) {
        try {
            val content = "枚举: $data, 项目: $projectKey"
            val vector = bgeClient.embed(content)

            val fragment = VectorFragment(
                id = "$projectKey:enums",
                title = "枚举类",
                content = "业务常量和状态定义",
                fullContent = data,
                tags = listOf("enum", "constant", projectKey),
                metadata = mapOf(
                    "type" to "enums",
                    "projectKey" to projectKey
                ),
                vector = vector
            )

            vectorStore.add(fragment)
            logger.info("已向量化枚举: projectKey={}", projectKey)
            1
        } catch (e: Exception) {
            logger.error("向量化枚举失败", e)
            0
        }
    }

    /**
     * 向量化公共类
     */
    suspend fun vectorizeCommonClasses(data: String): Int = withContext(Dispatchers.IO) {
        try {
            val content = "公共类: $data, 项目: $projectKey"
            val vector = bgeClient.embed(content)

            val fragment = VectorFragment(
                id = "$projectKey:common_classes",
                title = "公共类",
                content = "通用工具类和帮助类",
                fullContent = data,
                tags = listOf("class", "common", "utility", projectKey),
                metadata = mapOf(
                    "type" to "common_classes",
                    "projectKey" to projectKey
                ),
                vector = vector
            )

            vectorStore.add(fragment)
            logger.info("已向量化公共类: projectKey={}", projectKey)
            1
        } catch (e: Exception) {
            logger.error("向量化公共类失败", e)
            0
        }
    }

    /**
     * 向量化 XML 代码
     */
    suspend fun vectorizeXmlCodes(data: String): Int = withContext(Dispatchers.IO) {
        try {
            val content = "XML配置: $data, 项目: $projectKey"
            val vector = bgeClient.embed(content)

            val fragment = VectorFragment(
                id = "$projectKey:xml_configs",
                title = "XML配置",
                content = "配置文件和映射文件",
                fullContent = data,
                tags = listOf("xml", "config", "mapper", projectKey),
                metadata = mapOf(
                    "type" to "xml_configs",
                    "projectKey" to projectKey
                ),
                vector = vector
            )

            vectorStore.add(fragment)
            logger.info("已向量化XML配置: projectKey={}", projectKey)
            1
        } catch (e: Exception) {
            logger.error("向量化XML配置失败", e)
            0
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
