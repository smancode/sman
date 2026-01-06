package ai.smancode.sman.ide.ui.components

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.IconLoader
import com.intellij.util.IconUtil
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import ai.smancode.sman.ide.ui.ChatColors
import ai.smancode.sman.ide.ui.layout.MessageWrapper
import java.awt.*
import javax.swing.*
import javax.swing.border.EmptyBorder

class ChatHistoryPanel(private val project: Project? = null) : JPanel(BorderLayout()) {
    
    private val cardLayout = CardLayout()
    private val contentPanel = JPanel(cardLayout)
    
    // 核心：实现 Scrollable 的内部容器
    private class ScrollablePanel : JPanel(), Scrollable {
        init {
            isOpaque = true
            background = ChatColors.background
        }
        
        override fun getPreferredScrollableViewportSize(): Dimension = preferredSize

        override fun getScrollableUnitIncrement(visibleRect: Rectangle?, orientation: Int, direction: Int): Int = JBUI.scale(20)

        override fun getScrollableBlockIncrement(visibleRect: Rectangle?, orientation: Int, direction: Int): Int = JBUI.scale(60)

        // 关键：强制宽度跟随视口，解决拖窄消失问题
        override fun getScrollableTracksViewportWidth(): Boolean = true

        // 高度不跟随，由内容决定
        override fun getScrollableTracksViewportHeight(): Boolean = false
    }
    
    // 欢迎页面
    private class WelcomePanel : JPanel(GridBagLayout()) {
        init {
            isOpaque = true
            background = ChatColors.background
            
            val gbc = GridBagConstraints()
            gbc.gridx = 0
            gbc.gridy = 0
            gbc.anchor = GridBagConstraints.CENTER
            gbc.insets = JBUI.insetsBottom(16)
            
            // Logo (去色)
            try {
                // 尝试加载图标，如果失败则忽略
                // 优先使用 ToolWindow 图标，因为它通常更适合 UI 显示，且路径更稳定
                var originalIcon = IconLoader.findIcon("/META-INF/pluginIconToolWindow.svg", ChatHistoryPanel::class.java)
                
                if (originalIcon == null) {
                    originalIcon = IconLoader.findIcon("/META-INF/pluginIcon.svg", ChatHistoryPanel::class.java)
                }
                
                if (originalIcon != null) {
                    // 获取去色/禁用状态图标
                    val grayIcon = IconLoader.getDisabledIcon(originalIcon)
                    
                    // 放大图标 (4倍，确保比标题大)
                    val scaledIcon = IconUtil.scale(grayIcon, null, 4.0f)
                    
                    add(JLabel(scaledIcon), gbc)
                }
            } catch (e: Exception) {
                // 图标加载失败，不显示
                e.printStackTrace()
            }
            
            // Title
            gbc.gridy++
            gbc.insets = JBUI.insetsBottom(8)
            val titleLabel = JLabel("SiliconMan").apply {
                font = font.deriveFont(Font.BOLD, JBUI.scale(24f))
                // 颜色调整：比纯白/纯黑淡一点，但比 Secondary (灰色) 亮/显著一点
                foreground = JBColor(Color(0x505050), Color(0xBBBBBB))
            }
            add(titleLabel, gbc)
            
            // Description
            gbc.gridy++
            // 底部增加较大 Padding，利用 GridBagLayout 的居中特性，使视觉重心上移
            // 增加约 160px 的底部空间，使内容整体上移约 80px (4行左右)
            gbc.insets = JBUI.insetsBottom(160)
            val descLabel = JLabel("SiliconMan-硅基人 AI助手插件，与后端agent通讯，帮助用户分析需求、实施编码。").apply {
                font = font.deriveFont(JBUI.scale(13f))
                foreground = ChatColors.textSecondary
            }
            add(descLabel, gbc)
        }
    }

    private val messageGridPanel = JPanel(GridBagLayout()).apply {
        isOpaque = false
    }
    private var nextGridY = 0
    
    private val userMessageWrappers = mutableListOf<MessageWrapper>()
    private val aiMessageWrappers = mutableListOf<MessageWrapper>()
    
    // 🆕 记录当前 loading wrapper，用于更新 TODO list
    private var currentLoadingWrapper: MessageWrapper? = null

