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
     * 注意：Swing HTML 引擎只支持 CSS Level 1，避免使用现代 CSS 属性
     */
    fun createStyledEditorKit(colors: ColorPalette = ThemeColors.getCurrentColors()): HTMLEditorKit {
        val editorKit = HTMLEditorKit()
        val styleSheet = StyleSheet()

        // 获取字体信息
        val labelFont = com.intellij.util.ui.UIUtil.getLabelFont()
        val editorFont = com.intellij.openapi.editor.colors.EditorColorsManager.getInstance().globalScheme

        // 基础样式 - 使用 UI 字体
        styleSheet.addRule("""
            body {
                font-family: "${labelFont.family}", sans-serif;
                font-size: 100%;
                color: ${toHexString(colors.textPrimary)};
                margin-top: 0;
                margin-bottom: 0;
                word-wrap: break-word;
            }
        """.trimIndent())

        // 段落样式
        styleSheet.addRule("p { margin-top: 4px; margin-bottom: 4px; }")

        // 标题样式 - 使用 info 颜色，保持与正文一致大小
        styleSheet.addRule("h1, h2, h3 { font-size: 100%; font-weight: bold; color: ${toHexString(colors.info)}; margin-top: 10px; margin-bottom: 5px; }")

        // 文本样式
        styleSheet.addRule("strong { color: ${toHexString(colors.warning)}; }")
        styleSheet.addRule("b { color: ${toHexString(colors.warning)}; }")
        styleSheet.addRule("em { font-style: italic; }")
        styleSheet.addRule("i { font-style: italic; }")

        // 代码样式 - 使用编辑器字体
        styleSheet.addRule("""
            code {
                font-family: "${editorFont.editorFontName}", Monospaced;
                font-size: 100%;
                color: ${toHexString(colors.codeString)};
            }
        """.trimIndent())

        styleSheet.addRule("""
            pre {
                background-color: ${toHexString(colors.background)};
                padding: 5px;
                font-family: "${editorFont.editorFontName}", Monospaced;
                font-size: 100%;
                margin-top: 8px;
                margin-bottom: 8px;
            }
        """.trimIndent())

        // 列表样式
        styleSheet.addRule("ul, ol { margin-top: 4px; margin-bottom: 4px; margin-left: 15px; }")
        styleSheet.addRule("li { margin-top: 2px; margin-bottom: 2px; }")
        styleSheet.addRule("li p { margin-top: 0; margin-bottom: 0; }")

        // 引用样式
        styleSheet.addRule("blockquote { border-left: 3px solid ${toHexString(colors.info)}; margin: 10px 0; padding-left: 10px; color: ${toHexString(colors.textSecondary)}; }")

        // 表格样式
        styleSheet.addRule("table { border-collapse: collapse; width: 100%; margin: 10px 0; }")
        styleSheet.addRule("th, td { border: 1px solid ${toHexString(colors.border)}; padding: 6px; text-align: left; }")
        styleSheet.addRule("th { background-color: ${toHexString(colors.surface)}; font-weight: bold; }")

        // 链接样式
        styleSheet.addRule("a { color: ${toHexString(colors.codeFunction)}; text-decoration: none; }")

        // 水平线样式
        styleSheet.addRule("hr { border-top: 1px solid ${toHexString(colors.border)}; margin-top: 15px; margin-bottom: 15px; }")

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
