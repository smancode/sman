package com.smancode.smanagent.smancode.core

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.smancode.smanagent.model.message.Message
import com.smancode.smanagent.model.part.Part
import com.smancode.smanagent.model.part.TextPart
import com.smancode.smanagent.model.part.ToolPart
import com.smancode.smanagent.model.session.Session
import com.smancode.smanagent.smancode.llm.LlmService
import com.smancode.smanagent.tools.ToolExecutor
import com.smancode.smanagent.tools.ToolResult
import com.smancode.smanagent.util.StackTraceUtils
import org.slf4j.LoggerFactory
import java.util.function.Consumer

/**
 * 子任务执行器
 *
 * 实现工具调用的上下文隔离：
 * 1. 每个工具调用在独立的子会话中执行
 * 2. 只保留摘要，清理完整输出
 * 3. 防止 Token 爆炸
 */
class SubTaskExecutor(
    private val sessionManager: SessionManager,
    private val toolExecutor: ToolExecutor,
    private val resultSummarizer: ResultSummarizer,
    private val llmService: LlmService,
    private val notificationHandler: StreamingNotificationHandler
) {
    private val logger = LoggerFactory.getLogger(SubTaskExecutor::class.java)
    private val objectMapper = ObjectMapper()

    /**
     * 子任务执行结果
     */
    data class SubTaskResult(
        val toolName: String,
        val success: Boolean,
        val summary: String? = null,
        val error: String? = null,
        val displayTitle: String? = null
    ) {
        companion object {
            fun builder() = Builder()
        }

        class Builder {
            private var toolName: String = ""
            private var success: Boolean = false
            private var summary: String? = null
            private var error: String? = null
            private var displayTitle: String? = null

            fun toolName(toolName: String) = apply { this.toolName = toolName }
            fun success(success: Boolean) = apply { this.success = success }
            fun summary(summary: String?) = apply { this.summary = summary }
            fun error(error: String?) = apply { this.error = error }
            fun displayTitle(displayTitle: String?) = apply { this.displayTitle = displayTitle }

            fun build() = SubTaskResult(
                toolName = toolName,
                success = success,
                summary = summary,
                error = error,
                displayTitle = displayTitle
            )
        }
    }

    /**
     * 执行工具（带上下文隔离）
     *
     * @param toolPart      工具 Part
     * @param parentSession 父会话
     * @param partPusher    Part 推送器
     * @return 工具结果摘要
     */
    fun executeToolIsolated(
        toolPart: ToolPart,
        parentSession: Session,
        partPusher: Consumer<Part>
    ): SubTaskResult {
        val toolName = toolPart.toolName!!

        logger.info("【工具调用开始】toolName={}, parentSessionId={}, 参数={}",
            toolName, parentSession.id, toolPart.parameters)

        // 1. 创建子会话
        val childSession = sessionManager.createChildSession(parentSession.id!!)

        return try {
            // 2. 更新状态为 RUNNING（但不发送，避免冗余）
            toolPart.state = ToolPart.ToolState.RUNNING
            toolPart.touch()

            // 3. 在子会话中执行工具
            val projectKey = childSession.projectInfo!!.projectKey

            logger.info("【工具执行中】toolName={}, projectKey={}", toolName, projectKey)
            val fullResult = toolExecutor.execute(
                toolName,
                projectKey!!,
                toolPart.parameters!!,
                partPusher  // 传递 partPusher 用于流式输出
            )
            logger.info("【工具执行完成】toolName={}, success={}, displayTitle={}, displayContent长度={}, error={}",
                toolName, fullResult.isSuccess, fullResult.displayTitle,
                fullResult.displayContent?.length ?: 0,
                fullResult.error)

            // 4. 保留完整结果（不压缩），让 LLM 处理
            // 注意：不在这里生成摘要，让 LLM 在下一次调用时基于完整结果生成摘要

            // 5. 更新工具状态（不推送完整 TOOL Part，只推送摘要）
            if (fullResult.isSuccess) {
                toolPart.state = ToolPart.ToolState.COMPLETED
            } else {
                toolPart.state = ToolPart.ToolState.ERROR
            }
            toolPart.result = fullResult  // 保留完整结果（用于 LLM 生成摘要）
            toolPart.touch()

            // 6. 不自动生成摘要！保持 summary 为 null，等待 LLM 生成
            // toolPart.summary = null  // 默认就是 null，不需要显式设置

            // 7. 生成前端显示摘要（用于前端显示，不影响 LLM 逻辑）
            val displaySummary = resultSummarizer.summarize(toolName, fullResult, parentSession)

            // 8. 推送工具摘要（唯一推送给前端的内容）
            val summaryPart = createSummaryPart(toolPart, displaySummary, fullResult)
            partPusher.accept(summaryPart)

            // 9. 清理子会话
            sessionManager.cleanupChildSession(childSession.id!!)

            SubTaskResult.builder()
                .toolName(toolName)
                .success(fullResult.isSuccess)
                .summary(displaySummary)  // 只用于前端显示
                .displayTitle(fullResult.displayTitle)
                .build()

        } catch (e: Exception) {
            logger.error("工具执行失败: toolName={}, {}", toolName, StackTraceUtils.formatStackTrace(e))

            toolPart.state = ToolPart.ToolState.ERROR
            toolPart.result = ToolResult.failure(e.message)
            toolPart.touch()
            partPusher.accept(toolPart)

            sessionManager.cleanupChildSession(childSession.id!!)

            SubTaskResult.builder()
                .toolName(toolName)
                .success(false)
                .error(e.message)
                .build()
        }
    }

    /**
     * 创建摘要 Part
     *
     * 返回结构化数据，不包含任何显示格式（⏺、└─ 等由前端添加）
     */
    private fun createSummaryPart(toolPart: ToolPart, summary: String?, fullResult: ToolResult): Part {
        // 创建摘要文本 Part（纯数据，不包含显示格式）
        // 前端会识别这种格式并渲染：toolName(params)\nline1\nline2\n...
        val sb = StringBuilder()
        val toolName = toolPart.toolName!!
        sb.append(toolName)

        // 特殊处理 batch 工具：使用 displayContent 而不是原始参数
        if (toolName == "batch" && !fullResult.displayContent.isNullOrEmpty()) {
            // batch 工具的 displayContent 已经是简化格式："文件名, N个工具"
            sb.append("(").append(fullResult.displayContent).append(")")
        } else {
            // 其他工具：格式化参数
            val params = toolPart.parameters
            if (params?.isNotEmpty() == true) {
                val paramsStr = formatParamsForTitle(params)
                sb.append("(").append(paramsStr).append(")")
            }
        }
        sb.append("\n")

        // 添加摘要内容（纯文本行，不添加 ── 前缀）
        if (!summary.isNullOrEmpty()) {
            val lines = summary.split("\n")
            for (line in lines) {
                val trimmedLine = line.trim()
                if (trimmedLine.isNotEmpty() && !isRedundantSummaryLine(trimmedLine, toolPart.parameters)) {
                    sb.append(trimmedLine).append("\n")
                }
            }
        }

        val textPart = TextPart().apply {
            messageId = toolPart.messageId
            sessionId = toolPart.sessionId
            text = sb.toString()
            touch()
        }

        return textPart
    }

    /**
     * 检查摘要行是否与参数重复（冗余）
     * 例如："查询: read_file" 当参数中有 query=read_file 时就是冗余的
     */
    private fun isRedundantSummaryLine(line: String, params: Map<String, Any>?): Boolean {
        if (params.isNullOrEmpty()) {
            return false
        }

        // 检查是否是 "key: value" 格式
        if (!line.contains(":")) {
            return false
        }

        // 提取 key 和 value
        val colonIndex = line.indexOf(':')
        val lineKey = line.substring(0, colonIndex).trim().lowercase()
        val lineValue = line.substring(colonIndex + 1).trim().lowercase()

        // 检查参数中是否有相同的 key 和 value
        for ((key, value) in params) {
            val paramKey = key.lowercase()
            val paramValue = value?.toString()?.lowercase() ?: ""

            // 如果 key 匹配且 value 也匹配（或 value 包含在参数值中），认为是冗余
            if (lineKey == paramKey && paramValue.isNotEmpty()) {
                if (lineValue == paramValue || paramValue.contains(lineValue) || lineValue.contains(paramValue)) {
                    return true
                }
            }
        }

        return false
    }

    /**
     * 格式化参数为标题格式
     * 例如：{pattern: "*.java"} -> "*.java"
     */
    private fun formatParamsForTitle(params: Map<String, Any>?): String {
        if (params.isNullOrEmpty()) {
            return ""
        }

        // 特殊处理 grep_file：始终显示 pattern
        val pattern = params["pattern"]
        if (pattern != null) {
            val patternStr = pattern.toString()
            if (params.size == 1) {
                // 只有 pattern 时，只显示 pattern
                return patternStr
            } else if (params.containsKey("relativePath")) {
                // 有 pattern 和 relativePath 时，显示 pattern(文件名)
                val relPathObj = params["relativePath"]
                val relPath = relPathObj?.toString() ?: ""
                val lastSlash = maxOf(relPath.lastIndexOf('/'), relPath.lastIndexOf('\\'))
                val fileName = if (lastSlash >= 0 && lastSlash < relPath.length - 1) {
                    relPath.substring(lastSlash + 1)
                } else {
                    relPath
                }
                return "$patternStr($fileName)"
            }
        }

        // 特殊处理 apply_change：只保留 relativePath，且只显示文件名
        val relativePath = params["relativePath"] as? String
        if (relativePath != null) {
            // 提取文件名
            val lastSlash = maxOf(relativePath.lastIndexOf('/'), relativePath.lastIndexOf('\\'))
            if (lastSlash >= 0 && lastSlash < relativePath.length - 1) {
                return relativePath.substring(lastSlash + 1)
            }
            return relativePath
        }

        // 如果只有一个参数，直接返回其值
        if (params.size == 1) {
            val value = params.values.first()
            return value?.toString() ?: ""
        }

        // 多个参数，用逗号分隔
        val sb = StringBuilder()
        var first = true
        for ((key, value) in params) {
            if (!first) {
                sb.append(", ")
            }
            sb.append(key).append("=").append(value)
            first = false
        }
        return sb.toString()
    }
}
