package com.smancode.sman.domain.react

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.smancode.sman.config.SmanCodeProperties
import com.smancode.sman.config.SmanConfig
import com.smancode.sman.model.message.Message
import com.smancode.sman.model.message.Role
import com.smancode.sman.model.part.*
import com.smancode.sman.model.session.Session
import com.smancode.sman.smancode.core.ContextCompactor
import com.smancode.sman.smancode.core.StreamingNotificationHandler
import com.smancode.sman.smancode.core.SubTaskExecutor
import com.smancode.sman.smancode.prompt.DynamicPromptInjector
import com.smancode.sman.smancode.prompt.PromptDispatcher
import com.smancode.sman.tools.ToolRegistry
import com.smancode.sman.util.StackTraceUtils
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.*
import java.util.function.Consumer

/**
 * ReAct 循环主入口
 *
 * 极简设计 + 上下文隔离：
 * - 一个主循环处理所有消息
 * - 用户可以随时打断（通过 system-reminder）
 * - 完全由 LLM 决定行为（无硬编码意图识别）
 * - 每个工具调用在独立子会话中执行（防止 Token 爆炸）
 *
 * 流程：
 * 1. 接收用户消息
 * 2. 检查是否需要上下文压缩
 * 3. 调用 LLM 流式处理
 * 4. 在子会话中执行工具调用（上下文隔离）
 * 5. 只保留摘要，清理完整输出
 * 6. 推送 Part 到前端
 */
