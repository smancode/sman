package com.smancode.sman.ide.components

import com.intellij.openapi.util.IconLoader
import com.intellij.util.IconUtil
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.smancode.sman.ide.theme.ThemeColors
import java.awt.*
import javax.swing.*

/**
 * 欢迎面板
 *
 * 显示 Sman 图标、介绍文字和配置说明
 */
class WelcomePanel : JPanel(GridBagLayout()) {

    init {
        isOpaque = true
        background = ThemeColors.getCurrentColors().background

        val gbc = GridBagConstraints()
        gbc.gridx = 0
        gbc.anchor = GridBagConstraints.CENTER
        gbc.weighty = 0.0
        gbc.fill = GridBagConstraints.NONE

        // 顶部留白（使用 weighty 把内容推到中间）
        gbc.gridy = 0
        gbc.weighty = 1.0
        add(Box.createVerticalBox(), gbc)

        // Logo (去色)
        try {
            var originalIcon = IconLoader.findIcon("/META-INF/pluginIconToolWindow.svg", javaClass)

            if (originalIcon == null) {
                originalIcon = IconLoader.findIcon("/META-INF/pluginIcon.svg", javaClass)
            }

            if (originalIcon != null) {
                // 放大图标 (8倍 -> ~100px)
                val scaledIcon = IconUtil.scale(originalIcon, null, 8.0f)

                gbc.gridy = 1
                gbc.weighty = 0.0
                gbc.insets = JBUI.insetsBottom(16)
                add(JLabel(scaledIcon), gbc)
            }
        } catch (e: Exception) {
            // 图标加载失败，不显示
        }

        // Title
        gbc.gridy = 2
        gbc.insets = JBUI.insetsBottom(8)
        val titleLabel = JLabel("Sman").apply {
            font = font.deriveFont(Font.BOLD, JBUI.scale(24f))
            foreground = JBColor(Color(0x505050), Color(0xBBBBBB))
        }
        add(titleLabel, gbc)

        // Description
        gbc.gridy = 3
        gbc.insets = JBUI.insetsBottom(16)
        val descLabel = JLabel("智能代码分析助手 - 帮助开发者理解代码逻辑和架构").apply {
            font = font.deriveFont(JBUI.scale(13f))
            foreground = ThemeColors.getCurrentColors().textSecondary
        }
        add(descLabel, gbc)

        // 配置步骤
        gbc.gridy = 4
        gbc.insets = JBUI.insetsBottom(20)
        val configLabel = JLabel("<html><div style='text-align: center;'>点击右上角的 <b>...</b> 按钮打开设置页面</div></html>").apply {
            font = font.deriveFont(JBUI.scale(12f))
            foreground = JBColor(Color(0x999999), Color(0x666666))
        }
        add(configLabel, gbc)

        // 底部留白（使用 weighty 把内容推到中间）
        gbc.gridy = 5
        gbc.weighty = 1.0
        add(Box.createVerticalBox(), gbc)
    }
}
