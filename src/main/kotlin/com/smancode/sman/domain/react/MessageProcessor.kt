package com.smancode.sman.domain.react

import com.smancode.sman.model.message.Message
import com.smancode.sman.model.part.ReasoningPart
import com.smancode.sman.model.part.TextPart
import com.smancode.sman.model.part.ToolPart
import com.smancode.sman.model.session.Session
import com.smancode.sman.smancode.prompt.DynamicPromptInjector
import com.smancode.sman.smancode.prompt.PromptDispatcher
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.*

/**
 * 消息处理器
 *
 * 负责：
 * - 消息预处理
 * - 上下文检查
 * - 用户输入处理
 * - System Prompt 构建（带缓存）
 * - User Prompt 构建（包含压缩后的上下文和工具结果）
 */
class MessageProcessor(
    private val promptDispatcher: PromptDispatcher,
    private val dynamicPromptInjector: DynamicPromptInjector
) {
    private val logger = LoggerFactory.getLogger(MessageProcessor::class.java)
    private val partHandlerLogger = LoggerFactory.getLogger("PartHandler")

    // System Prompt 缓存（每个会话缓存一次）
    private val systemPromptCache = mutableMapOf<String, String>()

    /**
     * 构建系统提示词（带缓存）
     */
    fun buildSystemPrompt(session: Session): String {
        val sessionId = session.id!!

        return systemPromptCache.getOrPut(sessionId) {
            logger.info("会话 {} 首次构建 System Prompt", sessionId)

            val prompt = StringBuilder()
            prompt.append(promptDispatcher.buildSystemPrompt())
            prompt.append("\n\n").append(promptDispatcher.toolSummary)

            // 动态 Prompt 注入（只注入一次）
            val projectKey = session.projectInfo?.projectKey
            val injectResult = dynamicPromptInjector.detectAndInject(sessionId, projectKey)

            if (injectResult.hasContent()) {
                logger.info("会话 {} 首次加载 Prompt: complexTaskWorkflow={}, codingBestPractices={}, projectContext={}",
                    sessionId,
                    injectResult.isNeedComplexTaskWorkflow,
                    injectResult.isNeedCodingBestPractices,
                    injectResult.isNeedProjectContext)
                prompt.append(injectResult.injectedContent)
            }

            // 用户配置的 RULES
            val userRules = session.projectInfo?.rules
            if (!userRules.isNullOrEmpty()) {
                logger.info("会话 {} 追加用户配置的 RULES", sessionId)
                prompt.append("\n\n## 用户配置的工作规则 (User Rules)\n\n")
                prompt.append(userRules)
                prompt.append("\n\n---\n")
            }

            prompt.toString()
        }
    }

    /**
     * 构建用户提示词（包含压缩后的上下文和工具结果）
     *
     * 关键修改：将 ToolPart 的执行结果添加到对话历史
     * 这样 LLM 可以看到之前工具调用的结果，并基于此决定下一步行动
     */
    fun buildUserPrompt(
        session: Session,
        isLastStep: Boolean,
        formatParamsBrief: (Map<String, Any>?) -> String
    ): String {
        val prompt = StringBuilder()

        // 添加项目上下文信息
        val projectContext = getProjectContext(session)
        if (projectContext.isNotEmpty()) {
            prompt.append(projectContext).append("\n\n")
        }

        // 检查是否有新的用户消息（支持打断）
        val lastAssistant = session.latestAssistantMessage
        if (lastAssistant != null && session.hasNewUserMessageAfter(lastAssistant.id)) {
            prompt.append("\n\n")
            prompt.append("<system-reminder>\n")
            prompt.append("用户发送了以下消息：\n\n")

            val messages = session.messages
            var foundAssistant = false
            for (msg in messages) {
                if (msg.id == lastAssistant.id) {
                    foundAssistant = true
                } else if (foundAssistant && msg.isUserMessage()) {
                    for (part in msg.parts) {
                        if (part is TextPart) {
                            prompt.append(part.text).append("\n")
                        }
                    }
                }
            }

            prompt.append("\n请立即响应该消息，并调整你的计划。\n")
            prompt.append("</system-reminder>\n")
        }

        // 添加历史上下文（最近 3 轮对话，自动停止于压缩点）
        val messages = getFilteredMessages(session)
        val contextSize = minOf(6, messages.size)

        if (messages.isNotEmpty()) {
            prompt.append("\n\n## Conversation History\n\n")
        }

        for (i in maxOf(0, messages.size - contextSize) until messages.size) {
            val msg = messages[i]
            if (msg.isUserMessage()) {
                prompt.append("### User\n")
                for (part in msg.parts) {
                    if (part is TextPart) {
                        prompt.append(part.text).append("\n")
                    }
                }
            } else {
                prompt.append("### Assistant\n")
                for (part in msg.parts) {
                    when (part) {
                        is TextPart -> prompt.append(part.text).append("\n")
                        is ReasoningPart -> prompt.append("思考: ").append(part.text).append("\n")
                        is ToolPart -> {
                            // 智能摘要机制
                            prompt.append("调用工具: ${part.toolName}\n")

                            if (!part.parameters.isNullOrEmpty()) {
                                prompt.append("参数: ").append(formatParamsBrief(part.parameters!!)).append("\n")
                            }

                            if (part.result != null) {
                                val result = part.result!!
                                if (result.isSuccess) {
                                    if (!part.summary.isNullOrEmpty()) {
                                        // 有 summary：历史工具，只发送摘要
                                        prompt.append("结果: \n").append(part.summary).append("\n")
                                    } else {
                                        // 无 summary：新执行完的工具，发送完整结果
                                        if (!result.relativePath.isNullOrEmpty()) {
                                            prompt.append("文件路径: ").append(result.relativePath!!).append("\n")
                                        }

                                        val fullData = result.data?.toString()
                                        if (!fullData.isNullOrEmpty()) {
                                            prompt.append("结果: \n").append(fullData).append("\n")
                                            prompt.append("[This tool result has no summary yet, you need to generate one]\n")
                                            prompt.append("[IMPORTANT: When generating summary, must preserve file path (relativePath) info]\n")
                                        } else {
                                            val displayContent = result.displayContent
                                            if (!displayContent.isNullOrEmpty()) {
                                                prompt.append("结果: \n").append(displayContent).append("\n")
                                            } else {
                                                prompt.append("结果: (执行成功，无返回内容)\n")
                                            }
                                        }
                                    }

                                    // 添加 metadata 变更信息
                                    val metadata = result.metadata
                                    if (!metadata.isNullOrEmpty()) {
                                        if (metadata.containsKey("description")) {
                                            val desc = metadata["description"]
                                            if (desc != null && desc.toString().isNotEmpty()) {
                                                prompt.append("变更说明: ").append(desc.toString()).append("\n")
                                            }
                                        }
                                        if (metadata.containsKey("changeSummary")) {
                                            val summary = metadata["changeSummary"]
                                            if (summary != null && summary.toString().isNotEmpty()) {
                                                prompt.append("变更详情: \n").append(summary.toString()).append("\n")
                                            }
                                        }
                                    }
                                } else {
                                    val error = result.error
                                    prompt.append("执行失败: ").append(error ?: "未知错误").append("\n")
                                }
                            }
                        }
                        else -> {}
                    }
                }
            }
            prompt.append("\n")
        }

        // 添加 ReAct 分析和决策指南
        prompt.append("\n\n## Next Step\n\n")

        prompt.append("**Response Format**: Valid JSON starting with `{` ending with `}`.\n")
        prompt.append("- Tool call: `{\"parts\": [{\"type\": \"text\", \"text\": \"...\"}, {\"type\": \"tool\", \"toolName\": \"...\", \"parameters\": {...}}]}`\n")
        prompt.append("- Direct answer: `{\"text\": \"complete answer\"}`\n")
        prompt.append("- DO NOT imitate conversation history format like \"调用工具: xxx\"\n\n")

        prompt.append("**Decision**:\n")
        prompt.append("1. Have enough info? → Return `{\"text\": \"answer\"}`\n")
        prompt.append("2. Need more info? → Return tool call JSON\n")
        prompt.append("3. Tool failed? → Try different approach\n\n")

        prompt.append("**Summary (if calling new tool)**: Add `\"summary\"` field to summarize previous tool result.\n\n")

        if (isLastStep) {
            prompt.append("\n**FINAL STEP**: No more tools. Summarize progress and provide recommendations.\n")
        }

        return prompt.toString()
    }

    /**
     * 获取过滤后的消息列表（停止于压缩点）
     */
    fun getFilteredMessages(session: Session): List<Message> {
        val result = mutableListOf<Message>()
        val messages = session.messages

        // 从最新到最旧遍历
        for (i in messages.size - 1 downTo 0) {
            val msg = messages[i]
            result.add(msg)

            // 遇到压缩点则停止
            if (msg.isAssistantMessage() && msg.createdTime.isBefore(Instant.EPOCH.plusSeconds(1))) {
                break
            }
        }

        // 反转回最新在前
        return result.reversed()
    }

    /**
     * 获取项目上下文信息
     */
    private fun getProjectContext(session: Session): String {
        return try {
            val projectInfo = session.projectInfo
            if (projectInfo == null) {
                partHandlerLogger.debug("Session 没有关联的 ProjectInfo")
                return ""
            }

            // 从 Session 的 metadata 中获取预存储的项目上下文
            val context = session.metadata["projectContext"] as? String
            if (context != null) {
                context
            } else {
                partHandlerLogger.debug("Session 没有预存储的项目上下文")
                ""
            }
        } catch (e: Exception) {
            partHandlerLogger.warn("获取项目上下文失败", e)
            ""
        }
    }

    /**
     * 创建助手消息
     */
    fun createAssistantMessage(sessionId: String): Message {
        return Message().apply {
            id = UUID.randomUUID().toString()
            this.sessionId = sessionId
            role = com.smancode.sman.model.message.Role.ASSISTANT
            createdTime = Instant.now()
        }
    }

    /**
     * 创建压缩消息
     */
    fun createCompactionMessage(sessionId: String, summary: String): Message {
        val message = Message().apply {
            id = UUID.randomUUID().toString()
            this.sessionId = sessionId
            role = com.smancode.sman.model.message.Role.ASSISTANT
            createdTime = Instant.EPOCH  // 特殊标记
        }

        val partId = UUID.randomUUID().toString()
        val textPart = TextPart(partId, message.id!!, message.id!!).apply {
            text = "🗑️ 上下文已压缩\n\n为避免 Token 超限，之前的对话历史已压缩为以下摘要：\n\n$summary\n"
            touch()
        }

        message.addPart(textPart)
        return message
    }

    /**
     * 创建忙碌消息
     */
    fun createBusyMessage(sessionId: String): Message {
        val message = Message().apply {
            id = UUID.randomUUID().toString()
            this.sessionId = sessionId
            role = com.smancode.sman.model.message.Role.ASSISTANT
            createdTime = Instant.now()
        }

        val partId = UUID.randomUUID().toString()
        val textPart = TextPart(partId, message.id!!, message.id!!).apply {
            text = "正在处理上一个请求，请稍候..."
            touch()
        }

        message.addPart(textPart)
        return message
    }

    /**
     * 创建错误消息
     */
    fun createErrorMessage(sessionId: String, error: String): Message {
        val message = Message().apply {
            id = UUID.randomUUID().toString()
            this.sessionId = sessionId
            role = com.smancode.sman.model.message.Role.ASSISTANT
            createdTime = Instant.now()
        }

        val partId = UUID.randomUUID().toString()
        val textPart = TextPart(partId, message.id!!, message.id!!).apply {
            text = "处理失败: $error"
            touch()
        }

        message.addPart(textPart)
        return message
    }

    /**
     * 清除指定会话的 System Prompt 缓存
     */
    fun clearSystemPromptCache(sessionId: String) {
        systemPromptCache.remove(sessionId)
    }
}
