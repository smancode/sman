package com.smancode.sman.smancode.core

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.smancode.sman.model.message.Message
import com.smancode.sman.model.part.Part
import com.smancode.sman.model.part.TextPart
import com.smancode.sman.model.part.ToolPart
import com.smancode.sman.model.session.Session
import com.smancode.sman.smancode.llm.LlmService
import org.slf4j.LoggerFactory
import java.time.Instant

/**
 * 上下文压缩器
 *
 * 实现 OpenCode 风格的上下文压缩机制：
 * 1. Pruning: 清理旧的工具输出
 * 2. Compaction: 生成会话摘要
 */
class ContextCompactor(
    private val llmService: LlmService
) {
    private val logger = LoggerFactory.getLogger(ContextCompactor::class.java)
    private val objectMapper = ObjectMapper()

    companion object {
        // 配置参数（参考 OpenCode）
        private const val PRUNE_MINIMUM_TOKENS = 5_000     // 至少清理 5k tokens
        private const val PRUNE_PROTECT_TOKENS = 10_000    // 保护最近 10k tokens
        private const val PRUNE_PROTECT_ROUNDS = 2         // 保护最近 2 轮对话
        private const val MAX_CONTEXT_TOKENS = 100_000     // 最大上下文 tokens
    }

    /**
     * 检查是否需要压缩
     */
    fun needsCompaction(session: Session): Boolean {
        val estimatedTokens = estimateSessionTokens(session)
        return estimatedTokens > MAX_CONTEXT_TOKENS
    }

    /**
     * 估算会话 Token 数量
     */
    fun estimateSessionTokens(session: Session): Int {
        var total = 0
        for (message in session.messages) {
            for (part in message.parts) {
                total += TokenEstimator.estimate(part)
            }
        }
        return total
    }

    /**
     * 执行 Pruning（清理旧工具输出）
     *
     * @return 清理的 token 数量
     */
    fun prune(session: Session): Int {
        logger.info("开始 Pruning: sessionId={}", session.id)

        var totalTokens = 0
        var prunedTokens = 0
        val partsToPrune = mutableListOf<Part>()
        var rounds = 0
        var protect = true

        // 倒序遍历消息（从最新到最旧）
        val messages = session.messages
        for (i in messages.size - 1 downTo 0) {
            val msg = messages[i]

            if (msg.isAssistantMessage()) {
                rounds++

                // 跳过最近 N 轮对话
                if (rounds <= PRUNE_PROTECT_ROUNDS) {
                    continue
                }

                // 检查是否已有压缩标记
                if (msg.createdTime.isBefore(Instant.EPOCH)) {
                    break  // 已压缩过，停止
                }

                // 查找可清理的工具调用
                for (part in msg.parts) {
                    if (part is ToolPart) {
                        if (part.state == ToolPart.ToolState.COMPLETED) {
                            // 检查是否已清理过
                            val result = part.result
                            if (result != null &&
                                result.data != null &&
                                !isPruned(result)) {

                                val partTokens = TokenEstimator.estimate(part)
                                totalTokens += partTokens

                                // 超过保护阈值后开始清理
                                if (totalTokens > PRUNE_PROTECT_TOKENS) {
                                    partsToPrune.add(part)
                                    prunedTokens += partTokens
                                }
                            }
                        }
                    }
                }
            }
        }

        // 如果清理量足够大，执行清理
        if (prunedTokens > PRUNE_MINIMUM_TOKENS) {
            for (part in partsToPrune) {
                if (part is ToolPart) {
                    pruneToolPart(part)
                }
            }
            logger.info("Pruning 完成: sessionId={}, prunedTokens={}", session.id, prunedTokens)
        } else {
            logger.info("Pruning 跳过: sessionId={}, prunedTokens={} (阈值: {})",
                session.id, prunedTokens, PRUNE_MINIMUM_TOKENS)
        }

        return prunedTokens
    }

    /**
     * 执行 Compaction（生成会话摘要）
     */
    fun compact(session: Session): String {
        logger.info("开始 Compaction: sessionId={}", session.id)

        return try {
            // 1. 构建压缩提示词
            val prompt = buildCompactionPrompt(session)

            // 2. 调用 LLM 生成摘要
            val response = llmService.simpleRequest(prompt)

            // 3. 解析摘要
            val json = objectMapper.readTree(response)
            val summary = json.path("summary").asText("")

            // 4. 标记压缩点
            markCompactionPoint(session)

            logger.info("Compaction 完成: sessionId={}, summaryLength={}",
                session.id, summary.length)

            summary

        } catch (e: Exception) {
            logger.error("Compaction 失败: sessionId={}", session.id, e)
            ""
        }
    }

    /**
     * 清理工具 Part
     */
    private fun pruneToolPart(toolPart: ToolPart) {
        val result = toolPart.result
        if (result != null && result.data != null) {
            val original = result.data.toString()
            val pruned = "[Pruned: ${original.length} chars]"

            // 替换为占位符
            result.data = pruned

            // 标记已清理（通过设置 displayContent）
            result.displayContent = "[COMPACTED]"
        }
    }

    /**
     * 检查是否已清理
     */
    private fun isPruned(result: com.smancode.sman.tools.ToolResult): Boolean {
        if (result.data == null) {
            return true
        }
        val dataStr = result.data.toString()
        return dataStr.startsWith("[Pruned:")
    }

    /**
     * 标记压缩点
     */
    private fun markCompactionPoint(session: Session) {
        // 设置最早的助手消息的创建时间为特殊值
        for (message in session.messages) {
            if (message.isAssistantMessage()) {
                message.createdTime = Instant.EPOCH
                break  // 只标记第一个
            }
        }
    }

    /**
     * 构建压缩提示词
     */
    private fun buildCompactionPrompt(session: Session): String {
        val prompt = StringBuilder()

        prompt.append("你是代码分析助手。请将以下对话历史压缩为简洁的摘要。\n\n")

        prompt.append("## 用户原始问题\n")
        val firstUser = getFirstUserMessage(session)
        if (firstUser != null && firstUser.parts.isNotEmpty()) {
            val firstPart = firstUser.parts[0]
            if (firstPart is TextPart) {
                prompt.append(firstPart.text).append("\n\n")
            }
        }

        prompt.append("## 对话历史\n")
        prompt.append(formatConversationHistory(session)).append("\n\n")

        prompt.append("## 要求\n")
        prompt.append("请生成一个详细的摘要，包含以下信息：\n")
        prompt.append("1. 我们做了什么（已执行的工具和发现）\n")
        prompt.append("2. 我们正在做什么（当前状态）\n")
        prompt.append("3. 我们需要做什么（下一步计划）\n")
        prompt.append("4. 关键的用户请求、约束或偏好\n")
        prompt.append("5. 重要的技术决策及其原因\n\n")

        prompt.append("请以 JSON 格式返回：\n")
        prompt.append("{\n")
        prompt.append("  \"summary\": \"你的详细摘要\"\n")
        prompt.append("}")

        return prompt.toString()
    }

    /**
     * 格式化对话历史
     */
    private fun formatConversationHistory(session: Session): String {
        val sb = StringBuilder()

        for (message in session.messages) {
            if (message.isUserMessage()) {
                sb.append("### 用户\n")
                for (part in message.parts) {
                    if (part is TextPart) {
                        sb.append(part.text).append("\n")
                    }
                }
            } else {
                sb.append("### 助手\n")
                for (part in message.parts) {
                    if (part is TextPart) {
                        sb.append(part.text).append("\n")
                    } else if (part is ToolPart) {
                        sb.append("调用工具: ").append(part.toolName)
                        val result = part.result
                        if (result != null && result.data != null) {
                            val data = result.data.toString()
                            if (!data.startsWith("[Pruned:")) {
                                sb.append("\n结果: ").append(data.take(200))
                            }
                        }
                        sb.append("\n")
                    }
                }
            }
            sb.append("\n")
        }

        return sb.toString()
    }

    /**
     * 获取第一个用户消息
     */
    private fun getFirstUserMessage(session: Session): Message? {
        for (message in session.messages) {
            if (message.isUserMessage()) {
                return message
            }
        }
        return null
    }
}
