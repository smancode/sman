package ai.smancode.sman.ide.ui.components

import com.intellij.openapi.editor.colors.EditorColors
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.event.EditorMouseAdapter
import com.intellij.openapi.editor.event.EditorMouseListener
import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.markup.EffectType
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.ui.EditorTextField
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import ai.smancode.sman.ide.service.PsiNavigationHelper
import ai.smancode.sman.ide.ui.ChatColors
import ai.smancode.sman.ide.ui.ChatStyles
import java.awt.datatransfer.StringSelection
import javax.swing.event.HyperlinkEvent
import com.vladsch.flexmark.ast.FencedCodeBlock
import com.vladsch.flexmark.ext.autolink.AutolinkExtension
import com.vladsch.flexmark.ext.gfm.strikethrough.StrikethroughExtension
import com.vladsch.flexmark.ext.tables.TableBlock
import com.vladsch.flexmark.ext.tables.TablesExtension
import com.vladsch.flexmark.html.HtmlRenderer
import com.vladsch.flexmark.parser.Parser
import com.vladsch.flexmark.util.data.MutableDataSet
import java.awt.*
import java.awt.geom.GeneralPath
import java.net.URL
import java.util.Base64
import javax.imageio.ImageIO
import javax.swing.*
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.openapi.ui.DialogWrapper
import java.awt.event.MouseWheelEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import com.intellij.openapi.util.Disposer
import javax.swing.border.EmptyBorder
import com.intellij.ui.components.JBScrollBar
import com.intellij.ui.components.JBScrollPane
import javax.swing.text.View
import javax.swing.text.html.HTMLEditorKit

import com.intellij.openapi.util.Key
import com.intellij.openapi.editor.markup.RangeHighlighter
import java.util.concurrent.ConcurrentLinkedQueue

import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.event.DocumentEvent
import java.text.DecimalFormat

import ai.smancode.sman.ide.service.ProjectStorageService