class ReactLoop(
    private val promptDispatcher: PromptDispatcher,
    private val toolRegistry: ToolRegistry,
    private val subTaskExecutor: SubTaskExecutor,
    private val notificationHandler: StreamingNotificationHandler,
    private val contextCompactor: ContextCompactor,
    private val smanCodeProperties: SmanCodeProperties,
    private val dynamicPromptInjector: DynamicPromptInjector
) {
    private val logger = LoggerFactory.getLogger(ReactLoop::class.java)
    private val objectMapper = ObjectMapper()

    // 组件初始化
    private val responseParser = ResponseParser()
    private val toolExecutor = ToolExecutor()
    private val partHandler = PartHandler(toolRegistry)
    private val messageProcessor = MessageProcessor(promptDispatcher, dynamicPromptInjector)
    private val llmCaller = LlmCaller(smanCodeProperties)

    /**
     * 处理用户消息（核心入口）
     *
     * @param session    会话
     * @param userInput  用户输入
     * @param partPusher Part 推送器（实时推送前端）
     * @return 助手消息
     */
    fun process(session: Session, userInput: String, partPusher: Consumer<Part>): Message {
        val sessionId = session.id!!
        logger.info("开始处理: sessionId={}, userInput={}", sessionId, userInput)

        return try {
            // 添加用户消息到会话
            val userMessage = createUserMessage(sessionId, userInput)
            session.addMessage(userMessage)

            // 检查会话状态
            if (session.isBusy()) {
                val busyMessage = messageProcessor.createBusyMessage(sessionId)
                busyMessage.parts.forEach { partPusher.accept(it) }
                return busyMessage
            }

            // 检查是否需要上下文压缩
            handleContextCompaction(session, sessionId, partPusher)

            // 标记会话为忙碌
            session.markBusy()

            // 用户意图判断
            val ackResult = notificationHandler.pushImmediateAcknowledgment(session, partPusher)
            logger.info("用户意图判断: needConsult={}, isChat={}", ackResult.isNeedConsult, ackResult.isChat)

            // 主循环：调用 LLM 处理
            val assistantMessage = processWithLLM(session, partPusher)

            // 添加助手消息到会话
            session.addMessage(assistantMessage)

            // 标记会话为空闲
            session.markIdle()

            assistantMessage

        } catch (e: Exception) {
            logger.error("处理失败: {}", StackTraceUtils.formatStackTrace(e))
            session.markIdle()
            val errorMessage = messageProcessor.createErrorMessage(sessionId, e.message ?: "未知错误")
            errorMessage.parts.forEach { partPusher.accept(it) }
            errorMessage
        }
    }

    /**
     * 使用 LLM 处理（ReAct 循环核心）
     */
    private fun processWithLLM(session: Session, partPusher: Consumer<Part>): Message {
        val sessionId = session.id!!
        var assistantMessage = messageProcessor.createAssistantMessage(sessionId)

        return try {
            val maxSteps = llmCaller.getMaxSteps()
            var step = 0

            while (step < maxSteps) {
                step++
                logger.info("ReAct 循环: step={}/{}", step, maxSteps)

                val isLastStep = (step == maxSteps)
                if (isLastStep) {
                    logger.warn("达到最大步数限制: {}/{}，这是最后一次调用 LLM", step, maxSteps)
                }

                // 构建提示词
                val systemPrompt = messageProcessor.buildSystemPrompt(session)
                val userPrompt = messageProcessor.buildUserPrompt(session, isLastStep, partHandler::formatParamsBrief)

                // 调用 LLM
                val llmService = SmanConfig.createLlmService()
                val responseText = llmService.simpleRequest(systemPrompt, userPrompt)

                // 从响应中提取 JSON
                val jsonString = responseParser.extractJsonFromResponse(responseText)
                if (jsonString == null) {
                    // 无法提取 JSON，当作纯文本处理
                    logger.warn("无法提取 JSON，当作纯文本处理")
                    val textPart = createTextPart(responseText, assistantMessage, session)
                    assistantMessage.addPart(textPart)
                    partPusher.accept(textPart)
                    break
                }

                // 解析 JSON
                val json: JsonNode = try {
                    objectMapper.readTree(jsonString)
                } catch (e: Exception) {
                    logger.error("JSON 解析失败: {}", e.message)
                    val textPart = createTextPart(responseText, assistantMessage, session)
                    assistantMessage.addPart(textPart)
                    partPusher.accept(textPart)
                    break
                }

                logger.info("解析后的 JSON: has parts={}, has text={}",
                    json.has("parts"), json.has("text"))

                // 处理响应中的各个 Part
                val parts = json.path("parts")
                if (!parts.isArray || parts.isEmpty) {
                    // 没有 parts，检查是否有纯文本响应
                    val text = json.path("text").asText(null)
                    if (!text.isNullOrEmpty()) {
                        val textPart = createTextPart(text, assistantMessage, session)
                        assistantMessage.addPart(textPart)
                        partPusher.accept(textPart)

                        // 检测混合格式
                        if (partHandler.isSingleTextPart(json)) {
                            extractAndPushAdditionalParts(responseText, jsonString, assistantMessage, partPusher)
                        }
                    }
                    break
                }

                // 解析 Part
                val currentParts = mutableListOf<Part>()
                for (partJson in parts) {
                    partHandler.parsePart(partJson, assistantMessage.id!!, sessionId)?.let {
                        currentParts.add(it)
                    }
                }

                // 处理 LLM 生成的摘要
                handleSummaryGeneration(currentParts, session)

                // 检查是否有工具调用
                val hasTools = currentParts.any { it is ToolPart }

                if (!hasTools) {
                    // 没有工具调用，添加非工具 Part 并退出循环
                    logger.info("没有工具调用，添加 Part 并退出循环")
                    currentParts.forEach {
                        assistantMessage.addPart(it)
                        partPusher.accept(it)
                    }
                    break
                }

                // 执行工具
                logger.info("检测到工具调用，开始执行工具，数量: {}",
                    currentParts.count { it is ToolPart })

                var shouldBreak = false
                for (part in currentParts) {
                    if (part is ToolPart) {
                        // Doom Loop 检测
                        if (toolExecutor.detectDoomLoop(session, part)) {
                            logger.warn("检测到 Doom Loop，跳过工具调用: toolName={}", part.toolName)
                            val warningPart = toolExecutor.createDoomLoopWarningPart(assistantMessage.id, sessionId)
                            assistantMessage.addPart(warningPart)
                            partPusher.accept(warningPart)
                            shouldBreak = true
                            break
                        }

                        // 执行工具
                        subTaskExecutor.executeToolIsolated(part, session, partPusher)

                        // 将工具 Part 添加到助手消息
                        assistantMessage.addPart(part)

                        logger.info("工具执行完成: toolName={}, success={}, summaryLength={}",
                            part.toolName, part.result?.isSuccess,
                            part.result?.summary?.length ?: 0)

                        // 展开 batch 子结果
                        toolExecutor.expandBatchSubResults(part, assistantMessage, partHandler)

                    } else {
                        // 非 ToolPart 直接添加
                        assistantMessage.addPart(part)
                        partPusher.accept(part)
                    }
                }

                if (shouldBreak) break

                // 将当前助手消息添加到会话
                if (assistantMessage.parts.isNotEmpty()) {
                    session.addMessage(assistantMessage)
                    logger.info("助手消息已添加到会话，包含 {} 个 Part", assistantMessage.parts.size)
                }

                // 创建新的助手消息供下一轮使用
                assistantMessage = messageProcessor.createAssistantMessage(sessionId)
            }

            assistantMessage

        } catch (e: Exception) {
            logger.error("LLM 处理失败: {}", StackTraceUtils.formatStackTrace(e))
            val errorPart = createTextPart("处理失败: ${e.message}", assistantMessage, session)
            assistantMessage.addPart(errorPart)
            partPusher.accept(errorPart)
            assistantMessage
        }
    }

    // ==================== 辅助方法 ====================

    private fun createUserMessage(sessionId: String, userInput: String): Message {
        val userMessageId = UUID.randomUUID().toString()
        return Message().apply {
            id = userMessageId
            this.sessionId = sessionId
            role = Role.USER
            createdTime = Instant.now()

            val textPart = TextPart(UUID.randomUUID().toString(), userMessageId, sessionId).apply {
                text = userInput
                touch()
            }
            addPart(textPart)
        }
    }

    private fun handleContextCompaction(session: Session, sessionId: String, partPusher: Consumer<Part>) {
        if (contextCompactor.needsCompaction(session)) {
            logger.info("触发上下文压缩: sessionId={}", sessionId)
            contextCompactor.prune(session)

            if (contextCompactor.needsCompaction(session)) {
                val summary = contextCompactor.compact(session)
                val compactionMessage = messageProcessor.createCompactionMessage(sessionId, summary)
                session.addMessage(compactionMessage)
                partPusher.accept(compactionMessage.parts[0])
            }
        }
    }

    private fun handleSummaryGeneration(currentParts: List<Part>, session: Session) {
        logger.info("【摘要处理】开始检查 currentParts 中的 summary，总 Part 数={}", currentParts.size)

        val summaryCarrier = currentParts
            .filterIsInstance<ToolPart>()
            .firstOrNull { partHandler.hasToolPartSummary(it) }

        logger.info("【摘要处理】summaryCarrier={}", summaryCarrier?.toolName ?: "null")

        if (summaryCarrier is ToolPart) {
            val summary = summaryCarrier.summary
            logger.info("【摘要处理】找到 summary，开始查找目标工具，summary={}", summary)

            // 收集所有 Part 用于查找
            val allParts = session.messages.flatMap { it.parts }
            val targetTool = partHandler.findLastToolWithoutSummary(allParts)

            if (targetTool != null) {
                targetTool.summary = summary
                logger.info("【摘要处理】成功保存摘要: targetTool={}, summary={}",
                    targetTool.toolName, summary)
                summaryCarrier.summary = null
            } else {
                logger.warn("【摘要处理】LLM 生成了摘要，但没有找到需要摘要的历史工具")
            }
        } else {
            logger.info("【摘要处理】没有找到包含 summary 的 ToolPart")
        }
    }

    private fun createTextPart(text: String?, message: Message, session: Session): TextPart {
        val partId = UUID.randomUUID().toString()
        return TextPart(partId, message.id!!, session.id!!).apply {
            this.text = text ?: ""
            touch()
        }
    }

    private fun extractAndPushAdditionalParts(
        responseText: String,
        firstJson: String,
        assistantMessage: Message,
        partPusher: Consumer<Part>
    ) {
        logger.info("检测到混合格式：单个 text part，检查是否还有其他 part")
        val additionalParts = partHandler.extractAdditionalParts(
            responseText, firstJson, assistantMessage.id!!, assistantMessage.sessionId!!
        )
        if (additionalParts.isNotEmpty()) {
            logger.info("从混合格式中提取到 {} 个额外 parts", additionalParts.size)
            additionalParts.forEach { part ->
                assistantMessage.addPart(part)
                partPusher.accept(part)
            }
        }
    }
}
