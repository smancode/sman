package com.smancode.sman.analysis.executor

import com.smancode.sman.analysis.model.AnalysisTodo
import com.smancode.sman.analysis.model.AnalysisType
import com.smancode.sman.analysis.model.TodoStatus
import com.smancode.sman.evolution.guard.DoomLoopGuard
import com.smancode.sman.smancode.llm.LlmService
import com.smancode.sman.tools.ToolExecutor
import com.smancode.sman.tools.ToolRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

/**
 * 分析循环执行结果
 */
data class AnalysisLoopResult(
    val type: AnalysisType,
    val content: String,
    val completeness: Double,
    val missingSections: List<String>,
    val todos: List<AnalysisTodo>,
    val steps: Int,
    val toolCallHistory: String? = null
)

/**
 * 独立分析执行器
 *
 * 执行单个分析类型，复用工具调用能力，但不经过对话式 ReAct 循环。
 * 强制输出结构化 Markdown 报告，并进行完成度验证。
 */
class AnalysisLoopExecutor(
    private val toolRegistry: ToolRegistry,
    private val toolExecutor: ToolExecutor,
    private val doomLoopGuard: DoomLoopGuard,
    private val llmService: LlmService,
    private val validator: AnalysisOutputValidator,
    private val maxSteps: Int = 15
) {
    private val logger = LoggerFactory.getLogger(AnalysisLoopExecutor::class.java)

    /**
     * 执行分析
     *
     * @param type 分析类型
     * @param projectKey 项目唯一标识
     * @param priorContext 前置上下文（之前分析的结果）
     * @param existingTodos 现有的 TODO 列表
     * @return 分析结果
     */
    suspend fun execute(
        type: AnalysisType,
        projectKey: String,
        priorContext: String = "",
        existingTodos: List<AnalysisTodo> = emptyList()
    ): AnalysisLoopResult = withContext(Dispatchers.IO) {
        logger.info("开始执行分析: type={}, projectKey={}", type, projectKey)

        val checkResult = doomLoopGuard.shouldSkipQuestion(projectKey)
        if (checkResult.shouldSkip) {
            logger.warn("分析被跳过: reason={}", checkResult.reason)
            return@withContext createEmptyResult(type, existingTodos)
        }

        var step = 0
        var hasCompleteReport = false
        var currentContent = ""
        val toolResults = mutableListOf<String>()
        var finalCompleteness = 0.0
        var finalMissingSections = emptyList<String>()

        while (step < maxSteps && !hasCompleteReport) {
            step++
            logger.debug("分析步骤 {}/{}: type={}", step, maxSteps, type)

            val prompt = buildAnalysisPrompt(type, priorContext, existingTodos, toolResults, currentContent)
            val response = callLlm(prompt)
            currentContent = response

            val cleanedContent = validator.cleanMarkdownContent(response)
            val toolCalls = extractToolCalls(response)

            if (toolCalls.isNotEmpty()) {
                executeToolCalls(toolCalls, toolResults)
            } else {
                val validationResult = validator.validate(cleanedContent, type)
                finalCompleteness = validationResult.completeness
                finalMissingSections = validationResult.missingSections

                if (validationResult.isValid) {
                    hasCompleteReport = true
                    logger.info("分析完成: type={}, completeness={}", type, finalCompleteness)
                } else {
                    val supplementRequest = buildSupplementRequest(finalMissingSections)
                    toolResults.add(supplementRequest)
                    logger.debug("报告不完整，继续补充: missing={}", finalMissingSections)
                }
            }
        }

        val finalTodos = if (finalMissingSections.isNotEmpty()) {
            validator.generateTodos(finalMissingSections, type)
        } else {
            existingTodos
        }

        val finalContent = validator.cleanMarkdownContent(currentContent)
        doomLoopGuard.recordSuccess(projectKey)

        logger.info("分析执行完成: type={}, steps={}, completeness={}", type, step, finalCompleteness)

        AnalysisLoopResult(
            type = type,
            content = finalContent,
            completeness = finalCompleteness,
            missingSections = finalMissingSections,
            todos = finalTodos,
            steps = step,
            toolCallHistory = if (toolResults.isNotEmpty()) toolResults.joinToString("\n") else null
        )
    }

    private fun createEmptyResult(type: AnalysisType, existingTodos: List<AnalysisTodo>): AnalysisLoopResult {
        return AnalysisLoopResult(
            type = type,
            content = "",
            completeness = 0.0,
            missingSections = validator.extractMissingSections("", type),
            todos = existingTodos,
            steps = 0
        )
    }

    /**
     * 构建分析提示词
     */
    private fun buildAnalysisPrompt(
        type: AnalysisType,
        priorContext: String,
        existingTodos: List<AnalysisTodo>,
        toolResults: List<String>,
        currentContent: String
    ): String {
        return buildString {
            appendLine("# 任务：${type.displayName}")
            appendLine()
            appendLine("## 分析要求")
            appendLine("请对项目进行${type.displayName}，生成结构化的 Markdown 报告。")
            appendLine()

            appendLine("## 必填章节")
            appendLine(getRequiredSectionsDescription(type))
            appendLine()

            if (priorContext.isNotBlank()) {
                appendLine("## 已知上下文")
                appendLine(priorContext)
                appendLine()
            }

            if (existingTodos.isNotEmpty()) {
                appendLine("## 待补充内容")
                existingTodos.forEach { appendLine("- ${it.content}") }
                appendLine()
            }

            if (toolResults.isNotEmpty()) {
                appendLine("## 工具调用历史")
                toolResults.forEach { appendLine(it) }
                appendLine()
            }

            if (currentContent.isNotBlank()) {
                appendLine("## 当前分析内容")
                appendLine(currentContent)
                appendLine()
            }

            appendLine("## 输出格式")
            appendLine("请直接输出 Markdown 格式的分析报告，不要包含任何对话式内容。")
            appendLine("不要使用 <thinking> 或 <thinkable> 标签。")
        }
    }

    /**
     * 获取必填章节描述
     */
    private fun getRequiredSectionsDescription(type: AnalysisType): String {
        return when (type) {
            AnalysisType.PROJECT_STRUCTURE -> """
                - 项目概述：项目的基本介绍
                - 目录结构：项目的目录组织方式
                - 模块划分：各个模块的职责和关系
                - 依赖管理：项目使用的主要依赖
            """.trimIndent()

            AnalysisType.TECH_STACK -> """
                - 编程语言：使用的编程语言及版本
                - 构建工具：构建和打包工具
                - 框架：使用的框架
                - 数据存储：数据库和存储方案
            """.trimIndent()

            AnalysisType.API_ENTRIES -> """
                - 入口列表：所有 API 端点
                - 认证方式：API 的认证机制
                - 请求格式：请求的数据格式
                - 响应格式：响应的数据格式
            """.trimIndent()

            AnalysisType.DB_ENTITIES -> """
                - 实体列表：所有数据库实体
                - 表关系：实体之间的关系
                - 字段详情：重要字段的说明
            """.trimIndent()

            AnalysisType.ENUMS -> """
                - 枚举列表：所有枚举类型
                - 枚举用途：各枚举的用途说明
            """.trimIndent()

            AnalysisType.CONFIG_FILES -> """
                - 配置文件列表：所有配置文件
                - 环境配置：不同环境的配置差异
            """.trimIndent()
        }
    }

    /**
     * 调用 LLM
     */
    private suspend fun callLlm(prompt: String): String {
        return try {
            // 实际实现应该调用 llmService.chat(prompt)
            generateMockResponse(prompt)
        } catch (e: Exception) {
            logger.error("LLM 调用失败", e)
            ""
        }
    }

    /**
     * 生成模拟响应（用于测试）
     */
    private fun generateMockResponse(prompt: String): String {
        val type = AnalysisType.values().firstOrNull { prompt.contains(it.displayName) }
            ?: AnalysisType.PROJECT_STRUCTURE

        return when (type) {
            AnalysisType.PROJECT_STRUCTURE -> """
                # 项目结构分析

                ## 项目概述
                这是一个示例项目，用于演示分析功能。

                ## 目录结构
                - src/main/kotlin - 主代码目录
                - src/test/kotlin - 测试代码目录

                ## 模块划分
                - core: 核心功能模块
                - api: API 接口模块

                ## 依赖管理
                - Kotlin 1.9.20
                - Gradle 8.0
            """.trimIndent()

            AnalysisType.TECH_STACK -> """
                # 技术栈识别

                ## 编程语言
                Kotlin 1.9.20

                ## 构建工具
                Gradle 8.0

                ## 框架
                IntelliJ Platform Plugin SDK

                ## 数据存储
                H2 数据库
            """.trimIndent()

            else -> "# ${type.displayName}\n\n分析内容..."
        }
    }

    /**
     * 工具调用信息
     */
    data class ToolCallInfo(
        val name: String,
        val params: Map<String, Any>
    )

    /**
     * 从响应中提取工具调用
     */
    private fun extractToolCalls(response: String): List<ToolCallInfo> {
        // 实际实现应该解析 JSON 格式的工具调用
        return emptyList()
    }

    /**
     * 执行工具调用
     */
    private suspend fun executeToolCalls(toolCalls: List<ToolCallInfo>, toolResults: MutableList<String>) {
        for (toolCall in toolCalls) {
            val toolCheck = doomLoopGuard.shouldSkipToolCall(toolCall.name, toolCall.params)
            if (toolCheck.shouldSkip) {
                if (toolCheck.cachedResult != null) {
                    toolResults.add("工具 ${toolCall.name} (缓存): ${toolCheck.cachedResult}")
                }
            } else {
                val result = executeToolCall(toolCall)
                doomLoopGuard.recordToolCall(toolCall.name, toolCall.params, result)
                toolResults.add("工具 ${toolCall.name}: $result")
            }
        }
    }

    private suspend fun executeToolCall(toolCall: ToolCallInfo): String {
        return try {
            // 实际实现: toolExecutor.execute(toolCall.name, toolCall.params)
            "工具 ${toolCall.name} 执行结果"
        } catch (e: Exception) {
            logger.error("工具执行失败: name={}", toolCall.name, e)
            "工具执行失败: ${e.message}"
        }
    }

    /**
     * 构建补充请求
     */
    private fun buildSupplementRequest(missingSections: List<String>): String {
        return "请补充以下章节的分析内容：${missingSections.joinToString("、")}"
    }
}
