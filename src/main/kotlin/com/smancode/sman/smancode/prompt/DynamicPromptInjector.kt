package com.smancode.sman.smancode.prompt

import com.smancode.sman.analysis.service.ProjectContextInjector
import com.smancode.sman.analysis.config.VectorDatabaseConfig
import com.smancode.sman.analysis.config.VectorDbType
import com.smancode.sman.analysis.config.JVectorConfig
import com.smancode.sman.util.StackTraceUtils
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * Prompt 自动注入器
 *
 * 设计原则（极简方案）：
 * - 每个会话首次请求时，一次性加载所有必要的 Prompt
 * - 避免硬编码模式匹配
 * - 避免复杂的对话状态跟踪
 * - 避免额外的 LLM 调用
 *
 * 为什么这样设计？
 * - 系统主要处理复杂业务需求
 * - Prompt 只加载一次，后续请求不再加载
 * - 简单、透明、高效
 */
class DynamicPromptInjector(
    private val promptLoader: PromptLoaderService
) {
    private val logger = LoggerFactory.getLogger(DynamicPromptInjector::class.java)

    // ProjectContextInjector 实例（懒加载）
    private var projectContextInjector: ProjectContextInjector? = null
    private val injectorLock = Any()

    /**
     * 获取或创建 ProjectContextInjector
     */
    private fun getProjectContextInjector(projectKey: String): ProjectContextInjector? {
        if (projectContextInjector == null) {
            synchronized(injectorLock) {
                if (projectContextInjector == null) {
                    try {
                        val jdbcUrl = buildJdbcUrl(projectKey)
                        projectContextInjector = ProjectContextInjector(jdbcUrl)
                    } catch (e: Exception) {
                        logger.warn("创建 ProjectContextInjector 失败: projectKey={}", projectKey, e)
                        return null
                    }
                }
            }
        }
        return projectContextInjector
    }

    /**
     * 构建 H2 JDBC URL
     */
    private fun buildJdbcUrl(projectKey: String): String {
        val config = VectorDatabaseConfig.create(
            projectKey = projectKey,
            type = VectorDbType.JVECTOR,
            jvector = JVectorConfig()
        )
        return "jdbc:h2:${config.databasePath};MODE=PostgreSQL;AUTO_SERVER=TRUE"
    }

    /**
     * 记录已加载过 Prompt 的会话
     * key: sessionKey, value: true 表示已加载
     */
    private val loadedSessions = ConcurrentHashMap<String, Boolean>()

    /**
     * 检测并注入动态 Prompt
     *
     * 策略：
     * - 如果该会话是首次请求，加载所有 Prompt
     * - 如果该会话已加载过，返回空结果
     *
     * 无需：
     * - 硬编码模式匹配
     * - 对话轮次判断
     * - 用户确认检测
     * - LLM 显式请求
     *
     * @param sessionKey 会话标识
     * @param projectKey 项目标识符（可选，用于注入项目上下文）
     * @return 需要注入的额外 Prompt 内容
     */
    fun detectAndInject(sessionKey: String, projectKey: String? = null): InjectResult {
        val result = InjectResult()

        // 检查该会话是否已加载过
        if (loadedSessions.containsKey(sessionKey)) {
            logger.debug("会话 {} 已加载过 Prompt，跳过", sessionKey)
            return result
        }

        // 首次请求：加载所有 Prompt
        logger.info("会话 {} 首次请求，加载所有 Prompt", sessionKey)

        return try {
            // 加载复杂任务工作流
            val workflowPrompt = promptLoader.loadPrompt("common/complex-task-workflow.md")
            result.complexTaskWorkflow = workflowPrompt
            result.needComplexTaskWorkflow = true

            // 加载编码最佳实践
            val practicesPrompt = promptLoader.loadPrompt("common/coding-best-practices.md")
            result.codingBestPractices = practicesPrompt
            result.needCodingBestPractices = true

            // 如果提供了 projectKey，尝试注入项目上下文
            if (!projectKey.isNullOrEmpty()) {
                val projectContext = loadProjectContext(projectKey)
                if (projectContext.isNotEmpty()) {
                    result.projectContext = projectContext
                    result.needProjectContext = true
                    logger.info("会话 {} 已加载项目上下文: projectKey={}", sessionKey, projectKey)
                }
            }

            // 标记该会话已加载
            loadedSessions[sessionKey] = true

            logger.info("会话 {} Prompt 加载完成", sessionKey)
            result
        } catch (e: Exception) {
            logger.error("加载 Prompt 失败, sessionKey={}, {}", sessionKey, StackTraceUtils.formatStackTrace(e))
            result
        }
    }

    /**
     * 加载项目上下文
     *
     * @param projectKey 项目标识符
     * @return 格式化的项目上下文文本，如果分析未完成返回空字符串
     */
    private fun loadProjectContext(projectKey: String): String {
        return try {
            val injector = getProjectContextInjector(projectKey) ?: return ""
            runBlocking {
                injector.getProjectContextSummary(projectKey)
            }
        } catch (e: Exception) {
            logger.warn("加载项目上下文失败: projectKey={}", projectKey, e)
            ""
        }
    }

    /**
     * 清理会话记录（会话结束时调用）
     *
     * @param sessionKey 会话标识
     */
    fun clearSession(sessionKey: String) {
        loadedSessions.remove(sessionKey)
        logger.debug("清理会话 {} 的 Prompt 加载记录", sessionKey)
    }

    /**
     * 注入结果
     */
    class InjectResult {
        var needComplexTaskWorkflow: Boolean = false
        var needCodingBestPractices: Boolean = false
        var needProjectContext: Boolean = false
        var complexTaskWorkflow: String? = null
        var codingBestPractices: String? = null
        var projectContext: String? = null

        /**
         * 获取需要注入的完整内容
         */
        val injectedContent: String
            get() {
                val sb = StringBuilder()

                if (needProjectContext && projectContext != null) {
                    sb.append("\n\n## 项目上下文 (Project Context)\n\n")
                    sb.append(projectContext)
                }

                if (needComplexTaskWorkflow && complexTaskWorkflow != null) {
                    sb.append("\n\n## Loaded: Complex Task Workflow\n\n")
                    sb.append(complexTaskWorkflow)
                }

                if (needCodingBestPractices && codingBestPractices != null) {
                    sb.append("\n\n## Loaded: Coding Best Practices\n\n")
                    sb.append(codingBestPractices)
                }

                return sb.toString()
            }

        /**
         * 是否有需要注入的内容
         */
        fun hasContent(): Boolean {
            return (needComplexTaskWorkflow && complexTaskWorkflow != null)
                || (needCodingBestPractices && codingBestPractices != null)
                || (needProjectContext && projectContext != null)
        }

        // ========== 属性访问方式（兼容 Java 风格调用） ==========

        /**
         * 是否需要复杂任务工作流（属性访问方式）
         */
        val isNeedComplexTaskWorkflow: Boolean
            get() = needComplexTaskWorkflow

        /**
         * 是否需要编码最佳实践（属性访问方式）
         */
        val isNeedCodingBestPractices: Boolean
            get() = needCodingBestPractices

        /**
         * 是否需要项目上下文（属性访问方式）
         */
        val isNeedProjectContext: Boolean
            get() = needProjectContext
    }
}
