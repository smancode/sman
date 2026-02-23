package com.smancode.sman.domain.react

import com.smancode.sman.model.message.Message
import com.smancode.sman.model.part.Part
import com.smancode.sman.model.part.TextPart
import com.smancode.sman.model.part.ToolPart
import com.smancode.sman.model.session.Session
import org.slf4j.LoggerFactory
import java.util.*

/**
 * 工具执行器
 *
 * 负责：
 * - 工具调用处理
 * - 子会话隔离
 * - Doom Loop 检测
 * - 结果处理
 */
class ToolExecutor {
    private val logger = LoggerFactory.getLogger(ToolExecutor::class.java)

    /**
     * 检测 Doom Loop（无限循环）
     *
     * 检测最近 3 次是否有相同的工具调用
     *
     * @param session     会话
     * @param currentTool 当前工具
     * @return 是否检测到无限循环
     */
    fun detectDoomLoop(session: Session, currentTool: ToolPart): Boolean {
        val messages = session.messages
        if (messages.size < 2) return false

        val DOOM_LOOP_THRESHOLD = 3
        var count = 0

        // 从最新到最旧检查
        for (i in messages.size - 1 downTo maxOf(0, messages.size - DOOM_LOOP_THRESHOLD)) {
            val msg = messages[i]
            if (!msg.isAssistantMessage()) continue

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
     * 创建 Doom Loop 警告 Part
     */
    fun createDoomLoopWarningPart(messageId: String?, sessionId: String): TextPart {
        return TextPart().apply {
            this.messageId = messageId
            this.sessionId = sessionId
            text = "⚠️ 检测到重复的工具调用，停止循环以避免无限循环。"
            touch()
        }
    }

    /**
     * 比较两个对象是否相等（支持 Map 比较）
     */
    fun objectsEqual(obj1: Any?, obj2: Any?): Boolean {
        if (obj1 === obj2) return true
        if (obj1 == null || obj2 == null) return false
        if (obj1 is Map<*, *> && obj2 is Map<*, *>) return obj1 == obj2
        return obj1 == obj2
    }

    /**
     * 处理 batch 工具的子结果展开
     *
     * 如果是 batch 工具，需要将所有子工具的结果也添加到上下文
     * 这样 LLM 可以看到所有子工具的完整输出
     *
     * @param toolPart         执行完的 batch 工具 Part
     * @param assistantMessage 助手消息
     * @param partHandler      Part 处理器
     */
    fun expandBatchSubResults(
        toolPart: ToolPart,
        assistantMessage: Message,
        partHandler: PartHandler
    ) {
        val partResult = toolPart.result
        val batchSubResults = partResult?.batchSubResults

        if (toolPart.toolName != "batch" || partResult == null || batchSubResults == null) {
            return
        }

        logger.info("检测到 batch 工具，开始展开 {} 个子结果", batchSubResults.size)

        for (subResult in batchSubResults) {
            val subPartId = UUID.randomUUID().toString()
            val subToolPart = ToolPart(
                subPartId,
                assistantMessage.id!!,
                assistantMessage.sessionId!!,
                subResult.toolName!!
            )

            // 设置参数（使用 batch 的参数）
            subToolPart.parameters = toolPart.parameters

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

    /**
     * 工具执行结果
     */
    data class ToolExecutionResult(
        val success: Boolean,
        val shouldBreak: Boolean = false,  // 是否应该退出工具执行循环
        val warningPart: TextPart? = null
    ) {
        companion object {
            fun success() = ToolExecutionResult(success = true)
            fun doomLoopDetected(warningPart: TextPart) = ToolExecutionResult(
                success = false,
                shouldBreak = true,
                warningPart = warningPart
            )
        }
    }
}
