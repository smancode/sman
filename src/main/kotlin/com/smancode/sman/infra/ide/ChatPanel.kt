package com.smancode.sman.infra.ide

import com.intellij.openapi.project.Project
import com.smancode.sman.ide.components.CliControlBar
import com.smancode.sman.ide.components.CliInputArea
import com.smancode.sman.ide.components.TaskProgressBar
import com.smancode.sman.ide.components.WelcomePanel
import com.smancode.sman.ide.model.GraphModels
import com.smancode.sman.ide.model.PartData
import com.smancode.sman.ide.ui.LinkNavigationHandler
import com.smancode.sman.ide.service.SmanService
import com.smancode.sman.ide.service.storageService
import com.smancode.sman.ide.theme.ThemeColors
import org.slf4j.LoggerFactory
import java.awt.BorderLayout
import java.awt.CardLayout
import javax.swing.BoxLayout
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.border.EmptyBorder

/**
 * Sman 聊天面板
 *
 * 布局结构：
 * - 顶部：控制栏（新建会话、历史记录、设置）
 * - 中间：欢迎面板 / 消息输出区域（CardLayout 切换）
 * - 底部：任务进度栏 + 输入框
 *
 * 职责拆分：
 * - ChatToolbar: 会话管理、设置、分析结果
 * - ChatInput: 消息发送、命令处理
 * - ChatOutput: 消息渲染、主题应用
 */
class ChatPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val logger = LoggerFactory.getLogger(ChatPanel::class.java)

    // 服务引用
    private val smanService get() = SmanService.getInstance(project)
    private val storageService = project.storageService()

    // UI 组件
    private val centerPanel = JPanel(CardLayout())
    private val welcomePanel = WelcomePanel()
    private val outputArea = javax.swing.JTextPane().apply {
        isEditable = false
        font = com.smancode.sman.ide.ui.FontManager.getEditorFont()
        contentType = "text/html"
        margin = java.awt.Insets(0, 16, 0, 16)
    }

    private val linkNavigationHandler = LinkNavigationHandler(project, outputArea)

    // 使用 lateinit 解决循环依赖
    private lateinit var chatToolbar: ChatToolbar
    private lateinit var chatOutput: ChatOutput
    private lateinit var chatInput: ChatInput

    private val controlBar: CliControlBar by lazy {
        CliControlBar(
            onNewChatCallback = { chatToolbar.startNewSession() },
            onHistoryCallback = { chatToolbar.showHistory() },
            onSettingsCallback = { chatToolbar.showSettings() }
        )
    }

    private val inputArea: CliInputArea by lazy {
        CliInputArea(
            onSendCallback = { text, codeReferences ->
                chatInput.sendMessage(text, codeReferences, projectKey)
            },
            onInsertCodeReferenceCallback = {
                chatInput.showCodeReferenceHint()
            }
        )
    }

    private val taskProgressBar = TaskProgressBar()

    private val scrollPane = JScrollPane(outputArea).apply {
        verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
        horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        border = null
    }

    private val cardLayout get() = centerPanel.layout as CardLayout

    private val projectKey: String
        get() = project.name

    init {
        try {
            // 初始化功能处理器（解决循环依赖）
            chatOutput = ChatOutput(project, logger, outputArea)
            chatToolbar = ChatToolbar(project, logger, storageService, controlBar)
            chatInput = ChatInput(project, logger, inputArea)

            // 设置回调
            setupCallbacks()

            initComponents()
            applyTheme()
            chatOutput.setupLinkNavigation(linkNavigationHandler)
            chatInput.setupCodeReferenceCallback()

            // 检查服务初始化状态
            val initError = smanService.initializationError
            if (initError != null) {
                // 未配置 API Key，显示欢迎面板（包含配置说明）
                showWelcomePanel()
                logger.info("LLM API Key 未配置，显示欢迎面板")
            } else {
                chatToolbar.loadLastSession(outputArea, scrollPane)
            }

            logger.info("ChatPanel 初始化成功")
        } catch (e: Exception) {
            logger.error("ChatPanel 初始化失败", e)
            showErrorPanel("""
                ⚠️ 初始化失败：${e.message}

                请查看日志获取详细信息，或尝试重启 IDE。
            """.trimIndent())
        }
    }

    /**
     * 设置各处理器的回调
     */
    private fun setupCallbacks() {
        // ChatToolbar 回调
        chatToolbar.setCallback(object : ChatToolbar.Callback {
            override fun getCurrentSessionId(): String? = chatInput.getCurrentSessionId()
            override fun setCurrentSessionId(sessionId: String?) = chatInput.setCurrentSessionId(sessionId)
            override fun clearChatUI() = this@ChatPanel.clearChatUI()
            override fun showWelcome() = this@ChatPanel.showWelcome()
            override fun showChat() = this@ChatPanel.showChat()
            override fun appendPartToUI(part: PartData) = chatOutput.appendPartToUI(part, taskProgressBar)
            override fun getOutputArea() = outputArea
            override fun showAnalysisResults() = chatToolbar.showAnalysisResults()
        })

        // ChatOutput 回调
        chatOutput.setCallback(object : ChatOutput.Callback {
            override fun getCurrentSessionId(): String? = chatInput.getCurrentSessionId()
            override fun getStorageService() = storageService
        })

        // ChatInput 回调
        chatInput.setCallback(object : ChatInput.Callback {
            override fun getCurrentSessionId(): String? = chatInput.getCurrentSessionId()
            override fun setCurrentSessionId(sessionId: String?) = chatInput.setCurrentSessionId(sessionId)
            override fun showChat() = this@ChatPanel.showChat()
            override fun showWelcomePanel() = this@ChatPanel.showWelcomePanel()
            override fun getStorageService() = storageService
            override fun appendPartToUI(part: PartData) = chatOutput.appendPartToUI(part, taskProgressBar)
            override fun appendSystemMessage(text: String, isProcessing: Boolean, saveToHistory: Boolean) =
                chatOutput.appendSystemMessage(text, isProcessing, saveToHistory)
            override fun convertPartToData(part: com.smancode.sman.model.part.Part) =
                chatOutput.convertPartToData(part)
        })
    }

    private fun initComponents() {
        logger.info("开始初始化 ChatPanel 组件...")

        centerPanel.isOpaque = false
        centerPanel.add(welcomePanel, "welcome")
        centerPanel.add(scrollPane, "chat")

        showWelcome()

        add(controlBar, BorderLayout.NORTH)
        add(centerPanel, BorderLayout.CENTER)

        val bottomPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            border = EmptyBorder(10, 12, 10, 12)
            add(taskProgressBar)
            add(inputArea)
        }
        add(bottomPanel, BorderLayout.SOUTH)

        logger.info("ChatPanel 组件初始化完成")
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
     * 显示欢迎面板（包含配置说明）
     */
    private fun showWelcomePanel() {
        cardLayout.show(centerPanel, "welcome")
        logger.info("显示欢迎面板（未配置 API Key）")
    }

    /**
     * 清空聊天 UI
     */
    private fun clearChatUI() {
        chatOutput.clearOutput()
        taskProgressBar.clear()
        chatInput.clearInput()
    }

    /**
     * 显示错误面板
     */
    private fun showErrorPanel(errorMessage: String) {
        cardLayout.show(centerPanel, "chat")

        // 格式化错误信息为 HTML
        val fontFamily = com.smancode.sman.ide.ui.FontManager.getEditorFontFamily()
        val fontSize = com.smancode.sman.ide.ui.FontManager.getEditorFontSize()

        val errorHtml = """
            <html>
            <head>
                <style>
                    body {
                        font-family: '$fontFamily', monospace;
                        font-size: ${fontSize}px;
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
        chatOutput.applyTheme(scrollPane, taskProgressBar)
    }

    /**
     * 重新应用主题（当 IDE 主题切换时调用）
     */
    fun refreshTheme() {
        val colors = ThemeColors.getCurrentColors()
        background = colors.background
        chatOutput.refreshTheme(taskProgressBar)
        outputArea.repaint()
    }

    /**
     * 对外暴露的消息发送方法
     */
    fun sendMessage(inputText: String? = null, codeReferences: List<com.smancode.sman.ide.components.CodeReference> = emptyList()) {
        chatInput.sendMessage(inputText, codeReferences, projectKey)
    }

    /**
     * 释放资源
     */
    fun dispose() {
        // 保存当前会话
        chatToolbar.saveCurrentSessionIfNeeded()
    }
}