    private val chatContainer = ScrollablePanel().apply {
        layout = BorderLayout()
        border = EmptyBorder(0, 0, 0, 0)
        add(messageGridPanel, BorderLayout.NORTH)
    }
    
    private val scrollPane = JBScrollPane(chatContainer).apply {
        verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
        horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        // 设置圆角边框，稍微内缩一点
        border = BorderFactory.createEmptyBorder() // 先清空默认
        viewport.background = ChatColors.background
        viewport.isOpaque = true
    }
    
    init {
        background = ChatColors.background
        
        scrollPane.border = object : javax.swing.border.AbstractBorder() {
            override fun paintBorder(c: Component, g: Graphics, x: Int, y: Int, width: Int, height: Int) {
                val g2 = g as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = ChatColors.divider
                // 绘制圆角边框
                g2.drawRoundRect(x, y, width - 1, height - 1, JBUI.scale(12), JBUI.scale(12))
            }
        }
        
        contentPanel.add(WelcomePanel(), "WELCOME")
        contentPanel.add(scrollPane, "CHAT")
        
        add(contentPanel, BorderLayout.CENTER)
        
        // 初始显示欢迎页
        showWelcome()
    }
    
    private fun showWelcome() {
        cardLayout.show(contentPanel, "WELCOME")
    }
    
    private fun showChat() {
        cardLayout.show(contentPanel, "CHAT")
    }
    
    private fun addMessageComponent(comp: Component) {
        val gbc = GridBagConstraints()
        gbc.gridx = 0
        gbc.gridy = nextGridY++
        gbc.weightx = 1.0
        gbc.fill = GridBagConstraints.HORIZONTAL
        gbc.anchor = GridBagConstraints.NORTHWEST
        messageGridPanel.add(comp, gbc)
        
        // Add spacing
        val spacer = Box.createVerticalStrut(JBUI.scale(16))
        val gbcSpacer = GridBagConstraints()
        gbcSpacer.gridx = 0
        gbcSpacer.gridy = nextGridY++
        gbcSpacer.weightx = 1.0
        gbcSpacer.fill = GridBagConstraints.HORIZONTAL
        messageGridPanel.add(spacer, gbcSpacer)
    }
    
    fun addUserMessage(wrapper: MessageWrapper) {
        userMessageWrappers.add(wrapper)
        SwingUtilities.invokeLater {
            showChat()
            addMessageComponent(wrapper)
            configureNavigation(wrapper, userMessageWrappers)
            updateLayoutAndScroll()
        }
    }
    
    fun addAssistantMessage(wrapper: MessageWrapper) {
        aiMessageWrappers.add(wrapper)
        SwingUtilities.invokeLater {
            showChat()
            addMessageComponent(wrapper)
            configureNavigation(wrapper, aiMessageWrappers)
            updateLayoutAndScroll()
        }
    }
    
    fun addLoadingMessage() {
        SwingUtilities.invokeLater {
            showChat()
            // 修复：传递 project 参数，确保后续生成的链接可以跳转
            val bubble = MessageBubble("正在思考...", false, isLoading = true, project = project)
            // Loading 消息也通过 Wrapper 包装，这里选择居中或靠左均可，这里复用普通样式
            val wrapper = MessageWrapper(bubble)
            wrapper.name = "loading"
            
            // 🆕 记录当前 loading wrapper
            currentLoadingWrapper = wrapper
            
            addMessageComponent(wrapper)
            updateLayoutAndScroll()
        }
    }
    
    fun updateLoadingMessage(text: String) {
        SwingUtilities.invokeLater {
            // 找到最后一个 loading 状态的 wrapper
            // 注意：addMessageComponent 会在组件后添加 Spacer，所以倒序查找
            val components = messageGridPanel.components
            var loadingWrapper: MessageWrapper? = null

            for (i in components.indices.reversed()) {
                val comp = components[i]
                if (comp is MessageWrapper && comp.name == "loading") {
                    loadingWrapper = comp
                    break
                }
            }

            if (loadingWrapper != null) {
                val bubble = loadingWrapper.bubble
                bubble?.appendThinking(text)
                // 强制刷新布局，因为气泡大小可能变了
                // 必须调用 revalidate() 触发重新布局
                messageGridPanel.revalidate()
                messageGridPanel.repaint()
            }
        }
    }

