package com.smancode.sman.ide.components

import com.intellij.icons.AllIcons
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.*
import javax.swing.*
import javax.swing.border.EmptyBorder

/**
 * CLI 风格控制栏
 *
 * 包含：新建会话、历史记录、设置按钮
 * 布局：左侧（新建会话、历史记录），右侧（设置）
 */
class CliControlBar(
    private val onNewChatCallback: () -> Unit,
    private val onHistoryCallback: () -> Unit,
    private val onSettingsCallback: () -> Unit
) : JPanel(BorderLayout()) {

    private val logger = org.slf4j.LoggerFactory.getLogger(CliControlBar::class.java)

    // 历史按钮引用（供外部使用）
    private val _historyButtonRef = java.util.concurrent.atomic.AtomicReference<JButton>()

    init {
        val colors = com.smancode.sman.ide.theme.ThemeColors.getCurrentColors()
        background = colors.background
        border = EmptyBorder(JBUI.scale(4), JBUI.scale(4), JBUI.scale(4), JBUI.scale(4))

        val controlPanel = JPanel(BorderLayout()).apply {
            isOpaque = false
            this.border = EmptyBorder(0, JBUI.scale(4), 0, JBUI.scale(4))
        }

        // 统一按钮高度
        val buttonHeight = JBUI.scale(28)

        // 左侧：新建会话、历史记录
        val leftPanel = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0)).apply {
            isOpaque = false
            preferredSize = Dimension(Short.MAX_VALUE.toInt(), buttonHeight)
            maximumSize = Dimension(Short.MAX_VALUE.toInt(), buttonHeight)

            // 新建会话按钮
            val newChatButton = createNewChatButton(buttonHeight)
            add(newChatButton)

            // 历史记录按钮
            val historyBtn = createHistoryButton(buttonHeight)
            _historyButtonRef.set(historyBtn)
            add(historyBtn)
        }

        // 右侧：设置
        val rightPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0)).apply {
            isOpaque = false
            preferredSize = Dimension(Short.MAX_VALUE.toInt(), buttonHeight)
            maximumSize = Dimension(Short.MAX_VALUE.toInt(), buttonHeight)

            val settingsButton = createSettingsButton(buttonHeight)
            add(settingsButton)
        }

        controlPanel.add(leftPanel, BorderLayout.WEST)
        controlPanel.add(rightPanel, BorderLayout.EAST)
        add(controlPanel, BorderLayout.CENTER)
    }

    fun getHistoryButton(): JButton? {
        return _historyButtonRef.get()
    }

    private fun createSettingsButton(height: Int): JButton {
        val icon = object : Icon {
            override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
                val g2 = g.create() as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

                val dotSize = JBUI.scale(3.5f).toInt()
                val gap = JBUI.scale(3)

                val totalWidth = dotSize * 3 + gap * 2
                val startX = x + (iconWidth - totalWidth) / 2
                val startY = y + (iconHeight - dotSize) / 2

                g2.fillOval(startX, startY, dotSize, dotSize)
                g2.fillOval(startX + dotSize + gap, startY, dotSize, dotSize)
                g2.fillOval(startX + (dotSize + gap) * 2, startY, dotSize, dotSize)
                g2.dispose()
            }

            override fun getIconWidth() = JBUI.scale(16)
            override fun getIconHeight() = JBUI.scale(16)
        }

        return createIconButton(icon, "设置", height) {
            onSettingsCallback()
        }
    }

    private fun createHistoryButton(height: Int): JButton {
        val icon = AllIcons.Vcs.History

        return createIconButton(icon, "历史记录", height) {
            onHistoryCallback()
        }
    }

    /**
     * 创建新建会话按钮（与历史按钮相同的绘制方式）
     */
    private fun createNewChatButton(height: Int): JButton {
        return object : JButton() {
            override fun paintComponent(g: Graphics) {
                val g2 = g as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)

                val colors = com.smancode.sman.ide.theme.ThemeColors.getCurrentColors()

                // 背景
                if (model.isRollover) {
                    g2.color = colors.surface
                    g2.fillRoundRect(0, 0, width, height, JBUI.scale(6), JBUI.scale(6))
                }

                // 绘制 + 符号（居中）
                g2.color = colors.textPrimary
                g2.font = Font(g2.font.name, Font.BOLD, 18)
                val fm = g2.fontMetrics
                val text = "+"
                val textWidth = fm.stringWidth(text)
                val textHeight = fm.ascent
                val x = (width - textWidth) / 2
                val y = (height + textHeight) / 2 - 2  // 微调垂直位置
                g2.drawString(text, x, y)
            }
        }.apply {
            toolTipText = "新建会话"
            isContentAreaFilled = false
            isOpaque = false
            isFocusPainted = false
            isBorderPainted = false
            border = null
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            preferredSize = Dimension(JBUI.scale(30), height)
            addActionListener { onNewChatCallback() }
        }
    }

    private fun createTextButton(text: String, tooltip: String, action: () -> Unit): JButton {
        return JButton(text).apply {
            this.toolTipText = tooltip
            isContentAreaFilled = false
            isOpaque = false
            isFocusPainted = false
            isBorderPainted = false
            border = null
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            preferredSize = Dimension(JBUI.scale(60), JBUI.scale(26))
            addActionListener { action() }
        }
    }

    private fun createIconButton(icon: Icon, tooltip: String, height: Int, action: () -> Unit): JButton {
        return object : JButton(icon) {
            override fun paintBorder(g: Graphics?) {
            }

            override fun paintComponent(g: Graphics) {
                val g2 = g as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

                if (model.isRollover) {
                    val colors = com.smancode.sman.ide.theme.ThemeColors.getCurrentColors()
                    g2.color = colors.surface
                    g2.fillRoundRect(0, 0, width, height, JBUI.scale(6), JBUI.scale(6))
                }

                val iconX = (width - icon.iconWidth) / 2
                val iconY = (height - icon.iconHeight) / 2
                icon.paintIcon(this, g2, iconX, iconY)
            }

        }.apply {
            toolTipText = tooltip
            isContentAreaFilled = false
            isOpaque = false
            isFocusPainted = false
            isBorderPainted = false
            border = null
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            preferredSize = Dimension(JBUI.scale(30), height)
            addActionListener { action() }
        }
    }
}
