package com.smancode.sman.architect.storage

import com.smancode.sman.analysis.model.AnalysisType
import com.smancode.sman.analysis.paths.ProjectPaths
import com.smancode.sman.architect.model.EvaluationResult
import com.smancode.sman.architect.model.MdMetadata
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * MD 文件服务
 *
 * 负责架构师 Agent 分析结果的持久化
 *
 * 核心职责：
 * 1. 读取 MD 文件及其元信息
 * 2. 写入带元信息的 MD 文件
 * 3. 提取内容区
 */
class MdFileService(
    private val projectPath: Path
) {
    private val logger = LoggerFactory.getLogger(MdFileService::class.java)

    private val paths = ProjectPaths.forProject(projectPath)
    private val baseDir = paths.baseDir

    /**
     * 获取 MD 文件路径
     */
    fun getMdPath(type: AnalysisType): Path {
        return baseDir.resolve(type.mdFileName)
    }

    /**
     * 检查 MD 文件是否存在
     */
    fun exists(type: AnalysisType): Boolean {
        return getMdPath(type).exists()
    }

    /**
     * 读取 MD 文件内容
     */
    fun readContent(type: AnalysisType): String? {
        val path = getMdPath(type)
        return if (path.exists()) {
            path.readText()
        } else {
            null
        }
    }

    /**
     * 读取 MD 文件元信息
     */
    fun readMetadata(type: AnalysisType): MdMetadata? {
        val content = readContent(type) ?: return null
        return MdMetadata.fromContent(content)
    }

    /**
     * 读取 MD 文件内容区（去除元信息注释）
     */
    fun readContentOnly(type: AnalysisType): String? {
        val content = readContent(type) ?: return null
        return extractContent(content)
    }

    /**
     * 保存带元信息的 MD 文件
     *
     * @param type 分析类型
     * @param content 分析内容
     * @param evaluation 评估结果
     * @param previousMetadata 之前的元信息（用于增量更新）
     */
    fun saveWithMetadata(
        type: AnalysisType,
        content: String,
        evaluation: EvaluationResult,
        previousMetadata: MdMetadata? = null
    ): Path {
        // 确保目录存在
        paths.ensureDirectoriesExist()

        // 构建元信息
        val metadata = MdMetadata(
            analysisType = type,
            lastModified = Instant.now(),
            completeness = evaluation.completeness,
            todos = evaluation.todos,
            iterationCount = (previousMetadata?.iterationCount ?: 0) + 1,
            version = (previousMetadata?.version ?: 1) + 1
        )

        // 构建完整内容
        val fullContent = buildFullContent(metadata, content)

        // 写入文件
        val path = getMdPath(type)
        path.writeText(fullContent)

        logger.info("保存 MD 文件: path={}, completeness={}, todos={}",
            path, evaluation.completeness, evaluation.todos.size)

        return path
    }

    /**
     * 更新 MD 文件的时间戳
     */
    fun touchTimestamp(type: AnalysisType): Boolean {
        val content = readContent(type) ?: return false
        val metadata = MdMetadata.fromContent(content) ?: return false

        val updatedMetadata = metadata.copy(
            lastModified = Instant.now()
        )

        val contentOnly = extractContent(content) ?: content
        val fullContent = buildFullContent(updatedMetadata, contentOnly)

        getMdPath(type).writeText(fullContent)
        return true
    }

    /**
     * 构建完整内容（元信息 + 内容区）
     */
    private fun buildFullContent(metadata: MdMetadata, content: String): String {
        val metaComment = metadata.toComment()
        val cleanContent = removeExistingMeta(content)

        return "$metaComment\n\n$cleanContent".trim()
    }

    /**
     * 提取内容区（去除元信息注释）
     */
    private fun extractContent(content: String): String {
        return removeExistingMeta(content).trim()
    }

    /**
     * 移除现有的元信息注释
     */
    private fun removeExistingMeta(content: String): String {
        // 匹配 <!-- META ... --> 注释块
        val metaPattern = Regex("""<!--\s*META[\s\S]*?-->""")
        var result = metaPattern.replace(content, "")

        // 清理开头的空白行
        while (result.startsWith("\n") || result.startsWith("\r")) {
            result = result.substring(1)
        }

        return result.trim()
    }

    /**
     * 获取所有 MD 文件的最后修改时间
     *
     * @return Map<AnalysisType, Instant>
     */
    fun getAllLastModifiedTimes(): Map<AnalysisType, Instant> {
        val result = mutableMapOf<AnalysisType, Instant>()

        for (type in AnalysisType.allTypes()) {
            val metadata = readMetadata(type)
            if (metadata != null) {
                result[type] = metadata.lastModified
            }
        }

        return result
    }

    /**
     * 获取所有未完成的分析类型
     *
     * 【修复】同时考虑完成度和 TODO：
     * - MD 不存在
     * - 完成度低于阈值
     * - 有未完成的 TODO
     *
     * @param threshold 完成度阈值
     * @return 未完成的分析类型列表
     */
    fun getIncompleteTypes(threshold: Double = 0.8): List<AnalysisType> {
        return AnalysisType.allTypes().filter { type ->
            val metadata = readMetadata(type)
            // 以下情况需要继续分析：
            // 1. MD 不存在
            // 2. 完成度低于阈值
            // 3. 有未完成的 TODO
            metadata == null ||
            metadata.completeness < threshold ||
            metadata.todos.isNotEmpty()
        }
    }

    /**
     * 获取所有需要更新的分析类型（基于 TODO）
     */
    fun getTypesWithTodos(): List<Pair<AnalysisType, List<String>>> {
        return AnalysisType.allTypes().mapNotNull { type ->
            val metadata = readMetadata(type)
            if (metadata != null && metadata.todos.isNotEmpty()) {
                type to metadata.todos.map { it.content }
            } else {
                null
            }
        }
    }

    companion object {
        /**
         * 当前时间戳（人类可读格式）
         */
        fun currentTimestamp(): String {
            val formatter = java.time.format.DateTimeFormatter
                .ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(java.time.ZoneId.systemDefault())
            return formatter.format(Instant.now())
        }
    }
}
