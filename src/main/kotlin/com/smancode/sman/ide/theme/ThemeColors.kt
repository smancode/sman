package com.smancode.sman.ide.theme

import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.util.SystemInfo
import java.awt.Color

/**
 * 主题配色常量
 * <p>
 * 设计原则：
 * - 基于 JetBrains IDE 语法主题配色
 * - 支持 Light/Dark 主题自动切换
 * - WCAG AAA 可访问性标准 (4.5:1 对比度)
 * - 专业、现代、干净的开发者工具风格
 */
object ThemeColors {

    /**
     * 检测当前 IDE 是否为暗色主题
     */
    fun isDarkTheme(): Boolean {
        val scheme = EditorColorsManager.getInstance().globalScheme
        return isDarkColor(scheme.defaultBackground)
    }

    private fun isDarkColor(color: Color?): Boolean {
        if (color == null) return false
        // 计算亮度 (Rec. 601)
        val luminance = (0.299 * color.red + 0.587 * color.green + 0.114 * color.blue) / 255
        return luminance < 0.5
    }

    /**
     * 获取当前主题配色
     */
    fun getCurrentColors(): ColorPalette {
        return if (isDarkTheme()) DarkColors else LightColors
    }
}

/**
 * 配色方案接口
 */
interface ColorPalette {
    val background: Color
    val surface: Color
    val border: Color
    val textPrimary: Color
    val textSecondary: Color
    val textMuted: Color

    // 状态颜色
    val success: Color
    val error: Color
    val warning: Color
    val info: Color

    // 阶段性结论颜色
    val conclusion: Color

    // 代码语法高亮颜色 (基于 IntelliJ Darcula / Light)
    val codeKeyword: Color
    val codeString: Color
    val codeComment: Color
    val codeNumber: Color
    val codeFunction: Color
    val codeVariable: Color

    // UI 元素颜色
    val divider: Color
    val progressBackground: Color
    val progressForeground: Color
    val iconAccent: Color
}

/**
 * 暗色主题配色 (基于 Darcula + JetBrains Dark)
 * <p>
 * 适合：夜间编码、长时间使用、OLED 屏幕
 */
object DarkColors : ColorPalette {
    // 背景色系
    override val background = Color(0x1E1E1E)      // #1E1E1E - Darcula 背景
    override val surface = Color(0x2B2B2B)         // #2B2B2B - 卡片/面板背景
    override val border = Color(0x3C3C3C)          // #3C3C3C - 边框
    override val divider = Color(0x4A4A4A)         // #4A4A4A - 分隔线

    // 文字颜色 (高对比度，WCAG AAA)
    override val textPrimary = Color(0xA9B7C6)     // #A9B7C6 - 主要文字
    override val textSecondary = Color(0x808080)   // #808080 - 次要文字
    override val textMuted = Color(0x6C6C6C)       // #6C6C6C - 弱化文字

    // 状态颜色
    override val success = Color(0x57A64A)        // #57A64A - Git 绿
    override val error = Color(0xFF6B68)          // #FF6B68 - 错误红
    override val warning = Color(0xFFC66D)         // #FFC66D - 警告橙
    override val info = Color(0x61AFEF)           // #61AFEF - 信息蓝
    override val conclusion = Color(0xC678DD)     // #C678DD - 阶段性结论紫

    // 代码语法高亮 (Darcula 配色)
    override val codeKeyword = Color(0xC678DD)    // #C678DD - 关键字 (紫色)
    override val codeString = Color(0x98C379)     // #98C379 - 字符串 (绿色)
    override val codeComment = Color(0x5C6370)    // #5C6370 - 注释 (灰色)
    override val codeNumber = Color(0xD19A66)     // #D19A66 - 数字 (橙色)
    override val codeFunction = Color(0x61AFEF)   // #61AFEF - 函数 (蓝色)
    override val codeVariable = Color(0xE5C07B)   // #E5C07B - 变量 (橙红)

    // UI 元素
    override val progressBackground = Color(0x3C3C3C)
    override val progressForeground = Color(0x61AFEF)
    override val iconAccent = Color(0x61AFEF)
}

/**
 * 亮色主题配色 (基于 IntelliJ Light)
 * <p>
 * 适合：日间编码、明亮环境、打印友好
 */
object LightColors : ColorPalette {
    // 背景色系（淡雅配色，不过于抢眼）
    override val background = Color(0xFAFAFA)      // #FAFAFA - 淡灰白背景
    override val surface = Color(0xF5F5F5)         // #F5F5F5 - 卡片/面板背景
    override val border = Color(0xEBEBEB)          // #EBEBEB - 边框
    override val divider = Color(0xE5E5E5)         // #E5E5E5 - 分隔线

