package com.smancode.sman.smancode.core

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.smancode.sman.model.message.Message
import com.smancode.sman.model.part.Part
import com.smancode.sman.model.part.ReasoningPart
import com.smancode.sman.model.part.TextPart
import com.smancode.sman.model.part.ToolPart
import com.smancode.sman.model.session.Session
import com.smancode.sman.smancode.llm.LlmService
import com.smancode.sman.tools.ToolResult
import org.slf4j.LoggerFactory
import java.util.function.Consumer

/**
 * 流式通知处理器
 *
 * 负责生成和推送渐进式流式输出的各种通知消息
 */
class StreamingNotificationHandler(
    private val llmService: LlmService
) {
    private val logger = LoggerFactory.getLogger(StreamingNotificationHandler::class.java)
    private val objectMapper = ObjectMapper()

    /**
     * 立即推送确认消息（返回判断结果）
     *
     * 返回一个 AcknowledgmentResult 对象，包含：
     * - needConsult: 是否需要专家咨询
     * - isChat: 是否是闲聊
     */
    fun pushImmediateAcknowledgment(session: Session, partPusher: Consumer<Part>): AcknowledgmentResult {
        val latestUser = session.latestUserMessage
        if (latestUser == null || latestUser.parts.isEmpty()) {
            return AcknowledgmentResult(needConsult = true, isChat = false)  // 默认需要专家咨询
        }

        val firstPart = latestUser.parts[0]
        if (firstPart !is TextPart) {
            return AcknowledgmentResult(needConsult = true, isChat = false)
        }

        val userQuestion = firstPart.text

        // 调用 LLM 生成简短确认并判断（使用 System Prompt 提高缓存命中率）
        val ackSystemPrompt = buildAcknowledgmentSystemPrompt()
        val ackUserPrompt = buildAcknowledgmentUserPrompt(userQuestion)
        return try {
            val json = llmService.jsonRequest(ackSystemPrompt, ackUserPrompt)

            val ackText = json.path("acknowledgment").asText("")
            val needConsult = json.path("needConsult").asBoolean(true)
            val isChat = json.path("isChat").asBoolean(false)

            // 如果不是闲聊且有确认语，则推送
            if (!isChat && ackText.isNotEmpty()) {
                pushAcknowledgment(ackText, session.id!!, partPusher)
            }

            AcknowledgmentResult(needConsult, isChat)

        } catch (e: Exception) {
            logger.warn("生成确认消息失败", e)
            // 失败时使用默认确认，保守策略：需要专家咨询
            pushAcknowledgment("思考中", session.id!!, partPusher)
            AcknowledgmentResult(needConsult = true, isChat = false)
        }
    }

    /**
     * 推送确认消息到前端
     *
     * @param text       确认文本
     * @param sessionId  会话 ID
     * @param partPusher Part 推送器
     */
    private fun pushAcknowledgment(text: String, sessionId: String, partPusher: Consumer<Part>) {
        val ackPart = ReasoningPart().apply {
            this.sessionId = sessionId
            this.text = text
            touch()
        }
        partPusher.accept(ackPart)
    }

    /**
     * 确认结果
     */
    data class AcknowledgmentResult(
        val needConsult: Boolean,
        val isChat: Boolean
    ) {
        // ========== 属性访问方式（兼容 Java 风格调用） ==========

        /**
         * 是否需要咨询（属性访问方式）
         */
        val isNeedConsult: Boolean
            get() = needConsult
    }

    /**
     * 构建确认消息 System Prompt（固定内容，可缓存）
     */
    private fun buildAcknowledgmentSystemPrompt(): String {
        return """
            # Task: Analyze User Input

            You are analyzing a user's input to determine:
            1. Is this a casual chat (greeting/thanks/self-introduction)?
            2. Does this require expert consultation (user has no specific target)?
            3. Generate a brief acknowledgment if needed

            ## Analysis Rules (Think in English)

            ### isChat = true
            - Greetings: "你好", "嗨", "早上好", "hello"
            - Thanks: "谢谢", "感谢", "thx"
            - Self-introduction: "我是..."

            ### needConsult = false (User has clear target)
            - User provides specific class name: "ReadFileTool.execute 方法分析一下"
            - User provides specific file path: "分析 com/smancode/... 下的文件"
            - User provides explicit instruction on what to analyze
            - DO NOT add extra steps when user is clear!

            ### needConsult = true (User needs help finding context)
            - User describes problem in natural language: "支付流程是怎样的？"
            - User mentions business terms without specific class: "账号挂失怎么处理？"
            - User asks vague questions requiring context discovery

            ## Output Format (Chinese)

            ```json
            {
              "acknowledgment": "简短确认语（闲聊时留空）",
              "needConsult": true/false,
              "isChat": true/false
            }
            ```

            ## Examples

            Input: "ReadFileTool.execute 方法分析一下"
            Output: {"acknowledgment": "收到，已理解需求", "needConsult": false, "isChat": false}

            Input: "支付流程是怎样的？"
            Output: {"acknowledgment": "正在分析支付流程", "needConsult": true, "isChat": false}

            Input: "你好"
            Output: {"acknowledgment": "", "needConsult": false, "isChat": true}
        """.trimIndent()
    }

    /**
     * 构建 User Prompt（只有用户输入是变化的）
     */
    private fun buildAcknowledgmentUserPrompt(userQuestion: String): String {
        return "## User Input\n$userQuestion"
    }

    /**
     * 推送最终总结
     */
    fun pushFinalSummary(assistantMessage: Message, session: Session, partPusher: Consumer<Part>) {
        try {
            // 构建最终总结提示词
            val summaryPrompt = buildFinalSummaryPrompt(assistantMessage, session)

            // 调用 LLM 生成总结
            val json = llmService.jsonRequest(summaryPrompt)
            val summaryText = json.path("summary").asText("")

            if (summaryText.isNotEmpty()) {
                val summaryPart = TextPart().apply {
                    messageId = assistantMessage.id
                    sessionId = sessionId
                    text = "📋 完整结论\n\n$summaryText\n"
                    touch()
                }
                partPusher.accept(summaryPart)
            }

        } catch (e: Exception) {
            logger.warn("生成最终总结失败", e)
            // 失败不影响主流程
        }
    }

    /**
     * 构建最终总结提示词
     */
    private fun buildFinalSummaryPrompt(assistantMessage: Message, session: Session): String {
        val prompt = StringBuilder()
        prompt.append("你是代码分析助手。刚刚执行了一系列分析工具，请生成最终总结。\n\n")

        // 添加用户问题
        prompt.append("## 用户问题\n")
        val latestUser = session.latestUserMessage
        if (latestUser != null && latestUser.parts.isNotEmpty()) {
            val firstPart = latestUser.parts[0]
            if (firstPart is TextPart) {
                prompt.append(firstPart.text).append("\n\n")
            }
        }

        // 添加执行的工具和结果摘要
        prompt.append("## 执行的工具\n")
        for (part in assistantMessage.parts) {
            if (part is ToolPart) {
                prompt.append("- ").append(part.toolName)
                if (part.parameters?.isNotEmpty() == true) {
                    prompt.append(" (参数: ").append(formatParamsBrief(part.parameters!!)).append(")")
                }
                prompt.append("\n")

                if (part.result != null && part.result?.data != null) {
                    val resultSummary = ToolResultFormatter.generateResultSummary(
                        part.toolName!!,
                        part.result?.data
                    )
                    prompt.append("  结果: ").append(resultSummary).append("\n")
                }
            }
        }
        prompt.append("\n")

        prompt.append("## 要求\n")
        prompt.append("请生成完整的分析总结，包括：\n")
        prompt.append("1. 核心发现：分析过程中最重要的发现是什么\n")
        prompt.append("2. 详细说明：结合所有工具结果，给出完整的分析\n")
        prompt.append("3. 建议或结论：基于分析结果给出具体建议或结论\n\n")
        prompt.append("请以 JSON 格式返回：\n")
        prompt.append("{\n")
        prompt.append("  \"summary\": \"你的完整总结\"\n")
        prompt.append("}")

        return prompt.toString()
    }

    /**
     * 推送工具调用通知
     */
    fun pushToolCallNotification(toolPart: ToolPart, partPusher: Consumer<Part>) {
        val notification = TextPart().apply {
            messageId = toolPart.messageId
            sessionId = toolPart.sessionId

            val sb = StringBuilder()
            sb.append("→ 调用工具: ").append(toolPart.toolName).append("\n")
            if (toolPart.parameters?.isNotEmpty() == true) {
                sb.append("   参数: ").append(formatParamsBrief(toolPart.parameters!!)).append("\n")
            }

            text = sb.toString()
            touch()
        }
        partPusher.accept(notification)
    }

    /**
     * 推送工具执行进度通知
     */
    fun pushToolProgressNotification(toolPart: ToolPart, partPusher: Consumer<Part>) {
        val notification = TextPart().apply {
            messageId = toolPart.messageId
            sessionId = toolPart.sessionId
            text = "⏳ 执行中: ${toolPart.toolName}\n"
            touch()
        }
        partPusher.accept(notification)
    }

    /**
     * 推送工具完成通知
     */
    fun pushToolCompletedNotification(toolPart: ToolPart, result: ToolResult, partPusher: Consumer<Part>) {
        val notification = TextPart().apply {
            messageId = toolPart.messageId
            sessionId = toolPart.sessionId

            val sb = StringBuilder()
            sb.append("✓ 工具完成: ").append(toolPart.toolName).append("\n")

            // 根据结果类型添加摘要
            val data = result.data
            if (data != null) {
                val summary = ToolResultFormatter.generateResultSummary(toolPart.toolName!!, data)
                if (summary.isNotEmpty()) {
                    sb.append("   ").append(summary).append("\n")
                }
            }

            text = sb.toString()
            touch()
        }
        partPusher.accept(notification)
    }

    /**
     * 推送工具错误通知
     */
    fun pushToolErrorNotification(toolPart: ToolPart, result: ToolResult, partPusher: Consumer<Part>) {
        val notification = TextPart().apply {
            messageId = toolPart.messageId
            sessionId = toolPart.sessionId
            text = "✗ 工具失败: ${toolPart.toolName}\n   原因: ${result.error ?: "未知错误"}\n"
            touch()
        }
        partPusher.accept(notification)
    }

    /**
     * 推送阶段性结论（通过 LLM 生成）
     */
    fun pushIntermediateConclusion(toolPart: ToolPart, result: ToolResult, session: Session, partPusher: Consumer<Part>) {
        try {
            // 构建阶段性结论提示词
            val conclusionPrompt = buildIntermediateConclusionPrompt(toolPart, result, session)

            // 调用 LLM 生成阶段性结论
            val json = llmService.jsonRequest(conclusionPrompt)
            val conclusionText = json.path("conclusion").asText("")

            if (conclusionText.isNotEmpty()) {
                val conclusionPart = TextPart().apply {
                    messageId = toolPart.messageId
                    sessionId = toolPart.sessionId

                    // 获取当前会话中已完成的工具数量
                    val completedCount = countCompletedTools(session)
                    text = "📊 阶段性结论 $completedCount:\n$conclusionText\n"

                    touch()
                }
                partPusher.accept(conclusionPart)
            }

        } catch (e: Exception) {
            logger.warn("生成阶段性结论失败: toolName={}", toolPart.toolName, e)
            // 失败不影响主流程
        }
    }

    /**
     * 构建阶段性结论提示词
     */
    private fun buildIntermediateConclusionPrompt(toolPart: ToolPart, result: ToolResult, session: Session): String {
        val prompt = StringBuilder()

        prompt.append("你是一个代码分析助手。刚刚执行了一个工具，请生成简短的阶段性结论。\n\n")

        prompt.append("## 工具信息\n")
        prompt.append("- 工具名称: ").append(toolPart.toolName).append("\n")
        prompt.append("- 工具参数: ").append(formatParamsBrief(toolPart.parameters)).append("\n")
        prompt.append("- 执行结果: ").append(ToolResultFormatter.formatToolResult(result)).append("\n\n")

        prompt.append("## 用户原始问题\n")
        val latestUser = session.latestUserMessage
        if (latestUser != null && latestUser.parts.isNotEmpty()) {
            val firstPart = latestUser.parts[0]
            if (firstPart is TextPart) {
                prompt.append(firstPart.text).append("\n\n")
            }
        }

        prompt.append("## 要求\n")
        prompt.append("请生成一个简短的阶段性结论（1-3句话），说明这个工具的执行发现了什么，")
        prompt.append("以及这对解决用户问题有什么帮助。\n\n")
        prompt.append("请以 JSON 格式返回：\n")
        prompt.append("{\n")
        prompt.append("  \"conclusion\": \"你的阶段性结论\"\n")
        prompt.append("}")

        return prompt.toString()
    }

    /**
     * 统计已完成的工具数量
     */
    private fun countCompletedTools(session: Session): Int {
        var count = 0
        for (message in session.messages) {
            if (message.isAssistantMessage()) {
                for (part in message.parts) {
                    if (part is ToolPart) {
                        val state = part.state
                        if (state == ToolPart.ToolState.COMPLETED) {
                            count++
                        }
                    }
                }
            }
        }
        return count
    }

    /**
     * 格式化参数简述
     */
    private fun formatParamsBrief(params: Map<String, Any>?): String {
        return ParamsFormatter.formatBrief(params)
    }
}
