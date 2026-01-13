package com.smancode.smanagent.ide.renderer

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
    fun createStyledEditorKit(): HTMLEditorKit {
        val editorKit = HTMLEditorKit()
        val styleSheet = StyleSheet()

        // 基础样式 - 明确设置颜色
        styleSheet.addRule("body { font-family: 'JetBrains Mono', monospace; font-size: 13pt; color: #A9B7C6; text-align: left; }")

        // 标题样式
        styleSheet.addRule("h1 { font-size: 18pt; color: #61AFEF; margin-top: 10px; }")
        styleSheet.addRule("h2 { font-size: 16pt; color: #61AFEF; margin-top: 8px; }")
        styleSheet.addRule("h3 { font-size: 14pt; color: #61AFEF; margin-top: 6px; }")

        // 文本样式 - 重要：使用 !important 确保颜色生效
        styleSheet.addRule("strong { color: #FFC66D !important; }")
        styleSheet.addRule("b { color: #FFC66D !important; }")
        styleSheet.addRule("em { font-style: italic; }")
        styleSheet.addRule("i { font-style: italic; }")

        // 代码样式
        styleSheet.addRule("code { font-family: 'JetBrains Mono', monospace; background-color: #2B2B2B; color: #98C379; padding: 2px 4px; border-radius: 3px; }")
        styleSheet.addRule("pre { background-color: #2B2B2B; padding: 10px; border-radius: 5px; margin: 10px 0; }")
        styleSheet.addRule("pre code { background-color: transparent; padding: 0; }")

        // 列表样式
        styleSheet.addRule("ul { margin-left: 20px; }")
        styleSheet.addRule("ol { margin-left: 20px; }")
        styleSheet.addRule("li { margin: 2px 0; }")

        // 引用样式
        styleSheet.addRule("blockquote { border-left: 3px solid #61AFEF; margin: 10px 0; padding-left: 10px; color: #808080; }")

        // 表格样式
        styleSheet.addRule("table { border-collapse: collapse; width: 100%; margin: 10px 0; }")
        styleSheet.addRule("th, td { border: 1px solid #3C3C3C; padding: 8px; text-align: left; }")
        styleSheet.addRule("th { background-color: #2B2B2B; }")

        // 链接样式
        styleSheet.addRule("a { color: #61AFEF; text-decoration: underline; }")

        // 水平线样式
        styleSheet.addRule("hr { border: none; border-top: 1px solid #3C3C3C; margin: 15px 0; }")

        // 段落样式
        styleSheet.addRule("p { margin: 5px 0; }")

        editorKit.styleSheet = styleSheet
        return editorKit
    }
}