class MessageBubble(
    private val text: String,
    val isUser: Boolean,
    hasActions: Boolean = false,
    private val isLoading: Boolean = false, // 修改为 property
    private val project: Project? = null,
    private val animate: Boolean = false,
    private val initialThinkingText: String? = null,
    private val initialThinkingDuration: Long? = null,
    private val initialProcess: String? = null  // 🔥 初始分析过程
) : JPanel() {
    
    private val cornerRadius = JBUI.scale(16)
    private val bubbleColor = if (isUser) ChatColors.userBubble else ChatColors.assistantBubble
    
    private var navUpBtn: NavButton? = null
    private var navDownBtn: NavButton? = null
    private var onNavUp: (() -> Unit)? = null
    private var onNavDown: (() -> Unit)? = null
    
    // 保存导航状态，确保重绘时能恢复
    private var canNavUp: Boolean = false
    private var canNavDown: Boolean = false

    fun setNavCallbacks(onUp: () -> Unit, onDown: () -> Unit) {
        this.onNavUp = onUp
        this.onNavDown = onDown
    }

    fun setNavigationState(canUp: Boolean, canDown: Boolean) {
        this.canNavUp = canUp
        this.canNavDown = canDown
        
        navUpBtn?.isEnabled = canUp
        navDownBtn?.isEnabled = canDown
        // navUpBtn?.isVisible = canUp // 始终可见，通过 isEnabled 控制绘制，以维持布局占位
        // navDownBtn?.isVisible = canDown 
        
        // 更新 Tooltip，如果禁用则不显示
        navUpBtn?.toolTipText = if (canUp) "上一个" else null
        navDownBtn?.toolTipText = if (canDown) "下一个" else null
        
        navUpBtn?.repaint()
        navDownBtn?.repaint()
    }
    
    // 内部容器，用于垂直堆叠内容
    private val bubblePanel = object : JPanel() {
        private var isBubbleHovered = false

        init {
            // 改为 BoxLayout Y_AXIS 以垂直堆叠 Thinking 和 Content
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            
            // 智能动态内边距：基于字体高度计算，而非硬编码像素
            // 1. 获取基础字体度量 (使用 UIUtil.getLabelFont() 以保持一致性)
            val font = UIUtil.getLabelFont()
            val metrics = getFontMetrics(font)
            val fontHeight = metrics.height
            
            // 2. 计算垂直内边距 (Vertical Padding)
            // - 对于有背景色的气泡 (User)，我们需要更多的呼吸空间，且需避开圆角区域
            // - 策略：取 (字体高度的 50%) 与 (圆角半径的 30%) 中的较大值，并额外增加 4px 以修复汉字截断
            //   假设 12pt 字体 (~16px height) -> 8px padding
            //   假设 16px cornerRadius -> ~5px padding
            val vPadding = if (isUser) {
                maxOf(fontHeight / 2, (cornerRadius * 0.3).toInt()) + JBUI.scale(4)
            } else {
                // AI 气泡无背景框，保持紧凑 (字体高度的 25%)
                fontHeight / 4
            }
            
            // 3. 计算水平内边距 (Horizontal Padding)
            // - 通常水平方向比垂直方向宽一点更美观 (0.75em ~ 1.0em)
            // - 对于 AI 气泡，由于外部 Wrapper 已经设置了 25px 边距，且 AI 气泡无背景框，内部不再额外添加水平内边距，以对齐视觉边缘
            val hPadding = if (isUser) (fontHeight * 0.75).toInt() else 0
            
            border = EmptyBorder(vPadding, hPadding, vPadding, hPadding)

            // 添加鼠标悬浮监听
            if (isUser) {
                val panel = this
                val hoverListener = object : MouseAdapter() {
                    override fun mouseEntered(e: MouseEvent) {
                        isBubbleHovered = true
                        repaint()
                    }

                    override fun mouseExited(e: MouseEvent) {
                        // 检查鼠标是否真的离开了整个 bubblePanel 区域
                        // 转换为屏幕坐标进行判断，或者使用 convertPoint
                        val point = SwingUtilities.convertPoint(e.component, e.point, panel)
                        if (!panel.contains(point)) {
                            isBubbleHovered = false
                            repaint()
                        }
                    }
                }
                addMouseListener(hoverListener)
                
                // 关键：递归地为所有子组件添加相同的监听器，以支持事件冒泡/传播的效果
                // 这样即使鼠标移动到子组件(如JEditorPane)上，也能保持 hover 状态
                SwingUtilities.invokeLater {
                    addRecursiveMouseListener(this, hoverListener)
                }
            }
        }

        private fun addRecursiveMouseListener(comp: Component, listener: MouseAdapter) {
            if (comp is Container) {
                comp.components.forEach { child ->
                    child.addMouseListener(listener)
                    addRecursiveMouseListener(child, listener)
                }
            }
        }
        
        override fun paintComponent(g: Graphics) {
            // 只有用户消息才显示气泡背景
            if (isUser) {
                val g2 = g as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                
                // 悬浮时显示淡淡的背景色
                if (isBubbleHovered) {
                    g2.color = ChatColors.userBubbleHover
                    g2.fillRoundRect(0, 0, width - 1, height - 1, cornerRadius, cornerRadius)
                }

                // 绘制边框 (减1防止裁剪)
                g2.color = ChatColors.userBubbleBorder
                val oldStroke = g2.stroke
                g2.stroke = BasicStroke(1.5f)
                g2.drawRoundRect(0, 0, width - 1, height - 1, cornerRadius, cornerRadius)
                g2.stroke = oldStroke
            }
            
            super.paintComponent(g)
        }
    }
    
    // 用于 Thinking 状态的组件
    private var thinkingTimer: javax.swing.Timer? = null
    private var thinkingPanel: JTextArea? = null
    private var thinkingScrollPane: JBScrollPane? = null
    private var thinkingWrapper: JPanel? = null
    private var todoListWrapper: JPanel? = null  // 🆕 TODO List 容器
    private var embeddedTodoListPanel: TodoListPanel? = null  // 🆕 内嵌的 TODO List
    private var contentWrapper: JPanel? = null

    // 🔥 用于分析过程（Process）的组件
    private var processWrapper: JPanel? = null
    private var processScrollPane: JBScrollPane? = null
    private var processToggleIcon: JComponent? = null
    private var isProcessCollapsed = true  // 默认折叠（显示"> 分析过程"）

    // 🔥 全局操作按钮（控制整个回复）
    private var globalActionPanel: JPanel? = null
    private var globalCollapseButton: CollapseButton? = null
    private var processContent: String = ""  // 🔥 存储分析过程内容，用于复制

    // 🔥 标记是否在loading状态（用于控制按钮显示）
    private var wasLoading: Boolean = false

    // 🔥 保存最终的回复内容（用于复制）
    private var finalContent: String = ""
    // 悬停交互相关 - 移除旧的边框逻辑，保留必要的颜色定义用于绘制线条
    // 使用更淡的颜色，增加透明度
    private val thinkingLineColor = JBColor(Color(0, 0, 0, 30), Color(255, 255, 255, 30))
    private val pendingThinkingText = StringBuilder()
    private var hasThinkingContent = false // 明确的标志位，用于判断是否需要换行
    private var thinkingFinishCallback: (() -> Unit)? = null // 思考结束后的回调，用于平滑过渡
    
    // 主内容打字机效果
    private var mainTypingTimer: javax.swing.Timer? = null
    private val mainDisplayedText = StringBuilder()
    
    // 标题动画 Timer
    private var titleTimer: javax.swing.Timer? = null
    private var titleLabel: JLabel? = null

    private var isThinkingCollapsed = false
    private var thinkingToggleIcon: JComponent? = null
    private var isThinkingHover = false // 新增：控制滚动条绘制状态
    private var isThinkingFinished = false // 新增：标记思考是否完成

    // Thinking 计时器相关
    private var thinkingDurationTimer: javax.swing.Timer? = null
    private var thinkingStartTime: Long = 0L
    private var timeLabel: JLabel? = null
    
    init {
        layout = BorderLayout()
        isOpaque = false
        background = Color(0, 0, 0, 0)
        
        // 初始化分层容器
        // 重写 paintComponent 以绘制左侧引导线
        thinkingWrapper = object : JPanel(BorderLayout()) {
            override fun paintComponent(g: Graphics) {
                super.paintComponent(g)
                
                // 只有在展开状态、箭头存在、且思考已完成时才绘制线条
                if (!isThinkingCollapsed && thinkingToggleIcon != null && thinkingToggleIcon!!.isShowing && isThinkingFinished) {
                    val g2 = g as Graphics2D
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                    g2.color = thinkingLineColor
                    // 线条宽度变细，1.0f
                    g2.stroke = BasicStroke(1.0f)
                    
                    // 计算线条位置
                    // 1. 获取箭头图标相对于 thinkingWrapper 的位置
                    val iconBounds = SwingUtilities.convertRectangle(thinkingToggleIcon!!.parent, thinkingToggleIcon!!.bounds, this)
                    
                    // X轴：箭头中心
                    val lineX = iconBounds.centerX
                    
                    // Y轴起点：箭头底部再往下一点，不要挨着
                    val startY = (iconBounds.y + iconBounds.height + JBUI.scale(2)).toDouble() 
                    
                    // Y轴终点：跟右侧文字高度一致
                    // 获取 thinkingScrollPane 的高度，或者 header 底部 + scrollPane 高度
                    // headerPanel 高度
                    val headerHeight = iconBounds.y + iconBounds.height + JBUI.scale(7) // headerPanel 的 bottom border 是 7
                    
                    // ScrollPane 的实际高度（可能被 max height 限制）
                    val scrollHeight = thinkingScrollPane?.height ?: 0
                    
                    // 线条终点 = Header 底部 + ScrollPane 高度 - 底部留白
                    val endY = (headerHeight + scrollHeight - JBUI.scale(4)).toDouble()
                    
                    // 只有当有足够高度时才绘制
                    if (endY > startY) {
                        val lineShape = java.awt.geom.Line2D.Double(lineX, startY, lineX, endY)
                        g2.draw(lineShape)
                    }
                }
            }
        }.apply {
            isOpaque = false
            isVisible = false // 默认隐藏
            // 给 Thinking 区域底部加一点间距，与 TODO List 隔开（1行空行）
            border = EmptyBorder(0, 0, JBUI.scale(12), 0)
            // 确保宽度铺满
            maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
            alignmentX = Component.LEFT_ALIGNMENT
        }
        
        contentWrapper = JPanel(BorderLayout()).apply { 
            isOpaque = false 
            maximumSize = Dimension(Int.MAX_VALUE, Int.MAX_VALUE)
            alignmentX = Component.LEFT_ALIGNMENT
        }
        
        // 🆕 TODO List Wrapper - 放在 Thinking 和 Content 之间
        todoListWrapper = JPanel(BorderLayout()).apply {
            isOpaque = false
            isVisible = false // 初始隐藏
            alignmentX = Component.LEFT_ALIGNMENT
            // 上下留一点间距（顶部0，底部8）
            border = EmptyBorder(0, 0, JBUI.scale(8), 0)
        }

        // 🔥 分析过程 Wrapper - 放在 Content 之后，层级同 Thinking
        processWrapper = object : JPanel(BorderLayout()) {
            override fun paintComponent(g: Graphics) {
                super.paintComponent(g)
                // 可以在这里绘制左侧引导线（类似 Thought）
            }

            override fun getPreferredSize(): Dimension {
                // 🔥🔥🔥 关键修复：基于 headerPanel 和 processScrollPane 计算 preferredSize
                val headerComp = if (componentCount > 0) getComponent(0) else null
                val headerHeight = if (headerComp != null) {
                    headerComp.preferredSize.height
                } else {
                    JBUI.scale(30)
                }

                // 🔥 获取 CENTER 组件（processScrollPane）的高度
                val centerComp = if (componentCount > 1) getComponent(1) else null
                val centerHeight = if (centerComp != null && centerComp.isVisible) {
                    // 如果 CENTER 可见，使用它的 preferredSize
                    centerComp.preferredSize.height
                } else {
                    // 如果 CENTER 不可见（折叠状态），高度为 0
                    0
                }

                // border 高度
                val borderInsets = border?.getBorderInsets(this) ?: EmptyBorder(0,0,0,0).getBorderInsets(this)
                val totalHeight = headerHeight + centerHeight + borderInsets.top + borderInsets.bottom

                // 宽度跟随父容器
                val width = if (parent != null && parent.width > 0) parent.width else 516

                return Dimension(width, totalHeight)
            }
        }.apply {
            isOpaque = false
            isVisible = false // 默认隐藏
            border = EmptyBorder(JBUI.scale(8), 0, JBUI.scale(12), 0)
            maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
            alignmentX = Component.LEFT_ALIGNMENT
        }

        bubblePanel.add(thinkingWrapper)
        bubblePanel.add(todoListWrapper)  // 🆕 在 Thinking 和 Content 之间
        bubblePanel.add(contentWrapper)
        bubblePanel.add(processWrapper)  // 🔥 在 Content 之后

        // 🔥 创建并保存全局操作按钮面板
        globalActionPanel = createGlobalActionPanel()
        bubblePanel.add(globalActionPanel)  // 🔥 全局操作按钮，控制整个回复

        if (isLoading) {
            // 🔥 标记为loading状态
            wasLoading = true

            // 🔥🔥🔥 在主内容区域显示"正在思考..."动画 + 计时器
            val loadingPanel = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.X_AXIS)  // 🔥 水平布局
                isOpaque = false
                border = EmptyBorder(0, JBUI.scale(3), 0, 0)  // 🔥 右移3px
            }

            // 计算固定宽度（"正在思考..." 的宽度）
            val tempLabel = JLabel("正在思考...")
            tempLabel.font = font.deriveFont(Font.PLAIN, JBUI.scale(13f))
            val fontMetrics = tempLabel.getFontMetrics(tempLabel.font)
            val fixedWidth = fontMetrics.stringWidth("正在思考...")

            val loadingLabel = JLabel("正在思考.").apply {
                // 🔥 使用 textPrimary + 50% 透明度，确保在所有主题下都可见
                val baseColor = ChatColors.textPrimary
                foreground = Color(baseColor.red, baseColor.green, baseColor.blue, 128)
                font = font.deriveFont(Font.PLAIN, JBUI.scale(13f))
                horizontalAlignment = SwingConstants.LEFT
            }

            // 🔥 先创建 loadingLabel，获取它的实际高度
            val labelHeight = loadingLabel.preferredSize.height

            // 🔥 左侧：动画文本（使用固定宽度面板包裹）
            val textWrapper = JPanel().apply {
                layout = BorderLayout()
                isOpaque = false
                // 🔥 强制固定宽度和实际高度（使用 loadingLabel 的真实高度）
                setPreferredSize(Dimension(fixedWidth, labelHeight))
                setMaximumSize(Dimension(fixedWidth, labelHeight))
                setMinimumSize(Dimension(fixedWidth, labelHeight))
            }
            textWrapper.add(loadingLabel, BorderLayout.WEST)
            loadingPanel.add(textWrapper)

            // 🔥 固定间距（不是弹性空间）
            loadingPanel.add(Box.createHorizontalStrut(JBUI.scale(10)))

            // 右侧：固定计时器（紧挨着，不跟着...移动）
            val timerLabel = JLabel("for   s").apply {  // 🔥 预留3位数字空间
                // 🔥 使用 textPrimary + 50% 透明度，确保在所有主题下都可见
                val baseColor = ChatColors.textPrimary
                foreground = Color(baseColor.red, baseColor.green, baseColor.blue, 128)
                font = font.deriveFont(Font.PLAIN, JBUI.scale(11f))  // 🔥 字体稍微小一点
            }
            loadingPanel.add(timerLabel)

            contentWrapper!!.add(loadingPanel, BorderLayout.CENTER)

            // 🔥 隐藏 thinking 框（用户只要"正在思考..."动画，不要thinking框）
            thinkingWrapper?.isVisible = false
            // 🔥 隐藏操作按钮（loading时不显示，且用户消息没有按钮）
            globalActionPanel?.isVisible = false

            // 🔥 启动动画 Timer（"正在思考." -> "正在思考.." -> "正在思考..." -> "正在思考."）
            var dotCount = 1  // 🔥 从1开始（至少1个点）
            val loadingTimer = javax.swing.Timer(500) {
                dotCount = if (dotCount >= 3) 1 else dotCount + 1  // 1 -> 2 -> 3 -> 1 (循环)
                val dots = ".".repeat(dotCount)
                loadingLabel.text = "正在思考$dots"
            }
            loadingTimer.start()

            // 🔥 启动计时器 Timer（每秒更新，最多999s）
            var secondsElapsed = 0
            val timerUpdateTimer = javax.swing.Timer(1000) {
                secondsElapsed++
                if (secondsElapsed <= 999) {
                    timerLabel.text = String.format("for %3ds", secondsElapsed)  // 🔥 固定3位，右对齐
                } else {
                    timerLabel.text = "for 999s+"  // 🔥 超过999s显示+
                }
            }
            timerUpdateTimer.start()

            // 🔥 保存 timer 引用（需要保存两个，防止被垃圾回收）
            thinkingTimer = loadingTimer
            // 🔥 将计时器 timer 保存到 contentWrapper 的 client property，防止被 GC
            contentWrapper!!.putClientProperty("timerUpdateTimer", timerUpdateTimer)
        } else {
            // 如果有初始 Thinking 文本，显示出来
            if (!initialThinkingText.isNullOrEmpty()) {
                initThinkingPanel()
                thinkingPanel?.text = initialThinkingText
                
                // 恢复历史记录时，如果提供了耗时，则直接显示
                if (initialThinkingDuration != null && initialThinkingDuration > 0) {
                     val seconds = initialThinkingDuration / 1000
                     val formatter = DecimalFormat("#,###")
                     timeLabel?.text = "for ${formatter.format(seconds)}s"
                }
                
                // 恢复历史记录时，标题直接显示为 "thought"
                updateTitleToThought()
            }

            if (animate && !isUser) {
                // 如果是 AI 回复且要求动画，则开始打字机效果
                contentWrapper!!.add(renderContent(content = ""))
                startMainTyping(text)
            } else {
                contentWrapper!!.add(renderContent(content = text))
            }

            // 🔥 恢复历史记录时，如果有 process，添加分析过程面板
            if (!initialProcess.isNullOrEmpty()) {
                addProcessPanel(initialProcess!!)
            }
        }
        
        add(bubblePanel, BorderLayout.CENTER)
    }

    /**
     * 🔥 创建全局操作按钮面板（控制整个回复）
     */
    private fun createGlobalActionPanel(): JPanel {
        val panel = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = EmptyBorder(JBUI.scale(8), 0, JBUI.scale(4), 0)
            alignmentX = Component.LEFT_ALIGNMENT

            val buttonPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0)).apply {
                isOpaque = false

                // 0. Navigation Buttons
                val navBox = JPanel(GridLayout(1, 2, JBUI.scale(2), 0)).apply {
                    isOpaque = false
                    border = BorderFactory.createEmptyBorder(0, 0, 0, JBUI.scale(4))
                }

                navUpBtn = NavButton(true) { onNavUp?.invoke() }
                navDownBtn = NavButton(false) { onNavDown?.invoke() }

                navUpBtn?.isVisible = true
                navUpBtn?.isEnabled = canNavUp
                navDownBtn?.isVisible = true
                navDownBtn?.isEnabled = canNavDown

                navBox.add(navUpBtn)
                navBox.add(navDownBtn)

                add(navBox)

                add(Box.createHorizontalStrut(JBUI.scale(16)))

                // 1. 折叠/展开按钮（控制整个回复）
                // 🔥 用户消息不显示折叠按钮，只保留复制和跳转按钮
                if (!isUser) {
                    globalCollapseButton = CollapseButton(ChatColors.assistantBubble) { shouldCollapse ->
                        // 折叠整个回复（contentWrapper + processWrapper）
                        contentWrapper?.isVisible = !shouldCollapse
                        if (shouldCollapse) {
                            // 折叠时，同时隐藏 processWrapper
                            processWrapper?.isVisible = false
                        } else {
                            // 展开时，只展开 contentWrapper，processWrapper 保持原状态
                        }
                        SwingUtilities.invokeLater {
                            bubblePanel.revalidate()
                            bubblePanel.repaint()
                        }
                    }
                    add(globalCollapseButton)
                    add(Box.createHorizontalStrut(JBUI.scale(8)))
                }

                add(Box.createHorizontalStrut(JBUI.scale(8)))

                // 2. 复制按钮（复制整个回复）
                // 🔥 传递 lambda 函数，点击时动态计算内容（确保获取最新的 finalContent）
                add(CopyButton {
                    buildString {
                        // 🔥 优先使用 finalContent（最终回复），如果没有则使用 text（初始文本）
                        val contentToCopy = if (finalContent.isNotEmpty()) finalContent else text
                        append(contentToCopy)
                        if (processContent.isNotEmpty()) {
                            append("\n\n## 分析过程\n\n")
                            append(processContent)
                        }
                    }
                })
            }
            add(buttonPanel, BorderLayout.EAST)
        }
        return panel
    }



    private fun initThinkingPanel() {
        thinkingWrapper?.isVisible = true
        
        titleLabel = object : JLabel("Thinking..") {
            override fun getPreferredSize(): Dimension {
                // 强制宽度为 "Thinking..." 的宽度，防止动画抖动
                // 如果当前文字是 Thought，则按实际宽度（或者也保持一致？Thought 比 Thinking 短，变短没关系，只要不抖动）
                // 实际上 Thought 状态下动画已停止。
                // 仅在 Thinking 状态下（文本包含 Thinking）固定宽度。
                if (text.startsWith("Thinking")) {
                    val metrics = getFontMetrics(font)
                    val width = metrics.stringWidth("Thinking...")
                    val height = super.getPreferredSize().height
                    return Dimension(width + JBUI.scale(2), height)
                }
                return super.getPreferredSize()
            }
        }.apply {
            foreground = ChatColors.textSecondary
            // 字体大小和颜色同代码框标题栏的语言类型的字体
            font = UIUtil.getLabelFont().deriveFont(Font.BOLD, JBUI.scale(11f))
        }
        
        // Toggle Icon
        thinkingToggleIcon = object : JComponent() {
            init {
                preferredSize = Dimension(JBUI.scale(14), JBUI.scale(14))
                isOpaque = false
            }
            override fun paintComponent(g: Graphics) {
                val g2 = g as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = ChatColors.textSecondary
                g2.stroke = BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                
                val size = JBUI.scale(8) // Arrow size
                val x = (width - size) / 2
                val y = (height - size) / 2
                
                val path = GeneralPath()
                if (isThinkingCollapsed) {
                    // Pointing Right (Collapsed) >
                    path.moveTo(x.toDouble(), y.toDouble())
                    path.lineTo((x + size / 2).toDouble(), (y + size / 2).toDouble())
                    path.lineTo(x.toDouble(), (y + size).toDouble())
                } else {
                    // Pointing Down (Expanded) v
                    path.moveTo(x.toDouble(), y.toDouble() + size / 4)
                    path.lineTo((x + size / 2).toDouble(), (y + size / 2 + size / 4).toDouble())
                    path.lineTo((x + size).toDouble(), y.toDouble() + size / 4)
                }
                g2.draw(path)
            }
        }
        
        // 1. 标题栏
        val headerPanel = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0)).apply {
            isOpaque = false
            // 底部间距增加 3px (从 4 增加到 7)，增加 Thinking/Thought 与下方内容框的距离
            border = EmptyBorder(JBUI.scale(4), 0, JBUI.scale(7), 0)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            
            // 计时标签
            timeLabel = JLabel("").apply {
                // 使用更淡的颜色 (TextSecondary + 透明度)
                val baseColor = ChatColors.textSecondary
                foreground = Color(baseColor.red, baseColor.green, baseColor.blue, 128) // ~50% opacity
                font = UIUtil.getLabelFont().deriveFont(Font.BOLD, JBUI.scale(11f))
            }

            add(thinkingToggleIcon)
            add(titleLabel)
            add(Box.createHorizontalStrut(JBUI.scale(4)))
            add(timeLabel)
            
            // Add Listener
            val listener = object : java.awt.event.MouseAdapter() {
                override fun mouseClicked(e: java.awt.event.MouseEvent) {
                    isThinkingCollapsed = !isThinkingCollapsed
                    thinkingScrollPane?.isVisible = !isThinkingCollapsed
                    thinkingToggleIcon?.repaint()
                    
                    // Re-layout
                    // 触发布局更新和重绘，确保线条的显示状态和组件高度正确更新
                    thinkingWrapper?.revalidate()
                    thinkingWrapper?.repaint()
                    bubblePanel.revalidate()
                    bubblePanel.repaint()
                }
            }
            addMouseListener(listener)
            titleLabel!!.addMouseListener(listener)
            thinkingToggleIcon!!.addMouseListener(listener)
        }
        
        // 2. 内容区域
        thinkingPanel = JTextArea().apply {
            isEditable = false
            isOpaque = false // 背景透明
            lineWrap = true
            wrapStyleWord = true
            background = Color(0, 0, 0, 0) // 关键：设置为完全透明
            foreground = ChatColors.textSecondary  // 🔥 与 todo list 保持一致，比正文暗淡
            font = UIUtil.getLabelFont().deriveFont(12f)
            
            // 修复：应用清除不可见字符的复制 Action
            setupCleanCopyAction(this)
            
            // 移除上下内边距，增加左侧内边距以避开引导线
            // 引导线大概在 X=11px 位置，我们需要留出足够的左边距，例如 20px
            border = EmptyBorder(0, JBUI.scale(20), 0, JBUI.scale(8))
            rows = 1 
        }
        
        thinkingScrollPane = object : JBScrollPane(thinkingPanel) {
            override fun getPreferredSize(): Dimension {
                val superSize = super.getPreferredSize()
                
                // 修复：手动计算基于当前宽度的真实高度，解决 JTextArea 自动换行高度计算滞后问题
                try {
                    val viewport = viewport
                    var viewWidth = viewport.width
                    // 如果尚未布局，尝试使用 ScrollPane 自身宽度
                    if (viewWidth <= 0) viewWidth = width
                    // 如果还是 0，使用父容器宽度或默认值
                    if (viewWidth <= 0) viewWidth = JBUI.scale(200)
                    
                    val textArea = thinkingPanel!!
                    val insets = textArea.insets
                    val availableWidth = (viewWidth - insets.left - insets.right).coerceAtLeast(1)
                    
                    // 使用 UI View 强制计算高度
                    val view = textArea.ui.getRootView(textArea)
                    if (view != null) {
                        view.setSize(availableWidth.toFloat(), 0f)
                        val prefHeight = view.getPreferredSpan(View.Y_AXIS).toInt() + insets.top + insets.bottom
                        
                        // 计算最大高度限制 (改为 13 行)
                        val fontMetrics = textArea.getFontMetrics(textArea.font)
                        val lineHeight = fontMetrics.height
                        // 13行高度 + 顶部底部 padding
                        val maxHeight = (lineHeight * 13) + insets.top + insets.bottom
                        
                        val finalHeight = prefHeight.coerceAtMost(maxHeight)
                        
                        // 加上 ScrollPane 自身的边框高度
                        val scrollInsets = this.insets
                        val totalHeight = finalHeight + scrollInsets.top + scrollInsets.bottom
                        
                        return Dimension(superSize.width, totalHeight)
                    }
                } catch (e: Exception) {
                    // 降级处理：如果 View 计算失败，回退到默认逻辑
                }

                // 默认逻辑：基于 super 的高度，但应用最大高度限制
                val fontMetrics = thinkingPanel!!.getFontMetrics(thinkingPanel!!.font)
                val lineHeight = fontMetrics.height
                val maxHeight = (lineHeight * 13) + JBUI.scale(16) + JBUI.scale(4)
                
                if (superSize.height > maxHeight) {
                    superSize.height = maxHeight
                }
                return superSize
            }
        }.apply {
            // 初始状态：无边框
            putClientProperty("borderColor", null) 
            border = BorderFactory.createEmptyBorder() // 彻底移除边框
            viewportBorder = BorderFactory.createEmptyBorder()
            
            // 将背景设置为透明
            isOpaque = false 
            viewport.isOpaque = false
            background = Color(0, 0, 0, 0)
            viewport.background = Color(0, 0, 0, 0)
            
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            // 改回 AS_NEEDED 以支持滚轮，但通过自定义 ScrollBar 实现不占空间且不可见
            verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
            
            // 自定义垂直滚动条：
            // 1. 宽度为 0，确保不占据布局空间（防抖动）
            // 2. 不绘制任何内容（不可见）
            object : JBScrollBar() {
                override fun getPreferredSize(): Dimension {
                    return Dimension(0, 0)
                }
                
                override fun paint(g: Graphics) {
                    // 不绘制
                }
            }.also { 
                verticalScrollBar = it 
            }
            
            // 移除所有悬停监听器
        }
        
        // 修复：手动转发滚轮事件，确保在自定义滚动条策略下也能滚动
        thinkingPanel?.addMouseWheelListener { e ->
            thinkingScrollPane?.let { scrollPane ->
                val scrollBar = scrollPane.verticalScrollBar
                if (scrollBar != null) {
                    val amount = e.unitsToScroll * scrollBar.unitIncrement * 3 // 加速滚动
                    scrollBar.value += amount
                }
            }
        }
        
        // 将 Header 和 Content 放入 Wrapper
        thinkingWrapper?.add(headerPanel, BorderLayout.NORTH)
        thinkingWrapper?.add(thinkingScrollPane, BorderLayout.CENTER)
        
        // 启动计时器
        thinkingDurationTimer = javax.swing.Timer(100) {
            val duration = System.currentTimeMillis() - thinkingStartTime
            val seconds = duration / 1000
            val formatter = DecimalFormat("#,###")
            timeLabel?.text = "for ${formatter.format(seconds)}s"
        }
        thinkingDurationTimer?.start()
    }

    private fun startTitleAnimation() {
        if (titleTimer != null && titleTimer!!.isRunning) return
        
        var dotCount = 2
        titleTimer = javax.swing.Timer(500) {
            dotCount = (dotCount % 3) + 1
            val dots = ".".repeat(dotCount)
            titleLabel?.text = "Thinking$dots"
        }
        titleTimer?.start()
    }
    
    private fun updateTitleToThought() {
        titleTimer?.stop()
        titleTimer = null

        // 🔥 隐藏整个 thinking 框（用户要求隐藏）
        thinkingWrapper?.isVisible = false

        // 停止计时
        thinkingDurationTimer?.stop()
        thinkingDurationTimer = null

        // 更新最终时间（仅当有有效开始时间时，且未被初始值覆盖）
        if (thinkingStartTime > 0) {
            val duration = System.currentTimeMillis() - thinkingStartTime
            val seconds = duration / 1000
            val formatter = DecimalFormat("#,###")
            timeLabel?.text = "for ${formatter.format(seconds)}s"

            // 🆕 保存耗时到持久化存储
            if (project != null) {
                ProjectStorageService.getInstance(project).updateLastMessageThinkingDuration(duration)
            }
        }

        // 标记思考完成
        isThinkingFinished = true

        // 收到 COMPLETE 后自动折叠
        isThinkingCollapsed = true
        thinkingScrollPane?.isVisible = false
        thinkingToggleIcon?.repaint()

        // 刷新布局以生效
        // 确保 thinkingWrapper 和父容器都重新布局
        thinkingWrapper?.revalidate()
        thinkingWrapper?.repaint()
        bubblePanel.revalidate()
        bubblePanel.repaint()
    }

    /**
     * 结束 Thinking 状态并显示最终结果
     */
    fun finishThinking(result: String, process: String = "", animate: Boolean, onTypingComplete: (() -> Unit)? = null) {
        // 🔥🔥🔥 立即隐藏整个 thinking 框（用户要求隐藏）
        thinkingWrapper?.isVisible = false

        // 🔥 显示操作按钮（从loading状态完成时）
        if (wasLoading) {
            globalActionPanel?.isVisible = true
            wasLoading = false
        }

        // 🔥 保存最终的回复内容（用于复制）
        finalContent = result

        // 🔥🔥🔥 DEBUG: 打印保存结果
        println("🔍 [DEBUG] finishThinking: SAVED finalContent")
        println("  - finalContent.length: ${finalContent.length}")
        println("  - finalContent preview: ${finalContent.take(100)}")
        println("  - finalContent.isNotEmpty: ${finalContent.isNotEmpty()}")

        // 🔥🔥🔥 DEBUG: 打印 process 参数信息
        println("🔍 [DEBUG] finishThinking called:")
        println("  - result length: ${result.length}")
        println("  - process parameter: '${process.take(100)}...'")
        println("  - process length: ${process.length}")
        println("  - process isBlank: ${process.isBlank()}")
        println("  - process isEmpty: ${process.isEmpty()}")
        println("  - animate: $animate")

        // 🔥 保存 process 参数，供打字机完成后使用
        val pendingProcess = process

        // 定义完成后的逻辑：切换标题，开始渲染主内容
        val finishLogic = {
            thinkingTimer?.stop()
            thinkingTimer = null

            // 确保在内容渲染前更新状态
            updateTitleToThought()

            // 🔥 自动折叠 TODO List
            embeddedTodoListPanel?.collapse()

            // 🔥🔥🔥 修复：如果已经有 process panel，保存它（防止被 removeAll 清除）
            val savedProcessWrapper = processWrapper
            val savedProcessContent = if (savedProcessWrapper != null) {
                // 提取现有的 process 内容（JLabel 中的文本）
                val components = savedProcessWrapper.components
                components.filterIsInstance<JBScrollPane>().firstOrNull()?.viewport?.components?.filterIsInstance<JPanel>()?.firstOrNull()?.components?.firstOrNull()
            } else null

            // 🔥🔥🔥 关键修复：包装 onTypingComplete，在打字机完成后添加 processWrapper
            val wrappedCallback = {
                // 先执行原始回调
                onTypingComplete?.invoke()

                // 🔥🔥🔥 打字机完成后再添加 processWrapper（此时布局已完成）
                println("🔍 [DEBUG] Typing complete, adding process panel:")
                println("  - savedProcessWrapper: $savedProcessWrapper")
                println("  - pendingProcess is blank: ${pendingProcess.isBlank()}")
                println("  - pendingProcess length: ${pendingProcess.length}")

                if (savedProcessWrapper != null && pendingProcess.isBlank()) {
                    // 如果这次没有新 process，恢复之前的
                    println("🔍 [DEBUG] Restoring saved process panel")
                    contentWrapper?.add(savedProcessWrapper, BorderLayout.SOUTH)
                    processWrapper = savedProcessWrapper
                }
                // 🔥🔥🔥 优先级2：如果有新的 process，添加它
                else if (pendingProcess.isNotBlank()) {
                    println("🔍 [DEBUG] Adding new process panel after typing")
                    addProcessPanel(pendingProcess)
                } else {
                    println("🔍 [DEBUG] No process to add (savedProcessWrapper is null AND pendingProcess is blank)")
                }

                // 🔥🔥🔥 强制重新布局
                contentWrapper?.revalidate()
                contentWrapper?.repaint()
                revalidate()
                repaint()
            }

            if (animate) {
                // 🔥🔥🔥 修复：动画模式下不恢复 processWrapper，让打字机完成后重新创建
                contentWrapper!!.removeAll()
                contentWrapper!!.add(renderContent(content = ""))
                startMainTyping(result, wrappedCallback)
            } else {
                contentWrapper!!.removeAll()
                contentWrapper!!.add(renderContent(content = result))

                // 🔥 非动画模式，立即添加 processWrapper
                println("🔍 [DEBUG] Non-animate mode, adding process panel immediately:")
                println("  - savedProcessWrapper: $savedProcessWrapper")
                println("  - pendingProcess is blank: ${pendingProcess.isBlank()}")

                if (savedProcessWrapper != null && pendingProcess.isBlank()) {
                    println("🔍 [DEBUG] Restoring saved process panel")
                    contentWrapper?.add(savedProcessWrapper, BorderLayout.SOUTH)
                    processWrapper = savedProcessWrapper
                }
                else if (pendingProcess.isNotBlank()) {
                    println("🔍 [DEBUG] Adding new process panel immediately")
                    addProcessPanel(pendingProcess)
                } else {
                    println("🔍 [DEBUG] No process to add")
                }

                contentWrapper?.revalidate()
                contentWrapper?.repaint()
                revalidate()
                repaint()

                // 最后触发回调
                onTypingComplete?.invoke()
            }

            // 触发重绘（不包含 processWrapper，因为它会在打字机完成后添加）
            revalidate()
            repaint()
        }

        synchronized(pendingThinkingText) {
            // 收到 COMPLETE 信号，不再等待打字机效果，直接强制结束
            // 1. 如果还有未显示的 Thinking 内容，直接一次性追加显示
            if (pendingThinkingText.isNotEmpty()) {
                thinkingPanel?.append(pendingThinkingText.toString())
                pendingThinkingText.setLength(0)
            }

            // 2. 清理回调（防止重复触发）
            thinkingFinishCallback = null

            // 3. 立即执行完成逻辑
            finishLogic()
        }
    }

    /**
     * 🔥 添加分析过程折叠面板
     */
    private fun addProcessPanel(process: String) {
        // 🔥 保存分析过程内容，用于复制
        processContent = process

        // 🔥🔥🔥 关键修复：只有当 process 真正有内容时才显示面板
        // 严格检查：process 必须非空、非空白字符串
        if (process.isBlank()) {
            println("⚠️ [DEBUG] addProcessPanel: process is blank, skipping panel creation")
            println("  - process length: ${process.length}")
            println("  - process content: '$process'")
            return
        }

        println("🔍 [DEBUG] addProcessPanel called:")
        println("  - process length: ${process.length}")
        println("  - process preview: ${process.take(100)}")
        println("  - processWrapper: $processWrapper")

        // 🔥 processWrapper 已在初始化时创建，这里只清空并重新填充内容
        processWrapper?.removeAll()

        // 1. 创建折叠按钮图标
        processToggleIcon = object : JComponent() {
            init {
                preferredSize = Dimension(JBUI.scale(14), JBUI.scale(14))
                isOpaque = false
            }
            override fun paintComponent(g: Graphics) {
                val g2 = g as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = ChatColors.textSecondary
                g2.stroke = BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)

                val size = JBUI.scale(8) // Arrow size
                val x = (width - size) / 2
                val y = (height - size) / 2

                val path = GeneralPath()
                if (isProcessCollapsed) {
                    // Pointing Right (Collapsed) >
                    path.moveTo(x.toDouble(), y.toDouble())
                    path.lineTo((x + size / 2).toDouble(), (y + size / 2).toDouble())
                    path.lineTo(x.toDouble(), (y + size).toDouble())
                } else {
                    // Pointing Down (Expanded) v
                    path.moveTo(x.toDouble(), y.toDouble() + size / 4)
                    path.lineTo((x + size / 2).toDouble(), (y + size / 2 + size / 4).toDouble())
                    path.lineTo((x + size).toDouble(), y.toDouble() + size / 4)
                }
                g2.draw(path)
            }
        }

        // 3. 创建标题栏
        val headerPanel = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0)).apply {
            isOpaque = false
            border = EmptyBorder(JBUI.scale(4), 0, JBUI.scale(7), 0)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)

            // 标题标签
            val titleLabel = JLabel("分析过程").apply {
                foreground = ChatColors.textSecondary
                font = UIUtil.getLabelFont().deriveFont(Font.BOLD, JBUI.scale(11f))
            }

            add(processToggleIcon)
            add(titleLabel)

            // 点击事件：切换折叠状态
            val listener = object : java.awt.event.MouseAdapter() {
                override fun mouseClicked(e: java.awt.event.MouseEvent) {
                    println("🔍 [DEBUG] ========== Process panel clicked! ==========")
                    println("  - isProcessCollapsed (before): $isProcessCollapsed")
                    println("  - processScrollPane: $processScrollPane")
                    println("  - processScrollPane visible (before): ${processScrollPane?.isVisible}")

                    isProcessCollapsed = !isProcessCollapsed
                    processScrollPane?.isVisible = !isProcessCollapsed

                    println("  - isProcessCollapsed (after): $isProcessCollapsed")
                    println("  - processScrollPane visible (after): ${processScrollPane?.isVisible}")

                    // 🔥 检查 viewport 的内容
                    val viewportView = processScrollPane?.viewport?.view
                    println("  - viewport view: $viewportView")
                    println("  - viewport view class: ${viewportView?.javaClass?.name}")

                    if (viewportView is java.awt.Container) {
                        println("  - viewport view component count: ${viewportView.componentCount}")
                        println("  - viewport view components: ${viewportView.components?.map { it.javaClass.simpleName }}")

                        // 🔥 递归打印所有子组件
                        fun printComponents(comp: java.awt.Component, indent: Int = 0) {
                            val prefix = "  ".repeat(indent)
                            println("$prefix- ${comp.javaClass.simpleName}: ${comp.javaClass.name}")
                            println("$prefix  size: ${comp.size}")
                            println("$prefix  visible: ${comp.isVisible}")
                            if (comp is java.awt.Container) {
                                for (child in comp.components) {
                                    printComponents(child, indent + 1)
                                }
                            }
                        }
                        printComponents(viewportView, 3)
                    }

                    processToggleIcon?.repaint()

                    // 🔥 关键修复：立即强制布局（因为 revalidate 是异步的）
                    bubblePanel.doLayout()
                    bubblePanel.layout?.layoutContainer(bubblePanel)

                    // 🔥 强制 processScrollPane 重新计算尺寸
                    processScrollPane?.let { scroll ->
                        scroll.viewport?.let { vp ->
                            vp.doLayout()
                            (vp.view as? java.awt.Container)?.let { view ->
                                view.revalidate()
                            }
                        }
                    }

                    println("🔍 [DEBUG] ========== Re-layout done ==========")
                }
            }
            addMouseListener(listener)
        }

        // 🔥 关键修复：在添加到 processWrapper 之前，先让 headerPanel 计算 preferredSize
        headerPanel.doLayout()
        println("🔍 [DEBUG] headerPanel preferredSize after doLayout: ${headerPanel.preferredSize}")

        // 4. 创建内容面板（渲染 process 的 Markdown 内容）
        val contentPanel = JPanel(BorderLayout()).apply {
            isOpaque = false
        }

        // 🔥🔥🔥 关键修复：直接使用 renderedContent，不需要提取 CENTER
        // 原因：renderContent(content = process, showActions = false) 返回的 wrapperPanel 不包含操作栏（SOUTH）
        // 直接使用 wrapperPanel 即可，避免复杂的组件提取逻辑
        val renderedContent = renderContent(content = process, showActions = false)

        println("✅ [DEBUG] Adding renderedContent to contentPanel")
        println("  - renderedContent class: ${renderedContent.javaClass.simpleName}")
        println("  - renderedContent componentCount: ${renderedContent.componentCount}")
        println("  - renderedContent components: ${renderedContent.components?.map { it.javaClass.simpleName }}")
        println("  - renderedContent preferredSize: ${renderedContent.preferredSize}")

        // 🔥 直接添加 renderedContent 到 contentPanel
        contentPanel.add(renderedContent, BorderLayout.CENTER)

        // 🔥 关键修复：立即触发重新布局，确保 preferredSize 正确计算
        contentPanel.revalidate()
        contentPanel.repaint()

        println("✅ [DEBUG] After contentPanel revalidate:")
        println("  - contentPanel preferredSize: ${contentPanel.preferredSize}")

        // 5. 创建 JScrollPane
        processScrollPane = object : JBScrollPane(contentPanel) {
            override fun getPreferredSize(): Dimension {
                // 🔥 关键修复：直接使用 renderedContent 的 preferredSize，而不是 contentPanel 的
                // 原因：contentPanel 可能还没有完成布局，preferredSize 为 0
                val contentSize = renderedContent.preferredSize

                println("🔍 [DEBUG] processScrollPane.getPreferredSize():")
                println("  - contentPanel preferredSize: ${contentPanel.preferredSize}")
                println("  - renderedContent preferredSize: ${contentSize}")

                // 🔥 展开后不限制高度，完整显示所有内容
                println("  - calculated size (no limit): $contentSize")

                return contentSize
            }
        }.apply {
            isOpaque = false
            viewport.isOpaque = false
            horizontalScrollBarPolicy = JBScrollPane.HORIZONTAL_SCROLLBAR_NEVER
            verticalScrollBarPolicy = JBScrollPane.VERTICAL_SCROLLBAR_NEVER  // 🔥 隐藏滚动条
            border = null
            isVisible = !isProcessCollapsed  // 默认折叠
        }

        println("🔍 [DEBUG] processScrollPane viewport view: ${processScrollPane?.viewport?.view}")
        println("🔍 [DEBUG] processScrollPane viewport view components count: ${if (processScrollPane?.viewport?.view is java.awt.Container) (processScrollPane?.viewport?.view as java.awt.Container).componentCount else "N/A"}")

        // 6. 组装面板
        processWrapper!!.add(headerPanel, BorderLayout.NORTH)
        processWrapper!!.add(processScrollPane, BorderLayout.CENTER)

        println("🔍 [DEBUG] processWrapper components: ${processWrapper!!.componentCount}")
        println("🔍 [DEBUG] headerPanel: $headerPanel")
        println("🔍 [DEBUG] headerPanel preferredSize: ${headerPanel.preferredSize}")
        println("🔍 [DEBUG] processScrollPane: $processScrollPane")
        println("🔍 [DEBUG] processScrollPane preferredSize: ${processScrollPane?.preferredSize}")
        println("🔍 [DEBUG] processScrollPane visible: ${processScrollPane?.isVisible}")
        println("🔍 [DEBUG] isProcessCollapsed: $isProcessCollapsed")
        println("🔍 [DEBUG] processWrapper preferredSize (before add): ${processWrapper?.preferredSize}")

        // 6. 显示 processWrapper（已在初始化时添加到 bubblePanel）
        processWrapper?.isVisible = true

        println("🔍 [DEBUG] After showing processWrapper:")
        println("  - processWrapper parent: ${processWrapper?.parent}")
        println("  - processWrapper visible: ${processWrapper?.isVisible}")
        println("  - processWrapper preferredSize: ${processWrapper?.preferredSize}")

        // 🔥 关键修复：立即重新计算 processWrapper 的尺寸
        processWrapper?.revalidate()
        processWrapper?.repaint()

        println("🔍 [DEBUG] After processWrapper revalidate:")
        println("  - processWrapper size: ${processWrapper?.size}")
        println("  - processWrapper preferredSize: ${processWrapper?.preferredSize}")

        // 🔥🔥🔥 强制 bubblePanel 重新布局，让 processWrapper 获得正确的空间
        // 修复：processWrapper 现在是 bubblePanel 的直接子组件，不是 contentWrapper 的子组件
        bubblePanel.revalidate()
        bubblePanel.repaint()

        println("🔍 [DEBUG] After bubblePanel revalidate:")
        println("  - bubblePanel size: ${bubblePanel.size}")
        println("  - processWrapper size: ${processWrapper?.size}")
        println("  - processWrapper bounds: ${processWrapper?.bounds}")

        // 🔥🔥🔥 强制立即布局（因为 revalidate 是异步的）
        bubblePanel.doLayout()
        bubblePanel.layout?.layoutContainer(bubblePanel)

        // 🔥🔥🔥 关键修复：强制 processScrollPane 重新计算尺寸
        // 原因：processScrollPane 的 viewport 可能在内容添加后没有正确更新
        processScrollPane?.let { scroll ->
            scroll.viewport?.let { vp ->
                // 强制 viewport 重新计算尺寸
                vp.doLayout()
                // 确保 viewport 的尺寸正确传递给 view
                (vp.view as? java.awt.Container)?.let { view ->
                    view.revalidate()
                    view.repaint()
                }
            }
        }

        println("🔍 [DEBUG] After forced layout:")
        println("  - processWrapper size: ${processWrapper?.size}")
        println("  - processWrapper bounds: ${processWrapper?.bounds}")
        println("  - processScrollPane size: ${processScrollPane?.size}")
        println("  - processScrollPane viewport size: ${processScrollPane?.viewport?.size}")
    }

    // 新增：立即停止打字机效果，显示全部内容
    fun stopTyping() {
        // 停止主内容打字机
        if (mainTypingTimer != null && mainTypingTimer!!.isRunning) {
            mainTypingTimer!!.stop()
            mainTypingTimer = null
            
            // 立即显示完整内容
            // 注意：我们需要获取完整内容。但在 startMainTyping 中 fullText 是局部变量。
            // 解决方案：我们无法直接访问 fullText。
            // 但我们可以通过将 fullText 提升为类属性或者让 timer 执行最后一次逻辑。
            // 实际上，startMainTyping 是用闭包捕获了 fullText。
            // 我们可以触发一次带有 "force finish" 标志的 Timer 事件，或者重新设计。
            // 最简单的方法：设置一个标志位，下次 Timer 执行时直接跳到最后。
            // 但我们需要立即响应。
            // 更好的做法：mainTypingTimer 的 Action Listener 是一个闭包。
            // 我们无法从外部强制它执行特定逻辑。
            // 妥协方案：我们只能在 startMainTyping 中把 fullText 存下来，或者提供一个 completionHandler。
        }
    }
    
    // 为了支持 stopTyping，我们需要保存 fullText
    private var currentFullText: String? = null
    
    /**
     * 🆕 更新内嵌的 TODO List
     * TODO List 放在 Thinking 框下面、回复内容上面
     */
    fun updateTodoList(todos: List<TodoListPanel.TodoData>) {
        if (todos.isEmpty()) {
            // 隐藏 TODO List
            todoListWrapper?.isVisible = false
            embeddedTodoListPanel = null
            todoListWrapper?.removeAll()
        } else {
            // 创建或更新 TODO List
            if (embeddedTodoListPanel == null) {
                embeddedTodoListPanel = TodoListPanel()
                todoListWrapper?.removeAll()
                todoListWrapper?.add(embeddedTodoListPanel, BorderLayout.CENTER)
            }
            embeddedTodoListPanel?.updateTodos(todos)
            todoListWrapper?.isVisible = true
        }
        
        // 触发布局更新
        todoListWrapper?.revalidate()
        todoListWrapper?.repaint()
        revalidate()
        repaint()
    }
    
    /**
     * 🆕 获取当前 TODO List 数据（用于持久化）
     */
    fun getTodoItems(): List<TodoListPanel.TodoData>? {
        return embeddedTodoListPanel?.getCurrentTodos()
    }
    private var currentTypingCompleteCallback: (() -> Unit)? = null

    fun stopTypingAndRenderFull() {
        // 核心变更：停止时不再显示全文，而是就此停止
        if (mainTypingTimer != null) {
            mainTypingTimer?.stop()
            mainTypingTimer = null
            
            // 触发回调以复位按钮，但不渲染剩余文本
            currentTypingCompleteCallback?.invoke()
            currentTypingCompleteCallback = null
            currentFullText = null
            
            // 停止后，确保显示操作栏 (基于当前已显示的内容)
            val currentContent = mainDisplayedText.toString()
            if (currentContent.isNotEmpty()) {
                val newWrapper = renderContent(content = currentContent, showActions = true)
                smartUpdate(contentWrapper!!, newWrapper)
            }
        }
    }

    private fun startMainTyping(fullText: String, onTypingComplete: (() -> Unit)? = null) {
        currentFullText = fullText
        currentTypingCompleteCallback = onTypingComplete
        mainDisplayedText.setLength(0)
        
        var startTime = System.currentTimeMillis()
        val targetSpeed = 150.0 // 字符/秒
        val totalLength = fullText.length
        var lastRenderTime = 0L
        
        // 性能优化：渲染节流阈值 (33ms ≈ 30FPS)
        val renderInterval = 33L
        
        mainTypingTimer = javax.swing.Timer(15) { timerEvt ->
            val now = System.currentTimeMillis()
            val elapsedSeconds = (now - startTime) / 1000.0
            
            // 基于时间计算目标索引
            var targetIndex = (elapsedSeconds * targetSpeed).toInt().coerceAtMost(totalLength)
            
            // === 核心优化：代码块瞬间渲染检测 (消除抖动) ===
            // 检测逻辑：如果处于代码块内部，或者即将进入代码块，则直接显示整个代码块
            val currentLen = mainDisplayedText.length
            if (currentLen < totalLength) {
                // 1. 查找下一个代码块标记
                val nextMarker = fullText.indexOf("```", currentLen)
                
                if (nextMarker != -1) {
                    // 2. 判断是否处于代码块内部 (简单奇偶校验)
                    var markerCount = 0
                    var idx = fullText.indexOf("```")
                    while (idx != -1 && idx < currentLen) {
                        markerCount++
                        idx = fullText.indexOf("```", idx + 3)
                    }
                    
                    val isInside = (markerCount % 2 != 0)
                    var jumpToIndex = -1
                    
                    if (isInside) {
                        // 情况 A: 当前处于代码块内部 (可能是上次时间步长正好落在中间)
                        // 立即跳到下一个标记 (闭合标记) 的末尾
                        jumpToIndex = nextMarker + 3
                    } else {
                        // 情况 B: 当前在代码块外部，检查是否即将进入
                        if (targetIndex >= nextMarker) {
                             // 找到闭合标记
                             val closingIdx = fullText.indexOf("```", nextMarker + 3)
                             if (closingIdx != -1) {
                                 jumpToIndex = closingIdx + 3
                             } else {
                                 // 没有闭合标记，可能是文末？直接显示到最后
                                 jumpToIndex = totalLength
                             }
                        }
                    }
                    
                    if (jumpToIndex != -1) {
                        // 执行跳跃
                        jumpToIndex = jumpToIndex.coerceAtMost(totalLength)
                        val chunk = fullText.substring(currentLen, jumpToIndex)
                        mainDisplayedText.append(chunk)
                        
                        // 时间补偿
                        val skippedChars = jumpToIndex - currentLen
                        val timeSavedSeconds = skippedChars / targetSpeed
                        startTime -= (timeSavedSeconds * 1000).toLong()
                        
                        // 强制更新 UI
                        val newWrapper = renderContent(content = mainDisplayedText.toString(), showActions = false)
                        smartUpdate(contentWrapper!!, newWrapper)
                        lastRenderTime = System.currentTimeMillis()
                        
                        return@Timer
                    }
                }
            }
            
            if (targetIndex > mainDisplayedText.length) {
                // 追加新内容
                mainDisplayedText.setLength(0)
                mainDisplayedText.append(fullText.substring(0, targetIndex))
                
                // 检查是否满足渲染时间间隔，或者是最后一次更新
                if (now - lastRenderTime >= renderInterval || targetIndex == totalLength) {
                    // Smart update to avoid jitter
                    // 打字过程中不显示操作栏，避免频繁创建导致 UI 堆叠
                    val newWrapper = renderContent(content = mainDisplayedText.toString(), showActions = false)
                    smartUpdate(contentWrapper!!, newWrapper)
                    lastRenderTime = now
                }
            }
            
            if (targetIndex >= totalLength) {
                (timerEvt.source as javax.swing.Timer).stop()
                mainTypingTimer = null
                // 确保最终状态完整
                if (mainDisplayedText.length != totalLength) {
                    mainDisplayedText.setLength(0)
                    mainDisplayedText.append(fullText)
                    
                    // 最终状态：显示操作栏
                    val newWrapper = renderContent(content = mainDisplayedText.toString(), showActions = true)
                    smartUpdate(contentWrapper!!, newWrapper)
                } else {
                    // 即使内容已经完整，也需要重新渲染一次以显示操作栏 (因为最后一次 timer update 是 showActions=false)
                    val newWrapper = renderContent(content = mainDisplayedText.toString(), showActions = true)
                    smartUpdate(contentWrapper!!, newWrapper)
                }
                contentWrapper!!.revalidate()
                contentWrapper!!.repaint()
                
                // 触发完成回调
                currentTypingCompleteCallback?.invoke()
                currentTypingCompleteCallback = null
                currentFullText = null
            }
        }
        mainTypingTimer?.start()
    }

    /**
     * 追加 Thinking 过程日志
     */
    fun appendThinking(text: String) {
        // 清理 [LOADING] 标记（如果有）
        val cleanText = text.replace("[LOADING]", "").trim()
        if (cleanText.isEmpty()) return

        // 1. 确保面板已初始化
        if (thinkingWrapper == null || !thinkingWrapper!!.isVisible) {
            thinkingStartTime = System.currentTimeMillis()
            initThinkingPanel()
            bubblePanel.revalidate()
            bubblePanel.repaint()
        }

        // 2. 将新文本加入队列
        // 核心优化：使用明确的 hasThinkingContent 标志位
        // 如果之前已经追加过内容，且本次新内容不以换行符开头，则先追加一个换行符
        synchronized(pendingThinkingText) {
            if (hasThinkingContent) {
                if (!cleanText.startsWith("\n")) {
                    // 增加一个空行 (两个换行符) 来区分每次思考的内容
                    pendingThinkingText.append("\n\n")
                } else if (!cleanText.startsWith("\n\n")) {
                    // 如果只包含一个换行符，则补充一个，确保有两个换行符
                    pendingThinkingText.append("\n")
                }
            }
            pendingThinkingText.append(cleanText)
            hasThinkingContent = true
        }

        // 确保 Timer 正在运行
        if (thinkingTimer == null || !thinkingTimer!!.isRunning) {
            startThinkingTyping()
        }
    }

    /**
     * 🆕 流式更新 Markdown 内容（用于增量渲染）
     * @param markdown Markdown 内容
     */
    fun updateStreamingMarkdown(markdown: String) {
        // 1. 如果还没有 content 面板，先结束 Thinking 状态
        if (contentWrapper == null) {
            finishThinking(markdown, process = "", animate = false)
            return
        }

        // 2. 重新渲染 Markdown 内容
        val newContentPanel = renderContent(markdown)

        // 3. 替换旧内容
        SwingUtilities.invokeLater {
            contentWrapper?.removeAll()
            contentWrapper?.add(newContentPanel, BorderLayout.CENTER)
            contentWrapper?.revalidate()
            contentWrapper?.repaint()
            bubblePanel.revalidate()
            bubblePanel.repaint()
        }
    }

    private fun startThinkingTyping() {
        if (thinkingTimer != null && thinkingTimer!!.isRunning) return

        // 核心优化：改用基于时间的令牌桶算法，确保速度不受 Timer 精度影响
        // 目标速度：150 字符/秒 (与正文保持一致，解决"慢悠悠"的问题)
        val targetSpeed = 150.0 
        var lastTime = System.currentTimeMillis()
        var charAccumulator = 0.0

        thinkingTimer = javax.swing.Timer(15) { 
            val now = System.currentTimeMillis()
            // 计算时间差 (秒)
            val dt = (now - lastTime) / 1000.0
            lastTime = now
            
            // 累加应输出的字符数
            charAccumulator += dt * targetSpeed
            
            synchronized(pendingThinkingText) {
                if (pendingThinkingText.isNotEmpty()) {
                    // 取出累积的整数部分
                    var count = charAccumulator.toInt()
                    
                    if (count > 0) {
                        // 消费字符
                        count = count.coerceAtMost(pendingThinkingText.length)
                        val chunk = pendingThinkingText.substring(0, count)
                        pendingThinkingText.delete(0, count)
                        charAccumulator -= count // 扣除已消费的令牌
                        
                        thinkingPanel?.let { area ->
                            val currentHeight = area.height
                            area.append(chunk)
                            area.caretPosition = area.document.length
                            
                            // 智能检测是否需要触发布局更新 (revalidate)
                            var needRevalidate = false
                            
                            if (chunk.contains("\n")) {
                                needRevalidate = true
                            } else {
                                try {
                                    val rect = area.modelToView(area.document.length)
                                    if (rect != null) {
                                        val fontMetrics = area.getFontMetrics(area.font)
                                        val contentBottom = rect.y + fontMetrics.height + area.insets.bottom
                                        if (contentBottom > currentHeight) {
                                            needRevalidate = true
                                        }
                                    }
                                } catch (e: Exception) {
                                    // ignore
                                }
                            }

                            if (needRevalidate) {
                                bubblePanel.revalidate()
                                bubblePanel.repaint()
                                // Fix: Auto-scroll to bottom of this bubble when height changes
                                SwingUtilities.invokeLater {
                                    bubblePanel.scrollRectToVisible(Rectangle(0, bubblePanel.height - 1, 1, 1))
                                }
                            } else {
                                area.repaint()
                            }
                        }
                    }
                } else {
                    // 队列为空，检查是否应该停止
                    // 注意：这里不需要立即停止 Timer，因为可能还有新内容通过 appendThinking 进来
                    // 只有当 thinkingFinishCallback 存在时（说明后端已完成），才停止
                    
                    // 但为了节省资源，如果队列空了，我们可以暂停？
                    // 不，原逻辑是停止。但原逻辑依赖 appendThinking 重新启动。
                    // 我们保持原逻辑：队列空了就停止。
                    
                    (it.source as javax.swing.Timer).stop()
                    thinkingTimer = null
                    
                    bubblePanel.revalidate()
                    bubblePanel.repaint()
                    
                    // 检查是否有完成回调（即 Backend 已 COMPLETE，等待队列排空）
                    thinkingFinishCallback?.invoke()
                    thinkingFinishCallback = null
                }
            }
        }
        thinkingTimer?.start()
    }
    
    /**
     * 核心修复：重写 getPreferredSize 以实现自动换行
     * 策略变更：不再尝试计算最大宽度，而是返回一个较小的首选宽度。
     * 依靠父布局 (MessageWrapper/GridBagLayout) 的 fill=HORIZONTAL 特性将气泡拉伸到实际可用宽度。
     * 这样可以防止气泡"撑大"容器，迫使内部组件在受限空间内进行换行。
     */
    override fun getPreferredSize(): Dimension {
        // 1. 设置一个较小的基准宽度，确保父容器能将其压缩到这个尺寸
        val baseWidth = JBUI.scale(100)
        
        // 2. 计算高度
        // 此时我们需要基于"当前实际宽度"来计算高度，如果尚未布局(width=0)，则使用基准宽度估算
        val calcWidth = if (width > 0) width else JBUI.scale(300) // 估算值，仅用于初始高度计算
        
        var totalHeight = 0
        val insets = bubblePanel.insets
        totalHeight += insets.top + insets.bottom
        
        // 遍历 bubblePanel 的子组件 (BoxLayout)
        for (comp in bubblePanel.components) {
            if (!comp.isVisible) continue
            
            if (comp == contentWrapper) {
                // 深入 contentWrapper 查找 CollapsiblePanel
                val wrapperInsets = (comp as JComponent).insets
                val layout = comp.layout
                val centerComp = if (layout is BorderLayout) layout.getLayoutComponent(BorderLayout.CENTER) else null
                
                if (centerComp is CollapsiblePanel) {
                    val availableWidth = (calcWidth - insets.left - insets.right - wrapperInsets.left - wrapperInsets.right).coerceAtLeast(JBUI.scale(50))
                    totalHeight += centerComp.calculateHeight(availableWidth) + wrapperInsets.top + wrapperInsets.bottom
                } else {
                    totalHeight += comp.preferredSize.height
                }
            } else {
                // thinkingWrapper 或其他组件
                totalHeight += comp.preferredSize.height
            }
        }
        
        return Dimension(baseWidth, totalHeight)
    }

    // 统一处理链接跳转
    private fun handleLink(url: String) {
        if (url.startsWith("psi_class://")) {
            val className = url.removePrefix("psi_class://")
            project?.let { PsiNavigationHelper.navigateToClass(it, className) }
        } else if (url.startsWith("psi_method://")) {
            val methodName = url.removePrefix("psi_method://")
            project?.let { PsiNavigationHelper.navigateToMethod(it, methodName) }
        } else if (url.startsWith("psi_location://")) {
            val location = url.removePrefix("psi_location://")
            project?.let { PsiNavigationHelper.navigateToLocation(it, location) }
        }
    }

    // 辅助方法：注入零宽空格以支持长文本换行，并自动识别方法和类名为链接
    private fun injectWordBreaks(html: String): String {
        val tagMap = mutableMapOf<String, String>()
        var tagCounter = 0
        // 保护所有 HTML 标签，避免破坏属性（如 href="..."）
        // 将该闭包提取出来以便复用
        fun protect(text: String): String {
            return text.replace(Regex("<[^>]+>")) {
                // 使用不包含 . / _ 的占位符，防止替换逻辑破坏占位符
                val key = "@@@TAG${tagCounter++}@@@"
                tagMap[key] = it.value
                key
            }
        }

        var processed = protect(html)
        
        // === 核心修复：两阶段保护 ===
        // 1. 识别方法调用和类名，生成链接标签
        // 2. 立即再次调用 protect() 将新生成的标签也保护起来，防止后续的 <wbr> 插入逻辑破坏标签结构
        
        // 1. 识别方法调用: 小写开头，后跟左括号 e.g. findJavaFiles(
        // 已移除：在 renderNodeRecursive 中统一处理，避免标签损坏
        
        // 2. 识别类名 (启发式): 大写开头，后跟空格和小写字母
        // 已移除：在 renderNodeRecursive 中统一处理


        // 🔥 修复：使用零宽空格实现软换行
        // <wbr> 在 JEditorPane 复制时会被转换成空格，改用零宽空格
        // 零宽空格在复制时会保留，可以在 copyText 中清理
        val breakTag = "&#8203;"  // 零宽空格 \u200B

        // 在关键分隔符后插入换行机会
        processed = processed.replace("/", "/$breakTag")
        processed = processed.replace("\\", "\\$breakTag") // 支持 Windows 路径换行
        processed = processed.replace("-", "-$breakTag")   // 支持连字符换行
        // 移除对 . 和 _ 的强制换行，避免破坏包名和变量名
        // processed = processed.replace(".", ".$breakTag")
        // processed = processed.replace("_", "_$breakTag") 

        // 移除驼峰命名换行，彻底解决复制类名带不可见字符的问题
        // processed = processed.replace(Regex("(?<=[a-z])(?=[A-Z])"), breakTag)

        // 还原标签
        for ((key, value) in tagMap) {
            processed = processed.replace(key, value)
        }
        return processed
    }
    
    // 确保气泡可以缩小，防止撑大容器
    override fun getMinimumSize(): Dimension {
        return Dimension(JBUI.scale(100), super.getMinimumSize().height)
    }

    private fun smartUpdate(container: JPanel, newWrapper: JPanel) {
        if (container.componentCount != 1) {
            container.removeAll()
            container.add(newWrapper)
            container.revalidate()
            container.repaint()
            return
        }
        
        val currentWrapper = container.getComponent(0) as? JPanel
        if (currentWrapper == null || currentWrapper.layout !is BorderLayout) {
            container.removeAll()
            container.add(newWrapper)
            container.revalidate()
            container.repaint()
            return
        }
        
        // Update CollapsiblePanel (CENTER)
        val layout = currentWrapper.layout as BorderLayout
        val currentCollapsible = layout.getLayoutComponent(BorderLayout.CENTER) as? CollapsiblePanel
        val newCollapsible = (newWrapper.layout as BorderLayout).getLayoutComponent(BorderLayout.CENTER) as? CollapsiblePanel
        
        if (currentCollapsible != null && newCollapsible != null) {
            smartUpdateCollapsible(currentCollapsible, newCollapsible)
        } else {
            container.removeAll()
            container.add(newWrapper)
            container.revalidate()
            container.repaint()
            return
        }
        
        // Update Buttons (SOUTH)
        val newButtons = (newWrapper.layout as BorderLayout).getLayoutComponent(BorderLayout.SOUTH) as? JPanel
        if (newButtons != null) {
            // 核心修复：Rebind CollapseButton to currentCollapsible
            if (currentCollapsible != null) {
                // Find the button panel (it's a wrapper with BorderLayout, holding FlowLayout panel in EAST)
                // Structure in renderContent:
                // buttonWrapper (BorderLayout) -> buttonPanel (FlowLayout, EAST) -> [Nav, Collapse, Copy]
                
                val buttonPanel = (newButtons.layout as BorderLayout).getLayoutComponent(BorderLayout.EAST) as? JPanel
                if (buttonPanel != null) {
                    for (comp in buttonPanel.components) {
                        if (comp is CollapseButton) {
                            // Sync state
                            comp.isCollapsed = currentCollapsible.isCollapsed
                            
                            // Rebind callback
                            comp.onToggle = { isCollapsed ->
                                currentCollapsible.isCollapsed = isCollapsed
                                SwingUtilities.invokeLater {
                                    // Trigger update up the chain
                                    var parent = currentWrapper.parent
                                    while (parent != null) {
                                        if (parent is JPanel && parent != currentWrapper) { // MessageWrapper
                                             parent.revalidate()
                                             parent.repaint()
                                             break
                                        }
                                        parent = parent.parent
                                    }
                                    // Also revalidate currentWrapper
                                    currentWrapper.revalidate()
                                    currentWrapper.repaint()
                                }
                            }
                        }
                    }
                }
            }
            currentWrapper.add(newButtons, BorderLayout.SOUTH)
        } else {
            val oldButtons = layout.getLayoutComponent(BorderLayout.SOUTH)
            if (oldButtons != null) currentWrapper.remove(oldButtons)
        }
        
        currentWrapper.revalidate()
        currentWrapper.repaint()
        container.revalidate()
        container.repaint()
        
        // Auto-scroll to show the latest content
        SwingUtilities.invokeLater {
            if (container.isShowing) {
                container.scrollRectToVisible(Rectangle(0, container.height - 1, 1, 1))
            }
        }
    }

    private fun smartUpdateCollapsible(currentPanel: CollapsiblePanel, newPanel: CollapsiblePanel) {
         val currentComps = currentPanel.components
         val newComps = newPanel.components
         
         if (currentComps.size != newComps.size) {
             currentPanel.removeAll()
             newComps.forEach { currentPanel.add(it) }
             return
         }
         
         var contentChanged = false
         for (i in currentComps.indices) {
             val cur = currentComps[i]
             val next = newComps[i]
             
             if (cur::class != next::class) {
                 currentPanel.removeAll()
                 newComps.forEach { currentPanel.add(it) }
                 return
             }
             
             if (cur is JEditorPane && next is JEditorPane) {
                 if (cur.text != next.text) {
                     cur.text = next.text
                     contentChanged = true
                 }
             } else if (cur is JPanel && next is JPanel) {
                 val curEditor = findEditorTextField(cur)
                 val nextEditor = findEditorTextField(next)
                 if (curEditor != null && nextEditor != null) {
                     if (curEditor.text != nextEditor.text) {
                         // 核心修复：当代码块内容变化时，不复用旧组件，而是直接替换。
                         // 这样可以确保 EditorTextField 重新初始化，重新绑定 DocumentListener 和 LinkListener，
                         // 彻底解决打字机过程中链接失效的问题。
                         currentPanel.remove(i)
                         currentPanel.add(next, i)
                         contentChanged = true
                     }
                 } else {
                      currentPanel.remove(i)
                      currentPanel.add(next, i)
                      contentChanged = true
                 }
             }
         }
         
         if (contentChanged) {
             currentPanel.revalidate()
             currentPanel.repaint()
         }
    }
    
    private fun findEditorTextField(container: Container): EditorTextField? {
        for (comp in container.components) {
            if (comp is EditorTextField) return comp
            if (comp is Container) {
                val found = findEditorTextField(comp)
                if (found != null) return found
            }
        }
        return null
    }

    private fun renderContent(content: String = this.text, showActions: Boolean = false): JPanel {
        // 🔧 预处理：修复列表格式问题
        // 1. 移除行首的 * 1. 这种错误格式，强制转换为 1. 以正确渲染为有序列表
        var processedContent = content.replace(Regex("^\\s*\\*\\s*(\\d+\\.)", RegexOption.MULTILINE), "$1")

        // 🔧 预处理：确保表格前有空行（flexmark 要求）
        // 检测表格模式：| 开头，下一行是分隔线（包含 ---）
        // 在表格前插入空行，确保 flexmark 能正确解析
        processedContent = processedContent.replace(
            Regex("(?<!\\n\\n)(\\|[^\\n]*\\n\\|?[-:\\s|]+\\|?[\\-:\\s|]*\\n)"),
            "\n$1"
        )

        val wrapperPanel = JPanel(BorderLayout())
        wrapperPanel.isOpaque = false
        
        val collapsiblePanel = CollapsiblePanel()
        wrapperPanel.add(collapsiblePanel, BorderLayout.CENTER)
        
        try {
            val options = MutableDataSet().apply {
                set(Parser.EXTENSIONS, listOf(
                    TablesExtension.create(),
                    StrikethroughExtension.create(),
                    AutolinkExtension.create()
                ))
                // 增强列表解析的兼容性
                set(Parser.LISTS_AUTO_LOOSE, true)
                set(Parser.LISTS_LOOSE_WHEN_PREV_HAS_TRAILING_BLANK_LINE, true)

                // 表格解析配置：启用更宽松的表格识别（不要求前导空行）
                set(TablesExtension.APPEND_MISSING_COLUMNS, true)
                set(TablesExtension.DISCARD_EXTRA_COLUMNS, true)
                set(TablesExtension.HEADER_SEPARATOR_COLUMN_MATCH, false)
                set(TablesExtension.MIN_HEADER_ROWS, 1)
                set(TablesExtension.MAX_HEADER_ROWS, 1)

                // 关键修复：将软换行渲染为 <br />，防止多行文本被合并显示
                set(HtmlRenderer.SOFT_BREAK, "<br />")

                // 修复汉字截断：添加自定义 CSS 样式
                // set(HtmlRenderer.FENCED_CODE_CONTENT_BLOCK, true)
            }
            val parser = Parser.builder(options).build()
            val document = parser.parse(processedContent)
            
            val htmlRenderer = HtmlRenderer.builder(options).build()
            
            // 创建可折叠面板，放在 CENTER
            // val collapsiblePanel = CollapsiblePanel() // Hoisted
            // container.add(collapsiblePanel, BorderLayout.CENTER)
            
            val currentHtmlBuffer = StringBuilder()
            // 维护当前打开的标签栈，用于在打断 HTML 流时自动补全闭合标签
            val openTags = java.util.ArrayDeque<String>()
    
            // 辅助函数：刷新 HTML 缓冲区到 UI
            fun flushHtml() {
                if (currentHtmlBuffer.isNotEmpty()) {
                    // 1. 暂时闭合当前所有打开的标签，确保 HTML 片段合法
                    // 栈底是外层 (e.g. blockquote)，栈顶是内层 (e.g. li)
                    // 闭合时应从栈顶开始 (e.g. </li></blockquote>)
                    // 关键修复：使用 toList().reversed() 替代直接调用 reversed()，避免在 Java 17 环境下误调用 Java 21 的 Deque.reversed() 方法
                    val suffix = openTags.toList().reversed().joinToString("") { "</$it>" }
                    
                    // 修复：不再注入 <style> 标签，改用 HTMLEditorKit.styleSheet.addRule (在 createHtmlPane 中处理)
                    // 这样可以避免 CSS 代码直接显示在界面上的问题，也能更稳定地支持样式渲染
                    val htmlContent = "<html><body>" + currentHtmlBuffer.toString() + suffix + "</body></html>"
                    
                    collapsiblePanel.add(createHtmlPane(htmlContent))
                    currentHtmlBuffer.clear()
                    
                    // 2. 为下一个片段重新开启标签
                    // 开启时从栈底开始 (e.g. <blockquote><li>)
                    openTags.forEach { currentHtmlBuffer.append("<$it>") }
                }
            }
    
            // 递归渲染函数
            fun renderNodeRecursive(node: com.vladsch.flexmark.util.ast.Node, context: String = "") {
                var child = node.firstChild
                while (child != null) {
                    // println("MessageBubble: Visiting node ${child.javaClass.simpleName}")
                    when (child) {
                        is FencedCodeBlock -> {
                            // 1. 先刷新之前的 HTML
                            flushHtml()
                            
                            // 2. 渲染代码块
                            val codeContent = child.contentChars.toString()
                            val language = child.info.toString().trim()
                            
                            // 圆角半径
                            val radius = JBUI.scale(12)
                            
                            val contentPanel: JPanel
                            
                            if (language.equals("mermaid", ignoreCase = true)) {
                                // === Mermaid 渲染逻辑 ===
                                val cardLayout = CardLayout()
                                // 🔧 修复：自定义 JPanel，让 preferredSize 只取当前可见卡片的大小
                                var currentCardIndex = 0
                                val cards = object : JPanel(cardLayout) {
                                    override fun getPreferredSize(): Dimension {
                                        // 返回当前可见卡片的 preferredSize，而不是最大的
                                        if (componentCount > currentCardIndex) {
                                            val currentComp = getComponent(currentCardIndex)
                                            val pref = currentComp.preferredSize
                                            // 添加一些边距
                                            return Dimension(pref.width, pref.height + JBUI.scale(10))
                                        }
                                        return super.getPreferredSize()
                                    }
                                }.apply {
                                    isOpaque = false
                                }
                                
                                // 🔧 在最开始就清理 LLM 生成的非法 mermaid 语法
                                // mermaid 节点文本中不支持中文引号和 HTML 标签
                                var cleanedMermaidCode = codeContent

                                // 1. 替换中文引号 "" 为英文引号 ""（解决 Parse error on line 3 问题）
                                cleanedMermaidCode = cleanedMermaidCode
                                    .replace("\"", "\"")  // 中文双引号 → 英文双引号
                                    .replace("'", "'")   // 中文单引号 → 英文单引号

                                // 2. 替换中文括号为英文括号
                                cleanedMermaidCode = cleanedMermaidCode
                                    .replace("（", "(")
                                    .replace("）", ")")

                                // 2.1 修复混用括号问题：{文本] → {文本} 或 [文本} → [文本]
                                cleanedMermaidCode = cleanedMermaidCode
                                    .replace(Regex("""\{([^}\]]+)\]"""), """{$1}""")  // {文本] → {文本}
                                    .replace(Regex("""\[([^{\[]+)\}"""), """[$1]""")  // [文本} → [文本]

                                // 3. 移除 <br/> 标签（mermaid 不支持 HTML 标签在节点文本中）
                                cleanedMermaidCode = cleanedMermaidCode
                                    .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), " ")

                                // 4. 移除其他可能导致问题的 HTML 标签
                                cleanedMermaidCode = cleanedMermaidCode
                                    .replace(Regex("<[^>]+>"), "")

                                // 5. 清理多余空格（保留必要的换行）
                                cleanedMermaidCode = cleanedMermaidCode
                                    .replace(Regex(" +"), " ")
                                    .replace(Regex("\n\\s*\n"), "\n")

                                println("🔧 [Mermaid] Cleaned code: ${cleanedMermaidCode.take(200)}...")
                                
                                // 1. Preview Card
                                // 离线渲染：使用 JBCefBrowser (JCEF)
                                val previewPanel = JPanel(BorderLayout()).apply {
                                    isOpaque = false
                                    border = BorderFactory.createEmptyBorder(JBUI.scale(8), JBUI.scale(8), JBUI.scale(8), JBUI.scale(8))
                                }

                                if (!JBCefApp.isSupported()) {
                                    previewPanel.add(JLabel("当前 IDE 不支持 JCEF，无法渲染图表", SwingConstants.CENTER).apply {
                                        foreground = ChatColors.textSecondary
                                    }, BorderLayout.CENTER)
                                } else {
                                    // 创建 JBCefBrowser
                                    val browser = JBCefBrowser()
                                    // 注册销毁：绑定到 project 或 chatPanel (这里暂时没有 chatPanel 引用，绑定到 bubblePanel 的生命周期？)
                                    // 由于 MessageBubble 生命周期较长，且可能被移除，最好在 removeNotify 中销毁，但 Swing 组件销毁比较麻烦。
                                    // 简单起见，我们让它跟随 project，或者在 Component 移除时销毁。
                                    // 注意：频繁创建 Browser 开销较大，但对于少量图表可接受。
                                    
                                    // 放入 Panel
                                    // 🔧 修复：使用动态高度，根据内容自适应
                                    val browserComp = browser.component
                                    
                                    // 使用包装 Panel 来控制布局
                                    // 🔧 逻辑：宽度撑满，高度自适应但最大 600px
                                    val maxAllowedHeight = JBUI.scale(600)
                                    val browserWrapper = object : JPanel(BorderLayout()) {
                                        private var dynamicHeight = JBUI.scale(300) // 默认高度
                                        
                                        fun setDynamicHeight(height: Int) {
                                            val newHeight = height.coerceAtMost(maxAllowedHeight)
                                            if (newHeight != dynamicHeight) {
                                                dynamicHeight = newHeight
                                                // 🔧 刷新整个组件树
                                                invalidate()
                                                revalidate()
                                                repaint()
                                                // 向上遍历刷新所有父容器
                                                var p = parent
                                                while (p != null) {
                                                    p.invalidate()
                                                    p.revalidate()
                                                    p.repaint()
                                                    p = p.parent
                                                }
                                            }
                                        }
                                        
                                        override fun getPreferredSize(): Dimension {
                                            val parentWidth = parent?.width ?: JBUI.scale(600)
                                            return Dimension(parentWidth, dynamicHeight)
                                        }
                                        
                                        override fun getMaximumSize(): Dimension {
                                            return Dimension(Int.MAX_VALUE, maxAllowedHeight)
                                        }
                                        
                                        override fun getMinimumSize(): Dimension {
                                            return Dimension(JBUI.scale(100), JBUI.scale(100))
                                        }
                                    }.apply {
                                        isOpaque = false
                                        add(browserComp, BorderLayout.CENTER)
                                    }
                                    previewPanel.add(browserWrapper, BorderLayout.CENTER)
                                    
                                    // 保存引用，用于后续高度调整
                                    val wrapperRef = browserWrapper
                                    
                                    // 🔧 初始高度，等待 JS 回调后动态调整
                                    wrapperRef.setDynamicHeight(JBUI.scale(300))
                                    
                                    // 🔧 使用 CefLoadHandler 在页面加载完成后获取实际高度
                                    browser.jbCefClient.addLoadHandler(object : org.cef.handler.CefLoadHandlerAdapter() {
                                        override fun onLoadEnd(cefBrowser: org.cef.browser.CefBrowser?, frame: org.cef.browser.CefFrame?, httpStatusCode: Int) {
                                            println("🔧 [Mermaid] onLoadEnd: isMain=${frame?.isMain}, status=$httpStatusCode")
                                            if (frame?.isMain == true) {
                                                // 延迟执行，等待 Mermaid 渲染完成
                                                java.util.Timer().schedule(object : java.util.TimerTask() {
                                                    override fun run() {
                                                        try {
                                                            println("🔧 [Mermaid] Executing JS to get height...")
                                                            // 执行 JS 获取 SVG 高度并设置到 title
                                                            cefBrowser?.executeJavaScript("""
                                                                (function() {
                                                                    var svg = document.querySelector('.mermaid svg');
                                                                    console.log('Mermaid SVG:', svg);
                                                                    if (svg) {
                                                                        var height = Math.ceil(svg.getBoundingClientRect().height) + 30;
                                                                        console.log('Mermaid height:', height);
                                                                        document.title = 'H:' + height;
                                                                    } else {
                                                                        console.log('No SVG found');
                                                                    }
                                                                })();
                                                            """.trimIndent(), "", 0)
                                                        } catch (e: Exception) {
                                                            println("🔧 [Mermaid] JS Error: ${e.message}")
                                                        }
                                                    }
                                                }, 800) // 延迟 800ms 等待 Mermaid 渲染
                                            }
                                        }
                                    }, browser.cefBrowser)
                                    
                                    // 🔧 监听 title 变化获取高度
                                    browser.jbCefClient.addDisplayHandler(object : org.cef.handler.CefDisplayHandlerAdapter() {
                                        override fun onTitleChange(cefBrowser: org.cef.browser.CefBrowser?, title: String?) {
                                            println("🔧 [Mermaid] onTitleChange: $title")
                                            if (title != null && title.startsWith("H:")) {
                                                try {
                                                    val height = title.removePrefix("H:").toInt().coerceIn(100, 200)
                                                    println("🔧 [Mermaid] Setting height to: $height")
                                                    javax.swing.SwingUtilities.invokeLater {
                                                        wrapperRef.setDynamicHeight(JBUI.scale(height))
                                                    }
                                                } catch (e: Exception) {
                                                    println("🔧 [Mermaid] Error: ${e.message}")
                                                }
                                            }
                                        }
                                    }, browser.cefBrowser)
                                    
                                    // 加载 Mermaid 库和渲染代码
                                    try {
                                        val mermaidJsStream = this::class.java.getResourceAsStream("/ui/mermaid.min.js")
                                        if (mermaidJsStream == null) {
                                            browser.loadHTML("<html><body><h3 style='color:red'>Error: mermaid.min.js not found in resources.</h3></body></html>")
                                        } else {
                                            val mermaidJs = mermaidJsStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                                            
                                            // 检查是否是占位符
                                            if (mermaidJs.contains("PLACEHOLDER FOR MERMAID.JS")) {
                                                browser.loadHTML("""
                                                    <html>
                                                    <head>
                                                        <meta charset="UTF-8">
                                                    </head>
                                                    <body style="background-color: ${ChatStyles.colorToHex(ChatColors.codeBackground)}; color: ${ChatStyles.colorToHex(ChatColors.textPrimary)}; font-family: sans-serif; padding: 20px;">
                                                        <h3>Mermaid 库未安装</h3>
                                                        <p>为了支持离线渲染，请下载 <b>mermaid.min.js</b> 并替换以下文件：</p>
                                                        <pre>src/main/resources/ui/mermaid.min.js</pre>
                                                        <p>下载地址: <a href="https://cdn.jsdelivr.net/npm/mermaid/dist/mermaid.min.js">jsdelivr</a></p>
                                                    </body>
                                                    </html>
                                                """.trimIndent())
                                            } else {
                                                // 🔧 使用宣纸色背景（柔和米黄色，眼睛友好）
                                                val bgColor = "#E6DDD0"  // 米黄色偏白
                                                val textColor = "#333333"  // 深灰色文字
                                                
                                                // 🔧 使用 JSON 编码确保特殊字符（如换行、引号、Unicode）正确传递给 JS
                                                // 避免直接拼接 HTML 导致的实体转义问题或语法错误
                                                val jsonCode = org.json.JSONObject.quote(cleanedMermaidCode)
                                                println("DEBUG MERMAID JSON: $jsonCode") // 🔧 调试日志
                                                
                                                val htmlContent = """
                                                    <!DOCTYPE html>
                                                    <html>
                                                    <head>
                                                        <meta charset="UTF-8">
                                                        <style>
                                                            html, body {
                                                                background-color: $bgColor;
                                                                color: $textColor;
                                                                margin: 0;
                                                                padding: 10px;
                                                                font-family: sans-serif;
                                                                /* 🔧 允许内部滚动，滚动条默认隐藏 */
                                                                overflow: auto;
                                                            }
                                                            /* 🔧 滚动条宽度为0，悬停时显示 */
                                                            html, body {
                                                                /* 🔧 使用 overlay 并设置滚动条宽度为0 */
                                                                overflow-y: overlay;
                                                            }
                                                            ::-webkit-scrollbar {
                                                                width: 0;
                                                                height: 0;
                                                                display: block;
                                                            }
                                                            ::-webkit-scrollbar-track {
                                                                background: transparent;
                                                            }
                                                            ::-webkit-scrollbar-thumb {
                                                                background-color: rgba(0, 0, 0, 0.2);
                                                                border-radius: 4px;
                                                            }
                                                            ::-webkit-scrollbar-thumb:hover {
                                                                background-color: rgba(0, 0, 0, 0.4);
                                                            }
                                                            .mermaid { 
                                                                display: block;
                                                                text-align: center;
                                                            }
                                                            .mermaid svg {
                                                                max-width: 100%;
                                                                height: auto;
                                                                /* 🔧 SVG 圆角 */
                                                                border-radius: 8px;
                                                            }
                                                        </style>
                                                        <script>
                                                            $mermaidJs
                                                            
                                                            document.addEventListener('DOMContentLoaded', async function() {
                                                                try {
                                                                    mermaid.initialize({ 
                                                                        startOnLoad: false,
                                                                        theme: 'default',
                                                                        securityLevel: 'loose',
                                                                        flowchart: { 
                                                                            useMaxWidth: false
                                                                        },
                                                                        gantt: {
                                                                            useMaxWidth: false
                                                                        }
                                                                    });
                                                                    
                                                                    const code = $jsonCode;
                                                                    const div = document.querySelector('.mermaid');
                                                                    div.textContent = code;
                                                                    
                                                                    await mermaid.run({ nodes: [div] });
                                                                    
                                                                    // 🔧 渲染完成后，给 SVG 元素添加圆角
                                                                    const svg = div.querySelector('svg');
                                                                    if (svg) {
                                                                        svg.style.borderRadius = '8px';
                                                                        // 给所有 rect（矩形节点）添加圆角
                                                                        svg.querySelectorAll('rect').forEach(rect => {
                                                                            rect.style.rx = '6px';
                                                                            rect.style.ry = '6px';
                                                                        });
                                                                    }
                                                                } catch (e) {
                                                                    console.error('Mermaid Error:', e);
                                                                    document.body.innerHTML = '<div style="padding:20px;background:#fff8f8;">' +
                                                                        '<div style="color:#c00;font-weight:bold;margin-bottom:10px;">Mermaid 渲染失败</div>' +
                                                                        '<div style="color:#666;font-size:12px;margin-bottom:8px;">' + e.message + '</div>' +
                                                                        '<pre style="background:#f5f5f5;padding:10px;border:1px solid #ddd;font-size:11px;white-space:pre-wrap;max-height:200px;overflow:auto;">' + 
                                                                        $jsonCode.replace(/</g,'&lt;') + '</pre>' +
                                                                        '</div>';
                                                                }
                                                            });
                                                        </script>
                                                    </head>
                                                    <body>
                                                        <div class="mermaid"></div>
                                                    </body>
                                                    </html>
                                                """.trimIndent()
                                                
                                                browser.loadHTML(htmlContent)
                                            }
                                        }
                                    } catch (e: Exception) {
                                        browser.loadHTML("<html><body>Error loading mermaid: ${e.message}</body></html>")
                                    }
                                    
                                    // 注册销毁逻辑：当 contentPanel 所在的 MessageBubble 被移除时，需要销毁 browser
                                    // 这里使用一个简单的生命周期监听
                                    previewPanel.addHierarchyListener { e ->
                                        if (e.changeFlags and java.awt.event.HierarchyEvent.PARENT_CHANGED.toLong() != 0L) {
                                            if (previewPanel.parent == null) {
                                                Disposer.dispose(browser)
                                            }
                                        }
                                    }
                                }
                                
                                cards.add(previewPanel, "PREVIEW")
                                
                                // 2. Code Card
                                val editorComp = createCodeEditor(codeContent, language)
                                cards.add(editorComp, "CODE")
                                
                                // 3. Header with Toggle
                                val headerPanel = JPanel(BorderLayout()).apply {
                                    isOpaque = false
                                    border = BorderFactory.createEmptyBorder(JBUI.scale(2), JBUI.scale(8), JBUI.scale(2), JBUI.scale(4))
                                    
                                    // 左侧：语言标识
                                    add(JLabel(language).apply {
                                        foreground = ChatColors.textSecondary
                                        font = UIUtil.getLabelFont().deriveFont(Font.BOLD, JBUI.scale(11f))
                                    }, BorderLayout.WEST)
                                    
                                    // 右侧：按钮组
                                    val buttonPanel = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(4), 0)).apply {
                                        isOpaque = false
                                        
                                        // 🔍 放大镜按钮 - 弹出全屏查看（使用清理后的代码）
                                        add(ZoomButton(cleanedMermaidCode, project))
                                        
                                        // 切换按钮
                                        add(SplitViewButton {
                                            currentCardIndex = 1 - currentCardIndex  // 0 -> 1, 1 -> 0
                                            cardLayout.next(cards)
                                            cards.revalidate()
                                            cards.repaint()
                                        })
                                        // 复制按钮（使用清理后的代码）
                                        add(CopyButton { cleanedMermaidCode })
                                    }
                                    add(buttonPanel, BorderLayout.EAST)
                                }
                                
                                // 4. Container
                                contentPanel = object : JPanel(BorderLayout()) {
                                    override fun paintComponent(g: Graphics) {
                                        val g2 = g as Graphics2D
                                        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                                        g2.color = background
                                        g2.fillRoundRect(0, 0, width - 1, height - 1, radius, radius)
                                        super.paintComponent(g)
                                    }
                                    
                                    override fun paintChildren(g: Graphics) {
                                        val g2 = g.create() as Graphics2D
                                        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                                        val clip = java.awt.geom.RoundRectangle2D.Float(
                                            1f, 1f, 
                                            (width - 2).toFloat(), (height - 2).toFloat(), 
                                            radius.toFloat(), radius.toFloat()
                                        )
                                        g2.clip(clip)
                                        super.paintChildren(g2)
                                        g2.dispose()
                                    }
                                }.apply {
                                    isOpaque = false
                                    background = ChatColors.codeBackground
                                    border = RoundedBorder(radius)
                                    
                                    add(headerPanel, BorderLayout.NORTH)
                                    add(cards, BorderLayout.CENTER)
                                }
                                
                                // 异步加载图片 (Removed)
                                
                            } else {
                                // === 普通代码块渲染逻辑 ===
                                val editorComp = createCodeEditor(codeContent, language)
                                
                                contentPanel = object : JPanel(BorderLayout()) {
                                    override fun paintComponent(g: Graphics) {
                                        val g2 = g as Graphics2D
                                        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                                        g2.color = background
                                        g2.fillRoundRect(0, 0, width - 1, height - 1, radius, radius)
                                        super.paintComponent(g)
                                    }
                                    
                                    override fun paintChildren(g: Graphics) {
                                        val g2 = g.create() as Graphics2D
                                        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                                        val clip = java.awt.geom.RoundRectangle2D.Float(
                                            1f, 1f, 
                                            (width - 2).toFloat(), (height - 2).toFloat(), 
                                            radius.toFloat(), radius.toFloat()
                                        )
                                        g2.clip(clip)
                                        super.paintChildren(g2)
                                        g2.dispose()
                                    }
                                }.apply {
                                    isOpaque = false
                                    background = ChatColors.codeBackground
                                    border = RoundedBorder(radius)
                                    
                                    val headerPanel = JPanel(BorderLayout()).apply {
                                        isOpaque = false
                                        border = BorderFactory.createEmptyBorder(JBUI.scale(2), JBUI.scale(8), JBUI.scale(2), JBUI.scale(4))
                                        
                                        if (language.isNotBlank()) {
                                            add(JLabel(language).apply {
                                                foreground = ChatColors.textSecondary
                                                font = UIUtil.getLabelFont().deriveFont(Font.BOLD, JBUI.scale(11f))
                                            }, BorderLayout.WEST)
                                        }

                                        add(CopyButton { codeContent }, BorderLayout.EAST)
                                    }
                                    
                                    add(headerPanel, BorderLayout.NORTH)
                                    add(editorComp, BorderLayout.CENTER)
                                }
                            }
                            
                            // 根据上下文添加样式装饰
                            val wrapper = if (context == "blockquote") {
                                // 引用块中的代码：添加左侧竖线
                                JPanel(BorderLayout()).apply {
                                    isOpaque = false
                                    border = BorderFactory.createCompoundBorder(
                                        BorderFactory.createMatteBorder(0, JBUI.scale(4), 0, 0, JBColor(Color(0xDDDDDD), Color(0x505050))),
                                        BorderFactory.createEmptyBorder(0, JBUI.scale(8), 0, 0)
                                    )
                                    add(contentPanel, BorderLayout.CENTER)
                                }
                            } else if (context == "list") {
                                // 列表中的代码：添加左侧缩进
                                JPanel(BorderLayout()).apply {
                                    isOpaque = false
                                    border = BorderFactory.createEmptyBorder(0, JBUI.scale(20), 0, 0)
                                    add(contentPanel, BorderLayout.CENTER)
                                }
                            } else {
                                contentPanel
                            }
    
                            collapsiblePanel.add(wrapper)
                            collapsiblePanel.add(Box.createVerticalStrut(JBUI.scale(8)))
                        }
                        
                        is com.vladsch.flexmark.ext.tables.TableBlock -> {
                            flushHtml()
                            
                            val tableHtml = htmlRenderer.render(child)
                            val tablePane = createHtmlPane(tableHtml)
                            
                            val scrollPane = object : JBScrollPane(tablePane) {
                                override fun getPreferredSize(): Dimension {
                                    val superSize = super.getPreferredSize()
                                    return Dimension(JBUI.scale(100), superSize.height + JBUI.scale(20))
                                }
                                override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, super.getMaximumSize().height)
                            }.apply {
                                border = BorderFactory.createEmptyBorder()
                                viewport.isOpaque = false
                                isOpaque = false
                                background = ChatColors.surface
                                horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED
                                verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER
                                viewportBorder = null
                            }
                            
                            // 上下文样式
                            val wrapper = if (context == "blockquote") {
                                JPanel(BorderLayout()).apply {
                                    isOpaque = false
                                    border = BorderFactory.createCompoundBorder(
                                        BorderFactory.createMatteBorder(0, JBUI.scale(4), 0, 0, JBColor(Color(0xDDDDDD), Color(0x505050))),
                                        BorderFactory.createEmptyBorder(0, JBUI.scale(8), 0, 0)
                                    )
                                    add(scrollPane, BorderLayout.CENTER)
                                }
                            } else if (context == "list") {
                                JPanel(BorderLayout()).apply {
                                    isOpaque = false
                                    border = BorderFactory.createEmptyBorder(0, JBUI.scale(20), 0, 0)
                                    add(scrollPane, BorderLayout.CENTER)
                                }
                            } else {
                                scrollPane
                            }
                            
                            collapsiblePanel.add(wrapper)
                            collapsiblePanel.add(Box.createVerticalStrut(JBUI.scale(8)))
                        }
                        
                        is com.vladsch.flexmark.ast.BlockQuote -> {
                            openTags.addLast("blockquote")
                            currentHtmlBuffer.append("<blockquote>")
                            renderNodeRecursive(child, "blockquote")
                            // 必须先移除，再 append 结束标签，否则 flushHtml 会重复关闭
                            openTags.removeLast()
                            currentHtmlBuffer.append("</blockquote>")
                        }
                        
                        is com.vladsch.flexmark.ast.BulletList -> {
                            openTags.addLast("ul")
                            currentHtmlBuffer.append("<ul>")
                            renderNodeRecursive(child, "list")
                            openTags.removeLast()
                            currentHtmlBuffer.append("</ul>")
                        }
                        
                        is com.vladsch.flexmark.ast.OrderedList -> {
                            openTags.addLast("ol")
                            val listNode = child as com.vladsch.flexmark.ast.OrderedList
                            val start = listNode.startNumber
                            if (start != 1) {
                                currentHtmlBuffer.append("<ol start=\"$start\">")
                            } else {
                                currentHtmlBuffer.append("<ol>")
                            }
                            renderNodeRecursive(child, "list")
                            openTags.removeLast()
                            currentHtmlBuffer.append("</ol>")
                        }
                        
                        is com.vladsch.flexmark.ast.ListItem -> {
                            openTags.addLast("li")
                            currentHtmlBuffer.append("<li>")
                            renderNodeRecursive(child, context) // 保持父级上下文 (list)
                            
                            // 修复空列表项：如果 Buffer 以 <li> 结尾，说明该项为空（可能因为 flushHtml 重置后无内容），删除之
                            if (currentHtmlBuffer.endsWith("<li>")) {
                                currentHtmlBuffer.setLength(currentHtmlBuffer.length - 4) // remove "<li>"
                            } else {
                                currentHtmlBuffer.append("</li>")
                            }
                            openTags.removeLast()
                        }
                        
                        else -> {
                            // 其他节点 (Paragraph, Text, etc.)
                            // 使用 renderer 渲染为 HTML 片段
                            var htmlFragment = htmlRenderer.render(child)

                            
                            // === 复用之前的链接处理和 injectWordBreaks 逻辑 ===
                            val linkMap = mutableMapOf<String, String>()
                            var linkCounter = 0
                            val protectLinks: (String) -> String = { text ->
                                text.replace(Regex("<a\\b[^>]*>.*?</a>", RegexOption.DOT_MATCHES_ALL)) {
                                    val key = "___LINK_${linkCounter++}___"
                                    linkMap[key] = it.value
                                    key
                                }
                            }
                            
                            
                            htmlFragment = htmlFragment.replace(
                                Regex("<code>([A-Z][a-zA-Z0-9_]+)\\.([a-z][a-zA-Z0-9_]+)</code>"),
                                "<code><a href=\"psi_location://\$1.\$2\" style=\"color: ${ChatStyles.colorToHex(ChatColors.locationLinkColor)}; text-decoration: none;\">\$1.\$2</a></code>"
                            )
                            htmlFragment = protectLinks(htmlFragment)
                            
                            htmlFragment = htmlFragment.replace(
                                Regex("<code>([A-Z][a-zA-Z0-9_]{2,})</code>"), 
                                "<code><a href=\"psi_class://\$1\" style=\"color: ${ChatStyles.colorToHex(ChatColors.classLinkColor)}; text-decoration: none;\">\$1</a></code>"
                            )
                            htmlFragment = protectLinks(htmlFragment)
                            
                            htmlFragment = htmlFragment.replace(
                                Regex("<code>([a-z][a-zA-Z0-9_]*)(\\(\\))?</code>"), 
                                "<code><a href=\"psi_method://\$1\" style=\"color: ${ChatStyles.colorToHex(ChatColors.methodLinkColor)}; text-decoration: none;\">\$1\$2</a></code>"
                            )
                            htmlFragment = protectLinks(htmlFragment) // 保护代码块链接
                            htmlFragment = protectLinks(htmlFragment) // 保护 Markdown 自动链接
                            
                            // 2. 处理普通文本 (逐层处理并保护)
                            
                            // C. 堆栈跟踪/全限定名: com.pkg.Class.method() [123]
                            htmlFragment = htmlFragment.replace(
                                Regex("(?<=[\\s>]|^)((?:[a-z0-9_]+\\.)+[A-Z][\\w$]*\\.[\\w$]+\\([^)]*\\))(?: (\\d+))?(?![a-zA-Z0-9_])")
                            ) { matchResult ->
                                val fullPath = matchResult.groupValues[1]
                                val lineNum = matchResult.groupValues.getOrNull(2)
                                val linkUrl = if (!lineNum.isNullOrBlank()) "psi_location://$fullPath:$lineNum" else "psi_location://$fullPath"
                                "<a href=\"$linkUrl\" style=\"color: ${ChatStyles.colorToHex(ChatColors.locationLinkColor)}; text-decoration: none;\">${matchResult.value}</a>"
                            }
                            htmlFragment = protectLinks(htmlFragment)
                            
                            // C2. 全限定类名 (可能带括号)
                            htmlFragment = htmlFragment.replace(
                                Regex("(?<=[\\s>]|^)((?:[a-z0-9_]+\\.)+[A-Z][\\w$]*(?:\\([^)]*\\))?)(?: (\\d+))?(?![a-zA-Z0-9_])")
                            ) { matchResult ->
                                val fullPath = matchResult.groupValues[1]
                                val lineNum = matchResult.groupValues.getOrNull(2)
                                val linkUrl = if (!lineNum.isNullOrBlank()) "psi_location://$fullPath:$lineNum" else "psi_location://$fullPath"
                                "<a href=\"$linkUrl\" style=\"color: ${ChatStyles.colorToHex(ChatColors.classLinkColor)}; text-decoration: none;\">${matchResult.value}</a>"
                            }
                            htmlFragment = protectLinks(htmlFragment)
    
                            // D. 类名.方法名 (支持变量名调用，如 abc.method)
                        htmlFragment = htmlFragment.replace(
                            Regex("(?<=[\\s>]|^)([a-zA-Z][a-zA-Z0-9_]*)\\.([a-z][a-zA-Z0-9_]+)(?![a-zA-Z0-9_])")
                        ) { matchResult ->
                            val className = matchResult.groupValues[1]
                            val methodName = matchResult.groupValues[2]
                            val fullPath = "$className.$methodName"
                            "<a href=\"psi_location://$fullPath\" style=\"color: ${ChatStyles.colorToHex(ChatColors.locationLinkColor)}; text-decoration: none;\">${matchResult.value}</a>"
                        }
                            htmlFragment = protectLinks(htmlFragment)
    
                            // A. 类名
                            htmlFragment = htmlFragment.replace(
                                Regex("(?<=[\\s>]|^)([A-Z][a-zA-Z0-9_]{2,})(?![a-zA-Z0-9_])"), 
                                "<a href=\"psi_class://\$1\" style=\"color: ${ChatStyles.colorToHex(ChatColors.classLinkColor)}; text-decoration: none;\">\$1</a>"
                            )
                            htmlFragment = protectLinks(htmlFragment)
    
                            // B. 方法名 (支持带参数，过滤关键字)
                            val methodKeywords = setOf("if", "for", "while", "switch", "catch", "synchronized", "return", "throw")
                            htmlFragment = htmlFragment.replace(
                                Regex("(?<=[\\s>]|^)([a-z][a-zA-Z0-9_]+)(\\s*\\([^)]*\\))(?![a-zA-Z0-9_])")
                            ) { match ->
                                val name = match.groupValues[1]
                                val args = match.groupValues[2]
                                if (name in methodKeywords) {
                                    match.value
                                } else {
                                    "<a href=\"psi_method://$name\" style=\"color: ${ChatStyles.colorToHex(ChatColors.methodLinkColor)}; text-decoration: none;\">$name</a>$args"
                                }
                            }
                            
                            // --- 还原链接 ---
                            for ((key, value) in linkMap) {
                                htmlFragment = htmlFragment.replace(key, value)
                            }
    
                            // 3. 注入零宽空格以支持长文本换行 (必须在链接还原后进行，但要保护标签)
                            htmlFragment = injectWordBreaks(htmlFragment)
    
                            currentHtmlBuffer.append(htmlFragment)
                        }
                    }
                    child = child.next
                }
            }
            
            // 开始递归遍历
            renderNodeRecursive(document)
            
            // 最终刷新，openTags 此时应该为空，但调用 flushHtml 保持一致性
            flushHtml()
            
            // 如果没有添加任何组件，显示提示
            if (collapsiblePanel.componentCount == 0) {
                println("MessageBubble: No components added to collapsiblePanel!")
                collapsiblePanel.add(JLabel("Empty Message").apply {
                    foreground = ChatColors.textSecondary
                })
            }

            // === 新增：添加操作栏（折叠 + 复制）到 SOUTH (BorderLayout) ===
            // 这样可以保证不被折叠内容覆盖
            // 修复：不再检查 initialText (this.text)，而是检查当前渲染的内容 content
            // 并且只有在内容非空且非"正在思考"且明确要求显示操作栏时才显示
            if (showActions && content.isNotEmpty() && !content.startsWith("Thinking")) {
                val buttonWrapper = JPanel(BorderLayout()).apply {
                    isOpaque = false
                    // 增加一点顶部间距
                    border = EmptyBorder(JBUI.scale(4), 0, 0, 0)
                    
                    val buttonPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0)).apply {
                        isOpaque = false
                        
                        // 0. Navigation Buttons
                    // 创建水平容器 (GridLayout 1x2)，确保固定占位
                    val navBox = JPanel(GridLayout(1, 2, JBUI.scale(2), 0)).apply {
                        isOpaque = false
                        // 确保不占太多宽度
                        border = BorderFactory.createEmptyBorder(0, 0, 0, JBUI.scale(4))
                    }
                    
                    navUpBtn = NavButton(true) { onNavUp?.invoke() }
                    navDownBtn = NavButton(false) { onNavDown?.invoke() }
                    
                    // 初始化状态：始终可见，仅禁用
                    navUpBtn?.isVisible = true
                    navUpBtn?.isEnabled = canNavUp
                    navDownBtn?.isVisible = true
                    navDownBtn?.isEnabled = canNavDown
                    
                    navBox.add(navUpBtn)
                    navBox.add(navDownBtn)
                    
                    add(navBox)
                    
                    // 增加间距
                    add(Box.createHorizontalStrut(JBUI.scale(16)))

                    // 1. 折叠/展开按钮 (传入背景色以便绘制图标)
                    // 🔥 用户消息不显示折叠按钮
                    if (!isUser) {
                        val collapseBtn = CollapseButton(ChatColors.assistantBubble) { isCollapsed ->
                            collapsiblePanel.isCollapsed = isCollapsed
                            SwingUtilities.invokeLater {
                                // 向上寻找 MessageWrapper 触发更新
                                var parent = this.parent
                                while (parent != null) {
                                    if (parent is JPanel) { // MessageWrapper
                                        parent.revalidate()
                                        parent.repaint()
                                        break
                                    }
                                    parent = parent.parent
                                }
                            }
                        }
                        add(collapseBtn)

                        add(Box.createHorizontalStrut(JBUI.scale(8)))
                    } // 🔥 end if (!isUser)

                    // 2. 复制按钮（无背景色）
                    add(CopyButton { content })
                    }
                    add(buttonPanel, BorderLayout.EAST)
                }
                wrapperPanel.add(buttonWrapper, BorderLayout.SOUTH)
            }
            // 强制刷新一次布局
            SwingUtilities.invokeLater {
                 collapsiblePanel.revalidate()
                 collapsiblePanel.repaint()
            }
        } catch (e: Throwable) {
            // 错误兜底：如果渲染失败，显示纯文本错误信息
            wrapperPanel.removeAll()
            wrapperPanel.add(JLabel("渲染错误: ${e.message}").apply {
                foreground = Color.RED
            }, BorderLayout.CENTER)
            println("MessageBubble: Render Error: ${e.message}")
        }
        return wrapperPanel
    }

    private fun createCodeEditor(code: String, language: String): JComponent {
        var type = "txt"
        if (language != "") {
            type = language
        }
        val fileType = FileTypeManager.getInstance().getFileTypeByExtension(type)
        
        val editorField = object : EditorTextField(code, project, fileType) {
            // 移除重写的 createEditor，改用 addSettingsProvider
            
            // 核心修复：重写 getMinimumSize，允许宽度被压缩，从而触发软换行
            override fun getMinimumSize(): Dimension {
                val superMin = super.getMinimumSize()
                // 允许宽度最小为 50px，这样父容器可以将其压缩
                return Dimension(JBUI.scale(50), superMin.height)
            }
        }
        
        editorField.setOneLineMode(false)
        editorField.ensureWillComputePreferredSize()
        editorField.border = JBUI.Borders.empty()
        
        // 使用 addSettingsProvider 统一管理 Editor 配置和事件监听
        editorField.addSettingsProvider { editor ->
            // 修复：应用清除不可见字符的复制 Action
            setupCleanCopyActionForEditor(editor)

            editor.isViewer = true
            
            editor.settings.isLineNumbersShown = true
            editor.settings.isFoldingOutlineShown = false
            editor.settings.isLineMarkerAreaShown = false
            editor.settings.isIndentGuidesShown = true
            editor.settings.isUseSoftWraps = true
            editor.settings.isVirtualSpace = false
            editor.settings.additionalLinesCount = 0
            
            editor.backgroundColor = ChatColors.codeBackground
            editor.colorsScheme.setColor(EditorColors.GUTTER_BACKGROUND, ChatColors.codeBackground)
            
            // 字体设置
            editor.colorsScheme.editorFontName = EditorColorsManager.getInstance().globalScheme.editorFontName
            editor.colorsScheme.editorFontSize = UIUtil.getLabelFont().size
            
            // 滚动条设置
            editor.scrollPane.horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            editor.scrollPane.verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
            editor.scrollPane.border = JBUI.Borders.empty()
            
            // Gutter 分割线
            (editor.gutter as? JComponent)?.let { gutter ->
                gutter.border = BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(0, 0, 0, JBUI.scale(1), ChatColors.divider),
                    BorderFactory.createEmptyBorder(0, JBUI.scale(5), 0, JBUI.scale(1))
                )
            }

            // 核心修复：定义更新链接的逻辑
            fun updateLinks() {
                // 必须在 EDT 中执行
                SwingUtilities.invokeLater {
                    if (!editor.isDisposed) {
                        addCodeBlockLinks(editor as EditorEx, editor.document.text)
                    }
                }
            }
            
            // 1. 立即执行一次，处理初始内容
            updateLinks()
            
            // 2. 监听 Document 变化，实时更新链接 (打字机效果会触发这里)
            editor.document.addDocumentListener(object : DocumentListener {
                override fun documentChanged(event: DocumentEvent) {
                    updateLinks()
                }
            })
        }
        
        return JPanel(BorderLayout()).apply {
            isOpaque = false
            border = BorderFactory.createEmptyBorder()
            add(editorField, BorderLayout.CENTER)
        }
    }

    private val LINK_HIGHLIGHTERS_KEY = Key.create<List<RangeHighlighter>>("LinkHighlighters")
    private val LINK_LISTENER_KEY = Key.create<EditorMouseListener>("LinkListener")

    private fun addCodeBlockLinks(editor: EditorEx, code: String) {
        // 0. 清理旧状态
        editor.getUserData(LINK_HIGHLIGHTERS_KEY)?.forEach { 
            editor.markupModel.removeHighlighter(it) 
        }
        editor.getUserData(LINK_LISTENER_KEY)?.let {
            editor.removeEditorMouseListener(it)
        }

        val locationAttributes = TextAttributes().apply {
            foregroundColor = ChatColors.locationLinkColor
            // effectType = EffectType.LINE_UNDERSCORE // 移除下划线
            // effectColor = ChatColors.locationLinkColor
        }
        val classAttributes = TextAttributes().apply {
            foregroundColor = ChatColors.classLinkColor
            // effectType = EffectType.LINE_UNDERSCORE // 移除下划线
            // effectColor = ChatColors.classLinkColor
        }
        val methodAttributes = TextAttributes().apply {
            foregroundColor = ChatColors.methodLinkColor
            // effectType = EffectType.LINE_UNDERSCORE // 移除下划线
            // effectColor = ChatColors.methodLinkColor
        }

        data class LinkMatch(val range: IntRange, val url: String, val attributes: TextAttributes)
        val matches = mutableListOf<LinkMatch>()

        fun addMatches(regex: Regex, attributes: TextAttributes, transform: (MatchResult) -> String?) {
            regex.findAll(code).forEach { match ->
                val url = transform(match)
                if (url != null) {
                    matches.add(LinkMatch(match.range, url, attributes))
                }
            }
        }

        // 1. C. FQN Method (带参数，带行号)
        addMatches(Regex("(?<![a-zA-Z0-9_])((?:[a-z0-9_]+\\.)+[A-Z][\\w$]*\\.[\\w$]+\\([^)]*\\))(?: (\\d+))?"), locationAttributes) { m ->
            val fullPath = m.groups[1]?.value ?: return@addMatches null
            val lineNum = m.groups[2]?.value
            if (lineNum != null) "psi_location://$fullPath:$lineNum" else "psi_location://$fullPath"
        }

        // 2. C2. FQN Class (带参数，带行号)
        addMatches(Regex("(?<![a-zA-Z0-9_])((?:[a-z0-9_]+\\.)+[A-Z][\\w$]*(?:\\([^)]*\\))?)(?: (\\d+))?"), classAttributes) { m ->
            val fullPath = m.groups[1]?.value ?: return@addMatches null
            val lineNum = m.groups[2]?.value
            if (lineNum != null) "psi_location://$fullPath:$lineNum" else "psi_location://$fullPath"
        }
        
        // 3. D. 类名.方法名 (支持变量名调用，如 abc.method)
        addMatches(Regex("(?<![a-zA-Z0-9_])([a-zA-Z][a-zA-Z0-9_]*)\\.([a-z][a-zA-Z0-9_]+)(?![a-zA-Z0-9_])"), locationAttributes) { m ->
            val className = m.groups[1]?.value ?: return@addMatches null
            val methodName = m.groups[2]?.value ?: return@addMatches null
            "psi_location://$className.$methodName"
        }
        
        // 4. A. 类名
        addMatches(Regex("(?<![a-zA-Z0-9_])([A-Z][a-zA-Z0-9_]{2,})(?![a-zA-Z0-9_])"), classAttributes) { m ->
            val className = m.groups[1]?.value ?: return@addMatches null
            "psi_class://$className"
        }
        
        // 5. B. 方法名
        addMatches(Regex("(?<![a-zA-Z0-9_])([a-z][a-zA-Z0-9_]+)\\(\\)(?![a-zA-Z0-9_])"), methodAttributes) { m ->
            val methodName = m.groups[1]?.value ?: return@addMatches null
            "psi_method://$methodName"
        }

        // 过滤重叠：优先保留更长的匹配 (Greedy)
        matches.sortWith(compareByDescending<LinkMatch> { it.range.last - it.range.first }.thenBy { it.range.first })

        val acceptedMatches = mutableListOf<LinkMatch>()
        val occupied = java.util.BitSet(code.length + 1)

        for (match in matches) {
            var isFree = true
            for (i in match.range) {
                if (occupied.get(i)) {
                    isFree = false
                    break
                }
            }
            if (isFree) {
                acceptedMatches.add(match)
                match.range.forEach { occupied.set(it) }
            }
        }

        val markup = editor.markupModel
        val highlighters = mutableListOf<RangeHighlighter>()
        for (match in acceptedMatches) {
            val h = markup.addRangeHighlighter(
                match.range.first,
                match.range.last + 1,
                HighlighterLayer.ADDITIONAL_SYNTAX,
                match.attributes,
                HighlighterTargetArea.EXACT_RANGE
            )
            highlighters.add(h)
        }
        editor.putUserData(LINK_HIGHLIGHTERS_KEY, highlighters)

        if (acceptedMatches.isNotEmpty()) {
            val listener = object : EditorMouseAdapter() {
                override fun mouseClicked(e: EditorMouseEvent) {
                    if (e.mouseEvent.button != java.awt.event.MouseEvent.BUTTON1) return
                    val offset = e.offset
                    val target = acceptedMatches.find { offset in it.range }
                    if (target != null) {
                        handleLink(target.url)
                        e.consume()
                    }
                }
            }
            editor.addEditorMouseListener(listener)
            editor.putUserData(LINK_LISTENER_KEY, listener)
        }
    }

    private fun createHtmlPane(htmlText: String): JEditorPane {
        // 计算分割线颜色（提前计算，以便后续使用）
        // 修复：分割线颜色使用 Thinking 文字颜色 (textSecondary) 叠加背景色，模拟 10% 透明度 (更淡)
        val fg = ChatColors.textSecondary
        val bg = bubbleColor // 使用当前气泡的实际背景色
        val r = (fg.red * 0.10 + bg.red * 0.90).toInt().coerceIn(0, 255)
        val g = (fg.green * 0.10 + bg.green * 0.90).toInt().coerceIn(0, 255)
        val b = (fg.blue * 0.10 + bg.blue * 0.90).toInt().coerceIn(0, 255)
        val hrColor = "#%02x%02x%02x".format(r, g, b)

        return JEditorPane().apply {
            val editor = this // 捕获引用
            contentType = "text/html"
            editorKit = HTMLEditorKit().apply {
                styleSheet = ChatStyles.createStyleSheet()
                
                // 修复汉字截断和 CSS 渲染问题的关键：使用 addRule 注入样式，而不是在 HTML 中拼接 <style>
                val fontFamily = UIUtil.getLabelFont().family
                val fontSize = UIUtil.getLabelFont().size
                
                styleSheet.addRule("""
                    body { 
                        /* 移除强制字体设置，依赖组件字体 (HONOR_DISPLAY_PROPERTIES) 以解决汉字显示问题 */
                        padding: 0px 4px 8px 4px; /* 底部增加 padding 防止截断 */
                        margin: 0;
                        line-height: 1.5;
                        word-wrap: break-word;
                    }
                """.trimIndent())
                
                // 动态获取颜色以适配主题
                val codeBg = ChatStyles.colorToHex(ChatColors.codeBackground)
                val codeText = ChatStyles.colorToHex(ChatColors.textPrimary) // 使用主文本颜色，避免红色刺眼
                val linkColor = ChatStyles.colorToHex(ChatColors.linkColor)
                
                styleSheet.addRule("p { margin-bottom: 6px; margin-top: 0; }")
                styleSheet.addRule("ul { margin-top: 0; margin-bottom: 6px; list-style-type: disc; }")
                styleSheet.addRule("ol { margin-top: 0; margin-bottom: 6px; list-style-type: decimal; }")
                styleSheet.addRule("li { margin-bottom: 4px; }")
                // 回退到 border-top 以确保 Swing 兼容性，但使用计算出的超淡颜色
                styleSheet.addRule("hr { border: 0; border-top: 1px solid $hrColor; margin: 10px 0; }")
                // 修复 code 样式：适配深色模式，避免刺眼背景；强制允许换行
                styleSheet.addRule("""
                    code { 
                        font-family: monospace; 
                        background-color: $codeBg; 
                        color: $codeText;
                        font-size: 95%;
                        word-break: break-all;
                    }
                """.trimIndent())
                // 🔧 修复长链接换行：长类名/方法名必须允许在任意位置换行
                styleSheet.addRule("""
                    a { 
                        color: $linkColor; 
                        text-decoration: none;
                        word-break: break-all;
                    }
                """.trimIndent())
            }
            
            // 🔧 修复：使用 div 替换 hr，以获得完全可控的分割线样式
            // hr 在 Swing 中渲染不稳定，容易出现 3D 边框或颜色不生效
            // 关键修正：使用 border-top 代替 background-color，并强制 font-size: 0 避免被字体撑高
            val safeHrHtml = """<div style="margin: 10px 0; border-top: 1px solid $hrColor; height: 0px; font-size: 0px; line-height: 0;"></div>"""
            // 替换所有形式的 hr 标签
            val processedHtml = htmlText.replace(Regex("<hr\\s*/?>", RegexOption.IGNORE_CASE), safeHrHtml)

            // 🔧 修复：如果 htmlText 已经包含 <html><body>，则直接使用，避免双重嵌套导致换行丢失
            if (processedHtml.trimStart().startsWith("<html>", ignoreCase = true)) {
                this.text = processedHtml
            } else {
                this.text = "<html><body>$processedHtml</body></html>"
            }
            
            isEditable = false
            isOpaque = false
            background = Color(0, 0, 0, 0)
            
            // 确保可选中
            isFocusable = true
            
            // 关键：让 JEditorPane 尊重字体设置和 DPI 缩放，并改善自动换行行为
            putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true)
            // putClientProperty("JEditorPane.w3cLengthUnits", true) // 移除：会导致 HiDPI 下双重缩放
            
            // 核心修复：显式设置组件字体与 IDE 一致
            // 因为 HONOR_DISPLAY_PROPERTIES 会使用组件字体作为 CSS body 的基准
            val baseFont = UIUtil.getLabelFont()
            font = baseFont
            
            // 添加超链接监听
            addHyperlinkListener { e ->
                if (e.eventType == HyperlinkEvent.EventType.ACTIVATED) {
                    val desc = e.description
                    if (desc != null && (desc.startsWith("psi_") || desc.startsWith("http"))) {
                        if (desc.startsWith("http")) {
                            if (java.awt.Desktop.isDesktopSupported() && e.url != null) {
                                try {
                                    java.awt.Desktop.getDesktop().browse(e.url.toURI())
                                } catch (ex: Exception) {
                                    // ignore
                                }
                            }
                        } else {
                            handleLink(desc)
                        }
                    }
                }
            }
            
            // 设置选中颜色
            selectionColor = UIManager.getColor("TextArea.selectionBackground") ?: Color.BLUE
            selectedTextColor = UIManager.getColor("TextArea.selectionForeground") ?: Color.WHITE
            
            // 添加右键复制菜单
            componentPopupMenu = JPopupMenu().apply {
                add(JMenuItem("复制").apply {
                    addActionListener {
                        copyText(editor)
                    }
                })
            }
            
            // 🔥 关键：覆盖 ActionMap 的 copy 操作，自动清理不可见字符
            // JEditorPane 的复制操作不经过 TransferHandler，必须覆盖 ActionMap
            setupCleanCopyAction(editor)
            
            putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true)
        }
    }
    
    /**
     * 🔥 设置清理复制的 Action（覆盖 JEditorPane/JTextArea 默认复制行为）
     * 这是唯一可靠的方式拦截 Cmd+C / Ctrl+C
     */
    private fun setupCleanCopyAction(component: javax.swing.text.JTextComponent) {
        val cleanCopyAction = object : javax.swing.AbstractAction("copy-clean") {
            override fun actionPerformed(e: java.awt.event.ActionEvent?) {
                val selected = component.selectedText
                if (!selected.isNullOrEmpty()) {
                    val cleanText = cleanInvisibleChars(selected)
                    val clipboard = Toolkit.getDefaultToolkit().systemClipboard
                    clipboard.setContents(java.awt.datatransfer.StringSelection(cleanText), null)
                }
            }
        }
        // 覆盖所有可能的 copy action 名称
        component.actionMap.put("copy", cleanCopyAction)
        component.actionMap.put("copy-to-clipboard", cleanCopyAction)
        component.actionMap.put(javax.swing.text.DefaultEditorKit.copyAction, cleanCopyAction)
    }

    /**
     * 🔥 设置清理复制的 Action（针对 IntelliJ Editor）
     */
    private fun setupCleanCopyActionForEditor(editor: com.intellij.openapi.editor.Editor) {
        val cleanCopyAction = object : javax.swing.AbstractAction("copy-clean") {
            override fun actionPerformed(e: java.awt.event.ActionEvent?) {
                val selected = editor.selectionModel.selectedText
                if (!selected.isNullOrEmpty()) {
                    val cleanText = cleanInvisibleChars(selected)
                    val clipboard = Toolkit.getDefaultToolkit().systemClipboard
                    clipboard.setContents(java.awt.datatransfer.StringSelection(cleanText), null)
                }
            }
        }
        // 覆盖 IntelliJ Editor 的 Copy Action
        editor.contentComponent.actionMap.put("\$Copy", cleanCopyAction)
        editor.contentComponent.actionMap.put("Copy", cleanCopyAction)
        editor.contentComponent.actionMap.put("copy", cleanCopyAction)
    }
    
    private fun copyText(editor: JEditorPane) {
        val selected = editor.selectedText
        if (!selected.isNullOrEmpty()) {
            val cleanText = cleanInvisibleChars(selected)
            val clipboard = Toolkit.getDefaultToolkit().systemClipboard
            clipboard.setContents(java.awt.datatransfer.StringSelection(cleanText), null)
        }
    }
    
    /**
     * 🔥 清理不可见字符（零宽空格等）
     * 用户复制时自动调用，避免复制出来的类名在 IDE 中搜不到
     */
    private fun cleanInvisibleChars(text: String): String {
        return text
            .replace("\u200B", "")  // 零宽空格 (ZERO WIDTH SPACE)
            .replace("\u200C", "")  // 零宽非连接符 (ZERO WIDTH NON-JOINER)
            .replace("\u200D", "")  // 零宽连接符 (ZERO WIDTH JOINER)
            .replace("\uFEFF", "")  // 字节顺序标记 (BOM)
            .replace("\u00A0", " ") // 不间断空格转普通空格
    }
    
    // 兼容旧接口
    fun updateForMaxWidth(maxWidth: Int) {}
    
    private class CollapsiblePanel : JPanel() {
        var isCollapsed = false
            set(value) {
                field = value
                revalidate()
                repaint()
            }
        
        init {
            // 移除 BoxLayout，使用自定义布局以强制宽度限制
            layout = null 
            isOpaque = false
        }
        
        override fun getPreferredSize(): Dimension {
            // 策略：如果当前宽度太小（如初始化时），使用自然宽度计算高度，避免因宽度极小导致的高度虚高（Gap问题）
            // 如果当前宽度正常（已布局），则基于当前宽度计算准确高度
            val currentW = width
            val targetW = if (currentW < JBUI.scale(100)) calculateWidth() else currentW
            // 再次兜底，确保不为0。移除 200px 的硬性限制，允许更窄的布局
            val effectiveW = maxOf(targetW, JBUI.scale(50))
            
            val h = calculateHeight(effectiveW)
            // println("CollapsiblePanel: getPreferredSize w=$currentW, targetW=$targetW, h=$h")
            return Dimension(effectiveW, h)
        }
        
        override fun doLayout() {
            var width = width
            // println("CollapsiblePanel: doLayout width=$width, componentCount=$componentCount")
            if (width <= 0) {
                // 兜底：如果宽度无效，使用默认宽度进行布局计算，确保组件被正确初始化
                width = JBUI.scale(300)
            }

            var y = 0
            var calculatedHeight = 0
            
            for (comp in components) {
                val h: Int
                if (comp is JEditorPane) {
                    // 关键：强制设置 View 的宽度，触发 HTML 换行计算
                    // 必须扣除 Insets，否则 View 认为可用空间比实际大，导致换行不足
                    val insets = comp.insets
                    val availableWidth = (width - insets.left - insets.right).coerceAtLeast(1)
                    val view = comp.ui.getRootView(comp)
                    view.setSize(availableWidth.toFloat(), 0f)
                    
                    // 向上取整并增加更大的缓冲 (5px)，防止精度丢失或字体 descent 导致的截断
                    val contentH = kotlin.math.ceil(view.getPreferredSpan(View.Y_AXIS)).toInt()
                    h = contentH + insets.top + insets.bottom + JBUI.scale(5)
                    
                    comp.setBounds(0, y, width, h)
                } else if (comp is Box.Filler) {
                    // Strut (间距)
                    h = comp.preferredSize.height
                    comp.setBounds(0, y, width, h)
                } else {
                    // 其他组件 (如代码块容器)
                    // 先设置宽度，让其调整内部布局
                    // 特别是代码块容器，需要知道宽度才能正确显示水平滚动条或换行
                    comp.setSize(width, if (comp.height > 0) comp.height else comp.preferredSize.height)
                    comp.validate() // 触发布局
                    
                    // 如果组件内部有 EditorTextField，确保它也更新
                    if (comp is JPanel && comp.componentCount > 0) {
                         val editor = comp.getComponent(0) as? EditorTextField
                         if (editor != null) {
                             editor.setSize(width, if (editor.height > 0) editor.height else 100)
                             editor.validate()
                             val realEditor = editor.editor
                             if (realEditor != null) {
                                 if (!realEditor.settings.isUseSoftWraps) {
                                     realEditor.settings.isUseSoftWraps = true
                                 }
                                 realEditor.component.setSize(width, if (realEditor.component.height > 0) realEditor.component.height else 100)
                                 realEditor.component.validate()
                             }
                             editor.ensureWillComputePreferredSize()
                         }
                    }
                    
                    h = comp.preferredSize.height
                    comp.setBounds(0, y, width, h)
                }
                // println("  Child ${comp.javaClass.simpleName} bounds: 0, $y, $width, $h")
                y += h
                calculatedHeight += h
            }
            
            // 检查是否需要调整高度 (解决 Clipping 问题)
            // 如果计算出的所需高度与当前高度不一致，且差异较大，触发重新布局
            if (Math.abs(calculatedHeight - height) > 5) {
                SwingUtilities.invokeLater {
                    revalidate()
                    repaint()
                }
            }
        }
        
        // 计算内容的自然宽度（最宽的行）
        fun calculateWidth(): Int {
            var maxWidth = 0
            for (comp in components) {
                if (comp is JEditorPane) {
                    // 尝试测量单行自然宽度
                    val view = comp.ui.getRootView(comp)
                    view.setSize(10000f, 0f)
                    val w = view.getPreferredSpan(View.X_AXIS)
                    maxWidth = maxOf(maxWidth, w.toInt())
                } else {
                    maxWidth = maxOf(maxWidth, comp.preferredSize.width)
                }
            }
            return maxWidth
        }

        fun calculateHeight(contentWidth: Int): Int {
            var totalHeight = 0
            
            for (comp in components) {
                if (comp is JEditorPane) {
                    val insets = comp.insets
                    val availableWidth = (contentWidth - insets.left - insets.right).coerceAtLeast(1)
                    
                    val view = comp.ui.getRootView(comp)
                    view.setSize(availableWidth.toFloat(), 0f)
                    val contentH = view.getPreferredSpan(View.Y_AXIS)
                    // 向上取整并增加缓冲，与 doLayout 保持一致
                    totalHeight += kotlin.math.ceil(contentH).toInt() + insets.top + insets.bottom + JBUI.scale(5)
                } else if (comp is Box.Filler) {
                    totalHeight += JBUI.scale(8) // 统一间距
                } else {
                    // 代码块或其他
                    // 模拟设置宽度后的高度
                    comp.setSize(contentWidth, comp.preferredSize.height)
                    comp.validate()
                    if (comp is JPanel && comp.componentCount > 0) {
                         val editor = comp.getComponent(0) as? EditorTextField
                         editor?.ensureWillComputePreferredSize()
                    }
                    totalHeight += comp.preferredSize.height
                }
            }
            
            // 如果折叠，限制高度
            if (isCollapsed) {
                val collapsedHeight = JBUI.scale(120)
                return totalHeight.coerceAtMost(collapsedHeight)
            }
            
            return totalHeight
        }
        
        // 重写 paintChildren 实现裁剪效果
        override fun paintChildren(g: Graphics) {
            if (isCollapsed) {
                val g2 = g.create()
                // 限制绘制区域
                g2.clipRect(0, 0, width, height)
                super.paintChildren(g2)
                g2.dispose()
            } else {
                super.paintChildren(g)
            }
        }
    }
    
    private class NavButton(
        private val isUp: Boolean,
        private val onClick: () -> Unit
    ) : JComponent() {
        private val size = JBUI.scale((14 * 0.8).toInt())
        
        init {
            // 恢复为方形尺寸 (size + 8)
            preferredSize = Dimension(size + JBUI.scale(8), size + JBUI.scale(8))
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            toolTipText = if (isUp) "上一个" else "下一个"
            isVisible = false // 默认不显示，由 setNavigationState 控制
            
            addMouseListener(object : java.awt.event.MouseAdapter() {
                override fun mouseClicked(e: java.awt.event.MouseEvent?) {
                    if (isEnabled) {
                        onClick()
                    }
                }
            })
        }
        
        override fun paintComponent(g: Graphics) {
            if (!isVisible) return
            // 核心修改：如果被禁用 (即没有上一条/下一条)，则不绘制任何内容 (透明占位)
            if (!isEnabled) return
            
            val g2 = g as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            
            val x = (width - size) / 2
            val y = (height - size) / 2
            val frameSize = size
            
            g2.color = if (isEnabled) ChatColors.textSecondary else JBColor.GRAY
            // 恢复线条绘制，与右侧图标风格保持一致
            g2.stroke = BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            
            val arrowX = x + frameSize / 2
            val arrowY = y + frameSize / 2
            
            // 90度夹角：半宽 = 高度
            // 使得 Tip 到两端的向量为 (-w, h) 和 (w, h)，点积 -w^2 + h^2 = 0 => w = h
            val arrowSize = JBUI.scale(5)
            val arrowHalfWidth = arrowSize
            val arrowHeight = arrowSize
            
            // 计算垂直偏移，确保居中
            val yOffset = arrowHeight / 2
            
            val xPoints = IntArray(3)
            val yPoints = IntArray(3)
            
            if (isUp) {
                // ^ Up Arrow (Chevron) - 去掉底边
                // 1. Bottom Left
                xPoints[0] = arrowX - arrowHalfWidth
                yPoints[0] = arrowY + yOffset
                
                // 2. Top (Apex)
                xPoints[1] = arrowX
                yPoints[1] = arrowY - yOffset
                
                // 3. Bottom Right
                xPoints[2] = arrowX + arrowHalfWidth
                yPoints[2] = arrowY + yOffset
            } else {
                // v Down Arrow (Chevron) - 去掉底边
                // 1. Top Left
                xPoints[0] = arrowX - arrowHalfWidth
                yPoints[0] = arrowY - yOffset
                
                // 2. Bottom (Apex)
                xPoints[1] = arrowX
                yPoints[1] = arrowY + yOffset
                
                // 3. Top Right
                xPoints[2] = arrowX + arrowHalfWidth
                yPoints[2] = arrowY - yOffset
            }
            
            // 使用 drawPolyline 绘制折线（不闭合，即去掉底边）
            g2.drawPolyline(xPoints, yPoints, 3)
        }
    }

    private class CollapseButton(
        private val bgColor: Color,
        var onToggle: (Boolean) -> Unit
    ) : JComponent() {
        // 默认是展开的 (false)，点击后变 true
        var isCollapsed = false 
        private val size = JBUI.scale((14 * 0.8).toInt()) // 与 CopyButton 尺寸一致
        private val cornerRadius = JBUI.scale(2) // 圆角半径
        
        init {
            // 尺寸与 CopyButton 一致
            preferredSize = Dimension(size + JBUI.scale(12), JBUI.scale((22 * 0.8).toInt()))
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            toolTipText = "收起/展开"
            
            addMouseListener(object : java.awt.event.MouseAdapter() {
                override fun mouseClicked(e: java.awt.event.MouseEvent?) {
                    isCollapsed = !isCollapsed
                    onToggle(isCollapsed)
                    repaint()
                }
            })
        }
        
        override fun paintComponent(g: Graphics) {
            val g2 = g as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            
            // 居中绘制
            val x = (width - size) / 2
            val y = (height - size) / 2
            val frameSize = size
            
            // 2. 绘制图标 (Arrows)
            g2.color = ChatColors.textSecondary
            g2.stroke = BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            
            val arrowX = x + frameSize / 2
            val arrowY = y + frameSize / 2
            
            // 增大箭头以填充原本方框的空间
            // 原方框大小为 size (~11px)，我们让箭头占据大部分空间
            val arrowWidth = frameSize / 3 // 宽度
            // 重新调整箭头绘制逻辑，使其更"和谐"
            
            if (isCollapsed) {
                // 折叠状态 (Action: Expand) -> 箭头向外 (上下分离)
                // 用户反馈：之前太挤了 (Too squeezed)
                // 调整：让两个箭头在垂直方向上分得更开一些
                // 再次调整：再分开一点 (offset = 3)
                
                val offset = JBUI.scale(3) // 距离中心的偏移量
                val arrowH = JBUI.scale(3) // 箭头高度
                
                // ^ (Up Arrow) - 位于上方
                // Tip: center - offset - arrowH (更靠上)
                // Legs: center - offset
                val upLegsY = y + frameSize / 2 - offset
                val upTipY = upLegsY - arrowH
                
                g2.drawLine(arrowX, upTipY, arrowX - arrowWidth, upLegsY)
                g2.drawLine(arrowX, upTipY, arrowX + arrowWidth, upLegsY)
                
                // v (Down Arrow) - 位于下方
                val downLegsY = y + frameSize / 2 + offset
                val downTipY = downLegsY + arrowH
                
                g2.drawLine(arrowX, downTipY, arrowX - arrowWidth, downLegsY)
                g2.drawLine(arrowX, downTipY, arrowX + arrowWidth, downLegsY)
                
            } else {
                // 展开状态 (Action: Collapse) -> 箭头向内 (相对)
                // 用户反馈：之前隔得太远 (Too far apart)，现在太近像 X (Too close, looks like X)
                // 调整：增加间距 (gap = 2)
                
                val gap = JBUI.scale(2) // 两个箭头尖端之间的垂直间距
                val arrowH = JBUI.scale(3)
                
                // v (Down Arrow) - 位于上方，指向中心
                // Tip: center - gap
                // Legs: center - gap - arrowH
                val downTipY = y + frameSize / 2 - gap
                val downLegsY = downTipY - arrowH
                
                g2.drawLine(arrowX, downTipY, arrowX - arrowWidth, downLegsY)
                g2.drawLine(arrowX, downTipY, arrowX + arrowWidth, downLegsY)
                
                // ^ (Up Arrow) - 位于下方，指向中心
                val upTipY = y + frameSize / 2 + gap
                val upLegsY = upTipY + arrowH
                
                g2.drawLine(arrowX, upTipY, arrowX - arrowWidth, upLegsY)
                g2.drawLine(arrowX, upTipY, arrowX + arrowWidth, upLegsY)
            }
        }
    }
    
    private class CopyButton(private val contentProvider: () -> String) : JComponent() {
        private var isChecked = false
        private val size = JBUI.scale(14) // 🔧 统一图标尺寸
        private val cornerRadius = JBUI.scale(2)

        init {
            // 🔧 统一高度为 22，与其他按钮对齐
            preferredSize = Dimension(size + JBUI.scale(12), JBUI.scale(22))
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            toolTipText = "复制原始内容"
            isOpaque = false  // 🔧 透明背景

            addMouseListener(object : java.awt.event.MouseAdapter() {
                override fun mouseClicked(e: java.awt.event.MouseEvent?) {
                    if (!isChecked) {
                        copyToClipboard()
                    }
                }
            })
        }
        
        private fun copyToClipboard() {
            try {
                // 🔥 点击时动态获取内容（确保获取最新的 finalContent）
                val textToCopy = contentProvider()

                // 🔥🔥🔥 DEBUG: 打印复制内容
                println("🔍 [DEBUG] CopyButton clicked:")
                println("  - textToCopy.length: ${textToCopy.length}")
                println("  - textToCopy preview: ${textToCopy.take(100)}")

                val selection = StringSelection(textToCopy)
                Toolkit.getDefaultToolkit().systemClipboard.setContents(selection, selection)

                isChecked = true
                repaint()

                // 3秒后恢复
                Timer(3000) {
                    isChecked = false
                    repaint()
                }.apply {
                    isRepeats = false
                    start()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        
        override fun paintComponent(g: Graphics) {
            val g2 = g as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            
            // 居中绘制
            val x = (width - size) / 2
            val y = (height - size) / 2
            
            g2.color = ChatColors.textSecondary
            
            if (isChecked) {
                // 绘制打勾 (Check) - 绿色
                g2.color = Color(0x4CAF50)
                g2.stroke = BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                val p1 = Point(x + 2, y + size / 2 + 1)
                val p2 = Point(x + size / 2, y + size - 2)
                val p3 = Point(x + size - 1, y + 3)
                g2.drawLine(p1.x, p1.y, p2.x, p2.y)
                g2.drawLine(p2.x, p2.y, p3.x, p3.y)
            } else {
                // 🔧 绘制复制图标 - 使用 Path2D 精确绘制
                g2.stroke = BasicStroke(1.3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                val boxSize = size - 4
                val offset = 3
                val r = cornerRadius.toDouble()
                
                // 后面的框 (Top-Right) - 只画露出的部分（上边、右边，下边被完全遮挡）
                val bx = (x + offset).toDouble()
                val by = y.toDouble()
                val frontRightEdge = x + boxSize  // 前面框的右边界
                
                val path = java.awt.geom.Path2D.Double()
                // 从左上角开始，顺时针画
                path.moveTo(bx, by + r)  // 左边起点
                path.quadTo(bx, by, bx + r, by)  // 左上圆角
                path.lineTo(bx + boxSize - r, by)  // 上边
                path.quadTo(bx + boxSize, by, bx + boxSize, by + r)  // 右上圆角
                path.lineTo(bx + boxSize, by + boxSize - r)  // 右边
                path.quadTo(bx + boxSize, by + boxSize, bx + boxSize - r, by + boxSize)  // 右下圆角
                // 下边只画到前面框右边界的位置（其余被遮挡）
                path.lineTo(frontRightEdge.toDouble(), by + boxSize)
                g2.draw(path)
                
                // 前面的框 (Bottom-Left) - 完整圆角矩形
                g2.draw(java.awt.geom.RoundRectangle2D.Double(
                    x.toDouble(), (y + offset).toDouble(), 
                    boxSize.toDouble(), boxSize.toDouble(), 
                    r * 2, r * 2
                ))
            }
        }
    }
    
    private class RoundedBorder(private val radius: Int) : javax.swing.border.Border {
        override fun paintBorder(c: Component, g: Graphics, x: Int, y: Int, width: Int, height: Int) {
            val g2 = g as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val oldStroke = g2.stroke
            
            // 恢复原来的细边框和颜色
            g2.color = JBColor.border()
            g2.stroke = BasicStroke(1.0f)
            g2.drawRoundRect(x, y, width - 1, height - 1, radius, radius)
            
            g2.stroke = oldStroke
        }
        
        override fun getBorderInsets(c: Component): Insets {
            // 恢复原来的内边距
            val padding = JBUI.scale(1)
            return Insets(padding, padding, padding, padding)
        }
        override fun isBorderOpaque(): Boolean = false
    }

    private class SplitViewButton(private val onToggle: () -> Unit) : JComponent() {
        private val size = JBUI.scale(14) // 恢复标准尺寸
        private val cornerRadius = JBUI.scale(2)
        
        init {
            preferredSize = Dimension(size + JBUI.scale(12), JBUI.scale(22))
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            toolTipText = "切换预览/代码"
            
            addMouseListener(object : java.awt.event.MouseAdapter() {
                override fun mouseClicked(e: java.awt.event.MouseEvent?) {
                    onToggle()
                    repaint()
                }
            })
        }
        
        override fun paintComponent(g: Graphics) {
            val g2 = g as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            
            val x = (width - size) / 2
            val y = (height - size) / 2
            val boxSize = size - 4 // JBUI.scale(10)
            
            g2.color = JBColor.GRAY
            g2.stroke = BasicStroke(1.2f)
            
            // 绘制矩形框
            g2.drawRoundRect(x, y + 2, boxSize, boxSize, cornerRadius, cornerRadius)
            
            // 绘制中间竖线
            val centerX = x + boxSize / 2
            g2.drawLine(centerX, y + 2, centerX, y + 2 + boxSize)
        }
    }

    /**
     * 🔍 放大镜按钮 - 点击后弹出全屏 Mermaid 图表
     */
    private class ZoomButton(private val mermaidCode: String, private val project: Project?) : JComponent() {
        private val size = JBUI.scale(14)
        private val cornerRadius = JBUI.scale(2)
        
        init {
            preferredSize = Dimension(size + JBUI.scale(12), JBUI.scale(22))
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            toolTipText = "放大查看"
            
            addMouseListener(object : java.awt.event.MouseAdapter() {
                override fun mouseClicked(e: java.awt.event.MouseEvent?) {
                    showFullScreenMermaid()
                }
            })
        }
        
        private fun showFullScreenMermaid() {
            // 创建对话框显示完整的 Mermaid 图表
            val dialog = object : DialogWrapper(project, true) {
                init {
                    title = "Mermaid 图表"
                    setSize(900, 700)
                    init()
                }
                
                override fun createCenterPanel(): JComponent {
                    val panel = JPanel(BorderLayout())
                    panel.preferredSize = Dimension(850, 650)
                    
                    if (!JBCefApp.isSupported()) {
                        panel.add(JLabel("当前 IDE 不支持 JCEF，无法渲染图表", SwingConstants.CENTER), BorderLayout.CENTER)
                        return panel
                    }
                    
                    val browser = JBCefBrowser()
                    
                    try {
                        val mermaidJsStream = this::class.java.getResourceAsStream("/ui/mermaid.min.js")
                        if (mermaidJsStream != null) {
                            val mermaidJs = mermaidJsStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                            
                            if (!mermaidJs.contains("PLACEHOLDER FOR MERMAID.JS")) {
                                // 🔧 使用 JSON 编码确保特殊字符正确传递
                                val jsonCode = org.json.JSONObject.quote(mermaidCode)
                                
                                // 🔧 增强版：支持平滑缩放和拖动
                                val htmlContent = """
                                    <!DOCTYPE html>
                                    <html>
                                    <head>
                                        <meta charset="UTF-8">
                                        <style>
                                            * { margin: 0; padding: 0; box-sizing: border-box; }
                                            body {
                                                background-color: #E6DDD0;
                                                color: #333333;
                                                font-family: sans-serif;
                                                overflow: hidden;
                                                width: 100vw;
                                                height: 100vh;
                                                cursor: grab;
                                            }
                                            body.dragging { cursor: grabbing; }
                                            #wrapper {
                                                position: absolute;
                                                top: 50%;
                                                left: 50%;
                                                transform: translate(-50%, -50%);
                                                width: calc(100% - 40px);
                                                height: calc(100% - 40px);
                                            }
                                            #inner-wrapper {
                                                width: 100%;
                                                height: 100%;
                                                /* 🔧 宣纸色背景的四个角圆角 */
                                                border-radius: 12px;
                                                background-color: #E6DDD0;
                                                overflow: hidden;
                                            }
                                            #container {
                                                position: absolute;
                                                transform-origin: 0 0;
                                                /* 🔧 移除 padding，由 inner-wrapper 处理 */
                                            }
                                            .mermaid { 
                                                display: inline-block;
                                            }
                                            #hint {
                                                position: fixed;
                                                bottom: 10px;
                                                left: 10px;
                                                background: rgba(0,0,0,0.6);
                                                color: white;
                                                padding: 8px 12px;
                                                border-radius: 4px;
                                                font-size: 12px;
                                                z-index: 1000;
                                            }
                                        </style>
                                        <script>
                                            $mermaidJs
                                        </script>
                                    </head>
                                    <body>
                                        <div id="wrapper">
                                            <div id="inner-wrapper">
                                                <div id="container">
                                                    <div class="mermaid"></div>
                                                </div>
                                            </div>
                                        </div>
                                        <div id="hint">滚轮缩放 | 拖动平移</div>
                                        <script>
                                            // 🔧 先初始化 Mermaid，startOnLoad 设为 false
                                            mermaid.initialize({ startOnLoad: false, theme: 'default', securityLevel: 'loose' });
                                            
                                            // 注入代码
                                            const code = $jsonCode;
                                            const div = document.querySelector('.mermaid');
                                            div.textContent = code;
                                            
                                            // 🔧 手动触发渲染并添加圆角
                                            mermaid.run({ nodes: [div] }).then(() => {
                                                const svg = div.querySelector('svg');
                                                if (svg) {
                                                    svg.style.borderRadius = '8px';
                                                    svg.querySelectorAll('rect').forEach(rect => {
                                                        rect.style.rx = '6px';
                                                        rect.style.ry = '6px';
                                                    });
                                                }
                                            });
                                            
                                            // 缩放和拖动逻辑
                                            let scale = 1;
                                            let translateX = 0;
                                            let translateY = 0;
                                            let isDragging = false;
                                            let startX, startY;
                                            const container = document.getElementById('container');
                                            
                                            function updateTransform() {
                                                container.style.transform = 'translate(' + translateX + 'px, ' + translateY + 'px) scale(' + scale + ')';
                                            }
                                            
                                            // 自动适配大小
                                            setTimeout(function() {
                                                const svg = document.querySelector('.mermaid svg');
                                                const wrapper = document.getElementById('inner-wrapper');
                                                if (svg && wrapper) {
                                                    const svgWidth = svg.getBoundingClientRect().width;
                                                    const svgHeight = svg.getBoundingClientRect().height;
                                                    const wrapperWidth = wrapper.clientWidth;
                                                    const wrapperHeight = wrapper.clientHeight;
                                                    const scaleX = wrapperWidth / svgWidth;
                                                    const scaleY = wrapperHeight / svgHeight;
                                                    scale = Math.min(scaleX, scaleY, 1.5);
                                                    translateX = (wrapperWidth - svgWidth * scale) / 2;
                                                    translateY = (wrapperHeight - svgHeight * scale) / 2;
                                                    updateTransform();
                                                }
                                            }, 500);
                                            
                                            // 🔧 滚轮逻辑：丝滑缩放（简单版）
                                            let targetScale = scale;
                                            let animationFrameId = null;
                                            
                                            function smoothUpdate() {
                                                // 缓动插值
                                                const ease = 0.12;
                                                scale += (targetScale - scale) * ease;
                                                
                                                const diff = Math.abs(targetScale - scale);
                                                container.style.transform = 'translate(' + translateX.toFixed(2) + 'px, ' + translateY.toFixed(2) + 'px) scale(' + scale.toFixed(4) + ')';
                                                
                                                if (diff > 0.0001) {
                                                    animationFrameId = requestAnimationFrame(smoothUpdate);
                                                }
                                            }
                                            
                                            document.addEventListener('wheel', function(e) {
                                                e.preventDefault();

                                                // 固定缩放系数：每次 3%
                                                const zoomFactor = e.deltaY > 0 ? 0.97 : 1.03;
                                                targetScale = Math.min(Math.max(targetScale * zoomFactor, 0.2), 5);
                                                
                                                // 取消之前的动画，开始新的
                                                if (animationFrameId) {
                                                    cancelAnimationFrame(animationFrameId);
                                                }
                                                animationFrameId = requestAnimationFrame(smoothUpdate);
                                            }, { passive: false });
                                            
                                            // 拖动开始
                                            document.addEventListener('mousedown', function(e) {
                                                isDragging = true;
                                                startX = e.clientX - translateX;
                                                startY = e.clientY - translateY;
                                                document.body.classList.add('dragging');
                                            });
                                            
                                            document.addEventListener('mousemove', function(e) {
                                                if (isDragging) {
                                                    translateX = e.clientX - startX;
                                                    translateY = e.clientY - startY;
                                                    updateTransform();
                                                }
                                            });
                                            
                                            document.addEventListener('mouseup', function() {
                                                isDragging = false;
                                                document.body.classList.remove('dragging');
                                            });
                                        </script>
                                    </body>
                                    </html>
                                """.trimIndent()
                                
                                browser.loadHTML(htmlContent)
                            }
                        }
                    } catch (e: Exception) {
                        browser.loadHTML("<html><body>Error: ${e.message}</body></html>")
                    }
                    
                    panel.add(browser.component, BorderLayout.CENTER)
                    
                    // 对话框关闭时销毁 browser
                    panel.addHierarchyListener { e ->
                        if (e.changeFlags and java.awt.event.HierarchyEvent.PARENT_CHANGED.toLong() != 0L) {
                            if (panel.parent == null) {
                                Disposer.dispose(browser)
                            }
                        }
                    }
                    
                    return panel
                }
            }
            dialog.show()
        }
        
        override fun paintComponent(g: Graphics) {
            val g2 = g as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

            val x = (width - size) / 2
            val y = (height - size) / 2 + JBUI.scale(2)

            g2.color = JBColor.GRAY
            g2.stroke = BasicStroke(1.5f)

            // 绘制放大镜圆圈
            val circleSize = size - 6
            g2.drawOval(x, y, circleSize, circleSize)

            // 绘制放大镜手柄
            val handleStartX = x + circleSize - 2
            val handleStartY = y + circleSize - 2
            g2.drawLine(handleStartX, handleStartY, handleStartX + 4, handleStartY + 4)
        }
    }

}
