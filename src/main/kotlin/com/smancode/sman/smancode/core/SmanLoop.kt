package com.smancode.sman.smancode.core

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.smancode.sman.model.message.Message
import com.smancode.sman.model.message.Role
import com.smancode.sman.model.part.*
import com.smancode.sman.model.session.Session
import com.smancode.sman.config.SmanConfig
import com.smancode.sman.smancode.llm.LlmService
import com.smancode.sman.smancode.prompt.DynamicPromptInjector
import com.smancode.sman.smancode.prompt.PromptDispatcher
import com.smancode.sman.tools.ToolRegistry
import com.smancode.sman.util.StackTraceUtils
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.*
import java.util.function.Consumer

/**
 * SmanAgent 核心循环（参考 OpenCode）
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
class SmanLoop(
    // private val llmService: LlmService,  // 移除，改为每次调用时创建
    private val promptDispatcher: PromptDispatcher,
    private val toolRegistry: ToolRegistry,
    private val subTaskExecutor: SubTaskExecutor,
    private val notificationHandler: StreamingNotificationHandler,
    private val contextCompactor: ContextCompactor,
    private val smanCodeProperties: com.smancode.sman.config.SmanCodeProperties,
    private val dynamicPromptInjector: DynamicPromptInjector
) {
    private val logger = LoggerFactory.getLogger(SmanLoop::class.java)
    private val objectMapper = ObjectMapper()

    // System Prompt 缓存（每个会话缓存一次，包含动态注入的 Prompt）
    private val systemPromptCache = mutableMapOf<String, String>()

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
            // 【关键】添加用户消息到会话（IntelliJ 插件版本，没有 WebSocket Handler）
            val userMessageId = UUID.randomUUID().toString()
            val userMessage = Message().apply {
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
            session.addMessage(userMessage)

            // 1. 检查会话状态
            if (session.isBusy()) {
                return createBusyMessage(sessionId, partPusher)
            }

            // 2. 检查是否需要上下文压缩
            if (contextCompactor.needsCompaction(session)) {
                logger.info("触发上下文压缩: sessionId={}", sessionId)
                contextCompactor.prune(session)

                if (contextCompactor.needsCompaction(session)) {
                    // Pruning 后仍然超限，执行 Compaction
                    val summary = contextCompactor.compact(session)

                    // 插入压缩消息
                    val compactionMessage = createCompactionMessage(sessionId, summary)
                    session.addMessage(compactionMessage)
                    partPusher.accept(compactionMessage.parts[0])
                }
            }

            // 3. 标记会话为忙碌
            session.markBusy()

            // 4. 【智能判断】先判断用户意图，再决定是否需要专家咨询
            val ackResult = notificationHandler.pushImmediateAcknowledgment(session, partPusher)

            logger.info("用户意图判断: needConsult={}, isChat={}",
                ackResult.isNeedConsult, ackResult.isChat)

            // 5. 主循环：调用 LLM 处理
            val assistantMessage = processWithLLM(session, partPusher)

            // 7. 添加助手消息到会话
            session.addMessage(assistantMessage)

            // 8. 标记会话为空闲
            session.markIdle()

            assistantMessage

        } catch (e: Exception) {
            logger.error("处理失败: {}", StackTraceUtils.formatStackTrace(e))
            session.markIdle()
            createErrorMessage(sessionId, e.message ?: "未知错误", partPusher)
        }
    }

    /**
     * 使用 LLM 处理（ReAct 循环核心）
     *
     * 参考 OpenCode 实现，支持真正的推理-行动循环：
     * 1. LLM 思考并决定是否调用工具
     * 2. 如果调用工具，在子会话中执行
     * 3. 工具结果添加到对话历史
     * 4. 回到步骤 1，LLM 基于工具结果决定下一步
     * 5. 直到 LLM 不再调用工具，返回最终结果
     */
    private fun processWithLLM(session: Session, partPusher: Consumer<Part>): Message {
        val sessionId = session.id!!
        var assistantMessage = createAssistantMessage(sessionId)

        return try {
            // ========== ReAct 循环开始 ==========
            val maxSteps = smanCodeProperties.react.maxSteps  // 从配置读取最大步数
            var step = 0
            var reachedMaxSteps = false

            while (step < maxSteps) {
                step++
                logger.info("ReAct 循环: step={}/{}", step, maxSteps)

                // 检查是否是最后一步
                val isLastStep = (step == maxSteps)
                if (isLastStep) {
                    logger.warn("达到最大步数限制: {}/{}，这是最后一次调用 LLM", step, maxSteps)
                    reachedMaxSteps = true
                }

                // 2. 构建提示词（包含之前的工具结果）
                val systemPrompt = buildSystemPrompt(session)
                val userPrompt = buildUserPrompt(session, isLastStep)

                // 3. 调用 LLM（每次都使用最新配置）
                val llmService = SmanConfig.createLlmService()
                val responseText = llmService.simpleRequest(systemPrompt, userPrompt)

                // 4. 从纯文本响应中提取 JSON
                val jsonString = extractJsonFromResponse(responseText)
                if (jsonString == null) {
                    // 无法提取 JSON，当作纯文本处理（循环结束）
                    logger.warn("无法提取 JSON，当作纯文本处理，结束循环。responseText长度={}",
                        responseText?.length ?: 0)
                    if (responseText != null && responseText.length < 500) {
                        logger.warn("responseText内容: {}", responseText)
                    }
                    val partId = UUID.randomUUID().toString()
                    val textPart = TextPart(partId, assistantMessage.id!!, session.id!!)
                    textPart.text = responseText
                    textPart.touch()
                    assistantMessage.addPart(textPart)
                    partPusher.accept(textPart)
                    break  // 退出循环
                }

                // 5. 解析 JSON（增加容错处理）
                val json: JsonNode = try {
                    objectMapper.readTree(jsonString)
                } catch (e: Exception) {
                    logger.error("JSON 解析失败，当作纯文本处理。jsonString长度={}, 错误: {}",
                        jsonString.length, e.message)
                    if (jsonString.length < 500) {
                        logger.warn("jsonString内容: {}", jsonString)
                    }
                    // 当作纯文本处理
                    val partId = UUID.randomUUID().toString()
                    val textPart = TextPart(partId, assistantMessage.id!!, session.id!!)
                    textPart.text = responseText
                    textPart.touch()
                    assistantMessage.addPart(textPart)
                    partPusher.accept(textPart)
                    break  // 退出循环
                }
                logger.info("解析后的 JSON: has parts={}, has text={}",
                    json.has("parts"), json.has("text"))

                // 6. 处理响应中的各个 Part
                val parts = json.path("parts")
                if (!parts.isArray || parts.isEmpty) {
                    // 没有 parts，检查是否有纯文本响应
                    val text = json.path("text").asText(null)
                    if (!text.isNullOrEmpty()) {
                        val partId = UUID.randomUUID().toString()
                        val textPart = TextPart(partId, assistantMessage.id!!, session.id!!)
                        textPart.text = text
                        textPart.touch()
                        assistantMessage.addPart(textPart)
                        partPusher.accept(textPart)

                        // 检测混合格式：单个 text part 后可能还有其他 parts
                        if (isSingleTextPart(json)) {
                            extractAndPushAdditionalParts(responseText, jsonString, assistantMessage, partPusher)
                        }
                    }
                    break  // 退出循环
                }

                // 7. 解析 Part
                val currentParts = mutableListOf<Part>()
                for (partJson in parts) {
                    val part = parsePart(partJson, assistantMessage.id!!, sessionId)
                    if (part != null) {
                        currentParts.add(part)
                    }
                }

                // 7.5 处理 LLM 生成的摘要：将新工具的 summary 保存到上一个无摘要的工具
                logger.info("【摘要处理】开始检查 currentParts 中的 summary，总 Part 数={}", currentParts.size)

                val summaryCarrier = currentParts
                    .filterIsInstance<ToolPart>()
                    .firstOrNull { hasToolPartSummary(it) }

                logger.info("【摘要处理】summaryCarrier={}", summaryCarrier?.toolName ?: "null")

                if (summaryCarrier is ToolPart) {
                    val summary = summaryCarrier.summary
                    logger.info("【摘要处理】找到 summary，开始查找目标工具，summary={}", summary)

                    // 查找上一个无摘要的 ToolPart
                    val targetTool = findLastToolWithoutSummary(session)
                    if (targetTool != null) {
                        targetTool.summary = summary
                        logger.info("【摘要处理】成功保存摘要: targetTool={}, summary={}",
                            targetTool.toolName, summary)
                        // 清空 summaryCarrier 的 summary，避免混淆
                        summaryCarrier.summary = null
                    } else {
                        logger.warn("【摘要处理】LLM 生成了摘要，但没有找到需要摘要的历史工具")
                    }
                } else {
                    logger.info("【摘要处理】没有找到包含 summary 的 ToolPart")
                }

                // 8. 检查是否有工具调用
                val hasTools = currentParts.any { it is ToolPart }

                if (!hasTools) {
                    // 没有工具调用，添加非工具 Part 并退出循环
                    logger.info("没有工具调用，添加 Part 并退出循环")
                    for (part in currentParts) {
                        assistantMessage.addPart(part)
                        partPusher.accept(part)
                    }
                    break  // 退出循环
                }

                // 9. 执行工具（在子会话中）
                logger.info("检测到工具调用，开始执行工具，数量: {}",
                    currentParts.count { it is ToolPart })

                for (part in currentParts) {
                    if (part is ToolPart) {
                        // Doom Loop 检测
                        if (detectDoomLoop(session, part)) {
                            logger.warn("检测到 Doom Loop，跳过工具调用: toolName={}",
                                part.toolName)
                            val warningPart = TextPart().apply {
                                messageId = assistantMessage.id
                                this.sessionId = sessionId
                                text = "⚠️ 检测到重复的工具调用，停止循环以避免无限循环。"
                                touch()
                            }
                            assistantMessage.addPart(warningPart)
                            partPusher.accept(warningPart)
                            break  // 退出工具执行循环
                        }

                        // 关键：执行工具并获取摘要
                        val result = subTaskExecutor.executeToolIsolated(
                            part, session, partPusher
                        )

                        // 关键：将工具 Part 添加到助手消息
                        // 下一次循环时，buildUserPrompt 会包含这个工具的结果
                        assistantMessage.addPart(part)

                        logger.info("工具执行完成: toolName={}, success={}, summaryLength={}",
                            part.toolName, result?.success,
                            result?.summary?.length ?: 0)

                        // ========== 特殊处理：展开 batch 工具的子结果 ==========
                        // 如果是 batch 工具，需要将所有子工具的结果也添加到上下文
                        // 这样 LLM 可以看到所有子工具的完整输出
                        val partResult = part.result
                        val batchSubResults = partResult?.batchSubResults
                        if (part.toolName == "batch" &&
                            partResult != null &&
                            batchSubResults != null) {

                            logger.info("检测到 batch 工具，开始展开 {} 个子结果",
                                batchSubResults.size)

                            // 为每个子结果创建一个虚拟的 ToolPart
                            for (subResult in batchSubResults) {
                                // 创建子工具的 ToolPart
                                val subPartId = UUID.randomUUID().toString()
                                val subToolPart = ToolPart(
                                    subPartId,
                                    assistantMessage.id!!,
                                    sessionId,
                                    subResult.toolName!!
                                )

                                // 设置参数（使用 batch 的参数）
                                subToolPart.parameters = part.parameters

                                // 设置结果
                                subToolPart.result = subResult.result

                                // 设置摘要（标记为 batch 子工具）
                                if (subResult.result != null) {
                                    val subSummary = "[batch] ${subResult.toolName}: ${if (subResult.isSuccess) "成功" else subResult.error}"
                                    subToolPart.summary = subSummary
                                }

                                // 添加到助手消息
                                assistantMessage.addPart(subToolPart)

                                logger.info("展开 batch 子结果: toolName={}, success={}",
                                    subResult.toolName, subResult.isSuccess)
                            }

                            logger.info("batch 子结果展开完成，当前 assistantMessage 包含 {} 个 Part",
                                assistantMessage.parts.size)
                        }

                    } else {
                        // 非 ToolPart 直接添加
                        assistantMessage.addPart(part)
                        partPusher.accept(part)
                    }
                }

                // 关键：将当前助手消息添加到会话
                // 这样下一次循环时，buildUserPrompt 就能看到工具结果了
                if (assistantMessage.parts.isNotEmpty()) {
                    session.addMessage(assistantMessage)
                    logger.info("助手消息已添加到会话，包含 {} 个 Part", assistantMessage.parts.size)
                }

                // 创建新的助手消息供下一轮使用
                assistantMessage = createAssistantMessage(sessionId)

                // 继续循环
            }
            // ========== ReAct 循环结束 ==========

            assistantMessage

        } catch (e: Exception) {
            logger.error("LLM 处理失败: {}", StackTraceUtils.formatStackTrace(e))
            val partId = UUID.randomUUID().toString()
            val errorPart = TextPart(partId, assistantMessage.id!!, session.id!!)
            errorPart.text = "处理失败: ${e.message}"
            errorPart.touch()
            assistantMessage.addPart(errorPart)
            partPusher.accept(errorPart)
            assistantMessage
        }
    }

    /**
     * 从响应中提取 JSON（9级递进式解析策略）
     *
     * 解析策略从简单到复杂，逐级尝试，确保最大容错能力：
     * Level 0: 解析非标准格式（minimax:tool_call、调用工具: xxx 等）【新增】
     * Level 1: 直接解析（最快）
     * Level 2: 清理后解析（去除 markdown 代码块）
     * Level 3: 修复转义后解析（修复常见转义问题）
     * Level 4: 智能大括号提取（增强版策略3）
     * Level 5: 正则提取尝试（多种模式匹配）
     * Level 6: 简单正则快速尝试（补充兜底）
     * Level 7: 终极大招 - LLM 辅助提取
     * Level 8: 降级为纯文本（兜底）
     *
     * @param response LLM 返回的原始响应
     * @return 提取出的 JSON 字符串，如果所有策略都失败则返回 null
     */
    private fun extractJsonFromResponse(response: String?): String? {
        if (response.isNullOrEmpty()) {
            return null
        }

        val trimmedResponse = response.trim()

        // ========== Level 0: 解析非标准格式（新增）==========
        // 支持 minimax 原生格式、模仿历史格式等
        val level0Result = extractNonStandardToolCall(trimmedResponse)
        if (level0Result != null && tryParseJson(level0Result)) {
            logger.info("Level 0 成功: 解析非标准工具调用格式")
            return level0Result
        }

        // ========== Level 1: 直接解析 ==========
        if (tryParseJson(trimmedResponse)) {
            logger.debug("Level 1 成功: 直接解析")
            return trimmedResponse
        }

        // ========== Level 2: 清理 markdown 代码块 ==========
        val level2Result = extractFromMarkdownBlock(trimmedResponse)
        if (level2Result != null && tryParseJson(level2Result)) {
            logger.debug("Level 2 成功: 清理 markdown 代码块")
            return level2Result
        }

        // ========== Level 3: 修复转义字符 ==========
        val level3Result = fixAndParse(trimmedResponse)
        if (level3Result != null) {
            logger.debug("Level 3 成功: 修复转义字符")
            return level3Result
        }

        // ========== Level 4: 智能大括号提取（增强版）==========
        val level4Result = extractWithSmartBraceMatching(trimmedResponse)
        if (level4Result != null && tryParseJson(level4Result)) {
            logger.debug("Level 4 成功: 智能大括号提取")
            return level4Result
        }

        // ========== Level 5: 正则提取尝试 ==========
        val level5Result = extractWithRegex(trimmedResponse)
        if (level5Result != null && tryParseJson(level5Result)) {
            logger.debug("Level 5 成功: 正则提取")
            return level5Result
        }

        // ========== Level 6: 简单正则快速尝试 ==========
        val level6Result = extractWithSimpleRegex(trimmedResponse)
        if (level6Result != null && tryParseJson(level6Result)) {
            logger.debug("Level 6 成功: 简单正则提取")
            return level6Result
        }

        // ========== Level 7: 终极大招 - LLM 辅助提取 ==========
        val level7Result = extractWithLlmHelper(response)
        if (level7Result != null && tryParseJson(level7Result)) {
            logger.debug("Level 7 成功: LLM 辅助提取")
            logger.debug("Level 7 成功: level7Result前200字符={}", level7Result.take(200))
            return level7Result
        } else {
            logger.warn("Level 7 失败: level7Result={}, 是否有效={}",
                if (level7Result != null) "有值" else "null",
                if (level7Result != null) tryParseJson(level7Result) else false)
            if (level7Result != null) {
                logger.warn("Level 7 失败: level7Result前200字符={}", level7Result.take(200))
            }
        }

        // ========== Level 8: 所有策略失败，降级为纯文本 ==========
        logger.warn("所有 JSON 提取策略失败，将降级为纯文本处理")
        return null
    }

    /**
     * Level 0: 解析非标准工具调用格式
     *
     * 支持以下格式：
     * 1. minimax 原生格式：<minimax:tool_call><invoke name="xxx">...</invoke></minimax:tool_call>
     * 2. 模仿历史格式：调用工具: xxx\n参数: {...}
     * 3. 伪代码格式：[TOOL_CALL]{tool => "xxx", parameters => {...}}[/TOOL_CALL]
     *
     * @param response LLM 响应
     * @return 转换后的标准 JSON 字符串，如果无法解析则返回 null
     */
    private fun extractNonStandardToolCall(response: String): String? {
        // 1. 尝试解析 minimax 原生格式
        val minimaxResult = extractMinimaxToolCall(response)
        if (minimaxResult != null) {
            logger.info("Level 0: 解析到 minimax 工具调用格式")
            return minimaxResult
        }

        // 2. 尝试解析"调用工具: xxx\n参数: {...}"格式
        val historyStyleResult = extractHistoryStyleToolCall(response)
        if (historyStyleResult != null) {
            logger.info("Level 0: 解析到历史风格工具调用格式")
            return historyStyleResult
        }

        // 3. 尝试解析 [TOOL_CALL]...[/TOOL_CALL] 格式
        val toolCallTagResult = extractToolCallTagFormat(response)
        if (toolCallTagResult != null) {
            logger.info("Level 0: 解析到 [TOOL_CALL] 标签格式")
            return toolCallTagResult
        }

        return null
    }

    /**
     * 解析 minimax 原生工具调用格式
     *
     * 格式示例：
     * <minimax:tool_call>
     * <invoke name="read_file">
     * <parameter name="simpleName">Application</parameter>
     * </invoke>
     * </minimax:tool_call>
     */
    private fun extractMinimaxToolCall(response: String): String? {
        // 检查是否包含 minimax:tool_call 标签
        if (!response.contains("<minimax:tool_call>", ignoreCase = true)) {
            return null
        }

        val toolCalls = mutableListOf<Map<String, Any>>()

        // 解析 <invoke name="xxx"> 标签
        val invokePattern = Regex("""<invoke\s+name=["'](\w+)["']>([\s\S]*?)</invoke>""", RegexOption.IGNORE_CASE)
        val matches = invokePattern.findAll(response)

        for (match in matches) {
            val toolName = match.groupValues[1]
            val parameterContent = match.groupValues[2]

            // 解析参数
            val parameters = mutableMapOf<String, Any>()
            val paramPattern = Regex("""<parameter\s+name=["'](\w+)["']>([\s\S]*?)</parameter>""", RegexOption.IGNORE_CASE)
            paramPattern.findAll(parameterContent).forEach { paramMatch ->
                val paramName = paramMatch.groupValues[1]
                val paramValue = paramMatch.groupValues[2].trim()
                parameters[paramName] = paramValue
            }

            toolCalls.add(mapOf(
                "type" to "tool",
                "toolName" to toolName,
                "parameters" to parameters
            ))
        }

        if (toolCalls.isEmpty()) {
            return null
        }

        // 转换为标准 JSON 格式
        return try {
            objectMapper.writeValueAsString(mapOf("parts" to toolCalls))
        } catch (e: Exception) {
            logger.warn("转换 minimax 工具调用为 JSON 失败: {}", e.message)
            null
        }
    }

    /**
     * 解析"调用工具: xxx\n参数: {...}"格式
     *
     * 格式示例：
     * 调用工具: read_file
     * 参数: {simpleName: "settings", startLine: 1, endLine: 100}
     */
    private fun extractHistoryStyleToolCall(response: String): String? {
        // 检查是否包含"调用工具:"模式
        val toolCallPattern = Regex("""调用工具[:：]\s*(\w+)""")
        val toolMatch = toolCallPattern.find(response) ?: return null

        val toolName = toolMatch.groupValues[1]

        // 解析参数
        val parameters = mutableMapOf<String, Any>()

        // 尝试解析"参数: {...}"格式
        val paramPattern = Regex("""参数[:：]\s*\{([^}]*)\}""")
        val paramMatch = paramPattern.find(response)
        if (paramMatch != null) {
            val paramContent = paramMatch.groupValues[1]
            // 解析 key: value 或 key: "value" 格式
            val keyValuePattern = Regex("""(\w+)[:：]\s*["']?([^,"'}\n]+)["']?""")
            keyValuePattern.findAll(paramContent).forEach { kvMatch ->
                val key = kvMatch.groupValues[1]
                val value = kvMatch.groupValues[2].trim()
                // 尝试解析为数字
                parameters[key] = value.toIntOrNull() ?: value.toLongOrNull() ?: value
            }
        }

        // 转换为标准 JSON 格式
        return try {
            val parts = mutableListOf<Map<String, Any>>(
                mapOf(
                    "type" to "tool",
                    "toolName" to toolName,
                    "parameters" to parameters
                )
            )
            objectMapper.writeValueAsString(mapOf("parts" to parts))
        } catch (e: Exception) {
            logger.warn("转换历史风格工具调用为 JSON 失败: {}", e.message)
            null
        }
    }

    /**
     * 解析 [TOOL_CALL]...[/TOOL_CALL] 格式
     *
     * 格式示例：
     * [TOOL_CALL]
     * {tool => "find_file", parameters => {
     *   --filePattern "*.gradle"
     * }}
     * [/TOOL_CALL]
     */
    private fun extractToolCallTagFormat(response: String): String? {
        // 检查是否包含 [TOOL_CALL] 标签
        if (!response.contains("[TOOL_CALL]", ignoreCase = true)) {
            return null
        }

        val toolCalls = mutableListOf<Map<String, Any>>()

        // 解析 [TOOL_CALL]...[/TOOL_CALL] 内容
        val toolCallPattern = Regex("""\[TOOL_CALL\]([\s\S]*?)\[/TOOL_CALL\]""", RegexOption.IGNORE_CASE)
        val matches = toolCallPattern.findAll(response)

        for (match in matches) {
            val content = match.groupValues[1].trim()

            // 尝试解析 {tool => "xxx", parameters => {...}} 格式
            val toolNamePattern = Regex("""tool\s*=>\s*["'](\w+)["']""")
            val toolNameMatch = toolNamePattern.find(content) ?: continue
            val toolName = toolNameMatch.groupValues[1]

            // 解析参数
            val parameters = mutableMapOf<String, Any>()

            // 解析 --key "value" 格式
            val dashParamPattern = Regex("""--(\w+)\s+["']([^"']+)["']""")
            dashParamPattern.findAll(content).forEach { paramMatch ->
                val key = paramMatch.groupValues[1]
                val value = paramMatch.groupValues[2]
                parameters[key] = value
            }

            // 解析 parameters => { key: "value" } 格式
            val paramsBlockPattern = Regex("""parameters\s*=>\s*\{([^}]+)\}""")
            val paramsBlockMatch = paramsBlockPattern.find(content)
            if (paramsBlockMatch != null) {
                val paramsContent = paramsBlockMatch.groupValues[1]
                val keyValuePattern = Regex("""(\w+)[:：]\s*["']?([^,"'}\n]+)["']?""")
                keyValuePattern.findAll(paramsContent).forEach { kvMatch ->
                    val key = kvMatch.groupValues[1]
                    val value = kvMatch.groupValues[2].trim()
                    if (!parameters.containsKey(key)) {
                        parameters[key] = value.toIntOrNull() ?: value.toLongOrNull() ?: value
                    }
                }
            }

            toolCalls.add(mapOf(
                "type" to "tool",
                "toolName" to toolName,
                "parameters" to parameters
            ))
        }

        if (toolCalls.isEmpty()) {
            return null
        }

        // 转换为标准 JSON 格式
        return try {
            objectMapper.writeValueAsString(mapOf("parts" to toolCalls))
        } catch (e: Exception) {
            logger.warn("转换 [TOOL_CALL] 工具调用为 JSON 失败: {}", e.message)
            null
        }
    }

    /**
     * Level 1: 尝试直接解析 JSON
     */
    private fun tryParseJson(str: String?): Boolean {
        if (str.isNullOrEmpty()) {
            return false
        }
        return try {
            objectMapper.readTree(str)
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Level 2: 从 markdown 代码块中提取 JSON
     */
    private fun extractFromMarkdownBlock(response: String): String? {
        // 尝试提取 ```json...``` 代码块
        val jsonStart = "```json"
        val jsonEnd = "```"

        var startIndex = response.indexOf(jsonStart)
        if (startIndex != -1) {
            startIndex += jsonStart.length
            val endIndex = response.indexOf(jsonEnd, startIndex)
            if (endIndex != -1) {
                val extracted = response.substring(startIndex, endIndex).trim()
                logger.debug("Level 2: 从 ```json 代码块提取, 长度={}, 前100字符={}", extracted.length, extracted.take(100))
                return extracted
            }
        }

        // 尝试提取 ```...``` 代码块（没有 json 标记）
        val codeStart = "```"
        val codeStartIndex = response.indexOf(codeStart)
        if (codeStartIndex != -1) {
            val afterStart = codeStartIndex + codeStart.length
            // 跳过可能的语言标记
            val firstBrace = response.indexOf('{', afterStart)
            if (firstBrace != -1) {
                val endIndex = response.indexOf(codeStart, firstBrace)
                if (endIndex != -1) {
                    val extracted = response.substring(firstBrace, endIndex).trim()
                    logger.debug("Level 2: 从 ``` 代码块提取, 长度={}, 前100字符={}", extracted.length, extracted.take(100))
                    return extracted
                }
            }
        }

        logger.debug("Level 2: 未找到 markdown 代码块")
        return null
    }

    /**
     * Level 3: 修复转义字符并解析
     *
     * 处理 LLM 返回的 JSON 中常见的转义问题：
     * - 字符串内部的换行符 \n 未转义
     * - 字符串内部的引号 " 未转义
     * - 字符串内部的反斜杠 \ 未转义
     */
    private fun fixAndParse(response: String): String? {
        // 先尝试从 markdown 代码块中提取
        val extracted = extractFromMarkdownBlock(response)
        val toFix = extracted ?: response

        // 尝试多种修复策略
        val fixedVersions = listOf(
            fixStringNewlines(toFix),           // 修复字符串内的换行
            fixUnescapedQuotes(toFix),          // 修复未转义的引号
            fixUnescapedBackslashes(toFix),     // 修复未转义的反斜杠
            fixAllCommonIssues(toFix)           // 修复所有常见问题
        )

        for (fixed in fixedVersions) {
            if (tryParseJson(fixed)) {
                return fixed
            }
        }

        return null
    }

    /**
     * 修复 JSON 字符串值中的未转义换行符
     *
     * 例如: {"text": "hello\nworld"} -> {"text": "hello\\nworld"}
     */
    private fun fixStringNewlines(json: String): String {
        // 这是一个简化版本，只处理最常见的情况
        // 更复杂的版本需要跟踪字符串状态
        return json.replace("\\\n", "\\\\n")
    }

    /**
     * 修复 JSON 字符串值中的未转义引号
     *
     * 这是一个启发式方法，尝试修复常见的未转义引号问题
     */
    private fun fixUnescapedQuotes(json: String): String {
        // 简化版本：只处理明显的情况
        // 注意：这是一个有损修复，可能不是所有情况都适用
        return json
    }

    /**
     * 修复 JSON 字符串值中的未转义反斜杠
     */
    private fun fixUnescapedBackslashes(json: String): String {
        // 简化版本：只处理明显的情况
        return json
    }

    /**
     * 修复所有常见的转义问题
     */
    private fun fixAllCommonIssues(json: String): String {
        var result = json
        result = fixStringNewlines(result)
        result = fixUnescapedQuotes(result)
        result = fixUnescapedBackslashes(result)
        return result
    }

    /**
     * Level 4: 智能大括号匹配提取
     *
     * 从复杂文本中提取完整的 JSON 对象，处理：
     * - 嵌套大括号
     * - 字符串内部的大括号
     * - 转义字符
     */
    private fun extractWithSmartBraceMatching(response: String): String? {
        val braceStart = response.indexOf('{')
        if (braceStart < 0) {
            return null
        }

        var depth = 0
        var inString = false
        var escape = false

        for (i in braceStart until response.length) {
            val c = response[i]

            if (escape) {
                escape = false
                continue
            }

            if (c == '\\' && inString) {
                escape = true
                continue
            }

            if (c == '"' && !escape) {
                inString = !inString
                continue
            }

            if (!inString) {
                if (c == '{') {
                    depth++
                } else if (c == '}') {
                    depth--
                    if (depth == 0) {
                        return response.substring(braceStart, i + 1)
                    }
                }
            }
        }

        return null
    }

    /**
     * Level 5: 使用正则表达式提取 JSON
     */
    private fun extractWithRegex(response: String): String? {
        // 尝试多种正则模式
        val patterns = listOf(
            // 模式1: 匹配 ```json 和 ``` 之间的内容
            Regex("""```json\s*([\s\S]*?)\s*```"""),
            // 模式2: 匹配 { 和 } 之间的完整 JSON 对象（贪婪）
            Regex("""\{[\s\S]*\}"""),
            // 模式3: 匹配嵌套的 JSON 对象
            Regex("""\{(?:[^{}]|\{[^{}]*\})*\}""")
        )

        for (pattern in patterns) {
            val matcher = pattern.find(response)
            if (matcher != null) {
                val match = matcher.groupValues.getOrNull(1) ?: matcher.value
                if (match.trim().isNotEmpty()) {
                    return match.trim()
                }
            }
        }

        return null
    }

    /**
     * Level 6: 简单正则快速提取
     */
    private fun extractWithSimpleRegex(response: String): String? {
        // 快速尝试：找到第一个 { 和最后一个 }
        val firstBrace = response.indexOf('{')
        val lastBrace = response.lastIndexOf('}')

        if (firstBrace >= 0 && lastBrace > firstBrace) {
            return response.substring(firstBrace, lastBrace + 1)
        }

        return null
    }

    /**
     * Level 7: 终极大招 - 使用 LLM 辅助修复 JSON
     *
     * 当所有常规方法都失败时，调用 LLM 让它帮我们修复 JSON 中的问题字段值。
     *
     * 注意：这不是重新提取 JSON，而是修复已识别出的 JSON 结构中无法解析的字段值。
     *
     * 常见问题：
     * - 字段值中包含未转义的换行符、引号、反斜杠
     * - 字段值中包含嵌套的代码块标记
     * - 字段值中包含特殊字符导致 JSON 结构破坏
     *
     * 这是一个"大招"，因为：
     * 1. 它会消耗额外的 Token 和时间
     * 2. 但它是最智能的方式，可以处理各种复杂的字段值问题
     * 3. LLM 自己输出的内容，LLM 自己应该能理解并修复
     */
    private fun extractWithLlmHelper(response: String): String? {
        if (response.isNullOrEmpty()) {
            return null
        }

        return try {
            logger.info("启动 Level 7 终极大招: 使用 LLM 辅助修复 JSON 字段值")

            // 先尝试用智能大括号提取找出 JSON 结构
            val candidateJson = extractWithSmartBraceMatching(response)
            if (candidateJson == null) {
                logger.warn("LLM 辅助修复: 无法提取 JSON 结构，跳过")
                return null
            }

            // 分析 JSON 结构，找出问题字段
            // 简单启发式：查找 "text": "...", "reasoning": "...", "summary": "..." 等常见字段
            val problematicField = extractProblematicField(candidateJson)
            if (problematicField == null) {
                // 新增：如果找不到具体问题字段，尝试让 LLM 直接修复整个 JSON
                logger.warn("LLM 辅助修复: 无法识别具体问题字段，尝试 LLM 整体修复")
                val fixedJson = fixEntireJsonWithLlm(candidateJson)
                if (fixedJson != null && tryParseJson(fixedJson)) {
                    logger.info("LLM 辅助修复: 整体修复成功")
                    return fixedJson
                }
                logger.warn("LLM 辅助修复: 整体修复也失败，跳过")
                return null
            }

            logger.info("LLM 辅助修复: 识别到问题字段，开始修复")

            // 调用 LLM 修复这个字段值
            val fixedJson = fixProblematicFieldWithLlm(candidateJson, problematicField)
            if (fixedJson == null) {
                logger.warn("LLM 辅助修复: LLM 修复失败")
                return null
            }

            logger.info("LLM 辅助修复完成")
            fixedJson

        } catch (e: Exception) {
            logger.error("LLM 辅助修复异常: {}", StackTraceUtils.formatStackTrace(e))
            null
        }
    }

    /**
     * 使用 LLM 整体修复 JSON
     *
     * 当无法识别具体问题字段时，让 LLM 尝试修复整个 JSON
     */
    private fun fixEntireJsonWithLlm(brokenJson: String): String? {
        return try {
            // 截断过长的 JSON（避免 Token 浪费）
            val MAX_JSON_LENGTH = 3000
            val truncatedJson = if (brokenJson.length > MAX_JSON_LENGTH) {
                brokenJson.take(MAX_JSON_LENGTH) + "..."
            } else {
                brokenJson
            }

            val systemPrompt = """
                <system_config>
                    <language_rule>
                        <input_processing>English (For logic & reasoning)</input_processing>
                        <final_output>Valid JSON only</final_output>
                    </language_rule>
                </system_config>

                <context>
                    <role>JSON Repair Expert</role>
                    <task>Fix malformed JSON to valid JSON</task>
                    <requirement>Preserve all original content, only fix format issues (escape sequences, structure)</requirement>
                </context>

                <interaction_protocol>
                1. **Analyze**: In <thinking> tags, identify what's broken
                2. **Fix**: Escape newlines as \n, quotes as \", backslashes as \\
                3. **Output**: Return ONLY the fixed JSON, no markdown blocks
                </interaction_protocol>

                <anti_hallucination_rules>
                1. **Strict Grounding**: Use ONLY the provided JSON, do NOT invent content
                2. **No Markdown**: Do NOT wrap output in ```json``` blocks
                3. **No Explanation**: Output ONLY the JSON object
                </anti_hallucination_rules>
            """.trimIndent()

            val userPrompt = """
                <task>
                Fix this broken JSON to be valid JSON.
                </task>

                <broken_json>
                $truncatedJson
                </broken_json>

                Output the fixed JSON (no markdown, no explanation):
            """.trimIndent()

            // 调用 LLM
            val llmService = SmanConfig.createLlmService()
            val llmResponse = llmService.simpleRequest(systemPrompt, userPrompt)
            if (llmResponse.isNullOrEmpty()) {
                return null
            }

            // 清理可能的 markdown 标记
            cleanLlmResponse(llmResponse)

        } catch (e: Exception) {
            logger.error("LLM 整体修复 JSON 异常: {}", e.message)
            null
        }
    }

    /**
     * 从 JSON 中提取出可能有问题的字段定义
     *
     * 简单启发式：查找 text、reasoning、summary 等常见字段
     *
     * 修复：降低最小长度要求（50 -> 10），扩展字段名列表
     */
    private fun extractProblematicField(json: String): String? {
        // 扩展字段名列表，覆盖更多可能的问题字段
        val fieldNames = listOf(
            "text", "reasoning", "summary", "content", "description",
            "result", "error", "message", "response", "output",
            "query", "code", "value", "data"
        )

        // 降低最小长度要求，从 50 改为 10
        val MIN_LENGTH = 10

        for (fieldName in fieldNames) {
            val pattern = "\"$fieldName\"\\s*:\\s*\"(.{$MIN_LENGTH,})(?:\"|\\n|\$)"
            val p = Regex(pattern, RegexOption.DOT_MATCHES_ALL)
            val m = p.find(json)
            if (m != null) {
                return "\"$fieldName\": ${m.groupValues[1]}"
            }
        }

        // 新增：尝试找到任意看起来有问题的字符串字段
        // 模式：字段值以 " 开头但没有正确闭合
        return findFirstProblematicStringField(json)
    }

    /**
     * 尝试找到第一个看起来有问题的字符串字段
     *
     * 问题特征：
     * - 字段值以 " 开头但没有正确闭合
     * - 字段值中包含未转义的换行符
     * - 字段值中包含未转义的引号
     */
    private fun findFirstProblematicStringField(json: String): String? {
        // 查找所有 "fieldName": " 模式
        val fieldPattern = Regex("\"([a-zA-Z_][a-zA-Z0-9_]*)\"\\s*:\\s*\"")
        val matches = fieldPattern.findAll(json)

        for (match in matches) {
            val fieldName = match.groupValues[1]
            val valueStart = match.range.last + 1

            // 尝试找到字段值的结束位置
            // 如果在合理范围内（1000 字符）没有找到结束引号，认为这个字段有问题
            var inEscape = false
            for (i in valueStart until minOf(valueStart + 2000, json.length)) {
                val c = json[i]

                if (inEscape) {
                    inEscape = false
                    continue
                }

                if (c == '\\') {
                    inEscape = true
                    continue
                }

                if (c == '"') {
                    // 找到了结束引号，这个字段看起来正常
                    break
                }

                // 如果遇到换行符且不是转义的，说明有问题
                if (c == '\n' || c == '\r') {
                    logger.debug("发现可能的问题字段: {} (包含未转义换行符)", fieldName)
                    return "\"$fieldName\": ${json.substring(valueStart, minOf(i + 100, json.length))}"
                }
            }
        }

        return null
    }

    /**
     * 调用 LLM 修复问题字段并重新组装 JSON
     */
    private fun fixProblematicFieldWithLlm(json: String, problematicField: String): String? {
        return try {
            // 提取字段名和原始值
            val fieldParts = problematicField.split(":", limit = 2)
            val fieldName = fieldParts[0].trim().replace("\"", "")
            val rawValue = fieldParts.getOrNull(1)?.trim() ?: return null

            // 截断过长的值（避免 Token 浪费）
            val MAX_VALUE_LENGTH = 2000
            val FALLBACK_PREFIX_LENGTH = 100
            val truncatedValue = if (rawValue.length > MAX_VALUE_LENGTH) {
                rawValue.take(MAX_VALUE_LENGTH) + "..."
            } else {
                rawValue
            }

            val systemPrompt = """
                <system_config>
                    <language_rule>
                        <input_processing>English (For logic & reasoning)</input_processing>
                        <final_output>Valid JSON only</final_output>
                    </language_rule>
                </system_config>

                <context>
                    <role>JSON Field Value Repair Expert</role>
                    <task>Fix malformed field values to valid JSON string literals</task>
                    <requirement>Preserve original meaning, only fix format issues (escape sequences)</requirement>
                </context>

                <interaction_protocol>
                1. **Think First**: In <thinking> tags, analyze the input value in English
                2. **Fix Issues**: Escape newlines as \n, quotes as \", backslashes as \\
                3. **Final Output**: Return {"fieldName": "fixed_value"} in valid JSON
                </interaction_protocol>

                <anti_hallucination_rules>
                1. **Strict Grounding**: Use ONLY the provided input value, do NOT invent content
                2. **No Markdown**: Do NOT wrap output in ```json``` blocks
                3. **No Explanation**: Output ONLY the JSON object, nothing else
                4. **Terminology**: Keep all text in original language (Chinese or English), do NOT translate
                </anti_hallucination_rules>

                <output_format>
                STRICTLY follow this template. Do NOT change the field name:

                {"%s": "fixed_value_here"}
                </output_format>
            """.trimIndent()

            val userPrompt = """
                <task>
                Fix the following field value to be a valid JSON string literal.
                </task>

                <input>
                <field_name>%s</field_name>
                <raw_value>
                %s
                </raw_value>
                </input>

                <thinking>
                1. Analyze the raw value for escape issues (newlines, quotes, backslashes)
                2. Apply proper JSON escaping: \n for newlines, \" for quotes, \\ for backslashes
                3. Preserve ALL original content and language
                4. Output only the JSON object
                </thinking>

                Output the fixed JSON (format: {"fieldName": "fixed_value"}):
            """.trimIndent()

            val formattedUserPrompt = String.format(userPrompt, fieldName, truncatedValue)

            // 调用 LLM（每次都使用最新配置）
            val llmService = SmanConfig.createLlmService()
            val llmResponse = llmService.simpleRequest(systemPrompt, formattedUserPrompt)
            if (llmResponse.isNullOrEmpty()) {
                return null
            }

            // 清理可能的 markdown 标记
            val cleanedResponse = cleanLlmResponse(llmResponse)

            // 解析 LLM 返回的简单 JSON，提取修复后的字段值
            val fixedValue = extractFieldValueFromSimpleJson(cleanedResponse, fieldName)
                ?: cleanedResponse

            // 重新组装完整的 JSON：替换原始字段值
            replaceFieldInJson(json, fieldName, fixedValue, rawValue, FALLBACK_PREFIX_LENGTH)

        } catch (e: Exception) {
            logger.error("LLM 修复字段值异常: {}", StackTraceUtils.formatStackTrace(e))
            null
        }
    }

    /**
     * 清理 LLM 响应，移除可能的 markdown 标记
     */
    private fun cleanLlmResponse(response: String): String {
        val cleaned = response.trim()
        if (cleaned.startsWith("```")) {
            val extracted = extractFromMarkdownBlock(cleaned)
            return extracted ?: cleaned
        }
        return cleaned
    }

    /**
     * 在 JSON 中替换指定字段的值
     * 先尝试正则替换，失败则回退到简单字符串替换
     */
    private fun replaceFieldInJson(
        json: String,
        fieldName: String,
        newValue: String,
        originalValue: String,
        fallbackPrefixLength: Int
    ): String {
        logger.debug("replaceFieldInJson: fieldName={}, newValue前100字符={}", fieldName, newValue.take(100))

        // 尝试正则替换
        val patternStr = "\"$fieldName\"\\s*:\\s*\".*?(?=\"|\\n)"
        var fixedJson = json.replace(Regex(patternStr), "\"$fieldName\": $newValue")

        logger.debug("正则替换后: fixedJson前200字符={}", fixedJson.take(200))

        // 如果正则替换失败，回退到简单字符串替换
        if (fixedJson == json) {
            logger.debug("正则替换失败，回退到简单字符串替换")
            val prefix = if (originalValue.length > fallbackPrefixLength) {
                originalValue.take(fallbackPrefixLength)
            } else {
                originalValue
            }
            fixedJson = json.replace(prefix, newValue.replace("\"", ""))
        }

        logger.debug("最终 fixedJson 前200字符: {}", fixedJson.take(200))

        // 验证最终 JSON 是否有效
        val isValid = tryParseJson(fixedJson)
        logger.debug("最终 fixedJson 有效性: {}", isValid)

        return fixedJson
    }

    /**
     * 从简单 JSON 中提取字段值
     *
     * 例如：{"text": "hello"} -> "hello"
     */
    private fun extractFieldValueFromSimpleJson(simpleJson: String, fieldName: String): String? {
        return try {
            val node = objectMapper.readTree(simpleJson)
            val valueNode = node.path(fieldName)
            if (!valueNode.isMissingNode) {
                // 返回带引号的字符串值
                "\"" + valueNode.asText()
                    .replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                    .replace("\n", "\\n")
                    .replace("\r", "\\r")
                    .replace("\t", "\\t") + "\""
            } else {
                null
            }
        } catch (e: Exception) {
            logger.debug("解析简单 JSON 失败: {}", e.message)
            null
        }
    }

    /**
     * 创建压缩消息
     */
    private fun createCompactionMessage(sessionId: String, summary: String): Message {
        val message = Message().apply {
            id = UUID.randomUUID().toString()
            this.sessionId = sessionId
            role = Role.ASSISTANT
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
     * 创建助手消息
     */
    private fun createAssistantMessage(sessionId: String): Message {
        return Message().apply {
            id = UUID.randomUUID().toString()
            this.sessionId = sessionId
            role = Role.ASSISTANT
            createdTime = Instant.now()
        }
    }

    /**
     * 构建系统提示词（带缓存）
     */
    private fun buildSystemPrompt(session: Session): String {
        val sessionId = session.id!!

        // 检查缓存
        return systemPromptCache.getOrPut(sessionId) {
            logger.info("会话 {} 首次构建 System Prompt", sessionId)

            val prompt = StringBuilder()
            prompt.append(promptDispatcher.buildSystemPrompt())
            prompt.append("\n\n").append(promptDispatcher.toolSummary)

            // ========== 动态 Prompt 注入（只注入一次）==========
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
            // ========== 动态 Prompt 注入结束 ==========

            // ========== 用户配置的 RULES（追加到 system prompt 后面）==========
            val userRules = session.projectInfo?.rules
            if (!userRules.isNullOrEmpty()) {
                logger.info("会话 {} 追加用户配置的 RULES", sessionId)
                prompt.append("\n\n## 用户配置的工作规则 (User Rules)\n\n")
                prompt.append(userRules)
                prompt.append("\n\n---\n")
            }
            // ========== 用户配置的 RULES 结束 ==========

            prompt.toString()
        }
    }

    /**
     * 构建用户提示词（包含压缩后的上下文和工具结果）
     *
     * 关键修改：将 ToolPart 的执行结果添加到对话历史
     * 这样 LLM 可以看到之前工具调用的结果，并基于此决定下一步行动
     */
    private fun buildUserPrompt(session: Session, isLastStep: Boolean): String {
        val prompt = StringBuilder()

        // 添加项目上下文信息（技术栈、构建命令等）
        // 从 Session 的 ProjectInfo 中获取项目路径，然后获取 SmanService
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
                            // 关键：智能摘要机制
                            // - 有 summary：说明是历史工具，只发送摘要（避免 Token 爆炸）
                            // - 无 summary：说明是新执行完的工具，发送完整结果 + 要求 LLM 生成摘要
                            prompt.append("调用工具: ${part.toolName}\n")

                            // 添加参数
                            if (!part.parameters.isNullOrEmpty()) {
                                prompt.append("参数: ").append(formatParamsBrief(part.parameters!!)).append("\n")
                            }

                            if (part.result != null) {
                                val result = part.result!!
                                if (result.isSuccess) {
                                    // 智能摘要机制
                                    if (!part.summary.isNullOrEmpty()) {
                                        // 有 summary：历史工具，只发送摘要
                                        prompt.append("结果: \n").append(part.summary).append("\n")
                                    } else {
                                        // 无 summary：新执行完的工具，发送完整结果
                                        // 新增：添加 relativePath（如果有）
                                        if (!result.relativePath.isNullOrEmpty()) {
                                            prompt.append("文件路径: ").append(result.relativePath!!).append("\n")
                                        }

                                        val fullData = result.data?.toString()
                                        if (!fullData.isNullOrEmpty()) {
                                            prompt.append("结果: \n").append(fullData).append("\n")
                                            // 标记为需要生成摘要，要求保留文件路径
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

                                    // 新增：如果有 metadata，添加关键变更信息（用于 apply_change 等工具）
                                    val metadata = result.metadata
                                    if (!metadata.isNullOrEmpty()) {
                                        // 添加 description（如果有）
                                        if (metadata.containsKey("description")) {
                                            val desc = metadata["description"]
                                            if (desc != null && desc.toString().isNotEmpty()) {
                                                prompt.append("变更说明: ").append(desc.toString()).append("\n")
                                            }
                                        }
                                        // 添加 changeSummary（如果有）
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

        // 简化的格式提醒
        prompt.append("**Response Format**: Valid JSON starting with `{` ending with `}`.\n")
        prompt.append("- Tool call: `{\"parts\": [{\"type\": \"text\", \"text\": \"...\"}, {\"type\": \"tool\", \"toolName\": \"...\", \"parameters\": {...}}]}`\n")
        prompt.append("- Direct answer: `{\"text\": \"complete answer\"}`\n")
        prompt.append("- DO NOT imitate conversation history format like \"调用工具: xxx\"\n\n")

        prompt.append("**Decision**:\n")
        prompt.append("1. Have enough info? → Return `{\"text\": \"answer\"}`\n")
        prompt.append("2. Need more info? → Return tool call JSON\n")
        prompt.append("3. Tool failed? → Try different approach\n\n")

        prompt.append("**Summary (if calling new tool)**: Add `\"summary\"` field to summarize previous tool result.\n\n")

        // 如果是最后一步，添加最大步数警告
        if (isLastStep) {
            prompt.append("\n**FINAL STEP**: No more tools. Summarize progress and provide recommendations.\n")
        }

        return prompt.toString()
    }

    /**
     * 获取过滤后的消息列表（停止于压缩点）
     */
    private fun getFilteredMessages(session: Session): List<Message> {
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
     * 解析 Part（从 LLM JSON 响应）
     */
    private fun parsePart(partJson: JsonNode, messageId: String, sessionId: String): Part? {
        val type = partJson.path("type").asText()

        // 标准格式：{"type": "tool", "toolName": "xxx", "parameters": {...}}
        // 兼容格式：{"type": "batch", ...} (LLM 直接用工具名作为 type)
        if (type == "tool") {
            return createToolPart(partJson, messageId, sessionId)
        }

        // 兼容 LLM 可能直接用工具名作为 type 的情况
        // 转换为标准格式：{"type": "tool", "toolName": type, ...}
        if (isToolName(type)) {
            logger.info("检测到工具类型 Part: {}, 转换为标准 tool 格式", type)
            return createToolPartFromLegacyFormat(partJson, messageId, sessionId, type)
        }

        // 其他 Part 类型
        return when (type) {
            "text" -> createTextPart(partJson, messageId, sessionId)
            "reasoning" -> createReasoningPart(partJson, messageId, sessionId)
            "subtask" -> createSubtaskPart(partJson, messageId, sessionId)
            "todo" -> createTodoPart(partJson, messageId, sessionId)
            else -> {
                logger.warn("未知的 Part 类型: {}", type)
                null
            }
        }
    }

    /**
     * 判断 type 是否为工具名称
     */
    private fun isToolName(type: String): Boolean {
        return toolRegistry.hasTool(type)
    }

    /**
     * 从旧格式创建 ToolPart（兼容 LLM 直接使用工具名作为 type 的情况）
     *
     * 旧格式：{"type": "batch", "parameters": {...}}
     * 新格式：{"type": "tool", "toolName": "batch", "parameters": {...}}
     */
    private fun createToolPartFromLegacyFormat(
        partJson: JsonNode,
        messageId: String,
        sessionId: String,
        toolName: String
    ): ToolPart {
        val partId = UUID.randomUUID().toString()
        val part = ToolPart(partId, messageId, sessionId, toolName)

        // 解析所有字段作为参数（跳过 type、summary 和 toolName）
        val params = mutableMapOf<String, Any>()
        val fields = partJson.fields()
        while (fields.hasNext()) {
            val entry = fields.next()
            val key = entry.key
            if (key != "type" && key != "summary" && key != "toolName") {
                val value = convertJsonNodeToObject(entry.value)
                if (value != null) {
                    params[key] = value
                }
            }
        }

        part.parameters = params

        // 提取 LLM 生成的摘要
        val summary = partJson.path("summary").asText(null)
        if (!summary.isNullOrEmpty()) {
            part.summary = summary
            logger.info("提取到 LLM 生成的摘要: toolName={}, summary={}", toolName, summary)
        }

        part.touch()
        return part
    }

    /**
     * 从混合格式中提取并推送额外的 parts
     *
     * @param responseText      原始响应文本
     * @param firstJson         已提取的第一个 JSON
     * @param assistantMessage  助手消息
     * @param partPusher        Part 推送器
     */
    private fun extractAndPushAdditionalParts(
        responseText: String,
        firstJson: String,
        assistantMessage: Message,
        partPusher: Consumer<Part>
    ) {
        logger.info("检测到混合格式：单个 text part，检查是否还有其他 part")
        val additionalParts = extractAdditionalParts(responseText, firstJson)
        if (additionalParts.isNotEmpty()) {
            logger.info("从混合格式中提取到 {} 个额外 parts", additionalParts.size)
            additionalParts.forEach { part ->
                assistantMessage.addPart(part)
                partPusher.accept(part)
            }
        }
    }

    /**
     * 从混合格式中提取额外的 Parts
     *
     * 处理 LLM 返回混合格式的情况：前导文本 + XML 标签中的 JSON parts
     * 例如：`我来帮你...<part>{"type": "text", ...}</part><part>{"type": "tool", ...}</part>`
     *
     * @param responseText 原始响应文本
     * @param firstJson    已提取的第一个 JSON（text part）
     * @return 额外的 Parts 列表
     */
    private fun extractAdditionalParts(responseText: String, firstJson: String): List<Part> {
        val additionalParts = mutableListOf<Part>()

        return try {
            // 找到第一个 JSON 结束的位置
            val firstJsonEnd = responseText.indexOf(firstJson) + firstJson.length

            // 从第一个 JSON 后面查找是否有 <part> 标签
            val remaining = responseText.substring(firstJsonEnd)

            // 查找所有 <part>...</part> 标签
            val partPattern = Regex("<part>\\s*(\\{.*?\\})\\s*</part>", RegexOption.DOT_MATCHES_ALL)
            val matches = partPattern.findAll(remaining)

            for (match in matches) {
                val partJson = match.groupValues[1]
                try {
                    val partNode = objectMapper.readTree(partJson)
                    val part = parsePart(partNode, "", "")  // messageId 和 sessionId 稍后设置
                    if (part != null) {
                        additionalParts.add(part)
                        logger.info("提取到额外 part: type={}", partNode.path("type").asText())
                    }
                } catch (e: Exception) {
                    logger.warn("解析额外 part 失败: {}", e.message)
                }
            }

            additionalParts
        } catch (e: Exception) {
            logger.warn("提取额外 parts 过程出错: {}", e.message)
            additionalParts
        }
    }

    /**
     * 判断 JSON 是否为单个 text part（用于检测混合格式）
     *
     * @param json JSON 节点
     * @return 是否为单个 text part
     */
    private fun isSingleTextPart(json: JsonNode): Boolean {
        return json.has("type") && json.path("type").asText() == "text"
    }

    private fun createTextPart(partJson: JsonNode, messageId: String, sessionId: String): TextPart {
        val partId = UUID.randomUUID().toString()
        val part = TextPart(partId, messageId, sessionId)
        part.text = partJson.path("text").asText()
        part.touch()
        return part
    }

    private fun createReasoningPart(partJson: JsonNode, messageId: String, sessionId: String): ReasoningPart {
        val partId = UUID.randomUUID().toString()
        val part = ReasoningPart(partId, messageId, sessionId)
        part.text = partJson.path("text").asText()
        part.touch()
        return part
    }

    /**
     * 检查 ToolPart 是否有摘要
     *
     * @param part Part 对象
     * @return 是否有摘要
     */
    private fun hasToolPartSummary(part: Part): Boolean {
        if (part !is ToolPart) {
            return false
        }

        val summary = part.summary
        val hasSummary = !summary.isNullOrEmpty()
        logger.info("【摘要处理】检查 ToolPart: toolName={}, hasSummary={}, summary={}",
            part.toolName, hasSummary,
            if (hasSummary) truncate(summary!!, 50) else "null")
        return hasSummary
    }

    /**
     * 截断字符串到指定长度
     *
     * @param s        字符串
     * @param maxLength 最大长度
     * @return 截断后的字符串
     */
    private fun truncate(s: String, maxLength: Int): String {
        return s.take(maxLength)
    }

    /**
     * 将 JsonNode 转换为 Java 对象
     *
     * 支持的类型：String, Number, Boolean, List, Map
     *
     * @param node JSON 节点
     * @return 转换后的 Java 对象
     */
    private fun convertJsonNodeToObject(node: JsonNode): Any? {
        return when {
            node.isNull -> null
            node.isBoolean -> node.asBoolean()
            node.isNumber -> node.numberValue()
            node.isTextual -> node.asText()
            node.isArray -> {
                // 递归处理数组
                node.map { convertJsonNodeToObject(it) }
            }
            node.isObject -> {
                // 递归处理对象
                val map = mutableMapOf<String, Any>()
                val fields = node.fields()
                while (fields.hasNext()) {
                    val entry = fields.next()
                    val value = convertJsonNodeToObject(entry.value)
                    if (value != null) {
                        map[entry.key] = value
                    }
                }
                map
            }
            else -> node.asText()
        }
    }

    private fun createToolPart(partJson: JsonNode, messageId: String, sessionId: String): ToolPart {
        val partId = UUID.randomUUID().toString()
        val toolName = partJson.path("toolName").asText()
        val part = ToolPart(partId, messageId, sessionId, toolName)

        // 解析 parameters 对象
        var params = parseJsonNodeToMap(partJson.path("parameters"))

        // Fallback: 如果 parameters 为空，尝试从根级别提取参数（兼容扁平格式）
        if (params.isEmpty()) {
            logger.info("检测到扁平格式参数，尝试从根级别提取: toolName={}", toolName)
            val rootParams = mutableMapOf<String, Any>()
            val fields = partJson.fields()
            while (fields.hasNext()) {
                val entry = fields.next()
                val key = entry.key
                if (key != "type" && key != "summary" && key != "toolName") {
                    val value = convertJsonNodeToObject(entry.value)
                    if (value != null) {
                        rootParams[key] = value
                    }
                }
            }
            if (rootParams.isNotEmpty()) {
                params = rootParams
                logger.info("从根级别提取参数成功: toolName={}, 参数={}", toolName, params.keys)
            }
        }

        part.parameters = params

        // 提取 LLM 生成的摘要
        val summary = partJson.path("summary").asText(null)
        if (!summary.isNullOrEmpty()) {
            part.summary = summary
            logger.info("提取到 LLM 生成的摘要: toolName={}, summary={}", part.toolName, summary)
        }

        part.touch()
        return part
    }

    private fun parseJsonNodeToMap(node: JsonNode): Map<String, Any> {
        if (!node.isObject) {
            return emptyMap()
        }

        val result = mutableMapOf<String, Any>()
        val fields = node.fields()
        while (fields.hasNext()) {
            val entry = fields.next()
            val value = convertJsonNodeToObject(entry.value)
            if (value != null) {
                result[entry.key] = value
            }
        }
        return result
    }

    private fun createSubtaskPart(partJson: JsonNode, messageId: String, sessionId: String): TextPart {
        val partId = UUID.randomUUID().toString()
        val part = TextPart(partId, messageId, sessionId)
        part.text = partJson.path("text").asText("子任务列表")
        part.touch()
        return part
    }

    /**
     * 创建 TodoPart
     */
    private fun createTodoPart(partJson: JsonNode, messageId: String, sessionId: String): TodoPart {
        val partId = UUID.randomUUID().toString()
        val part = TodoPart(partId, messageId, sessionId)

        // 解析 items 数组
        val itemsJson = partJson.path("items")
        if (itemsJson.isArray) {
            val items = mutableListOf<TodoPart.TodoItem>()
            for (itemJson in itemsJson) {
                val item = TodoPart.TodoItem().apply {
                    id = itemJson.path("id").asText(UUID.randomUUID().toString())
                    content = itemJson.path("content").asText()

                    // 解析 status
                    val statusStr = itemJson.path("status").asText("PENDING")
                    status = try {
                        TodoPart.TodoStatus.valueOf(statusStr.uppercase())
                    } catch (e: IllegalArgumentException) {
                        logger.warn("未知的 TodoStatus: {}, 使用默认值 PENDING", statusStr)
                        TodoPart.TodoStatus.PENDING
                    }
                }
                items.add(item)
            }
            part.items = items
        }

        part.touch()
        logger.info("创建 TodoPart: items={}", part.getTotalCount())
        return part
    }

    /**
     * 创建忙碌消息
     */
    private fun createBusyMessage(sessionId: String, partPusher: Consumer<Part>): Message {
        val message = Message().apply {
            id = UUID.randomUUID().toString()
            this.sessionId = sessionId
            role = Role.ASSISTANT
            createdTime = Instant.now()
        }

        val partId = UUID.randomUUID().toString()
        val textPart = TextPart(partId, message.id!!, message.id!!).apply {
            text = "正在处理上一个请求，请稍候..."
            touch()
        }

        message.addPart(textPart)
        partPusher.accept(textPart)
        return message
    }

    /**
     * 创建错误消息
     */
    private fun createErrorMessage(sessionId: String, error: String, partPusher: Consumer<Part>): Message {
        val message = Message().apply {
            id = UUID.randomUUID().toString()
            this.sessionId = sessionId
            role = Role.ASSISTANT
            createdTime = Instant.now()
        }

        val partId = UUID.randomUUID().toString()
        val textPart = TextPart(partId, message.id!!, message.id!!).apply {
            text = "处理失败: $error"
            touch()
        }

        message.addPart(textPart)
        partPusher.accept(textPart)
        return message
    }

    /**
     * 查找最后一个无摘要的 ToolPart
     *
     * 用于将 LLM 生成的摘要保存到对应的历史工具
     *
     * @param session 会话
     * @return 最后一个无摘要的 ToolPart，如果没有则返回 null
     */
    private fun findLastToolWithoutSummary(session: Session): ToolPart? {
        logger.info("【查找无摘要工具】开始查找，消息总数={}", session.messages.size)

        // 从后往前遍历所有消息
        for (i in session.messages.size - 1 downTo 0) {
            val message = session.messages[i]
            logger.info("【查找无摘要工具】检查消息 {}/{}: role={}, Part 数={}",
                i + 1, session.messages.size,
                message.role, message.parts.size)

            if (message.isAssistantMessage()) {
                // 从后往前遍历该消息的所有 Part
                for (j in message.parts.size - 1 downTo 0) {
                    val part = message.parts[j]
                    if (part is ToolPart) {
                        val summary = part.summary
                        val hasSummary = !summary.isNullOrEmpty()

                        logger.info("【查找无摘要工具】  检查 ToolPart: toolName={}, hasSummary={}, summary={}",
                            part.toolName, hasSummary,
                            if (hasSummary) summary!!.take(30) else "null")

                        // 检查是否有摘要
                        if (!hasSummary) {
                            // 找到最后一个无摘要的 ToolPart
                            logger.info("【查找无摘要工具】✅ 找到无摘要的工具: toolName={}, messageId={}",
                                part.toolName, message.id)
                            return part
                        }
                    }
                }
            }
        }

        logger.info("【查找无摘要工具】❌ 没有找到无摘要的工具")
        return null
    }

    /**
     * 格式化参数简述
     */
    private fun formatParamsBrief(params: Map<String, Any>?): String {
        if (params.isNullOrEmpty()) {
            return ""
        }
        val sb = StringBuilder()
        for ((key, value) in params) {
            sb.append(key).append("=").append(value).append(" ")
        }
        return sb.toString().trim()
    }

    /**
     * 检测 Doom Loop（无限循环）
     *
     * 参考 OpenCode 实现，检测最近 3 次是否有相同的工具调用
     *
     * @param session     会话
     * @param currentTool 当前工具
     * @return 是否检测到无限循环
     */
    private fun detectDoomLoop(session: Session, currentTool: ToolPart): Boolean {
        val messages = session.messages
        if (messages.size < 2) {
            return false
        }

        // 检查最近 3 次工具调用
        val DOOM_LOOP_THRESHOLD = 3
        var count = 0

        // 从最新到最旧检查
        for (i in messages.size - 1 downTo maxOf(0, messages.size - DOOM_LOOP_THRESHOLD)) {
            val msg = messages[i]
            if (!msg.isAssistantMessage()) {
                continue
            }

            for (part in msg.parts) {
                if (part is ToolPart) {
                    if (part.toolName == currentTool.toolName &&
                        part.state == ToolPart.ToolState.COMPLETED &&
                        objectsEqual(part.parameters, currentTool.parameters)) {
                        count++
                    }
                }
            }
        }

        if (count >= DOOM_LOOP_THRESHOLD) {
            logger.warn("检测到 Doom Loop: toolName={}, 参数重复 {} 次",
                currentTool.toolName, count)
            return true
        }

        return false
    }

    /**
     * 比较两个对象是否相等（支持 Map 比较）
     */
    private fun objectsEqual(obj1: Any?, obj2: Any?): Boolean {
        if (obj1 === obj2) {
            return true
        }
        if (obj1 == null || obj2 == null) {
            return false
        }
        if (obj1 is Map<*, *> && obj2 is Map<*, *>) {
            return obj1 == obj2
        }
        return obj1 == obj2
    }

    /**
     * 获取项目上下文信息（技术栈、构建命令等）
     * 通过 SessionManager 获取 SmanService，然后获取项目上下文
     */
    private fun getProjectContext(session: Session): String {
        return try {
            // 从 Session 获取 ProjectInfo
            val projectInfo = session.projectInfo
            if (projectInfo == null) {
                logger.debug("Session 没有关联的 ProjectInfo")
                return ""
            }

            // 通过反射或服务查找获取 SmanService
            // 这里我们使用一个简化的方式：直接返回提示信息
            // 实际的 SmanService 需要在创建 Session 时注入上下文

            // 从 Session 的 metadata 中获取预存储的项目上下文（如果有的话）
            val context = session.metadata["projectContext"] as? String
            if (context != null) {
                context
            } else {
                // 如果没有预存储的上下文，返回空
                logger.debug("Session 没有预存储的项目上下文")
                ""
            }
        } catch (e: Exception) {
            logger.warn("获取项目上下文失败", e)
            ""
        }
    }
}
