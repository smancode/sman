package com.smancode.smanagent.ide.renderer

import com.smancode.smanagent.ide.model.PartData
import com.smancode.smanagent.ide.model.GraphModels
import com.smancode.smanagent.ide.model.GraphModels.PartType
import com.smancode.smanagent.ide.theme.ThemeColors
import com.smancode.smanagent.ide.theme.ColorPalette
import javax.swing.text.MutableAttributeSet
import javax.swing.text.SimpleAttributeSet
import javax.swing.text.StyleConstants
import javax.swing.text.StyledDocument
import javax.swing.JTextPane
import java.awt.Color
import java.io.StringReader

/**
 * 富文本消息渲染器（支持 Markdown 和彩色输出）
 * <p>
 * 使用 JTextPane + HTMLEditorKit 实现 Markdown 渲染
 */
object StyledMessageRenderer {

    // 样式标记常量
    private const val RESET = "RESET"
    private const val PRIMARY = "PRIMARY"
    private const val SECONDARY = "SECONDARY"
    private const val MUTED = "MUTED"
    private const val SUCCESS = "SUCCESS"
    private const val ERROR = "ERROR"
    private const val WARNING = "WARNING"
    private const val INFO = "INFO"
    private const val TOOL = "TOOL"

    /**
     * 渲染 Part 到 JTextPane
     */
    fun renderToTextPane(part: PartData, textPane: JTextPane, colors: ColorPalette = ThemeColors.getCurrentColors()) {
        val doc = textPane.styledDocument

        when (part.type) {
            PartType.TEXT, PartType.REASONING -> {
                // TEXT 和 REASONING 使用 Markdown 渲染为 HTML
                val text = when (part.type) {
                    PartType.TEXT -> (part.data["text"] as? String) ?: ""
                    PartType.REASONING -> (part.data["text"] as? String) ?: ""
                    else -> ""
                }
                val html = MarkdownRenderer.markdownToHtml(text)
                val wrappedHtml = wrapHtml(html, part.type == PartType.REASONING)
                appendHtml(textPane, wrappedHtml)
            }
            PartType.USER -> {
                // 用户消息：使用 HTML 插入，保持与其他消息类型一致
                val text = part.data["text"] as? String ?: ""
                // 转义 HTML 特殊字符
                val escapedText = text.replace("&", "&amp;")
                    .replace("<", "&lt;")
                    .replace(">", "&gt;")
                val html = """
                    <div style="margin: 5px 0; text-align: left;">
                        <span style="color: ${toHexString(colors.warning)};">&gt;&gt;&gt; </span>
                        <span style="color: ${toHexString(colors.textPrimary)};">$escapedText</span>
                    </div>
                """.trimIndent()
                appendHtml(textPane, html)
            }
            else -> {
                // 其他类型：转换为 HTML
                val text = when (part.type) {
                    PartType.TOOL -> renderToolPart(part)
                    PartType.GOAL -> renderGoalPart(part)
                    PartType.PROGRESS -> renderProgressPart(part)
                    PartType.TODO -> renderTodoPart(part)
                    else -> ""
                }
                val html = convertStyledTextToHtml(text, colors)
                appendHtml(textPane, html)
            }
        }
    }

