package com.smancode.smanagent.ide.ui

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.smancode.smanagent.ide.components.HistoryPopup
import com.smancode.smanagent.ide.components.CliControlBar
import com.smancode.smanagent.ide.components.CliInputArea
import com.smancode.smanagent.ide.components.TaskProgressBar
import com.smancode.smanagent.ide.components.WelcomePanel
import com.smancode.smanagent.ide.model.GraphModels
import com.smancode.smanagent.ide.model.GraphModels.PartType
import com.smancode.smanagent.ide.model.GraphModels.UserPartData
import com.smancode.smanagent.ide.model.PartData
import com.smancode.smanagent.ide.renderer.StyledMessageRenderer
import com.smancode.smanagent.ide.service.SessionInfo
import com.smancode.smanagent.ide.service.SmanAgentService
import com.smancode.smanagent.ide.service.storageService
import com.smancode.smanagent.ide.util.SessionIdGenerator
import com.smancode.smanagent.ide.theme.ThemeColors
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.awt.BorderLayout
import java.awt.CardLayout
import java.time.Instant
import java.util.*
import java.util.function.Consumer
import javax.swing.*

/**
 * SmanAgent 聊天面板
 *
 * 布局结构：
 * - 顶部：控制栏（新建会话、历史记录、设置）
 * - 中间：欢迎面板 / 消息输出区域（CardLayout 切换）
 * - 底部：任务进度栏 + 输入框
 */
class SmanAgentChatPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val logger = LoggerFactory.getLogger(SmanAgentChatPanel::class.java)

    // 服务引用
    private val smanAgentService get() = SmanAgentService.getInstance(project)

    // UI 组件
    private val centerPanel = JPanel(CardLayout())
    private val welcomePanel = WelcomePanel()
    private val outputArea = JTextPane().apply {
        isEditable = false
        font = java.awt.Font("JetBrains Mono", java.awt.Font.PLAIN, 13)
        contentType = "text/html"
        margin = java.awt.Insets(0, 16, 0, 16)
    }

    private val linkNavigationHandler = LinkNavigationHandler(project, outputArea)

    private val controlBar = CliControlBar(
        onNewChatCallback = { startNewSession() },
        onHistoryCallback = { showHistory() },
        onSettingsCallback = { showSettings() }
    )

    private val inputArea = CliInputArea { text -> sendMessage(text) }
    private val taskProgressBar = TaskProgressBar()

    private val scrollPane = JScrollPane(outputArea).apply {
        verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
        horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        border = null
    }

    private val storageService = project.storageService()
    private val cardLayout get() = centerPanel.layout as CardLayout

    private var currentSessionId: String? = null
    private val projectKey: String
        get() = project.name

    init {
        try {
            initComponents()
            applyTheme()
            setupLinkNavigation()

            // 检查服务初始化状态
            val initError = smanAgentService.initializationError
            if (initError != null) {
                showErrorPanel(initError)
                logger.warn("SmanAgent 服务初始化失败，显示错误面板")
            } else {
                loadLastSession()
            }

            logger.info("SmanAgentChatPanel 初始化成功")
        } catch (e: Exception) {
            logger.error("SmanAgentChatPanel 初始化失败", e)
            showErrorPanel("""
                ⚠️ 初始化失败：${e.message}

                请查看日志获取详细信息，或尝试重启 IDE。
            """.trimIndent())
        }
    }

    /**
     * 显示错误面板
     */
    private fun showErrorPanel(errorMessage: String) {
        val cardLayout = centerPanel.layout as java.awt.CardLayout
        cardLayout.show(centerPanel, "chat")

        // 格式化错误信息为 HTML
        val errorHtml = """
            <html>
            <head>
                <style>
                    body {
                        font-family: 'JetBrains Mono', monospace;
                        font-size: 13px;
                        color: #E57373;
                        background-color: #263238;
                        padding: 16px;
                        white-space: pre-wrap;
                    }
                </style>
            </head>
            <body>${errorMessage.replace("\n", "<br>")}</body>
            </html>
        """.trimIndent()

        outputArea.text = errorHtml
    }

    /**
     * 应用主题配色
     */
    private fun applyTheme() {
        val colors = ThemeColors.getCurrentColors()

        background = colors.background
        outputArea.editorKit = com.smancode.smanagent.ide.renderer.MarkdownRenderer.createStyledEditorKit(colors)
        outputArea.background = colors.background
        outputArea.foreground = colors.textPrimary

        scrollPane.verticalScrollBar.apply {
            background = colors.background
            foreground = colors.textMuted
        }
    }

    /**
     * 重新应用主题（当 IDE 主题切换时调用）
     */
    fun refreshTheme() {
        applyTheme()
        outputArea.repaint()
        taskProgressBar.applyTheme()  // 刷新任务栏主题
    }

    private fun initComponents() {
        logger.info("开始初始化 SmanAgentChatPanel 组件...")

        centerPanel.isOpaque = false
        centerPanel.add(welcomePanel, "welcome")
        centerPanel.add(scrollPane, "chat")

        showWelcome()

        add(controlBar, BorderLayout.NORTH)
        add(centerPanel, BorderLayout.CENTER)

        val bottomPanel = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = javax.swing.border.EmptyBorder(10, 0, 0, 0)
            add(taskProgressBar, BorderLayout.NORTH)
            add(inputArea, BorderLayout.CENTER)
        }
        add(bottomPanel, BorderLayout.SOUTH)

        logger.info("SmanAgentChatPanel 组件初始化完成")
    }

    /**
     * 显示欢迎面板
     */
    private fun showWelcome() {
        cardLayout.show(centerPanel, "welcome")
        logger.debug("显示欢迎面板")
    }

    /**
     * 显示聊天消息区域
     */
    private fun showChat() {
        cardLayout.show(centerPanel, "chat")
        logger.debug("显示聊天区域")
    }

    /**
     * 清空聊天 UI
     */
    private fun clearChatUI() {
        // 对于 HTML 文档，需要使用 setText 设置空 HTML
        outputArea.text = "<html><body></body></html>"
        taskProgressBar.clear()
        inputArea.clear()
    }

    /**
     * 新建会话
     */
    private fun startNewSession() {
        logger.info("新建会话")

        // 保存当前会话（如果有内容）
        saveCurrentSessionIfNeeded()

        // 清空状态
        currentSessionId = null
        storageService.setCurrentSessionId(null)
        clearChatUI()
        showWelcome()
    }

    /**
     * 保存当前会话（如果需要）
     */
    private fun saveCurrentSessionIfNeeded() {
        val sessionId = currentSessionId
        if (sessionId != null) {
            val session = storageService.getSession(sessionId)
            if (session != null && session.parts.isNotEmpty()) {
                logger.info("保存当前会话: sessionId={}, parts={}", sessionId, session.parts.size)
                storageService.updateSessionTimestamp(sessionId)
            }
        }
    }

    /**
     * 显示历史记录
     */
    private fun showHistory() {
        logger.info("显示历史记录")

        // 先保存当前会话
        saveCurrentSessionIfNeeded()

        // 获取历史会话列表（仅当前项目）
        val history = storageService.getHistorySessions(projectKey)

        // 显示弹窗
        HistoryPopup(
            history = history,
            onSelect = { sessionInfo -> loadSession(sessionInfo.id) },
            onDelete = { sessionInfo -> deleteSession(sessionInfo.id) }
        ).show(controlBar.historyButton)
    }

    /**
     * 加载会话
     */
    private fun loadSession(sessionId: String) {
        logger.info("加载会话: sessionId={}", sessionId)

        // 清空当前 UI
        clearChatUI()

        // 加载会话数据
        val session = storageService.getSession(sessionId)
        if (session != null) {
            currentSessionId = session.id
            storageService.setCurrentSessionId(session.id)

            // 渲染历史消息
            if (session.parts.isNotEmpty()) {
                // 清空现有内容
                outputArea.text = "<html><body></body></html>"
                session.parts.forEach { part ->
                    appendPartToUI(part)
                }
                showChat()
            } else {
                showWelcome()
            }

            logger.info("会话加载完成: sessionId={}, parts={}", sessionId, session.parts.size)
        } else {
            logger.warn("会话不存在: sessionId={}", sessionId)
            showWelcome()
        }
    }

    /**
     * 删除会话
     */
    private fun deleteSession(sessionId: String) {
        logger.info("删除会话: sessionId={}", sessionId)

        storageService.deleteSession(sessionId)

        // 如果删除的是当前会话，清空 UI
        if (currentSessionId == sessionId) {
            currentSessionId = null
            storageService.setCurrentSessionId(null)
            clearChatUI()
            showWelcome()
        }
    }

    /**
     * 加载最后一次会话
     */
    private fun loadLastSession() {
        try {
            val lastSessionId = storageService.getCurrentSessionId()
            if (lastSessionId != null) {
                val session = storageService.getSession(lastSessionId)
                if (session != null && session.parts.isNotEmpty()) {
                    currentSessionId = session.id

                    // 渲染历史消息
                    outputArea.text = "<html><body></body></html>"
                    session.parts.forEach { part ->
                        appendPartToUI(part)
                    }
                    showChat()

                    // 延迟触发重绘，确保布局完全就绪
                    SwingUtilities.invokeLater {
                        // 强制重新计算尺寸（模拟组件大小变化）
                        val currentSize = outputArea.size
                        outputArea.size = java.awt.Dimension(1, 1)
                        outputArea.revalidate()
                        outputArea.repaint()
                        scrollPane.revalidate()
                        scrollPane.repaint()
                        // 恢复原始大小
                        outputArea.size = currentSize
                        outputArea.revalidate()
                        outputArea.repaint()
                    }

                    logger.info("加载上次会话: sessionId={}, parts={}", lastSessionId, session.parts.size)
                } else {
                    logger.info("无上次会话内容，显示欢迎面板")
                }
            }
        } catch (e: Exception) {
            logger.error("加载会话失败", e)
        }
    }

    private fun showSettings() {
        try {
            SettingsDialog.show(project)
        } catch (e: Exception) {
            logger.error("打开设置失败", e)
        }
    }

    // WebSocket 相关方法已移除，改为本地调用

    fun sendMessage(inputText: String? = null) {
        val text = inputText ?: inputArea.text.trim()
        if (text.isEmpty()) return

        // 检查服务初始化状态
        smanAgentService.initializationError?.let { error ->
            showErrorPanel(error)
            return
        }

        // 检测是否是内置命令
        val isCommitCommand = text.startsWith("/commit")

        // 确保有 sessionId（新建或复用）
        if (currentSessionId == null) {
            currentSessionId = SessionIdGenerator.generate()
            storageService.setCurrentSessionId(currentSessionId)
            // 立即创建会话记录
            storageService.createOrGetSession(currentSessionId!!, projectKey)
            logger.info("创建新会话: sessionId={}", currentSessionId)
        }

        // 清空输入框（如果是按钮触发）
        if (inputText != null) {
            inputArea.text = ""
        }

        showChat()

        // 创建用户消息 Part
        val userPart = createUserPart(currentSessionId!!, text)

        // 立即保存用户消息
        storageService.addPartToSession(currentSessionId!!, userPart)

        // UI 显示用户消息
        appendPartToUI(userPart)

        // 处理内置命令
        if (isCommitCommand) {
            handleCommitCommand()
            return
        }

        // 本地调用 SmanAgentLoop
        logger.info("本地调用 SmanAgentLoop: sessionId={}, input={}", currentSessionId, text)
        processWithAgentLoop(currentSessionId!!, text)
    }

    /**
     * 使用 SmanAgentLoop 处理消息
     */
    private fun processWithAgentLoop(sessionId: String, userInput: String) {
        // 获取 SmanAgentService（使用类级别的属性）

        // 创建 partPusher 回调
        val partPusher = Consumer<com.smancode.smanagent.model.part.Part> { part ->
            // 在 EDT 线程中更新 UI
            SwingUtilities.invokeLater {
                try {
                    // 转换为 UI PartData
                    val partData = convertPartToData(part)
                    // 保存到存储
                    storageService.addPartToSession(sessionId, partData)
                    // 显示在 UI 上
                    appendPartToUI(partData)
                } catch (e: Exception) {
                    logger.error("处理 Part 失败", e)
                }
            }
        }

        // 在后台线程中处理
        Thread {
            try {
                logger.info("开始处理: sessionId={}, input={}", sessionId, userInput)
                val assistantMessage = smanAgentService.processMessage(sessionId, userInput, partPusher)
                logger.info("处理完成: sessionId={}, parts={}", sessionId, assistantMessage.parts.size)
            } catch (e: Exception) {
                logger.error("SmanAgentLoop 处理失败", e)
                SwingUtilities.invokeLater {
                    appendSystemMessage("❌ 处理失败: ${e.message}")
                }
            }
        }.start()
    }

    /**
     * 将后端 Part 转换为 UI PartData
     */
    private fun convertPartToData(part: com.smancode.smanagent.model.part.Part): PartData {
        val commonData = CommonPartData(
            id = part.id ?: UUID.randomUUID().toString(),
            messageId = part.messageId ?: UUID.randomUUID().toString(),
            sessionId = part.sessionId ?: "",
            createdTime = part.createdTime,
            updatedTime = part.updatedTime
        )

        return when (part.type) {
            com.smancode.smanagent.model.part.PartType.TEXT -> {
                val textPart = part as com.smancode.smanagent.model.part.TextPart
                GraphModels.TextPartData(
                    id = commonData.id,
                    messageId = commonData.messageId,
                    sessionId = commonData.sessionId,
                    createdTime = commonData.createdTime,
                    updatedTime = commonData.updatedTime,
                    data = mapOf("text" to (textPart.text ?: ""))
                )
            }
            com.smancode.smanagent.model.part.PartType.TOOL -> {
                val toolPart = part as com.smancode.smanagent.model.part.ToolPart
                GraphModels.ToolPartData(
                    id = commonData.id,
                    messageId = commonData.messageId,
                    sessionId = commonData.sessionId,
                    createdTime = commonData.createdTime,
                    updatedTime = commonData.updatedTime,
                    data = buildToolPartData(toolPart)
                )
            }
            com.smancode.smanagent.model.part.PartType.REASONING -> {
                val reasoningPart = part as com.smancode.smanagent.model.part.ReasoningPart
                GraphModels.ReasoningPartData(
                    id = commonData.id,
                    messageId = commonData.messageId,
                    sessionId = commonData.sessionId,
                    createdTime = commonData.createdTime,
                    updatedTime = commonData.updatedTime,
                    data = mapOf("text" to (reasoningPart.text ?: ""))
                )
            }
            else -> {
                GraphModels.TextPartData(
                    id = commonData.id,
                    messageId = commonData.messageId,
                    sessionId = commonData.sessionId,
                    createdTime = commonData.createdTime,
                    updatedTime = commonData.updatedTime,
                    data = mapOf("text" to "[${part.type}]")
                )
            }
        }
    }

    /**
     * 构建 ToolPart 数据
     */
    private fun buildToolPartData(toolPart: com.smancode.smanagent.model.part.ToolPart): Map<String, Any> {
        val data = mutableMapOf<String, Any>()
        data["toolName"] = toolPart.toolName ?: ""
        data["state"] = toolPart.state?.name ?: "PENDING"
        toolPart.parameters?.let { data["parameters"] = it }
        toolPart.result?.error?.let { data["error"] = it }
        toolPart.result?.displayTitle?.let { data["title"] = it }
        toolPart.result?.displayContent?.let { data["content"] = it }
        return data
    }

    /**
     * 通用 Part 数据
     */
    private data class CommonPartData(
        val id: String,
        val messageId: String,
        val sessionId: String,
        val createdTime: Instant,
        val updatedTime: Instant
    )

    /**
     * 创建用户消息 Part
     */
    private fun createUserPart(sessionId: String, text: String): UserPartData {
        val now = Instant.now()
        return UserPartData(
            id = UUID.randomUUID().toString(),
            messageId = UUID.randomUUID().toString(),
            sessionId = sessionId,
            createdTime = now,
            updatedTime = now,
            data = mapOf("text" to text)
        )
    }

    /**
     * 追加 Part 到 UI
     */
    private fun appendPartToUI(part: PartData) {
        logger.info("=== appendPartToUI === type={}, data={}", part.type, part.data.keys)

        // TodoPart 特殊处理：更新任务栏而非插入消息流
        if (part.type == PartType.TODO) {
            taskProgressBar.updateTasks(part)
            return
        }

        // 其他 Part 使用 Markdown 渲染（传入 project 用于代码链接处理）
        StyledMessageRenderer.renderToTextPane(part, outputArea, project)
    }

    /**
     * 追加系统消息
     * @param text 消息文本
     * @param isProcessing 是否是处理中状态（灰色）
     * @param saveToHistory 是否保存到历史记录（默认 true）
     */
    private fun appendSystemMessage(text: String, isProcessing: Boolean = false, saveToHistory: Boolean = true) {
        val colors = ThemeColors.getCurrentColors()

        // 将文本转换为 HTML 格式（保留换行）
        var htmlText = text.replace("\n", "<br>")

        // 处理特殊颜色标记（与 StyledMessageRenderer 保持一致）
        // 替换 "Commit:" 为蓝色
        htmlText = htmlText.replace("Commit:", "<span style='color: ${toHexString(colors.codeFunction)};'>Commit:</span>")
        // 替换 "文件变更:" 为黄色
        htmlText = htmlText.replace("文件变更:", "<span style='color: ${toHexString(colors.warning)};'>文件变更:</span>")

        val colorHex = if (isProcessing) {
            String.format("#%06X", colors.textMuted.rgb and 0xFFFFFF)
        } else {
            String.format("#%06X", colors.textPrimary.rgb and 0xFFFFFF)
        }

        // 使用与 StyledMessageRenderer 相同的方式追加 HTML，避免覆盖已有样式
        val html = if (outputArea.text.contains("<body>")) {
            // 已有 HTML 内容，在 </body> 前插入
            val currentHtml = outputArea.text
            currentHtml.replace("</body>", "<div style='color:$colorHex; font-family: \"JetBrains Mono\", monospace; margin: 4px 0;'>$htmlText</div></body>")
        } else {
            // 空 HTML，初始化
            "<html><body><div style='color:$colorHex; font-family: \"JetBrains Mono\", monospace; margin: 4px 0;'>$htmlText</div></body></html>"
        }

        // 使用 HTMLEditorKit 的方式来追加内容，避免覆盖已有样式
        val kit = outputArea.editorKit as? javax.swing.text.html.HTMLEditorKit
        val doc = outputArea.styledDocument

        if (kit != null && outputArea.text.contains("<body>")) {
            // 使用 HTMLEditorKit.read() 追加内容（与 StyledMessageRenderer 一致）
            try {
                val reader = java.io.StringReader(html)
                // 先清空，然后重新写入整个文档
                doc.remove(0, doc.length)
                kit.read(reader, doc, 0)
            } catch (e: Exception) {
                logger.error("追加系统消息失败", e)
            }
        } else {
            // 回退到直接设置
            outputArea.text = html
        }

        // 滚动到底部
        outputArea.caretPosition = outputArea.document.length

        // 保存到历史记录
        if (saveToHistory && currentSessionId != null) {
            val now = Instant.now()
            // 如果是处理中消息，添加特殊标记，用于历史加载时识别
            val savedText = if (isProcessing) {
                "[PROCESSING]$text"
            } else {
                text
            }
            val systemPart = GraphModels.TextPartData(
                id = UUID.randomUUID().toString(),
                messageId = UUID.randomUUID().toString(),
                sessionId = currentSessionId!!,
                createdTime = now,
                updatedTime = now,
                data = mapOf("text" to savedText)
            )
            storageService.addPartToSession(currentSessionId!!, systemPart)
        }
    }

    /**
     * 将 Color 转换为十六进制字符串
     */
    private fun toHexString(color: java.awt.Color): String {
        return "#${color.red.toString(16).padStart(2, '0')}${color.green.toString(16).padStart(2, '0')}${color.blue.toString(16).padStart(2, '0')}"
    }

    /**
     * 处理工具调用（本地模式）
     * TODO: 实现本地工具调用逻辑
     */
    private fun handleToolCallLocally(toolName: String, params: Map<String, Any?>) {
        logger.info("本地工具调用: toolName={}, params={}", toolName, params)
        // TODO: 使用 LocalToolExecutor 执行工具
    }

    /**
     * 设置代码链接导航功能
     */
    private fun setupLinkNavigation() {
        outputArea.addHyperlinkListener(linkNavigationHandler.hyperlinkListener)
        outputArea.addMouseListener(linkNavigationHandler.mouseClickListener)
        outputArea.addMouseMotionListener(linkNavigationHandler.mouseMotionListener)
    }

    /**
     * 处理 /commit 命令（本地模式）
     * TODO: 实现本地 commit 逻辑
     */
    private fun handleCommitCommand() {
        if (currentSessionId == null) {
            appendSystemMessage("错误：没有活动的会话")
            return
        }

        logger.info("【/commit命令】开始处理: sessionId={}", currentSessionId)
        appendSystemMessage("⚠️ /commit 命令在本地模式下正在开发中...", saveToHistory = true)
    }

    fun dispose() {
        // 保存当前会话
        saveCurrentSessionIfNeeded()
    }
}