    // 文字颜色 (高对比度，WCAG AAA)
    override val textPrimary = Color(0x1E1E1E)     // #1E1E1E - 主要文字 (接近黑)
    override val textSecondary = Color(0x4A4A4A)   // #4A4A4A - 次要文字
    override val textMuted = Color(0x8C8C8C)       // #8C8C8C - 弱化文字

    // 状态颜色
    override val success = Color(0x57A64A)        // #57A64A - Git 绿 (保持一致)
    override val error = Color(0xE45649)          // #E45649 - 错误红 (稍暗)
    override val warning = Color(0xFFA500)         // #FFA500 - 警告橙
    override val info = Color(0x0066CC)           // #0066CC - 信息蓝
    override val conclusion = Color(0x6F42C1)     // #6F42C1 - 阶段性结论紫

    // 代码语法高亮 (IntelliJ Light 配色)
    override val codeKeyword = Color(0x0000FF)    // #0000FF - 关键字 (蓝色)
    override val codeString = Color(0x008000)     // #008000 - 字符串 (绿色)
    override val codeComment = Color(0x808080)    // #808080 - 注释 (灰色)
    override val codeNumber = Color(0x000080)     // #000080 - 数字 (深蓝)
    override val codeFunction = Color(0x7F0055)   // #7F0055 - 函数 (紫红)
    override val codeVariable = Color(0x000000)   // #000000 - 变量 (黑色)

    // UI 元素
    override val progressBackground = Color(0xE0E0E0)
    override val progressForeground = Color(0x0066CC)
    override val iconAccent = Color(0x0066CC)
}

/**
 * 工具图标颜色
 */
object IconColors {
    fun getForTheme(dark: Boolean): IconColorSet {
        return if (dark) DarkIcons.get else LightIcons.get
    }
}

data class IconColorSet(
    val toolCall: Color,
    val progress: Color,
    val success: Color,
    val error: Color,
    val thinking: Color,
    val summary: Color
)

object DarkIcons {
    val get = IconColorSet(
        toolCall = Color(0x61AFEF),    // 蓝色
        progress = Color(0xFFC66D),     // 橙色
        success = Color(0x57A64A),      // 绿色
        error = Color(0xFF6B68),        // 红色
        thinking = Color(0x98C379),     // 黄绿色
        summary = Color(0xC678DD)       // 紫色
    )
}

object LightIcons {
    val get = IconColorSet(
        toolCall = Color(0x0066CC),     // 深蓝色
        progress = Color(0xFF9500),     // 深橙色
        success = Color(0x22863A),      // 深绿色
        error = Color(0xC41C16),        // 深红色
        thinking = Color(0x9A6700),     // 深黄色
        summary = Color(0x6F42C1)       // 深紫色
    )
}

/**
 * 代码块样式
 */
data class CodeBlockStyle(
    val background: Color,
    val border: Color,
    val headerBackground: Color,
    val headerText: Color
) {
    companion object {
        fun forTheme(dark: Boolean): CodeBlockStyle {
            return if (dark) {
                CodeBlockStyle(
                    background = Color(0x2B2B2B),
                    border = Color(0x3C3C3C),
                    headerBackground = Color(0x353535),
                    headerText = Color(0xA9B7C6)
                )
            } else {
                CodeBlockStyle(
                    background = Color(0xF5F5F5),
                    border = Color(0xE0E0E0),
                    headerBackground = Color(0xE8E8E8),
                    headerText = Color(0x1E1E1E)
                )
            }
        }
    }
}

/**
 * 分隔线样式
 */
data class DividerStyle(
    val color: Color,
    val thickness: Int,
    val style: DividerType
) {
    companion object {
        fun forTheme(dark: Boolean): DividerStyle {
            return DividerStyle(
                color = if (dark) Color(0x3C3C3C) else Color(0xE0E0E0),
                thickness = 1,
                style = DividerType.SOLID
            )
        }
    }
}

enum class DividerType {
    SOLID, DASHED, DOUBLE
}

/**
 * 聊天界面专用颜色（用于 HistoryPopup 等组件）
 */
object ChatColors {
    val surface: Color get() = ThemeColors.getCurrentColors().surface
    val textPrimary: Color get() = ThemeColors.getCurrentColors().textPrimary
    val textSecondary: Color get() = ThemeColors.getCurrentColors().textSecondary
    val userBubbleBorder: Color get() = ThemeColors.getCurrentColors().border
}
