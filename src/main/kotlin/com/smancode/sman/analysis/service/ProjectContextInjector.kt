package com.smancode.sman.analysis.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.smancode.sman.analysis.model.AnalysisStatus
import com.smancode.sman.analysis.model.AnalysisType
import com.smancode.sman.analysis.model.ProjectMapManager
import com.smancode.sman.analysis.model.StepState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Paths

/**
 * 项目上下文注入器
 *
 * 负责：
 * - 从 .sman/base/ 目录读取分析结果 MD 文件
 * - 格式化为适合注入到提示词的文本
 * - 检查分析状态
 */
class ProjectContextInjector(
    private val jdbcUrl: String
) {

    private val logger = LoggerFactory.getLogger(ProjectContextInjector::class.java)
    private val objectMapper = ObjectMapper()

    /**
     * 获取项目上下文摘要（用于注入到提示词）
     *
     * @param projectKey 项目标识符
     * @return 格式化后的项目上下文文本，如果分析未完成返回空字符串
     */
    suspend fun getProjectContextSummary(projectKey: String): String = withContext(Dispatchers.IO) {
        try {
            // 检查核心步骤是否完成
            val entry = ProjectMapManager.getProjectEntry(projectKey)
            if (entry == null) {
                logger.debug("项目未注册，跳过注入项目上下文: projectKey={}", projectKey)
                return@withContext ""
            }

            val structureCompleted = entry.analysisStatus.projectStructure == StepState.COMPLETED
            val techStackCompleted = entry.analysisStatus.techStack == StepState.COMPLETED

            if (!structureCompleted && !techStackCompleted) {
                logger.debug("核心步骤未完成，跳过注入项目上下文: projectKey={}", projectKey)
                return@withContext ""
            }

            val sb = StringBuilder()

            // 项目结构
            if (structureCompleted) {
                val structureContent = readMdFileContent(entry.path, AnalysisType.PROJECT_STRUCTURE.mdFileName)
                if (structureContent != null) {
                    sb.append("\n### 项目结构\n\n")
                    sb.append(extractSummary(structureContent, 200))
                    sb.append("\n")
                }
            }

            // 技术栈
            if (techStackCompleted) {
                val techStackContent = readMdFileContent(entry.path, AnalysisType.TECH_STACK.mdFileName)
                if (techStackContent != null) {
                    sb.append("\n### 技术栈\n\n")
                    sb.append(extractSummary(techStackContent, 200))
                    sb.append("\n")
                }
            }

            sb.toString()
        } catch (e: Exception) {
            logger.warn("获取项目上下文失败: projectKey={}", projectKey, e)
            ""
        }
    }

    /**
     * 检查项目分析是否完成（核心步骤完成即可）
     */
    suspend fun isAnalysisComplete(projectKey: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val entry = ProjectMapManager.getProjectEntry(projectKey)
            entry != null && entry.analysisStatus.isAllComplete()
        } catch (e: Exception) {
            logger.debug("检查分析状态失败: projectKey={}", projectKey, e)
            false
        }
    }

    /**
     * 检查核心步骤是否完成（用于提示词注入判断）
     */
    suspend fun areCoreStepsCompleted(projectKey: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val entry = ProjectMapManager.getProjectEntry(projectKey)
                ?: return@withContext false
            val structureCompleted = entry.analysisStatus.projectStructure == StepState.COMPLETED
            val techStackCompleted = entry.analysisStatus.techStack == StepState.COMPLETED
            structureCompleted || techStackCompleted
        } catch (e: Exception) {
            logger.debug("检查核心步骤状态失败: projectKey={}", projectKey, e)
            false
        }
    }

    // ========== 私有方法 ==========

    /**
     * 读取 MD 文件内容
     */
    private fun readMdFileContent(projectPath: String, mdFileName: String): String? {
        return try {
            val mdPath = Paths.get(projectPath, ".sman", "base", mdFileName)
            if (!Files.exists(mdPath)) {
                logger.debug("MD 文件不存在: {}", mdPath)
                return null
            }
            Files.readString(mdPath)
        } catch (e: Exception) {
            logger.warn("读取 MD 文件失败: {}", mdFileName, e)
            null
        }
    }

    /**
     * 从 Markdown 内容提取摘要（前 N 字）
     */
    private fun extractSummary(content: String, maxLength: Int): String {
        return try {
            // 移除标题行
            val lines = content.lines()
                .dropWhile { it.trim().startsWith("#") }
                .joinToString("\n")
                .trim()

            if (lines.length <= maxLength) {
                lines
            } else {
                lines.take(maxLength) + "..."
            }
        } catch (e: Exception) {
            content.take(maxLength)
        }
    }
}