    /**
     * 将样式标记文本转换为 HTML
     */
    private fun convertStyledTextToHtml(text: String, colors: ColorPalette): String {
        var result = text

        // 替换样式标记为 HTML（只使用颜色，不使用粗体）
        result = result.replace(Regex("""\[WARNING\](.*?)\[RESET\]""")) { match ->
            "<span style=\"color: ${toHexString(colors.warning)};\">${match.groupValues[1]}</span>"
        }
        result = result.replace(Regex("""\[PRIMARY\](.*?)\[RESET\]""")) { match ->
            "<span style=\"color: ${toHexString(colors.textPrimary)};\">${match.groupValues[1]}</span>"
        }
        result = result.replace(Regex("""\[SECONDARY\](.*?)\[RESET\]""")) { match ->
            "<span style=\"color: ${toHexString(colors.textSecondary)};\">${match.groupValues[1]}</span>"
        }
        result = result.replace(Regex("""\[MUTED\](.*?)\[RESET\]""")) { match ->
            "<span style=\"color: ${toHexString(colors.textMuted)};\">${match.groupValues[1]}</span>"
        }
        result = result.replace(Regex("""\[SUCCESS\](.*?)\[RESET\]""")) { match ->
            "<span style=\"color: ${toHexString(colors.success)};\">${match.groupValues[1]}</span>"
        }
        result = result.replace(Regex("""\[ERROR\](.*?)\[RESET\]""")) { match ->
            "<span style=\"color: ${toHexString(colors.error)};\">${match.groupValues[1]}</span>"
        }
        result = result.replace(Regex("""\[INFO\](.*?)\[RESET\]""")) { match ->
            "<span style=\"color: ${toHexString(colors.info)};\">${match.groupValues[1]}</span>"
        }
        result = result.replace(Regex("""\[TOOL\](.*?)\[RESET\]""")) { match ->
            "<span style=\"color: ${toHexString(colors.codeFunction)};\">${match.groupValues[1]}</span>"
        }

        // 处理换行
        result = result.replace("\n", "<br>")

        return result
    }

    /**
     * 将 HTML 追加到 JTextPane
     */
    private fun appendHtml(textPane: JTextPane, html: String) {
        val doc = textPane.styledDocument
        val kit = textPane.editorKit as? javax.swing.text.html.HTMLEditorKit ?: return

        try {
            val currentLength = doc.length
            kit.read(StringReader(html), doc, currentLength)
        } catch (e: Exception) {
            // 如果 HTML 解析失败，回退到纯文本
            try {
                val currentLength = doc.length
                doc.insertString(currentLength, html, javax.swing.text.SimpleAttributeSet())
            } catch (ex: Exception) {
                // 忽略错误
            }
        }

        // 滚动到底部
        textPane.caretPosition = doc.length
    }

    /**
     * 包装 HTML 内容
     */
    private fun wrapHtml(content: String, isReasoning: Boolean = false): String {
        val style = if (isReasoning) {
            "color: #61AFEF; font-style: italic; margin: 5px 0; text-align: left;"
        } else {
            "margin: 5px 0; text-align: left;"
        }
        return "<div style=\"$style\">$content</div>"
    }

    /**
     * 将 Color 转换为十六进制字符串
     */
    private fun toHexString(color: Color): String {
        return "#${color.red.toString(16).padStart(2, '0')}${color.green.toString(16).padStart(2, '0')}${color.blue.toString(16).padStart(2, '0')}"
    }

    // ========== Part 渲染方法（返回样式标记文本） ==========

    private fun renderToolPart(part: PartData): String {
        val toolName = part.data["toolName"] as? String ?: "unknown"
        val state = part.data["state"] as? String ?: "PENDING"

        return when (state) {
            "PENDING" -> {
                "▶ 调用工具: [$TOOL]$toolName[RESET]\n"
            }
            "RUNNING" -> {
                "⏳ 执行中: [$TOOL]$toolName[RESET]\n"
            }
            "COMPLETED" -> {
                val title = part.data["title"] as? String ?: ""
                val content = part.data["content"] as? String ?: ""
                val preview = if (content.length > 100) content.substring(0, 100) + "..." else content

                val sb = StringBuilder()
                sb.append("✓ 工具完成: [$TOOL]$toolName[RESET]\n")
                if (title.isNotEmpty()) {
                    sb.append("  └─ $title\n")
                }
                if (preview.isNotEmpty()) {
                    sb.append("  └─ $preview\n")
                }
                sb.toString()
            }
            "ERROR" -> {
                val error = part.data["error"] as? String ?: ""
                "✗ 工具失败: [$ERROR]$toolName[RESET]\n  └─ 原因: $error\n"
            }
            else -> {
                "▶ 调用工具: [$TOOL]$toolName[RESET] ($state)\n"
            }
        }
    }

