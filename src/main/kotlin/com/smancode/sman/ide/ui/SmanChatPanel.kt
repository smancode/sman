package com.smancode.sman.ide.ui

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.smancode.sman.ide.components.HistoryPopup
import com.smancode.sman.ide.components.CliControlBar
import com.smancode.sman.ide.components.CliInputArea
import com.smancode.sman.ide.components.TaskProgressBar
import com.smancode.sman.ide.components.WelcomePanel
import com.smancode.sman.ide.model.GraphModels
import com.smancode.sman.ide.model.GraphModels.PartType
import com.smancode.sman.ide.model.GraphModels.UserPartData
import com.smancode.sman.ide.model.PartData
import com.smancode.sman.ide.renderer.StyledMessageRenderer
import com.smancode.sman.ide.service.SessionInfo
import com.smancode.sman.ide.service.SmanService
import com.smancode.sman.ide.service.storageService
import com.smancode.sman.ide.util.SessionIdGenerator
import com.smancode.sman.ide.theme.ThemeColors
import com.smancode.sman.analysis.model.ProjectMapManager
import com.smancode.sman.analysis.model.AnalysisType
import com.smancode.sman.analysis.model.StepState
import com.smancode.sman.analysis.model.ProjectEntry
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.awt.BorderLayout
import java.awt.CardLayout
import java.nio.file.Paths
import java.time.Instant
import java.util.*
import java.util.function.Consumer
import javax.swing.*

/**
 * Sman 聊天面板
 *
 * 布局结构：
 * - 顶部：控制栏（新建会话、历史记录、设置）
 * - 中间：欢迎面板 / 消息输出区域（CardLayout 切换）
 * - 底部：任务进度栏 + 输入框
 */
class SmanChatPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val logger = LoggerFactory.getLogger(SmanChatPanel::class.java)

    // 服务引用
    private val smanService get() = SmanService.getInstance(project)

    // UI 组件
    private val centerPanel = JPanel(CardLayout())
    private val welcomePanel = WelcomePanel()
    private val outputArea = JTextPane().apply {
        isEditable = false
        font = FontManager.getEditorFont()
        contentType = "text/html"
        margin = java.awt.Insets(0, 16, 0, 16)
    }

    private val linkNavigationHandler = LinkNavigationHandler(project, outputArea)

    private val controlBar = CliControlBar(
        onNewChatCallback = { startNewSession() },
        onHistoryCallback = { showHistory() },
        onSettingsCallback = { showSettings() },
        onProjectAnalysisCallback = { triggerProjectAnalysis() }
    )

    private val inputArea = CliInputArea(
        onSendCallback = { text, codeReferences ->
            sendMessage(text, codeReferences)
        },
        onInsertCodeReferenceCallback = {
            // 提示用户使用快捷键或显示帮助
            showCodeReferenceHint()
        }
    )

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
            setupCodeReferenceCallback()

            // 检查服务初始化状态
            val initError = smanService.initializationError
            if (initError != null) {
                // 未配置 API Key，显示欢迎面板（包含配置说明）
                showWelcomePanel()
                logger.info("LLM API Key 未配置，显示欢迎面板")
            } else {
                loadLastSession()
            }

            logger.info("SmanChatPanel 初始化成功")
        } catch (e: Exception) {
            logger.error("SmanChatPanel 初始化失败", e)
            showErrorPanel("""
                ⚠️ 初始化失败：${e.message}

                请查看日志获取详细信息，或尝试重启 IDE。
            """.trimIndent())
        }
    }

    /**
     * 显示分析结果（弹窗方式）
     */
    private fun showAnalysisResults() {
        logger.info("显示分析结果: projectKey={}", projectKey)

        // 获取项目根目录
        val projectRoot = project.basePath?.let { Paths.get(it) }
        if (projectRoot == null) {
            showAnalysisDialog("项目分析结果", "无法获取项目路径。")
            return
        }

        // 获取项目分析状态（使用正确的 API）
        val entry = ProjectMapManager.getProjectEntry(projectRoot, projectKey)

        if (entry == null) {
            showAnalysisDialog("项目分析结果", """
                项目尚未注册到分析系统。

                可能的原因：
                1. 插件刚启动，后台分析尚未开始
                2. 自动分析已禁用（可在设置中开启）
                3. LLM API Key 未配置

                请检查设置并等待后台自动分析完成。
            """.trimIndent())
            return
        }

        // 构建分析结果报告并显示弹窗
        val report = buildAnalysisReport(entry)
        showAnalysisDialog("项目分析结果 - $projectKey", report)
    }

    /**
     * 显示分析结果弹窗（自定义大小）
     */
    private fun showAnalysisDialog(title: String, message: String) {
        javax.swing.SwingUtilities.invokeLater {
            // 创建文本区域显示内容
            val textArea = javax.swing.JTextArea(message).apply {
                isEditable = false
                lineWrap = true
                wrapStyleWord = true
                font = java.awt.Font("JetBrains Mono", java.awt.Font.PLAIN, 13)
                margin = java.awt.Insets(10, 10, 10, 10)
            }

            // 放入滚动面板
            val scrollPane = javax.swing.JScrollPane(textArea).apply {
                preferredSize = java.awt.Dimension(500, 400)  // 增加高度
                verticalScrollBarPolicy = javax.swing.JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
                horizontalScrollBarPolicy = javax.swing.JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
            }

            // 创建对话框
            val dialog = javax.swing.JDialog().apply {
                setTitle(title)
                isModal = true
                contentPane.add(scrollPane, java.awt.BorderLayout.CENTER)

                // 添加关闭按钮
                val closeButton = javax.swing.JButton("关闭").apply {
                    addActionListener { dispose() }
                }
                val buttonPanel = javax.swing.JPanel().apply {
                    add(closeButton)
                }
                contentPane.add(buttonPanel, java.awt.BorderLayout.SOUTH)

                pack()
                setLocationRelativeTo(null)  // 居中显示
            }

            dialog.isVisible = true
        }
    }

    /**
     * 构建分析报告
     */
    private fun buildAnalysisReport(entry: ProjectEntry): String {
        val sb = StringBuilder()
        sb.appendLine("📊 项目分析结果")
        sb.appendLine("═════════════════════════════")
        sb.appendLine()
        sb.appendLine("**项目**: ${projectKey}")
        sb.appendLine("**路径**: ${entry.path}")
        sb.appendLine()

        // 分析状态
        sb.appendLine("📋 分析状态:")
        sb.appendLine("  • 项目结构: ${statusIcon(entry.analysisStatus.projectStructure)}")
        sb.appendLine("  • 技术栈: ${statusIcon(entry.analysisStatus.techStack)}")
        sb.appendLine("  • API 入口: ${statusIcon(entry.analysisStatus.apiEntries)}")
        sb.appendLine("  • DB 实体: ${statusIcon(entry.analysisStatus.dbEntities)}")
        sb.appendLine("  • 枚举: ${statusIcon(entry.analysisStatus.enums)}")
        sb.appendLine("  • 配置文件: ${statusIcon(entry.analysisStatus.configFiles)}")
        sb.appendLine()

        // 最后分析时间（格式化显示）
        sb.appendLine("🕐 最后分析: ${formatTimestamp(entry.lastAnalyzed)}")
        sb.appendLine()

        // 统计信息
        sb.appendLine("📈 统计:")
        val completedCount = countCompleted(entry)
        val failedCount = countFailed(entry)
        sb.appendLine("  • 已完成: $completedCount / 6 项")
        if (failedCount > 0) {
            sb.appendLine("  • 失败: $failedCount 项（将在下次循环重试）")
        }
        sb.appendLine()

        return sb.toString()
    }

    /**
     * 格式化时间戳为可读格式
     */
    private fun formatTimestamp(timestamp: Long?): String {
        if (timestamp == null || timestamp <= 0) return "尚未分析"
        return try {
            java.time.Instant.ofEpochMilli(timestamp)
                .atZone(java.time.ZoneId.systemDefault())
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
        } catch (e: Exception) {
            "时间格式错误"
        }
    }

    /**
     * 获取状态图标
     */
    private fun statusIcon(state: StepState): String {
        return when (state) {
            StepState.COMPLETED -> "✅ 已完成"
            StepState.RUNNING -> "🔄 进行中"
            StepState.PENDING -> "⏳ 待处理"
            StepState.FAILED -> "❌ 失败"
            StepState.SKIPPED -> "⏭️ 跳过"
        }
    }

    /**
     * 统计失败的分析项
     */
    private fun countFailed(entry: ProjectEntry): Int {
        var count = 0
        if (entry.analysisStatus.projectStructure == StepState.FAILED) count++
        if (entry.analysisStatus.techStack == StepState.FAILED) count++
        if (entry.analysisStatus.apiEntries == StepState.FAILED) count++
        if (entry.analysisStatus.dbEntities == StepState.FAILED) count++
        if (entry.analysisStatus.enums == StepState.FAILED) count++
        if (entry.analysisStatus.configFiles == StepState.FAILED) count++
        return count
    }

    /**
     * 统计已完成的分析项
     */
    private fun countCompleted(entry: ProjectEntry): Int {
        var count = 0
        if (entry.analysisStatus.projectStructure == StepState.COMPLETED) count++
        if (entry.analysisStatus.techStack == StepState.COMPLETED) count++
        if (entry.analysisStatus.apiEntries == StepState.COMPLETED) count++
        if (entry.analysisStatus.dbEntities == StepState.COMPLETED) count++
        if (entry.analysisStatus.enums == StepState.COMPLETED) count++
        if (entry.analysisStatus.configFiles == StepState.COMPLETED) count++
        return count
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
                        font-family: '${FontManager.getEditorFontFamily()}', monospace;
                        font-size: ${FontManager.getEditorFontSize()}px;
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
        val editorFont = FontManager.getEditorFont()

        background = colors.background
        outputArea.editorKit = com.smancode.sman.ide.renderer.MarkdownRenderer.createStyledEditorKit(colors)
        outputArea.font = editorFont
        outputArea.background = colors.background
        outputArea.foreground = colors.textPrimary

        // 强制设置 JTextPane 的默认字体到编辑器字体
        outputArea.putClientProperty("font", editorFont)

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
        logger.info("开始初始化 SmanChatPanel 组件...")

        centerPanel.isOpaque = false
        centerPanel.add(welcomePanel, "welcome")
        centerPanel.add(scrollPane, "chat")

        showWelcome()

        add(controlBar, BorderLayout.NORTH)
        add(centerPanel, BorderLayout.CENTER)

        val bottomPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            border = javax.swing.border.EmptyBorder(10, 12, 10, 12)
            add(taskProgressBar)
            add(inputArea)
        }
        add(bottomPanel, BorderLayout.SOUTH)

        logger.info("SmanChatPanel 组件初始化完成")
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
        val cardLayout = centerPanel.layout as CardLayout
        cardLayout.show(centerPanel, "welcome")
        logger.info("显示欢迎面板（未配置 API Key）")
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
        ).show(controlBar.getHistoryButton() ?: return)
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
            SettingsDialog.show(project, onAnalysisResultsCallback = { showAnalysisResults() })
        } catch (e: Exception) {
            logger.error("打开设置失败", e)
        }
    }

    // WebSocket 相关方法已移除，改为本地调用

    fun sendMessage(inputText: String? = null, codeReferences: List<com.smancode.sman.ide.components.CodeReference> = emptyList()) {
        val text = inputText ?: inputArea.text.trim()
        if (text.isEmpty() && codeReferences.isEmpty()) return

        // 检查服务初始化状态
        smanService.initializationError?.let { error ->
            // 未配置 API Key，显示欢迎面板（包含配置说明）
            showWelcomePanel()
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

        // 构建用户输入（包含代码引用上下文）
        val enhancedInput = buildUserInputWithCodeReferences(text, codeReferences)

        // 创建用户消息 Part
        val userPart = createUserPart(currentSessionId!!, enhancedInput)

        // 立即保存用户消息
        storageService.addPartToSession(currentSessionId!!, userPart)

        // UI 显示用户消息（显示原始文本，不包含代码上下文）
        val displayPart = createUserPart(currentSessionId!!, text)
        appendPartToUI(displayPart)

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
    private fun buildUserInputWithCodeReferences(userInput: String, codeReferences: List<com.smancode.sman.ide.components.CodeReference>): String {
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
        // 获取 SmanService（使用类级别的属性）

        // 创建 partPusher 回调
        val partPusher = Consumer<com.smancode.sman.model.part.Part> { part ->
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
                val assistantMessage = smanService.processMessage(sessionId, userInput, partPusher)
                logger.info("处理完成: sessionId={}, parts={}", sessionId, assistantMessage.parts.size)
            } catch (e: Exception) {
                logger.error("SmanLoop 处理失败", e)
                SwingUtilities.invokeLater {
                    appendSystemMessage("❌ 处理失败: ${e.message}")
                }
            }
        }.start()
    }

    /**
     * 将后端 Part 转换为 UI PartData
     */
    private fun convertPartToData(part: com.smancode.sman.model.part.Part): PartData {
        val commonData = CommonPartData(
            id = part.id ?: UUID.randomUUID().toString(),
            messageId = part.messageId ?: UUID.randomUUID().toString(),
            sessionId = part.sessionId ?: "",
            createdTime = part.createdTime,
            updatedTime = part.updatedTime
        )

        return when (part.type) {
            com.smancode.sman.model.part.PartType.TEXT -> {
                val textPart = part as com.smancode.sman.model.part.TextPart
                GraphModels.TextPartData(
                    id = commonData.id,
                    messageId = commonData.messageId,
                    sessionId = commonData.sessionId,
                    createdTime = commonData.createdTime,
                    updatedTime = commonData.updatedTime,
                    data = mapOf("text" to (textPart.text ?: ""))
                )
            }
            com.smancode.sman.model.part.PartType.TOOL -> {
                val toolPart = part as com.smancode.sman.model.part.ToolPart
                GraphModels.ToolPartData(
                    id = commonData.id,
                    messageId = commonData.messageId,
                    sessionId = commonData.sessionId,
                    createdTime = commonData.createdTime,
                    updatedTime = commonData.updatedTime,
                    data = buildToolPartData(toolPart)
                )
            }
            com.smancode.sman.model.part.PartType.REASONING -> {
                val reasoningPart = part as com.smancode.sman.model.part.ReasoningPart
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
    private fun buildToolPartData(toolPart: com.smancode.sman.model.part.ToolPart): Map<String, Any> {
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
        // 使用编辑器字体设置
        val html = if (outputArea.text.contains("<body>")) {
            // 已有 HTML 内容，在 </body> 前插入
            val currentHtml = outputArea.text
            currentHtml.replace("</body>", "<div style='color:$colorHex; font-family: \"${FontManager.getEditorFontFamily()}\", monospace; font-size: ${FontManager.getEditorFontSize()}px; margin: 4px 0;'>$htmlText</div></body>")
        } else {
            // 空 HTML，初始化
            "<html><body><div style='color:$colorHex; font-family: \"${FontManager.getEditorFontFamily()}\", monospace; font-size: ${FontManager.getEditorFontSize()}px; margin: 4px 0;'>$htmlText</div></body></html>"
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
     * 显示代码引用提示
     */
    private fun showCodeReferenceHint() {
        appendSystemMessage("""
            💡 提示：在编辑器中选中代码后，按 Ctrl+L (macOS: Cmd+L) 即可将代码引用插入到输入框。
        """.trimIndent())
    }

    /**
     * 设置代码引用回调
     */
    private fun setupCodeReferenceCallback() {
        smanService.onCodeReferenceCallback = { codeReference ->
            inputArea.insertCodeReference(codeReference)
        }
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

    /**
     * 触发项目分析（使用 java-scanner 元 Skill）
     *
     * 通过 LLM 执行 java-scanner skills，生成项目专属 Skill 文件
     */
    private fun triggerProjectAnalysis() {
        logger.info("触发项目分析")

        // 检查服务初始化状态
        smanService.initializationError?.let { error ->
            appendSystemMessage("❌ 请先配置 LLM API Key")
            showWelcomePanel()
            return
        }

        // 确保有 sessionId
        if (currentSessionId == null) {
            currentSessionId = SessionIdGenerator.generate()
            storageService.setCurrentSessionId(currentSessionId)
            storageService.createOrGetSession(currentSessionId!!, projectKey)
            logger.info("创建新会话: sessionId={}", currentSessionId)
        }

        showChat()

        // 显示提示消息
        appendSystemMessage("""
            🔍 开始项目分析

            将使用内置的 java-scanner skills 分析项目：
            1. 项目架构扫描
            2. API 接口扫描
            3. 数据实体扫描
            4. 枚举类扫描
            5. 配置文件扫描
            6. 外调接口扫描
            7. 公共类扫描

            分析结果将保存为项目专属 Skill 文件，后续对话可直接使用。
        """.trimIndent())

        // 构建分析提示词
        val analysisPrompt = """
请帮我分析这个 Java 项目，使用以下 skills：

1. 首先加载 java-arch-scanner skill 分析项目架构
2. 然后加载 java-api-scanner skill 扫描 API 接口
3. 加载 java-entity-scanner skill 扫描数据实体
4. 加载 java-enum-scanner skill 扫描枚举类
5. 加载 java-config-scanner skill 扫描配置文件
6. 加载 java-external-call-scanner skill 扫描外调接口
7. 加载 java-common-class-scanner skill 扫描公共类

每个 skill 扫描完成后，将结果保存到 `.sman/skills/` 目录下对应的项目 Skill 文件中。

请按顺序执行，每个 skill 分批处理以避免 token 超限。
        """.trimIndent()

        // 复用现有的消息处理逻辑
        processWithAgentLoop(currentSessionId!!, analysisPrompt)
    }

    fun dispose() {
        // 保存当前会话
        saveCurrentSessionIfNeeded()
    }
}
