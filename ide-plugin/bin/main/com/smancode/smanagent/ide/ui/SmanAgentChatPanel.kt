package com.smancode.smanagent.ide.ui

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.intellij.openapi.project.Project
import com.smancode.smanagent.ide.components.HistoryPopup
import com.smancode.smanagent.ide.components.CliControlBar
import com.smancode.smanagent.ide.components.CliInputArea
import com.smancode.smanagent.ide.components.TaskProgressBar
import com.smancode.smanagent.ide.components.WelcomePanel
import com.smancode.smanagent.ide.model.GraphModels.PartType
import com.smancode.smanagent.ide.model.GraphModels.UserPartData
import com.smancode.smanagent.ide.model.PartData
import com.smancode.smanagent.ide.renderer.StyledMessageRenderer
import com.smancode.smanagent.ide.service.AgentWebSocketClient
import com.smancode.smanagent.ide.service.SessionInfo
import com.smancode.smanagent.ide.service.storageService
import com.smancode.smanagent.ide.util.SessionIdGenerator
import com.smancode.smanagent.ide.util.SystemInfoProvider
import com.smancode.smanagent.ide.theme.ThemeColors
import com.smancode.smanagent.ide.theme.ColorPalette
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Cursor
import java.awt.Point
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
import java.io.File
import java.net.URL
import java.time.Instant
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue
import javax.swing.*
import javax.swing.event.HyperlinkEvent
import javax.swing.text.*

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

    // 中间内容区域（使用 CardLayout 切换欢迎面板和消息区）
    private val centerPanel = JPanel(CardLayout())
    private val welcomePanel = WelcomePanel()
    private val outputArea = JTextPane().apply {
        isEditable = false
        font = java.awt.Font("JetBrains Mono", java.awt.Font.PLAIN, 13)
        // 使用 HTMLEditorKit 支持 Markdown 渲染（延迟初始化，需要在 applyTheme 后）
        contentType = "text/html"
        // 设置边距：左右各16px
        margin = java.awt.Insets(0, 16, 0, 16)
    }

    // 添加 HyperlinkListener 处理链接点击
    private val hyperlinkListener = javax.swing.event.HyperlinkListener { e ->
        if (e.eventType == HyperlinkEvent.EventType.ACTIVATED) {
            navigateToUrl(e.url)
        }
    }

    // 添加鼠标监听器实现悬停手型光标效果
    private val mouseMotionListener = object : MouseMotionAdapter() {
        override fun mouseMoved(e: MouseEvent) {
            updateCursorForPosition(e.point)
        }
    }

    private val controlBar = CliControlBar(
        onNewChatCallback = { startNewSession() },
        onHistoryCallback = { showHistory() },
        onSettingsCallback = { showSettings() }
    )

    private val inputArea = CliInputArea { text -> sendMessage(text) }

    // 任务进度栏（固定在底部显示）
    private val taskProgressBar = TaskProgressBar()

    private val scrollPane = JScrollPane(outputArea).apply {
        verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
        horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        border = null
    }

    private val storageService = project.storageService()
    private var webSocketClient: AgentWebSocketClient? = null

    private var currentSessionId: String? = null
    private val projectKey: String
        get() = project.name

    // 待发送请求队列（支持多个请求排队）
    private val pendingRequests = ConcurrentLinkedQueue<PendingRequest>()

    // 是否正在连接
    @Volatile
    private var isConnecting = false

    init {
        try {
            initComponents()
            // 注册监听器
            setupLinkNavigation()
            loadLastSession()
            // 不在 init 时连接 WebSocket，延迟到第一次发送消息时连接
            applyTheme()
            logger.info("SmanAgentChatPanel 初始化成功")
        } catch (e: Exception) {
            logger.error("SmanAgentChatPanel 初始化失败", e)
            outputArea.text = "初始化失败: ${e.message}\n\n请检查配置或重启插件。"
        }
    }

    /**
     * 应用主题配色
     */
    private fun applyTheme() {
        val colors = ThemeColors.getCurrentColors()

        // 背景色（与输出区一致，实现悬浮效果）
        background = colors.background

        // 输出区域 - 设置 EditorKit（包含主题样式）
        outputArea.editorKit = com.smancode.smanagent.ide.renderer.MarkdownRenderer.createStyledEditorKit(colors)
        outputArea.background = colors.background
        outputArea.foreground = colors.textPrimary

        // 滚动条
        scrollPane.verticalScrollBar.apply {
            background = colors.background
            foreground = colors.textMuted
        }

        // 控制栏和输入框会自动适配主题
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

        // 初始化中间内容区域（CardLayout）
        centerPanel.isOpaque = false
        centerPanel.add(welcomePanel, "welcome")
        centerPanel.add(scrollPane, "chat")

        // 默认显示欢迎面板
        showWelcome()

        // 顶部：控制栏
        add(controlBar, BorderLayout.NORTH)

        // 中间：内容区域（欢迎面板或聊天消息）
        add(centerPanel, BorderLayout.CENTER)

        // 底部：任务进度栏 + 输入框
        val bottomPanel = JPanel(BorderLayout()).apply {
            isOpaque = false
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
        val layout = centerPanel.layout as CardLayout
        layout.show(centerPanel, "welcome")
        logger.debug("显示欢迎面板")
    }

    /**
     * 显示聊天消息区域
     */
    private fun showChat() {
        val layout = centerPanel.layout as CardLayout
        layout.show(centerPanel, "chat")
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

        // 获取历史会话列表
        val history = storageService.getHistorySessions()

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

    /**
     * 连接到后端（复用或新建 WebSocket）
     */
    private fun ensureWebSocketConnected() {
        // 检查连接状态
        if (webSocketClient != null && webSocketClient?.isConnected() == true) {
            logger.debug("WebSocket 已连接，复用现有连接")
            sendNextPendingRequest()
            return
        }

        // 如果正在连接，等待连接完成
        if (isConnecting) {
            logger.debug("WebSocket 正在连接中，等待连接完成")
            return
        }

        // 需要建立新连接
        connectToBackend()
    }

    /**
     * 建立新的 WebSocket 连接
     */
    private fun connectToBackend() {
        if (isConnecting) {
            logger.debug("已有连接正在建立，跳过")
            return
        }

        isConnecting = true
        try {
            val serverUrl = storageService.backendUrl
            logger.info("连接到后端: {}", serverUrl)

            webSocketClient = AgentWebSocketClient(serverUrl) { part ->
                SwingUtilities.invokeLater {
                    // 只处理当前活动会话的 Part
                    if (part.sessionId == currentSessionId) {
                        // 跳过 USER 类型的 Part，因为已经在 sendMessage 中渲染过了
                        if (part.type != PartType.USER) {
                            logger.debug("渲染 Part: type={}, sessionId={}", part.type, part.sessionId)
                            appendPartToUI(part)
                        } else {
                            logger.debug("跳过 USER Part: sessionId={}", part.sessionId)
                        }
                        storageService.addPartToSession(part.sessionId, part)
                    } else {
                        logger.debug("忽略非当前会话的 Part: partSessionId={}, currentSessionId={}", part.sessionId, currentSessionId)
                    }
                }
            }.apply {
                onComplete = { data ->
                    SwingUtilities.invokeLater {
                        val sessionId = data["sessionId"] as? String
                        if (sessionId != null) {
                            storageService.updateSessionTimestamp(sessionId)
                            logger.info("会话完成: sessionId={}", sessionId)
                        }
                        // 继续发送队列中的下一个请求
                        sendNextPendingRequest()
                    }
                }

                onToolCall = { data ->
                    // 处理工具调用
                    logger.info("收到工具调用: {}", data)
                    handleToolCall(data)
                }
            }

            // 异步连接，不阻塞 UI 线程
            webSocketClient?.connect()?.thenAccept {
                logger.info("WebSocket 连接成功")
                SwingUtilities.invokeLater {
                    isConnecting = false
                    // 连接成功后，发送队列中的请求
                    sendNextPendingRequest()
                }
            }?.exceptionally { ex ->
                logger.error("WebSocket 连接失败", ex)
                SwingUtilities.invokeLater {
                    isConnecting = false
                    appendSystemMessage("连接后端失败: ${ex.message}")
                    // 清空待发送请求
                    pendingRequests.clear()
                }
                null
            }

        } catch (e: Exception) {
            logger.error("连接后端失败", e)
            isConnecting = false
            appendSystemMessage("连接后端失败: ${e.message}")
        }
    }

    /**
     * 发送队列中的下一个请求
     */
    private fun sendNextPendingRequest() {
        val request = pendingRequests.poll()
        if (request != null) {
            sendRequest(request)
        }
    }

    /**
     * 发送请求到后端
     */
    private fun sendRequest(request: PendingRequest) {
        if (webSocketClient?.isConnected() != true) {
            // 连接已断开，重新加入队列
            logger.warn("WebSocket 未连接，重新加入队列: sessionId={}", request.sessionId)
            pendingRequests.add(request)
            connectToBackend()
            return
        }

        logger.info("发送请求: sessionId={}, input={}, isNewSession={}", request.sessionId, request.input, request.isNewSession)

        try {
            if (request.isNewSession) {
                // 新会话：获取系统信息
                val userIp = SystemInfoProvider.getLocalIpAddress()
                val userName = SystemInfoProvider.getHostName()
                logger.info("检测到系统信息: userIp={}, userName={}", userIp, userName)
                webSocketClient?.analyze(request.sessionId, request.projectKey, request.input, userIp, userName)
            } else {
                webSocketClient?.chat(request.sessionId, request.input)
            }
        } catch (e: Exception) {
            logger.error("发送请求失败", e)
            // 发送失败，重新加入队列
            pendingRequests.add(request)
        }
    }

    fun sendMessage(inputText: String? = null) {
        val text = inputText ?: inputArea.text.trim()
        if (text.isEmpty()) return

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

        // 判断是否为新会话（根据是否已有非用户消息的 Part）
        val session = storageService.getSession(currentSessionId!!)
        val isNewSession = session?.parts?.count { it.type != PartType.USER } == 0

        // 将请求加入队列
        val request = PendingRequest(
            sessionId = currentSessionId!!,
            projectKey = projectKey,
            input = text,
            isNewSession = isNewSession
        )
        pendingRequests.add(request)

        // 确保 WebSocket 连接并发送请求
        ensureWebSocketConnected()
    }

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
     * 待发送的分析请求
     */
    private data class PendingRequest(
        val sessionId: String,
        val projectKey: String,
        val input: String,
        val isNewSession: Boolean = true
    )

    /**
     * 追加 Part 到 UI
     */
    private fun appendPartToUI(part: PartData) {
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
     */
    private fun appendSystemMessage(text: String) {
        val colors = ThemeColors.getCurrentColors()
        val doc = outputArea.styledDocument
        StyledMessageRenderer.renderSystemMessageToDocument(text, doc, colors)
        outputArea.caretPosition = doc.length
    }

    /**
     * 处理工具调用
     */
    private fun handleToolCall(data: Map<String, Any>) {
        try {
            val toolName = data["toolName"] as? String ?: ""
            val toolCallId = data["toolCallId"] as? String ?: ""
            val params = data["params"] as? Map<String, Any?> ?: emptyMap()

            logger.info("执行工具: toolName={}, toolCallId={}, params={}", toolName, toolCallId, params)

            // 在后台线程执行工具
            Thread {
                try {
                    val toolExecutor = com.smancode.smanagent.ide.service.LocalToolExecutor(project)
                    val result = toolExecutor.execute(toolName, params, project.basePath)

                    logger.info("工具执行完成: toolName={}, success={}, result={}",
                            toolName, result.success, result.result)

                    // 发送 TOOL_RESULT 响应
                    val response = mutableMapOf(
                        "type" to "TOOL_RESULT",
                        "toolCallId" to toolCallId,
                        "toolName" to toolName,
                        "success" to result.success,
                        "result" to result.result,
                        "executionTime" to result.executionTime
                    )
                    // 添加 relativePath
                    if (result.relativePath != null) {
                        response["relativePath"] = result.relativePath
                    }
                    // 添加 relatedFilePaths
                    if (result.relatedFilePaths != null) {
                        response["relatedFilePaths"] = result.relatedFilePaths
                    }
                    // 添加 metadata
                    if (result.metadata != null) {
                        response["metadata"] = result.metadata
                    }
                    webSocketClient?.send(response)

                } catch (e: Exception) {
                    logger.error("工具执行失败: toolName={}", toolName, e)
                    // 发送错误响应
                    val response = mapOf(
                        "type" to "TOOL_RESULT",
                        "toolCallId" to toolCallId,
                        "toolName" to toolName,
                        "success" to false,
                        "result" to (e.message ?: "工具执行失败"),
                        "executionTime" to 0L
                    )
                    webSocketClient?.send(response)
                }
            }.start()
        } catch (e: Exception) {
            logger.error("处理工具调用失败", e)
        }
    }

    /**
     * 设置代码链接导航功能
     */
    private fun setupLinkNavigation() {
        // 添加 HyperlinkListener 处理链接点击
        outputArea.addHyperlinkListener(hyperlinkListener)
        // 添加鼠标移动监听器实现悬停手型光标
        outputArea.addMouseMotionListener(mouseMotionListener)
    }

    /**
     * 导航到 URL
     */
    private fun navigateToUrl(url: URL) {
        try {
            when (url.protocol) {
                "file" -> navigateToFile(url)
                "http", "https" -> {
                    // 使用浏览器打开 HTTP 链接
                    com.intellij.ide.BrowserUtil.browse(url)
                }
                else -> logger.warn("不支持的协议: {}", url.protocol)
            }
        } catch (e: Exception) {
            logger.error("导航失败: url={}", url, e)
        }
    }

    /**
     * 导航到文件并跳转到指定行
     */
    private fun navigateToFile(url: URL) {
        try {
            val file = File(url.toURI())
            val virtualFile = com.intellij.openapi.vfs.VirtualFileManager.getInstance()
                .findFileByUrl(url.toExternalForm()) ?: run {
                // 尝试通过 LocalFileSystem 查找
                com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByIoFile(file)
            }

            if (virtualFile == null) {
                logger.warn("文件不存在: {}", file.path)
                return
            }

            // 打开文件
            val fileEditorManager = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project)
            fileEditorManager.openFile(virtualFile, true)

            // 跳转到指定行（URL 中包含 #L123 格式的行号）
            url.ref?.let { ref ->
                // 支持格式：#L123 或 #123
                val lineNumber = ref.removePrefix("L").toIntOrNull()
                if (lineNumber != null && lineNumber > 0) {
                    jumpToLine(virtualFile, lineNumber)
                }
            }

            logger.info("成功导航到文件: file://{}", file.path)
        } catch (e: Exception) {
            logger.error("文件导航失败: url={}", url, e)
        }
    }

    /**
     * 跳转到指定行
     */
    private fun jumpToLine(virtualFile: com.intellij.openapi.vfs.VirtualFile, lineNumber: Int) {
        try {
            val fileEditorManager = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project)
            val editor = fileEditorManager.selectedTextEditor ?: return

            val document = editor.document
            val lineCount = document.lineCount

            if (lineNumber > lineCount) {
                logger.warn("行号超出范围: lineNumber={}, lineCount={}", lineNumber, lineCount)
                return
            }

            // 跳转到目标行
            val lineStartOffset = document.getLineStartOffset(lineNumber - 1)
            editor.caretModel.moveToOffset(lineStartOffset)
            editor.selectionModel.removeSelection()

            // 滚动到目标位置
            val scrollingModel = editor.scrollingModel
            scrollingModel.scrollToCaret(com.intellij.openapi.editor.ScrollType.CENTER)

            logger.debug("跳转到行: file={}, line={}", virtualFile.path, lineNumber)
        } catch (e: Exception) {
            logger.error("跳转失败: file={}, line={}", virtualFile.path, lineNumber, e)
        }
    }

    /**
     * 根据鼠标位置更新光标样式
     * 如果在链接上显示手型光标，否则显示默认光标
     */
    private fun updateCursorForPosition(point: Point) {
        try {
            val pos = outputArea.viewToModel(point)
            if (pos < 0) return

            val doc = outputArea.styledDocument
            val element = doc.getCharacterElement(pos)
            val attributes = element.attributes

            // 检查是否是链接（HTMLDocument 中链接会有 href 属性）
            val isLink = attributes.getAttribute(javax.swing.text.html.HTML.Attribute.HREF) != null

            outputArea.cursor = if (isLink) {
                Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            } else {
                Cursor.getDefaultCursor()
            }
        } catch (e: Exception) {
            // 出错时使用默认光标
            outputArea.cursor = Cursor.getDefaultCursor()
        }
    }

    fun dispose() {
        // 保存当前会话
        saveCurrentSessionIfNeeded()
        webSocketClient?.close()
    }
}