    private fun renderGoalPart(part: PartData): String {
        val title = part.data["title"] as? String ?: ""
        val description = part.data["description"] as? String ?: ""
        val status = part.data["status"] as? String ?: "PENDING"

        val icon = when (status) {
            "PENDING" -> "📋"
            "IN_PROGRESS" -> "🔄"
            "COMPLETED" -> "✅"
            "CANCELLED" -> "❌"
            else -> "📋"
        }

        val sb = StringBuilder()
        sb.append("\n")
        sb.append("$icon 目标: [$PRIMARY]$title[RESET]\n")
        if (description.isNotEmpty()) {
            sb.append("  描述: [$SECONDARY]$description[RESET]\n")
        }
        return sb.toString()
    }

    private fun renderProgressPart(part: PartData): String {
        val currentStep = part.data["currentStep"] as? Int ?: 0
        val totalSteps = part.data["totalSteps"] as? Int ?: 0
        val stepName = part.data["stepName"] as? String ?: ""

        return if (totalSteps > 0) {
            "[$INFO][$currentStep/$totalSteps][RESET] $stepName\n"
        } else {
            "⏳ [$WARNING]$stepName[RESET]\n"
        }
    }

    private fun renderTodoPart(part: PartData): String {
        val items = part.data["items"] as? List<*> ?: emptyList<Any>()

        val sb = StringBuilder()
        sb.append("\n")
        sb.append("📝 任务列表\n")

        for (item in items) {
            @Suppress("UNCHECKED_CAST")
            val map = item as? Map<String, Any> ?: continue
            val content = map["content"] as? String ?: ""
            val status = map["status"] as? String ?: "PENDING"

            val icon = when (status) {
                "PENDING" -> "⏳"
                "IN_PROGRESS" -> "▶"
                "COMPLETED" -> "✓"
                else -> "⏳"
            }

            when (status) {
                "PENDING" -> sb.append("$icon [$MUTED]$content[RESET]\n")
                "IN_PROGRESS" -> sb.append("$icon [$INFO]$content[RESET]\n")
                "COMPLETED" -> sb.append("$icon [$SUCCESS]$content[RESET]\n")
                else -> sb.append("$icon [$MUTED]$content[RESET]\n")
            }
        }

        return sb.toString()
    }

    // ========== 以下方法保持向后兼容 ==========

    /**
     * 渲染用户消息（旧接口，保持兼容）
     */
    fun renderUserMessageToDocument(text: String, doc: StyledDocument, colors: ColorPalette = ThemeColors.getCurrentColors()) {
        // 上下空行
        doc.insertString(doc.length, "\n", createAttributes(colors.textPrimary))

        // >>> 用户输入（加粗黄色）
        val prefix = ">>> "
        doc.insertString(doc.length, prefix, createAttributes(colors.warning, bold = true))
        doc.insertString(doc.length, "$text\n", createAttributes(colors.textPrimary))

        // 下方空行
        doc.insertString(doc.length, "\n", createAttributes(colors.textPrimary))
    }

    /**
     * 渲染系统消息（旧接口，保持兼容）
     */
    fun renderSystemMessageToDocument(text: String, doc: StyledDocument, colors: ColorPalette = ThemeColors.getCurrentColors()) {
        val content = "\n[SYSTEM] $text\n"
        val attr = createAttributes(colors.textMuted, italic = true)
        doc.insertString(doc.length, content, attr)
    }

    private fun createAttributes(
        color: Color,
        bold: Boolean = false,
        italic: Boolean = false
    ): MutableAttributeSet {
        val attr = SimpleAttributeSet()
        StyleConstants.setForeground(attr, color)
        StyleConstants.setBold(attr, bold)
        StyleConstants.setItalic(attr, italic)
        return attr
    }
}