    /**
     * 🆕 流式更新 Markdown 内容
     * @param content Markdown 内容
     */
    fun updateStreamingContent(content: String) {
        SwingUtilities.invokeLater {
            // 找到最后一个 loading 状态的 wrapper
            val components = messageGridPanel.components
            var loadingWrapper: MessageWrapper? = null

            for (i in components.indices.reversed()) {
                val comp = components[i]
                if (comp is MessageWrapper && comp.name == "loading") {
                    loadingWrapper = comp
                    break
                }
            }

            if (loadingWrapper != null) {
                val bubble = loadingWrapper.bubble
                // 实时更新 Markdown 内容
                bubble?.updateStreamingMarkdown(content)
                // 强制刷新布局
                messageGridPanel.revalidate()
                messageGridPanel.repaint()
                scrollToBottom()
            }
        }
    }

    /**
     * 🆕 更新 TODO List（显示在 loading wrapper 内部，bubble 下方）
     */
    fun updateTodoList(todos: List<TodoListPanel.TodoData>) {
        SwingUtilities.invokeLater {
            // 直接更新 loading wrapper 内的 TODO list
            currentLoadingWrapper?.updateTodoList(todos)
            
            messageGridPanel.revalidate()
            messageGridPanel.repaint()
            scrollToBottom()
        }
    }
    
    /**
     * 🆕 移除 TODO List
     */
    private fun removeTodoList() {
        currentLoadingWrapper?.removeTodoList()
    }
    
    fun finishLoadingMessage(result: String, process: String = "", onTypingComplete: (() -> Unit)? = null) {
        SwingUtilities.invokeLater {
            // 找到最后一个 loading 状态的 wrapper
            val components = messageGridPanel.components
            var loadingWrapper: MessageWrapper? = null

            for (i in components.indices.reversed()) {
                val comp = components[i]
                if (comp is MessageWrapper && comp.name == "loading") {
                    loadingWrapper = comp
                    break
                }
            }

            if (loadingWrapper != null) {
                val bubble = loadingWrapper.bubble
                // 1. 结束 Thinking 状态并显示结果
                // 🔥 禁用打字机动画，直接显示全量内容（避免状态混乱）
                // 🔥 传递 process 参数（修复之前传空字符串的问题）
                bubble?.finishThinking(result, process, animate = false, onTypingComplete = onTypingComplete)

                // 2. 标记为非 loading 状态
                loadingWrapper.name = null

                // 3. 🔥 隐藏 TODO list（用户要求隐藏）
                removeTodoList()

                // 4. 清理 loading wrapper 引用（但 TODO list 仍在 wrapper 中）
                currentLoadingWrapper = null

                // 5. 将其纳入 AI 消息列表管理 (支持导航)
                aiMessageWrappers.add(loadingWrapper)
                configureNavigation(loadingWrapper, aiMessageWrappers)

                // 6. 刷新布局
                messageGridPanel.revalidate()
                messageGridPanel.repaint()
                scrollToBottom()
            } else {
                // Fallback: 如果没找到 loading 消息，直接新增一条
                val bubble = MessageBubble(result, isUser = false, project = project, animate = true)
                val wrapper = MessageWrapper(bubble)
                addAssistantMessage(wrapper)

                // 手动触发回调
                onTypingComplete?.invoke()
            }
        }
    }

    // 停止当前正在生成的打字机效果
    fun stopGenerating() {
        SwingUtilities.invokeLater {
            // 1. 检查 loading 消息
            val components = messageGridPanel.components
            for (i in components.indices.reversed()) {
                val comp = components[i]
                if (comp is MessageWrapper) {
                    // 不管是 loading 还是已经变成 assistant 的消息，只要它是最后一个，我们就尝试停止它
                    // 实际上，stopGenerating 应该只针对最近的一条 AI 消息
                    if (comp.name == "loading" || aiMessageWrappers.lastOrNull() == comp) {
                        // 修复：直接调用 stopTypingAndRenderFull，不要先调用 stopTyping (因为它会清空 timer 导致后续无法操作)
                        comp.bubble?.stopTypingAndRenderFull()
                        return@invokeLater
                    }
                }
            }
        }
    }

