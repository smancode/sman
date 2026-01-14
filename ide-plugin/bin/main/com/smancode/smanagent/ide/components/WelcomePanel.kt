package com.smancode.smanagent.ide.components

import com.intellij.openapi.util.IconLoader
import com.intellij.util.IconUtil
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.smancode.smanagent.ide.theme.ThemeColors
import java.awt.*
import javax.swing.*

/**
 * 欢迎面板
 *
 * 显示 SmanAgent 图标和介绍文字
 */
class WelcomePanel : JPanel(GridBagLayout()) {

    init {
        isOpaque = true
        background = ThemeColors.getCurrentColors().background

        val gbc = GridBagConstraints()
        gbc.gridx = 0
        gbc.gridy = 0
        gbc.anchor = GridBagConstraints.CENTER
        gbc.insets = JBUI.insetsBottom(16)

        // Logo (去色)
        try {
            var originalIcon = IconLoader.findIcon("/META-INF/pluginIconToolWindow.svg", javaClass)

            if (originalIcon == null) {
                originalIcon = IconLoader.findIcon("/META-INF/pluginIcon.svg", javaClass)
            }

            if (originalIcon != null) {
                // 获取去色/禁用状态图标
                val grayIcon = IconLoader.getDisabledIcon(originalIcon)

                // 放大图标 (4倍)
                val scaledIcon = IconUtil.scale(grayIcon, null, 4.0f)

                add(JLabel(scaledIcon), gbc)
            }
        } catch (e: Exception) {
            // 图标加载失败，不显示
        }

        // Title
        gbc.gridy++
        gbc.insets = JBUI.insetsBottom(8)
        val titleLabel = JLabel("SmanAgent").apply {
            font = font.deriveFont(Font.BOLD, JBUI.scale(24f))
            foreground = JBColor(Color(0x505050), Color(0xBBBBBB))
        }
        add(titleLabel, gbc)

        // Description
        gbc.gridy++
        gbc.insets = JBUI.insetsBottom(160)
        val descLabel = JLabel("SmanAgent-智能代码分析助手，帮助用户分析需求、理解代码逻辑。").apply {
            font = font.deriveFont(JBUI.scale(13f))
            foreground = ThemeColors.getCurrentColors().textSecondary
        }
        add(descLabel, gbc)
    }
}
