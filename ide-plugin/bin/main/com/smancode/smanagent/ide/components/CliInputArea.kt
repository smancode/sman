package com.smancode.smanagent.ide.components

import com.intellij.util.ui.JBUI
import com.smancode.smanagent.ide.theme.ThemeColors
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
    private val placeholderText = "点击 + 新建会话，Enter 发送，Shift+Enter 换行\n/commit  自动总结并 #AI commit#"
    private var showPlaceholder = true
    private var isFocused = false

    // 命令补全相关
    private val commands = listOf("/commit")
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
        layout = BorderLayout()
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
                val fm = g2.fontMetrics
                val colors = ThemeColors.getCurrentColors()

                // 绘制占位符
                if (showPlaceholder && text.isBlank()) {
                    val oldColor = g2.color
                    g2.color = colors.textMuted
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                    val x = insets.left
                    var y = insets.top + fm.ascent

                    // 按行分割绘制
                    val lines = placeholderText.split("\n")
                    for (line in lines) {
                        g2.drawString(line, x, y)
                        y += fm.height  // 移动到下一行
                    }

                    g2.color = oldColor
                }

                // 绘制命令建议（灰色提示）
                if (suggestionText != null) {
                    val currentText = this.text.trim()
                    if (currentText.startsWith("/") && !currentText.contains(" ")) {
                        val oldColor = g2.color
                        g2.color = colors.textMuted
                        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

                        // 计算当前文本的宽度，在光标位置绘制建议
                        val currentTextWidth = fm.stringWidth(currentText)
                        val x = insets.left + currentTextWidth
                        val y = insets.top + fm.ascent

                        // 绘制建议文本（去掉已输入的部分）
                        val suggestion = suggestionText!!.substring(currentText.length)
                        g2.drawString(suggestion as String, x, y)

                        g2.color = oldColor
                    }
                }
            }
        }

        // 配置文本区域
        textArea.rows = minRows
        textArea.font = Font("JetBrains Mono", Font.PLAIN, 13)
        val colors = ThemeColors.getCurrentColors()
        textArea.foreground = colors.textPrimary
        textArea.caretColor = colors.textPrimary
        textArea.border = EmptyBorder(
            JBUI.scale(12),
            JBUI.scale(12),
            JBUI.scale(12),
            JBUI.scale(12)
        )

        // 创建滚动面板（显示滚动条但隐藏它）
        scrollPane = JScrollPane(textArea).apply {
            verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
            border = null
            isOpaque = false
            viewport.isOpaque = false

            // 隐藏滚动条但保留滚动功能
            verticalScrollBar.apply {
                preferredSize = Dimension(0, 0)
            }
        }

        // 包装在有边框的面板中
        val inputWrapper = JPanel(BorderLayout()).apply {
            isOpaque = true
            background = colors.background
            border = InputBorder(cornerRadius, colors)

            add(scrollPane, BorderLayout.CENTER)
        }

        add(inputWrapper, BorderLayout.CENTER)

        // 焦点监听
        textArea.addFocusListener(object : java.awt.event.FocusAdapter() {
            override fun focusGained(e: java.awt.event.FocusEvent?) {
                if (!isFocused) {
                    isFocused = true
                    inputWrapper.repaint()
                }
                showPlaceholder = false
                textArea.repaint()
            }

            override fun focusLost(e: java.awt.event.FocusEvent?) {
                if (isFocused) {
                    isFocused = false
                    inputWrapper.repaint()
                }
                updatePlaceholder()
            }
        })

        // 文本变化监听（用于自动增高和命令建议）
        textArea.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: javax.swing.event.DocumentEvent?) {
                updatePlaceholder()
                updateHeight()
                updateSuggestion()
            }
            override fun removeUpdate(e: javax.swing.event.DocumentEvent?) {
                updatePlaceholder()
                updateHeight()
                updateSuggestion()
            }
            override fun changedUpdate(e: javax.swing.event.DocumentEvent?) {
                updatePlaceholder()
                updateHeight()
                updateSuggestion()
            }
        })

        // 键盘快捷键
        setupActions()
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

        // 设置 JScrollPane 的视口首选高度
        scrollPane.viewport.preferredSize = Dimension(
            scrollPane.viewport.width,
            newHeight
        )

        revalidate()
        repaint()

        // 确保光标可见
        SwingUtilities.invokeLater {
            textArea.caretPosition = textArea.document.length
        }
    }

    override fun requestFocusInWindow(): Boolean {
        return textArea.requestFocusInWindow()
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

/**
 * 输入框边框（淡灰色，支持焦点状态）
 */
private class InputBorder(
    private val radius: Int,
    private val colors: com.smancode.smanagent.ide.theme.ColorPalette
) : javax.swing.border.Border {

    override fun paintBorder(c: Component?, g: Graphics?, x: Int, y: Int, width: Int, height: Int) {
        val g2 = g as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

        // 淡灰色边框（与背景色相近）
        g2.color = colors.border
        g2.stroke = BasicStroke(1.0f)
        g2.drawRoundRect(x, y, width - 1, height - 1, radius, radius)
    }

    override fun getBorderInsets(c: Component?): Insets {
        return JBUI.emptyInsets()
    }

    override fun isBorderOpaque(): Boolean {
        return true
    }
}
