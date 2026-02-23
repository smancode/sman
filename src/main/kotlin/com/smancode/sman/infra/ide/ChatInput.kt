package com.smancode.sman.infra.ide

import com.intellij.openapi.project.Project
import com.smancode.sman.ide.components.CodeReference
import com.smancode.sman.ide.model.GraphModels
import com.smancode.sman.ide.service.SmanService
import com.smancode.sman.ide.util.SessionIdGenerator
import org.slf4j.Logger
import java.time.Instant
import java.util.UUID
import java.util.function.Consumer
import javax.swing.SwingUtilities

/**
 * 聊天输入处理器
 *
 * 负责输入处理功能：
 * - 消息发送
 * - 代码引用构建
 * - 命令处理
 * - Agent 循环调用
 */
class ChatInput(
    private val project: Project,
    private val logger: Logger,
    private val inputArea: com.smancode.sman.ide.components.CliInputArea
) {
    private val smanService: SmanService get() = SmanService.getInstance(project)

    // 回调接口
    interface Callback {
        fun getCurrentSessionId(): String?
        fun setCurrentSessionId(sessionId: String?)
        fun showChat()
        fun showWelcomePanel()
        fun getStorageService(): com.smancode.sman.ide.service.StorageService
        fun appendPartToUI(part: com.smancode.sman.ide.model.PartData)
        fun appendSystemMessage(text: String, isProcessing: Boolean = false, saveToHistory: Boolean = true)
        fun convertPartToData(part: com.smancode.sman.model.part.Part): com.smancode.sman.ide.model.PartData
    }

    private var callback: Callback? = null
    private var currentSessionId: String? = null

    fun setCallback(callback: Callback) {
        this.callback = callback
    }

    fun getCurrentSessionId(): String? = currentSessionId

    fun setCurrentSessionId(sessionId: String?) {
        currentSessionId = sessionId
    }

    /**
     * 发送消息
     */
    fun sendMessage(
        inputText: String? = null,
        codeReferences: List<CodeReference> = emptyList(),
        projectKey: String
    ) {
        val text = inputText ?: inputArea.text.trim()
        if (text.isEmpty() && codeReferences.isEmpty()) return

        // 检查服务初始化状态
        smanService.initializationError?.let {
            // 未配置 API Key，显示欢迎面板（包含配置说明）
            callback?.showWelcomePanel()
            return
        }

        // 检测是否是内置命令
        val isCommitCommand = text.startsWith("/commit")

        // 确保有 sessionId（新建或复用）
        if (currentSessionId == null) {
            currentSessionId = SessionIdGenerator.generate()
            callback?.getStorageService()?.setCurrentSessionId(currentSessionId)
            // 立即创建会话记录
            callback?.getStorageService()?.createOrGetSession(currentSessionId!!, projectKey)
            logger.info("创建新会话: sessionId={}", currentSessionId)
        }

        // 清空输入框（如果是按钮触发）
        if (inputText != null) {
            inputArea.text = ""
        }

        callback?.showChat()

        // 构建用户输入（包含代码引用上下文）
        val enhancedInput = buildUserInputWithCodeReferences(text, codeReferences)

        // 创建用户消息 Part
        val userPart = createUserPart(currentSessionId!!, enhancedInput)

        // 立即保存用户消息
        callback?.getStorageService()?.addPartToSession(currentSessionId!!, userPart)

        // UI 显示用户消息（显示原始文本，不包含代码上下文）
        val displayPart = createUserPart(currentSessionId!!, text)
        callback?.appendPartToUI(displayPart)

        // 处理内置命令
        if (isCommitCommand) {
            handleCommitCommand()
            return
        }

        // 本地调用 SmanLoop
        logger.info("本地调用 SmanLoop: sessionId={}, input={}", currentSessionId, enhancedInput)
        processWithAgentLoop(currentSessionId!!, enhancedInput)
    }

    /**
     * 构建包含代码引用的用户输入
     */
    private fun buildUserInputWithCodeReferences(userInput: String, codeReferences: List<CodeReference>): String {
        if (codeReferences.isEmpty()) return userInput

        val sb = StringBuilder()
        sb.appendLine(userInput)

        // 添加代码引用上下文
        codeReferences.forEach { ref ->
            sb.appendLine()
            sb.appendLine("```")
            sb.appendLine("// ${ref.filePath}:${ref.startLine}-${ref.endLine}")
            sb.appendLine(ref.codeContent)
            sb.appendLine("```")
        }

        return sb.toString()
    }

    /**
     * 使用 SmanLoop 处理消息
     */
    private fun processWithAgentLoop(sessionId: String, userInput: String) {
        // 创建 partPusher 回调
        val partPusher = Consumer<com.smancode.sman.model.part.Part> { part ->
            // 在 EDT 线程中更新 UI
            SwingUtilities.invokeLater {
                try {
                    // 转换为 UI PartData
                    val partData = callback?.convertPartToData(part)
                    if (partData != null) {
                        // 保存到存储
                        callback?.getStorageService()?.addPartToSession(sessionId, partData)
                        // 显示在 UI 上
                        callback?.appendPartToUI(partData)
                    }
                } catch (e: Exception) {
                    logger.error("处理 Part 失败", e)
                }
            }
        }

        // 在后台线程中处理
        Thread {
            try {
                logger.info("开始处理: sessionId={}, input={}", sessionId, userInput)
                val assistantMessage = smanService.processMessage(sessionId, userInput, partPusher)
                logger.info("处理完成: sessionId={}, parts={}", sessionId, assistantMessage.parts.size)
            } catch (e: Exception) {
                logger.error("SmanLoop 处理失败", e)
                SwingUtilities.invokeLater {
                    callback?.appendSystemMessage("❌ 处理失败: ${e.message}")
                }
            }
        }.start()
    }

    /**
     * 创建用户消息 Part
     */
    private fun createUserPart(sessionId: String, text: String): GraphModels.UserPartData {
        val now = Instant.now()
        return GraphModels.UserPartData(
            id = UUID.randomUUID().toString(),
            messageId = UUID.randomUUID().toString(),
            sessionId = sessionId,
            createdTime = now,
            updatedTime = now,
            data = mapOf("text" to text)
        )
    }

    /**
     * 显示代码引用提示
     */
    fun showCodeReferenceHint() {
        callback?.appendSystemMessage("""
            💡 提示：在编辑器中选中代码后，按 Ctrl+L (macOS: Cmd+L) 即可将代码引用插入到输入框。
        """.trimIndent())
    }

    /**
     * 设置代码引用回调
     */
    fun setupCodeReferenceCallback() {
        smanService.onCodeReferenceCallback = { codeReference ->
            inputArea.insertCodeReference(codeReference)
        }
    }

    /**
     * 处理 /commit 命令（本地模式）
     */
    private fun handleCommitCommand() {
        if (currentSessionId == null) {
            callback?.appendSystemMessage("错误：没有活动的会话")
            return
        }

        logger.info("【/commit命令】开始处理: sessionId={}", currentSessionId)
        callback?.appendSystemMessage("⚠️ /commit 命令在本地模式下正在开发中...", saveToHistory = true)
    }

    /**
     * 处理工具调用（本地模式）
     * TODO: 实现本地工具调用逻辑
     */
    fun handleToolCallLocally(toolName: String, params: Map<String, Any?>) {
        logger.info("本地工具调用: toolName={}, params={}", toolName, params)
        // TODO: 使用 LocalToolExecutor 执行工具
    }

    /**
     * 清空输入区域
     */
    fun clearInput() {
        inputArea.clear()
    }
}
