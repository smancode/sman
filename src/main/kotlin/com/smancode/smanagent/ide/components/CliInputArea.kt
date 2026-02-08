package com.smancode.smanagent.ide.components

import com.intellij.util.ui.JBUI
import com.smancode.smanagent.ide.theme.ThemeColors
import com.smancode.smanagent.ide.ui.FontManager
import java.awt.*
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import java.awt.event.MouseAdapter
import java.util.concurrent.CopyOnWriteArrayList
import javax.swing.*
import javax.swing.border.EmptyBorder
import javax.swing.event.DocumentListener

/**
 * CLI 风格输入框（支持自动增高和滚动）
 *
 * 特点：
 * - 多行文本输入
 * - 占位符提示
 * - 自动增高（3-10行），超过10行可滚动
 * - 圆角边框（淡灰色）
 * - 悬浮/焦点状态反馈
 * - 回车发送，Shift+Enter 换行
 * - 鼠标滚轮支持，隐藏滚动条
 * - 背景色与消息区一致，实现悬浮效果
 * - 命令自动补全：输入 `/` 显示灰色提示，Tab 补全
 * - 支持代码引用标签（Ctrl+L 插入）
 */
class CliInputArea(
    private val onSendCallback: (String, List<CodeReference>) -> Unit,
    private val onInsertCodeReferenceCallback: (() -> Unit)? = null
) : JPanel() {

    private val maxRows = 10
    private val minRows = 3

    private val cornerRadius = JBUI.scale(8)
    private val placeholderText = "点击 + 新建会话，Enter 发送，Shift+Enter 换行"
    private var showPlaceholder = true
    var isFocused = false

    // 命令补全相关
    private val commands = listOf<String>()
    private var suggestionText: String? = null

    // 代码引用标签列表
    private val codeReferences = CopyOnWriteArrayList<CodeReference>()

    // 布局组件
    private val tagsPanel: JPanel
    private val scrollPane: JScrollPane

    // 文本区域
    val textArea: JTextArea

    // 计算行高
    private val lineHeight: Int
        get() {
            val fm = textArea.getFontMetrics(textArea.font)
            return fm.height
        }

    // 代理属性
    var text: String
        get() = textArea.text
        set(value) {
            textArea.text = value
            updatePlaceholder()
            updateHeight()
        }

    init {
        isOpaque = false
        layout = BorderLayout()
        border = null

        // 创建标签面板（用于存放代码引用标签）
        tagsPanel = JPanel().apply {
            isOpaque = false
            layout = FlowLayout(FlowLayout.LEFT, 6, 6)
            isVisible = false  // 默认隐藏，有标签时才显示
        }

        // 创建文本区域
        textArea = object : JTextArea() {
            init {
                isOpaque = false
                lineWrap = true
                wrapStyleWord = true
                enableInputMethods(true)
            }

            override fun paintComponent(g: Graphics) {
                super.paintComponent(g)

                val g2 = g as Graphics2D
                g2.setupRenderingHints()
                val fm = g2.fontMetrics
                val colors = ThemeColors.getCurrentColors()

                // 绘制占位符
                if (showPlaceholder && text.isBlank()) {
                    drawPlaceholder(g2, fm, colors)
                }

                // 绘制命令建议（灰色提示）
                suggestionText?.let { drawSuggestion(g2, fm, colors, it) }
            }
        }

        // 配置文本区域
        textArea.rows = minRows
        textArea.font = FontManager.getEditorFont()
        val colors = ThemeColors.getCurrentColors()
        textArea.foreground = colors.textPrimary
        textArea.caretColor = colors.textPrimary
        val padding = JBUI.scale(12)
        textArea.border = EmptyBorder(padding, padding, padding, padding)

        // 创建滚动面板
        scrollPane = JScrollPane(textArea).apply {
            verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
            border = null
            isOpaque = false
            viewport.isOpaque = false
            verticalScrollBar.apply { preferredSize = Dimension(0, 0) }
        }

        // 主面板（包含标签和输入框）
        val mainPanel = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(tagsPanel, BorderLayout.NORTH)
            add(scrollPane, BorderLayout.CENTER)
        }

        add(mainPanel, BorderLayout.CENTER)

        // 焦点监听
        textArea.addFocusListener(object : java.awt.event.FocusAdapter() {
            override fun focusGained(e: java.awt.event.FocusEvent?) {
                if (!isFocused) {
                    isFocused = true
                    repaint()
                }
                showPlaceholder = false
                textArea.repaint()
            }

            override fun focusLost(e: java.awt.event.FocusEvent?) {
                if (isFocused) {
                    isFocused = false
                    repaint()
                }
                updatePlaceholder()
            }
        })

        // 文本变化监听
        textArea.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: javax.swing.event.DocumentEvent?) = onTextChanged()
            override fun removeUpdate(e: javax.swing.event.DocumentEvent?) = onTextChanged()
            override fun changedUpdate(e: javax.swing.event.DocumentEvent?) = onTextChanged()
        })

        // 键盘快捷键
        setupActions()
    }

    /**
     * 插入代码引用标签
     */
    fun insertCodeReference(codeReference: CodeReference) {
        // 添加到列表
        codeReferences.add(codeReference)

        // 创建标签组件
        val tag = CodeReferenceTag(codeReference) {
            removeCodeReference(codeReference)
        }

        // 添加到面板
        tagsPanel.add(tag)
        tagsPanel.isVisible = true

        // 重新布局
        tagsPanel.revalidate()
        tagsPanel.repaint()

        // 聚焦到输入框
        textArea.requestFocusInWindow()
    }

    /**
     * 移除代码引用标签
     */
    private fun removeCodeReference(codeReference: CodeReference) {
        codeReferences.remove(codeReference)

        // 重建标签面板
        rebuildTagsPanel()
    }

    /**
     * 重建标签面板
     */
    private fun rebuildTagsPanel() {
        tagsPanel.removeAll()

        if (codeReferences.isEmpty()) {
            tagsPanel.isVisible = false
        } else {
            codeReferences.forEach { ref ->
                val tag = CodeReferenceTag(ref) {
                    removeCodeReference(ref)
                }
                tagsPanel.add(tag)
            }
            tagsPanel.isVisible = true
        }

        tagsPanel.revalidate()
        tagsPanel.repaint()
    }

    /**
     * 获取当前所有代码引用
     */
    fun getCodeReferences(): List<CodeReference> = codeReferences.toList()

    private fun setupGraphics(g2: java.awt.Graphics2D, colors: com.smancode.smanagent.ide.theme.ColorPalette): java.awt.Color {
        val oldColor = g2.color
        g2.color = colors.textMuted
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        return oldColor
    }

    private fun drawPlaceholder(g2: java.awt.Graphics2D, fm: java.awt.FontMetrics, colors: com.smancode.smanagent.ide.theme.ColorPalette) {
        val oldColor = setupGraphics(g2, colors)

        val x = insets.left + JBUI.scale(5)
        var y = insets.top + fm.ascent + JBUI.scale(5)

        placeholderText.split("\n").forEach { line ->
            g2.drawString(line, x, y)
            y += fm.height
        }

        g2.color = oldColor
    }

    private fun drawSuggestion(g2: java.awt.Graphics2D, fm: java.awt.FontMetrics, colors: com.smancode.smanagent.ide.theme.ColorPalette, suggestion: String) {
        val currentText = textArea.text.trim()
        if (!currentText.startsWith("/") || currentText.contains(" ")) return

        val oldColor = setupGraphics(g2, colors)

        val currentTextWidth = fm.stringWidth(currentText)
        val x = insets.left + currentTextWidth
        val y = insets.top + fm.ascent

        g2.drawString(suggestion.substring(currentText.length), x, y)

        g2.color = oldColor
    }

    private fun onTextChanged() {
        updatePlaceholder()
        updateHeight()
        updateSuggestion()
    }

    override fun getPreferredSize(): Dimension {
        val d = textArea.preferredSize ?: Dimension(100, JBUI.scale(80))
        val minHeight = JBUI.scale(40)
        if (d.height < minHeight) d.height = minHeight
        return d
    }

    override fun getMinimumSize(): Dimension {
        return getPreferredSize()
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g as Graphics2D
        g2.setupRenderingHints()

        val colors = ThemeColors.getCurrentColors()
        g2.color = colors.background
        g2.fillRoundRect(0, 0, width - 1, height - 1, cornerRadius, cornerRadius)
    }

    override fun paint(g: Graphics) {
        super.paint(g)

        val g2 = g as Graphics2D
        g2.setupRenderingHints()

        val colors = ThemeColors.getCurrentColors()

        if (isFocused) {
            g2.color = colors.textSecondary
            g2.stroke = BasicStroke(1.5f)
        } else {
            g2.color = colors.textSecondary.scaleBrightness(0.5f)
            g2.stroke = BasicStroke(1.0f)
        }

        g2.drawRoundRect(0, 0, width - 1, height - 1, cornerRadius, cornerRadius)
    }

    private fun Graphics2D.setupRenderingHints() {
        setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    }

    private fun java.awt.Color.scaleBrightness(factor: Float): java.awt.Color {
        return java.awt.Color(
            (red * factor).toInt().coerceIn(0, 255),
            (green * factor).toInt().coerceIn(0, 255),
            (blue * factor).toInt().coerceIn(0, 255),
            alpha
        )
    }

    override fun requestFocusInWindow(): Boolean {
        return textArea.requestFocusInWindow()
    }

    private fun updatePlaceholder() {
        val shouldShow = textArea.text.isBlank() && !textArea.hasFocus()
        if (showPlaceholder != shouldShow) {
            showPlaceholder = shouldShow
            textArea.repaint()
        }
    }

    private fun updateHeight() {
        val lineCount = textArea.lineCount
        val rows = lineCount.coerceIn(minRows, maxRows)

        val insets = textArea.insets
        val newHeight = rows * lineHeight + insets.top + insets.bottom

        val currentHeight = scrollPane.viewport.height

        if (currentHeight != newHeight) {
            scrollPane.viewport.preferredSize = Dimension(
                scrollPane.viewport.width,
                newHeight
            )
            revalidate()
        }
    }

    private fun updateSuggestion() {
        val text = textArea.text.trim()

        if (text.startsWith("/") && !text.contains(" ")) {
            suggestionText = commands.find { it.startsWith(text) }
        } else {
            suggestionText = null
        }

        textArea.repaint()
    }

    private fun autocomplete() {
        if (suggestionText != null) {
            textArea.text = suggestionText!!
            suggestionText = null
            textArea.repaint()
        }
    }

    private fun setupActions() {
        val inputMap = textArea.getInputMap(JComponent.WHEN_FOCUSED)
        val actionMap = textArea.actionMap

        val enterKey = KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0)
        val shiftEnterKey = KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, java.awt.event.InputEvent.SHIFT_DOWN_MASK)
        val tabKey = KeyStroke.getKeyStroke(KeyEvent.VK_TAB, 0)

        inputMap.put(enterKey, "sendMessage")
        actionMap.put("sendMessage", object : AbstractAction() {
            override fun actionPerformed(e: java.awt.event.ActionEvent?) {
                triggerSend()
            }
        })

        inputMap.put(shiftEnterKey, "insert-break")

        inputMap.put(tabKey, "autocomplete")
        actionMap.put("autocomplete", object : AbstractAction() {
            override fun actionPerformed(e: java.awt.event.ActionEvent?) {
                autocomplete()
            }
        })
    }

    private fun triggerSend() {
        val text = textArea.text.trim()
        if (text.isNotEmpty() || codeReferences.isNotEmpty()) {
            onSendCallback(text, codeReferences.toList())
            textArea.text = ""
            codeReferences.clear()
            rebuildTagsPanel()
            updateHeight()
        }
    }

    fun clear() {
        textArea.text = ""
        codeReferences.clear()
        rebuildTagsPanel()
        if (!textArea.hasFocus()) {
            showPlaceholder = true
            textArea.repaint()
        }
        updateHeight()
    }
}