    fun removeLoadingMessage() {
        SwingUtilities.invokeLater {
            val toRemove = mutableListOf<Component>()
            val components = messageGridPanel.components
            for (i in components.indices) {
                val comp = components[i]
                if (comp.name == "loading") {
                    toRemove.add(comp)
                    // 尝试移除紧随其后的 Strut (Spacer)
                    // 在 GridBagLayout 中，我们通常按顺序添加，所以下一个组件大概率是 Spacer
                    // 但为了安全，最好通过 GridBagLayout 检查或者简单地假设下一个就是 Spacer
                    if (i + 1 < components.size) {
                        val next = components[i + 1]
                        if (next is Box.Filler) {
                            toRemove.add(next)
                        }
                    }
                }
            }
            toRemove.forEach { messageGridPanel.remove(it) }
            
            if (toRemove.isNotEmpty()) {
                messageGridPanel.revalidate()
                messageGridPanel.repaint()
            }
        }
    }
    
    fun clearAllMessages() {
        userMessageWrappers.clear()
        aiMessageWrappers.clear()
        SwingUtilities.invokeLater {
            messageGridPanel.removeAll()
            nextGridY = 0
            messageGridPanel.revalidate()
            messageGridPanel.repaint()
            showWelcome()
        }
    }
    
    /**
     * 🔧 强制重新布局所有消息气泡
     * 解决历史消息恢复时换行不正确的问题
     */
    fun forceRelayoutAllMessages() {
        SwingUtilities.invokeLater {
            // 递归 invalidate 所有组件，强制重新计算布局
            fun invalidateDeep(comp: java.awt.Component) {
                comp.invalidate()
                if (comp is java.awt.Container) {
                    for (child in comp.components) {
                        invalidateDeep(child)
                    }
                }
            }
            
            // 对所有消息气泡强制 invalidate
            (userMessageWrappers + aiMessageWrappers).forEach { wrapper ->
                invalidateDeep(wrapper)
            }
            
            // 触发重新验证和重绘
            messageGridPanel.revalidate()
            messageGridPanel.repaint()
        }
    }
    
    fun addSessionDivider() {
        SwingUtilities.invokeLater {
            showChat()
            val divider = SessionDivider("新的对话")
            
            val gbc = GridBagConstraints()
            gbc.gridx = 0
            gbc.gridy = nextGridY++
            gbc.weightx = 1.0
            gbc.fill = GridBagConstraints.HORIZONTAL // 允许横向铺满以居中绘制
            gbc.anchor = GridBagConstraints.CENTER
            messageGridPanel.add(divider, gbc)
            
            // Add spacing
            val spacer = Box.createVerticalStrut(JBUI.scale(8))
            val gbcSpacer = GridBagConstraints()
            gbcSpacer.gridx = 0
            gbcSpacer.gridy = nextGridY++
            messageGridPanel.add(spacer, gbcSpacer)
            
            updateLayoutAndScroll()
        }
    }
    
    private var stickyListener: java.awt.event.AdjustmentListener? = null

    fun scrollToBottom() {
        val vertical = scrollPane.verticalScrollBar
        
        // 1. 移除旧的监听器，避免叠加
        stickyListener?.let { vertical.removeAdjustmentListener(it) }
        
        // 2. 创建新的监听器：只要滚动条范围或数值发生变化，就强制滚到底部
        // 这能有效应对 MessageBubble 高度动态变化（如代码块展开）的情况
        val listener = object : java.awt.event.AdjustmentListener {
            override fun adjustmentValueChanged(e: java.awt.event.AdjustmentEvent) {
                val scrollBar = e.source as JScrollBar
                // 只有当不在拖动滑块时才强制滚动
                if (!scrollBar.valueIsAdjusting) {
                    scrollBar.value = scrollBar.maximum
                }
            }
        }
        
        stickyListener = listener
        vertical.addAdjustmentListener(listener)
        
        // 3. 设置超时自动移除，防止用户无法向上滚动
        // 给予 800ms 的“粘滞期”，足以覆盖布局调整和渲染延迟
        Timer(800) {
            if (stickyListener == listener) {
                vertical.removeAdjustmentListener(listener)
                stickyListener = null
            }
        }.apply { isRepeats = false; start() }
        
        // 4. 立即触发一次滚动，应对无布局变化的情况
        SwingUtilities.invokeLater {
             vertical.value = vertical.maximum
        }
    }

