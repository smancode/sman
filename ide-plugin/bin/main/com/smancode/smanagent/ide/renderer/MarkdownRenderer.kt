package com.smancode.smanagent.ide.renderer

import com.smancode.smanagent.ide.theme.ColorPalette
import com.smancode.smanagent.ide.theme.ThemeColors
import com.vladsch.flexmark.ext.gfm.strikethrough.StrikethroughExtension
import com.vladsch.flexmark.ext.tables.TablesExtension
import com.vladsch.flexmark.html.HtmlRenderer
import com.vladsch.flexmark.parser.Parser
import com.vladsch.flexmark.util.ast.Document
import com.vladsch.flexmark.util.data.MutableDataSet
import javax.swing.text.html.HTMLEditorKit
import javax.swing.text.html.StyleSheet
import java.awt.Color
import java.io.StringReader

/**
 * Markdown 渲染器
 * <p>
 * 使用 flexmark-java 将 Markdown 转换为带样式的 HTML，
 * 然后通过 JTextPane 的 HTML 支持进行渲染。
 */
object MarkdownRenderer {

    private val options = MutableDataSet().apply {
        // 启用 GitHub Flavored Markdown 扩展
        set(Parser.EXTENSIONS, listOf(
            TablesExtension.create(),
            StrikethroughExtension.create()
        ))
    }

    private val parser = Parser.builder(options).build()
    private val htmlRenderer = HtmlRenderer.builder(options).build()

    /**
     * 将 Markdown 转换为 HTML
     */
    fun markdownToHtml(markdown: String): String {
        if (markdown.isBlank()) return ""

        val document: Document = parser.parse(markdown)
        return htmlRenderer.render(document)
    }

    /**
     * 创建带样式的 HTML EditorKit
     */
    fun createStyledEditorKit(colors: ColorPalette = ThemeColors.getCurrentColors()): HTMLEditorKit {
        val editorKit = HTMLEditorKit()
        val styleSheet = StyleSheet()

        // 基础样式 - 使用主题颜色
        styleSheet.addRule("body { font-family: 'JetBrains Mono', monospace; font-size: 13pt; color: ${toHexString(colors.textPrimary)}; text-align: left; max-width: 100%; overflow-wrap: break-word; }")

        // 标题样式 - 使用 info 颜色
        styleSheet.addRule("h1 { font-size: 18pt; color: ${toHexString(colors.info)}; margin-top: 10px; }")
        styleSheet.addRule("h2 { font-size: 16pt; color: ${toHexString(colors.info)}; margin-top: 8px; }")
        styleSheet.addRule("h3 { font-size: 14pt; color: ${toHexString(colors.info)}; margin-top: 6px; }")

        // 文本样式 - 重要：使用 !important 确保颜色生效
        styleSheet.addRule("strong { color: ${toHexString(colors.warning)} !important; }")
        styleSheet.addRule("b { color: ${toHexString(colors.warning)} !important; }")
        styleSheet.addRule("em { font-style: italic; }")
        styleSheet.addRule("i { font-style: italic; }")

        // 代码样式 - 使用 background 与 JTextPane 背景色保持一致
        styleSheet.addRule("code { font-family: 'JetBrains Mono', monospace; background-color: ${toHexString(colors.background)}; color: ${toHexString(colors.codeString)}; padding: 2px 4px; border-radius: 3px; }")
        styleSheet.addRule("pre { background-color: ${toHexString(colors.background)}; padding: 10px; border-radius: 5px; margin: 10px 0; }")
        styleSheet.addRule("pre code { background-color: transparent; padding: 0; }")

        // 列表样式 - 移除 li 内 p 标签的 margin 以确保对齐
        styleSheet.addRule("ul { margin-left: 20px; padding-left: 0; list-style-position: outside; }")
        styleSheet.addRule("ol { margin-left: 20px; padding-left: 0; list-style-position: outside; }")
        // 嵌套列表不额外缩进（因为 ul/ol 本身就是块级元素）
        styleSheet.addRule("ul ul, ul ol, ol ul, ol ol { margin-left: 0; }")
        styleSheet.addRule("li { padding-left: 0; margin: 2px 0; line-height: 1.4; }")
        styleSheet.addRule("li p { margin: 0; }")  // 关键：移除列表项内段落的上下边距

        // 引用样式
        styleSheet.addRule("blockquote { border-left: 3px solid ${toHexString(colors.info)}; margin: 10px 0; padding-left: 10px; color: ${toHexString(colors.textSecondary)}; }")

        // 表格样式
        styleSheet.addRule("table { border-collapse: collapse; width: 100%; margin: 10px 0; }")
        styleSheet.addRule("th, td { border: 1px solid ${toHexString(colors.border)}; padding: 8px; text-align: left; }")
        styleSheet.addRule("th { background-color: ${toHexString(colors.surface)}; }")

        // 链接样式 - 使用 codeFunction 颜色（蓝色），无下划线，悬停时通过 MouseMotionListener 显示手型光标
        styleSheet.addRule("a { color: ${toHexString(colors.codeFunction)}; text-decoration: none; }")

        // 水平线样式
        styleSheet.addRule("hr { border: none; border-top: 1px solid ${toHexString(colors.border)}; margin: 15px 0; }")

        // 段落样式
        styleSheet.addRule("p { margin: 5px 0; }")

        editorKit.styleSheet = styleSheet
        return editorKit
    }

    /**
     * 将 Color 转换为十六进制字符串
     */
    private fun toHexString(color: Color): String {
        return "#${color.red.toString(16).padStart(2, '0')}${color.green.toString(16).padStart(2, '0')}${color.blue.toString(16).padStart(2, '0')}"
    }
}
