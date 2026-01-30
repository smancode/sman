package com.smancode.smanagent.ide.components

import com.intellij.util.ui.JBUI
import com.smancode.smanagent.ide.theme.ThemeColors
import com.smancode.smanagent.ide.ui.FontManager
import java.awt.*
import java.awt.event.KeyEvent
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
 */
class CliInputArea(
    private val onSendCallback: (String) -> Unit
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

    // 文本区域和滚动面板
    val textArea: JTextArea
    private val scrollPane: JScrollPane

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
        layout = null  // 使用绝对布局
        border = null

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
        // 使用编辑器字体，与输出区域保持一致
        textArea.font = FontManager.getEditorFont()
        val colors = ThemeColors.getCurrentColors()
        textArea.foreground = colors.textPrimary
        textArea.caretColor = colors.textPrimary
        val padding = JBUI.scale(12)
        textArea.border = EmptyBorder(padding, padding, padding, padding)

        // 创建滚动面板（显示滚动条但隐藏它）
        scrollPane = JScrollPane(textArea).apply {
            verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
            border = null
            isOpaque = false
            viewport.isOpaque = false
            verticalScrollBar.apply { preferredSize = Dimension(0, 0) }
        }

        // 添加组件（使用绝对布局）
        add(scrollPane)

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

        // 文本变化监听（用于自动增高和命令建议）
        textArea.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: javax.swing.event.DocumentEvent?) = onTextChanged()
            override fun removeUpdate(e: javax.swing.event.DocumentEvent?) = onTextChanged()
            override fun changedUpdate(e: javax.swing.event.DocumentEvent?) = onTextChanged()
        })

        // 键盘快捷键
        setupActions()
    }

    private fun setupGraphics(g2: java.awt.Graphics2D, colors: com.smancode.smanagent.ide.theme.ColorPalette): java.awt.Color {
        val oldColor = g2.color
        g2.color = colors.textMuted
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        return oldColor
    }

    private fun drawPlaceholder(g2: java.awt.Graphics2D, fm: java.awt.FontMetrics, colors: com.smancode.smanagent.ide.theme.ColorPalette) {
        val oldColor = setupGraphics(g2, colors)

        // 偏移位置：向右 5px，向下 5px
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

    override fun doLayout() {
        val w = width
        val h = height
        scrollPane.setBounds(0, 0, w, h)
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

    /**
     * 自动增高输入框（3-10行）
     * 超过10行时固定高度，可通过滚动查看
     */
    private fun updateHeight() {
        val lineCount = textArea.lineCount
        val rows = lineCount.coerceIn(minRows, maxRows)

        // 计算新的高度：行高 × 行数 + 上下边框
        val insets = textArea.insets
        val newHeight = rows * lineHeight + insets.top + insets.bottom

        // 获取当前视口高度
        val currentHeight = scrollPane.viewport.height

        // 只在高度真正变化时才更新（避免不必要的重布局）
        if (currentHeight != newHeight) {
            scrollPane.viewport.preferredSize = Dimension(
                scrollPane.viewport.width,
                newHeight
            )
            revalidate()
        }
    }

    /**
     * 更新命令建议
     */
    private fun updateSuggestion() {
        val text = textArea.text.trim()

        // 只有在以 / 开头且不包含空格时才显示建议
        if (text.startsWith("/") && !text.contains(" ")) {
            // 查找匹配的命令
            suggestionText = commands.find { it.startsWith(text) }
        } else {
            suggestionText = null
        }

        textArea.repaint()
    }

    /**
     * 自动补全命令
     */
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

        // Tab 键补全命令
        inputMap.put(tabKey, "autocomplete")
        actionMap.put("autocomplete", object : AbstractAction() {
            override fun actionPerformed(e: java.awt.event.ActionEvent?) {
                autocomplete()
            }
        })
    }

    private fun triggerSend() {
        val text = textArea.text.trim()
        if (text.isNotEmpty()) {
            onSendCallback(text)
            textArea.text = ""
            updateHeight() // 重置高度
        }
    }

    fun clear() {
        textArea.text = ""
        if (!textArea.hasFocus()) {
            showPlaceholder = true
            textArea.repaint()
        }
        updateHeight() // 重置高度
    }
}