    private fun updateLayoutAndScroll() {
        chatContainer.revalidate()
        chatContainer.repaint()
        scrollToBottom()
    }

    private fun configureNavigation(wrapper: MessageWrapper, list: MutableList<MessageWrapper>) {
        val index = list.indexOf(wrapper)
        if (index == -1) return

        // 设置当前气泡的回调
        wrapper.bubble?.setNavCallbacks(
            onUp = {
                if (index > 0) scrollToWrapper(list[index - 1])
            },
            onDown = {
                if (index < list.size - 1) scrollToWrapper(list[index + 1])
            }
        )
        
        // 更新当前气泡的状态
        updateBubbleNavState(wrapper, index, list.size)
        
        // 如果有前一个气泡，更新它的状态（因为现在有了下一个）
        if (index > 0) {
            val prev = list[index - 1]
            updateBubbleNavState(prev, index - 1, list.size)
        }
    }

    private fun updateBubbleNavState(wrapper: MessageWrapper, index: Int, totalSize: Int) {
        val canUp = index > 0
        val canDown = index < totalSize - 1
        wrapper.bubble?.setNavigationState(canUp, canDown)
    }

    private fun scrollToWrapper(wrapper: MessageWrapper) {
        SwingUtilities.invokeLater {
            // 确保组件已布局
            if (wrapper.parent == null || wrapper.y == 0) {
                chatContainer.validate()
            }
            
            val y = wrapper.y
            val viewportHeight = scrollPane.viewport.height
            val contentHeight = chatContainer.height
            
            // 目标 Y 坐标：尽量让 wrapper 位于视口顶部
            val maxY = contentHeight - viewportHeight
            val targetY = y.coerceIn(0, maxOf(0, maxY))
            
            scrollPane.viewport.viewPosition = Point(0, targetY)
        }
    }

    // 自定义分隔线组件
    private class SessionDivider(private val text: String) : JPanel() {
        init {
            isOpaque = false
            // 增加高度，利用留白增强分割感
            preferredSize = Dimension(0, JBUI.scale(40))
            alignmentX = Component.CENTER_ALIGNMENT
        }

        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            val g2 = g as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            
            val fm = g2.fontMetrics
            val textWidth = fm.stringWidth(text)
            // 使用 ascent + descent 确保高度计算准确
            val textHeight = fm.ascent + fm.descent
            
            // 胶囊背景的内边距
            val hPad = JBUI.scale(12)
            val vPad = JBUI.scale(4)
            
            val boxWidth = textWidth + hPad * 2
            val boxHeight = textHeight + vPad
            
            val x = (width - boxWidth) / 2
            val y = (height - boxHeight) / 2
            
            // === 增强分割感：绘制左右线条 ===
            g2.color = ChatColors.divider
            val lineY = y + boxHeight / 2
            val lineGap = JBUI.scale(8) // 线条与胶囊的距离
            
            // 左线
            g2.drawLine(JBUI.scale(20), lineY, x - lineGap, lineY)
            // 右线
            g2.drawLine(x + boxWidth + lineGap, lineY, width - JBUI.scale(20), lineY)
            
            // 1. 绘制胶囊背景 (使用分割线颜色作为底色)
            g2.color = ChatColors.divider
            // 绘制全圆角矩形 (胶囊)
            g2.fillRoundRect(x, y, boxWidth, boxHeight, boxHeight, boxHeight)
            
            // 2. 绘制文字
            g2.color = ChatColors.textSecondary
            // 稍微调小字号
            g2.font = font.deriveFont(JBUI.scale(11f))
            
            val textX = (width - textWidth) / 2
            // 垂直居中对齐文字
            val textY = y + ((boxHeight - textHeight) / 2) + fm.ascent
            g2.drawString(text, textX, textY)
        }
    }
}
