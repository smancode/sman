package com.smancode.smanagent.ide.components

import com.intellij.icons.AllIcons
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.smancode.smanagent.ide.theme.ThemeColors
import java.awt.*
import javax.swing.*
import javax.swing.border.EmptyBorder

/**
 * CLI 风格控制栏
 *
 * 包含：新建会话、历史记录、设置按钮
 */
class CliControlBar(
    private val onNewChatCallback: () -> Unit,
    private val onHistoryCallback: () -> Unit,
    private val onSettingsCallback: () -> Unit
) : JPanel(BorderLayout()) {

    // 暴露历史按钮供 HistoryPopup 使用
    val historyButton: JButton

    init {
        val colors = ThemeColors.getCurrentColors()
        background = colors.background
        // 降低高度：减小垂直边距
        border = EmptyBorder(JBUI.scale(4), JBUI.scale(8), JBUI.scale(4), JBUI.scale(8))

        val controlPanel = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = EmptyBorder(0, JBUI.scale(4), 0, JBUI.scale(4))
        }

        // 左侧：新建会话、历史记录（移除额外间距，保持紧凑）
        val leftPanel = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            isOpaque = false
            add(createIconButton(AllIcons.General.Add, "新建会话", onNewChatCallback))
            add(Box.createHorizontalStrut(JBUI.scale(8)))
            historyButton = createIconButton(AllIcons.Vcs.History, "历史记录", onHistoryCallback)
            add(historyButton)
        }

        // 右侧：设置
        val rightPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0)).apply {
            isOpaque = false
            add(createSettingsButton())
            add(Box.createHorizontalStrut(JBUI.scale(4)))
        }

        controlPanel.add(leftPanel, BorderLayout.WEST)
        controlPanel.add(rightPanel, BorderLayout.EAST)

        add(controlPanel, BorderLayout.CENTER)
    }

    private fun createIconButton(icon: Icon, tooltip: String, action: () -> Unit): JButton {
        return object : JButton(icon) {
            override fun paintBorder(g: Graphics?) {
                // 不绘制边框
            }

            override fun paintComponent(g: Graphics) {
                val g2 = g as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

                // 悬停时显示背景
                if (model.isRollover) {
                    val colors = ThemeColors.getCurrentColors()
                    g2.color = colors.surface
                    g2.fillRoundRect(0, 0, width, height, JBUI.scale(6), JBUI.scale(6))
                }

                // 绘制图标
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
            preferredSize = Dimension(JBUI.scale(30), JBUI.scale(26))
            addActionListener { action() }
        }
    }

    private fun createSettingsButton(): JButton {
        // 自定义三点图标
        val icon = object : Icon {
            override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
                val g2 = g.create() as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

                g2.color = JBColor(Color(0x606060), Color(0xBBBBBB))

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

        return createIconButton(icon, "设置", onSettingsCallback)
    }
}
