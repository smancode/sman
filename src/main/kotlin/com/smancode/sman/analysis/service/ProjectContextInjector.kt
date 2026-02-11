package com.smancode.sman.analysis.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.smancode.sman.analysis.model.AnalysisStatus
import com.smancode.sman.analysis.model.StepStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.sql.Connection

/**
 * 项目上下文注入器
 *
 * 负责：
 * - 从 H2 数据库读取项目分析结果
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
            val stepsStatus = getStepsStatus(projectKey)
            val structureCompleted = stepsStatus["project_structure"] == StepStatus.COMPLETED
            val techStackCompleted = stepsStatus["tech_stack_detection"] == StepStatus.COMPLETED

            if (!structureCompleted && !techStackCompleted) {
                logger.debug("核心步骤未完成，跳过注入项目上下文: projectKey={}", projectKey)
                return@withContext ""
            }

            val sb = StringBuilder()

            // 项目结构
            if (structureCompleted) {
                val structureData = getStepData(projectKey, "project_structure")
                if (structureData != null) {
                    sb.append("\n### 项目结构\n\n")
                    sb.append(formatProjectStructure(structureData))
                    sb.append("\n")
                }
            }

            // 技术栈
            if (techStackCompleted) {
                val techStackData = getStepData(projectKey, "tech_stack_detection")
                if (techStackData != null) {
                    sb.append("\n### 技术栈\n\n")
                    sb.append(formatTechStack(techStackData))
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
            val status = getAnalysisStatus(projectKey)
            status == AnalysisStatus.COMPLETED
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
            val stepsStatus = getStepsStatus(projectKey)
            val structureCompleted = stepsStatus["project_structure"] == StepStatus.COMPLETED
            val techStackCompleted = stepsStatus["tech_stack_detection"] == StepStatus.COMPLETED
            structureCompleted || techStackCompleted
        } catch (e: Exception) {
            logger.debug("检查核心步骤状态失败: projectKey={}", projectKey, e)
            false
        }
    }

    // ========== 私有方法 ==========

    private suspend fun getStepsStatus(projectKey: String): Map<String, StepStatus> = withContext(Dispatchers.IO) {
        useConnection { connection ->
            val sql = "SELECT step_name, status FROM analysis_step WHERE project_key = ?"
            connection.prepareStatement(sql).use { stmt ->
                stmt.setString(1, projectKey)
                val rs = stmt.executeQuery()
                val statusMap = mutableMapOf<String, StepStatus>()
                while (rs.next()) {
                    val stepName = rs.getString("step_name")
                    val status = StepStatus.valueOf(rs.getString("status"))
                    statusMap[stepName] = status
                }
                statusMap
            }
        }
    }

    private suspend fun getStepData(projectKey: String, stepName: String): String? = withContext(Dispatchers.IO) {
        useConnection { connection ->
            val sql = "SELECT data FROM analysis_step WHERE project_key = ? AND step_name = ?"
            connection.prepareStatement(sql).use { stmt ->
                stmt.setString(1, projectKey)
                stmt.setString(2, stepName)
                val rs = stmt.executeQuery()
                if (rs.next()) rs.getString("data") else null
            }
        }
    }

    private suspend fun getAnalysisStatus(projectKey: String): AnalysisStatus? = withContext(Dispatchers.IO) {
        useConnection { connection ->
            val sql = "SELECT status FROM project_analysis WHERE project_key = ?"
            connection.prepareStatement(sql).use { stmt ->
                stmt.setString(1, projectKey)
                val rs = stmt.executeQuery()
                if (rs.next()) {
                    AnalysisStatus.valueOf(rs.getString("status"))
                } else {
                    null
                }
            }
        }
    }

    private suspend fun <T> useConnection(block: (Connection) -> T): T {
        return try {
            Class.forName("org.h2.Driver")
            val url = "$jdbcUrl;MODE=PostgreSQL"
            java.sql.DriverManager.getConnection(url, "sa", "").use(block)
        } catch (e: Exception) {
            logger.error("数据库操作失败", e)
            throw e
        }
    }

    // ========== 格式化方法 ==========

    /**
     * 格式化项目结构数据
     * 输入：JSON 格式的项目结构
     * 输出：精简的 Markdown 文本（< 200 字）
     */
    private fun formatProjectStructure(jsonData: String): String {
        return try {
            val root = objectMapper.readTree(jsonData)
            val sb = StringBuilder()

            // 提取模块信息
            val modules = root.get("modules")
            if (modules != null && modules.isArray) {
                sb.append("**模块**: ")
                val moduleNames = mutableListOf<String>()
                for (module in modules) {
                    val name = module.get("name")?.asText() ?: continue
                    moduleNames.add(name)
                }
                sb.append(moduleNames.take(5).joinToString(", "))
                if (moduleNames.size > 5) {
                    sb.append(" 等 ${moduleNames.size} 个模块")
                }
                sb.append("\n")
            }

            // 提取分层信息
            val layers = root.get("layers")
            if (layers != null && layers.isArray) {
                sb.append("**分层**: ")
                val layerNames = mutableListOf<String>()
                for (layer in layers) {
                    val name = layer.get("name")?.asText() ?: continue
                    layerNames.add(name)
                }
                sb.append(layerNames.joinToString(" → "))
                sb.append("\n")
            }

            sb.toString().take(200)
        } catch (e: Exception) {
            logger.warn("格式化项目结构失败", e)
            "项目结构解析失败"
        }
    }

    /**
     * 格式化技术栈数据
     * 输入：JSON 格式的技术栈
     * 输出：精简的 Markdown 文本（< 200 字）
     */
    private fun formatTechStack(jsonData: String): String {
        return try {
            val root = objectMapper.readTree(jsonData)
            val sb = StringBuilder()

            // 框架
            val frameworks = root.get("frameworks")
            if (frameworks != null && frameworks.isArray) {
                sb.append("**框架**: ")
                val names = mutableListOf<String>()
                for (fw in frameworks) {
                    names.add(fw.asText())
                }
                sb.append(names.joinToString(", "))
                sb.append("\n")
            }

            // 数据库
            val databases = root.get("databases")
            if (databases != null && databases.isArray) {
                sb.append("**数据库**: ")
                val names = mutableListOf<String>()
                for (db in databases) {
                    names.add(db.asText())
                }
                sb.append(names.joinToString(", "))
                sb.append("\n")
            }

            // 中间件
            val middleware = root.get("middleware")
            if (middleware != null && middleware.isArray) {
                sb.append("**中间件**: ")
                val names = mutableListOf<String>()
                for (mw in middleware) {
                    names.add(mw.asText())
                }
                sb.append(names.joinToString(", "))
                sb.append("\n")
            }

            sb.toString().take(200)
        } catch (e: Exception) {
            logger.warn("格式化技术栈失败", e)
            "技术栈解析失败"
        }
    }
}
