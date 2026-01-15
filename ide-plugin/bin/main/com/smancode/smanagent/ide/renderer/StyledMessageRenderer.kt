package com.smancode.smanagent.ide.renderer

import com.intellij.openapi.project.Project
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
    private const val CONCLUSION = "CONCLUSION"

    /**
     * 渲染 Part 到 JTextPane
     */
    fun renderToTextPane(part: PartData, textPane: JTextPane, project: Project, colors: ColorPalette = ThemeColors.getCurrentColors()) {
        when (part.type) {
            PartType.TEXT -> {
                val text = (part.data["text"] as? String) ?: ""

                // 检查是否是阶段性结论（以 "⏺ 阶段性结论" 或 "📊 阶段性结论" 开头）
                if (text.startsWith("⏺ 阶段性结论") || text.startsWith("📊 阶段性结论")) {
                    // 特殊渲染阶段性结论：只把"⏺ 阶段性结论 X:"部分染成紫色
                    val colonIndex = text.indexOf(":")
                    if (colonIndex > 0) {
                        val prefix = text.substring(0, colonIndex + 1)  // "⏺ 阶段性结论 X:"
                        val content = text.substring(colonIndex + 1)     // 后面的内容
                        // 转义 HTML 特殊字符
                        val escapedPrefix = prefix.replace("&", "&amp;")
                            .replace("<", "&lt;")
                            .replace(">", "&gt;")
                        val escapedContent = content.replace("&", "&amp;")
                            .replace("<", "&lt;")
                            .replace(">", "&gt;")
                        val html = """
                            <div style="margin: 5px 0; text-align: left;">
                                <span style="color: ${toHexString(colors.conclusion)};">$escapedPrefix</span><span style="color: ${toHexString(colors.textPrimary)};">$escapedContent</span>
                            </div>
                        """.trimIndent()
                        appendHtml(textPane, html)
                    } else {
                        // 没有 ":" 的情况，全部紫色
                        val html = """
                            <div style="margin: 5px 0; text-align: left;">
                                <span style="color: ${toHexString(colors.conclusion)};">$text</span>
                            </div>
                        """.trimIndent()
                        appendHtml(textPane, html)
                    }
                } else {
                    // 检查是否是工具摘要格式：toolName(params)\nline1\nline2
                    // 特征：第一行包含函数调用格式，即 xxx(yyy)
                    val lines = text.split("\n")
                    val firstLine = lines.firstOrNull() ?: ""

                    if (lines.size > 1 && firstLine.contains("(") && firstLine.contains(")")) {
                        // 这是工具摘要格式，前端负责渲染
                        val toolCallContent = firstLine  // toolName(params)
                        // 过滤掉空行、"null" 字符串和 "路径:" 前缀的行
                        val resultLines = lines.drop(1).filter { it.isNotBlank() && it != "null" && !it.trim().startsWith("路径:") }

                        // 提取工具名称（括号前的部分）
                        val toolName = toolCallContent.substringBefore("(")

                        // 转义 HTML 特殊字符
                        val escapedToolName = toolName.replace("&", "&amp;")
                            .replace("<", "&lt;")
                            .replace(">", "&gt;")
                        val escapedParams = toolCallContent.substringAfter("(")
                            .dropLast(1)  // 去掉结尾的 )
                            .replace("&", "&amp;")
                            .replace("<", "&lt;")
                            .replace(">", "&gt;")

                        // 计算对齐缩进：⏺(1字符) + 1个空格 = 2个字符
                        // 这样 └─ 会对齐到 toolName 的首字母（如 read_file 的 r）
                        val indentSize = 1 + 1  // ⏺ 占1个字符 + 1个空格
                        // 使用 &nbsp; 而不是空格，因为 HTML 会压缩连续空格
                        val indent = "&nbsp;".repeat(indentSize)

                        val html = StringBuilder()
                        html.append("<div style=\"margin: 0; text-align: left;\">")
                        // 工具调用行：⏺ toolName 黄色，(params) 灰色
                        html.append("<span style=\"color: ${toHexString(colors.warning)};\">⏺ $escapedToolName</span>")
                        html.append("<span style=\"color: ${toHexString(colors.textPrimary)};\">($escapedParams)</span>")
                        // 结果行（最多显示3行，超出显示省略号）
                        val displayLines = resultLines.take(3)
                        displayLines.forEach { line ->
                            // 先转义 HTML 特殊字符
                            val escapedLine = line.replace("&", "&amp;")
                                .replace("<", "&lt;")
                                .replace(">", "&gt;")
                            // 使用 CodeLinkProcessor 处理代码链接（支持点击跳转）
                            val processedLine = CodeLinkProcessor.processCodeLinks(escapedLine, project)
                            html.append("<br><span style=\"color: ${toHexString(colors.textPrimary)};\">$indent└─ $processedLine</span>")
                        }
                        // 如果有更多行，显示省略号
                        if (resultLines.size > 3) {
                            html.append("<br><span style=\"color: ${toHexString(colors.textPrimary)};\">$indent└─ ...</span>")
                        }
                        html.append("</div>")
                        appendHtml(textPane, html.toString())
                    } else {
                        // 普通 TEXT 使用 Markdown 渲染
                        // 检查是否包含 {"text": "...", "summary": "..."} 格式的 JSON，如果是则跳过不显示（避免重复）
                        // 这种格式通常是 LLM 返回的原始 JSON，内容已经在工具摘要中显示过了
                        val trimmedText = text.trim()
                        val hasJsonPattern = trimmedText.contains("{\"text\":") && trimmedText.contains("\"summary\"")

                        if (!hasJsonPattern) {
                            // 检查是否是处理中消息（以 [PROCESSING] 开头）
                            val isProcessing = text.startsWith("[PROCESSING]")
                            val actualText = if (isProcessing) {
                                text.substring("[PROCESSING]".length)
                            } else {
                                text
                            }

                            // 检查是否是 commit 结果（以 "Commit:" 开头）或处理中消息
                            val processedText = if (actualText.startsWith("Commit:")) {
                                // 将 "Commit:" 转换为蓝色，"文件变更:" 转换为黄色
                                var result = actualText
                                // 替换 "Commit:" 为蓝色
                                result = result.replace("Commit:", "<span style='color: ${toHexString(colors.codeFunction)};'>Commit:</span>")
                                // 替换 "文件变更:" 为黄色
                                result = result.replace("文件变更:", "<span style='color: ${toHexString(colors.warning)};'>文件变更:</span>")
                                result
                            } else {
                                actualText
                            }

                            var htmlContent = MarkdownRenderer.markdownToHtml(processedText)

                            // 后处理代码块 - 将 <pre><code>...</code></pre> 替换为自定义样式
                            // HTMLEditorKit 对 pre 标签的 CSS 支持很差，所以用 div + font-family 模拟
                            htmlContent = htmlContent.replace(Regex("""<pre>(.*?)</pre>""", RegexOption.DOT_MATCHES_ALL)) { matchResult ->
                                val codeContent = matchResult.groupValues[1]
                                    .replace("&lt;", "<")
                                    .replace("&gt;", ">")
                                    .replace("&amp;", "&")
                                // 使用 div 模拟 pre，但添加换行处理
                                """<div style="background-color: ${toHexString(colors.background)}; color: ${toHexString(colors.textPrimary)}; padding: 10px; border-radius: 5px; margin: 10px 0; font-family: 'JetBrains Mono', monospace; white-space: pre-wrap; word-wrap: break-word; overflow-wrap: break-word;">$codeContent</div>"""
                            }
                            // 处理行内代码 <code>...</code>（不在 pre 内的）
                            htmlContent = htmlContent.replace(Regex("""<code>(.*?)</code>""")) { matchResult ->
                                val codeContent = matchResult.groupValues[1]
                                """<span style="background-color: ${toHexString(colors.background)}; color: ${toHexString(colors.textPrimary)}; padding: 2px 4px; border-radius: 3px; font-family: 'JetBrains Mono', monospace;">$codeContent</span>"""
                            }

                            // 如果是处理中消息，包裹灰色样式
                            if (isProcessing) {
                                htmlContent = """
                                    <div style="margin: 5px 0; text-align: left; color: ${toHexString(colors.textMuted)};">
                                        $htmlContent
                                    </div>
                                """.trimIndent()
                            }

                            // 处理代码链接：自动识别并包装为可点击链接
                            htmlContent = CodeLinkProcessor.processCodeLinks(htmlContent, project)
                            val wrappedHtml = wrapHtml(htmlContent, false)
                            appendHtml(textPane, wrappedHtml)
                        }
                        // 如果包含 JSON 模式，直接跳过不显示
                    }
                }
            }
            PartType.REASONING -> {
                // REASONING 显示为 "> " + 实际内容
                val text = (part.data["text"] as? String) ?: "思考中"
                val html = """
                    <div style="margin: 5px 0; text-align: left;">
                        <span style="color: ${toHexString(colors.textSecondary)};">&gt; $text</span>
                    </div>
                """.trimIndent()
                appendHtml(textPane, html)
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
                val wrappedHtml = wrapHtml(html, false)
                appendHtml(textPane, wrappedHtml)
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
        result = result.replace(Regex("""\[CONCLUSION\](.*?)\[RESET\]""")) { match ->
            "<span style=\"color: ${toHexString(colors.conclusion)};\">${match.groupValues[1]}</span>"
        }

        // 处理换行
        result = result.replace("\n", "<br>")

        return result
    }

    /**
     * 将 HTML 追加到 JTextPane
     */
    private fun appendHtml(textPane: JTextPane, html: String) {
        // 确保容器已完成布局（解决初始化时宽度未确定的问题）
        textPane.size = textPane.parent?.size ?: textPane.size

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
            "color: #61AFEF; font-style: italic; margin: 5px 0; text-align: left; word-wrap: break-word; overflow-wrap: break-word; word-break: break-all;"
        } else {
            "margin: 5px 0; text-align: left; word-wrap: break-word; overflow-wrap: break-word; word-break: break-all;"
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
                // PENDING 状态不显示，等待 COMPLETED 状态再显示（避免重复）
                ""
            }
            "RUNNING" -> {
                // 不显示执行中状态，减少冗余
                ""
            }
            "COMPLETED" -> {
                val content = part.data["content"] as? String

                // 即使 content 为空，也显示工具调用信息（带参数）
                val params = part.data["parameters"] as? Map<*, *>
                val paramsStr = if (params != null && params.isNotEmpty()) {
                    params.entries.joinToString(", ") { (k, v) -> "$k=$v" }
                } else {
                    ""
                }

                val sb = StringBuilder()
                val toolName = part.data["toolName"] as? String ?: "unknown"
                sb.append("⏺ <b style=\"color: #E5C07B;\">$toolName</b>($paramsStr)\n")

                // 如果有内容，显示结果
                if (!content.isNullOrBlank()) {
                    val results = content.split("\n").filter { it.isNotBlank() && it != "null" }
                    val displayResults = if (results.size > 3) {
                        results.take(3) + listOf("...")
                    } else {
                        results
                    }

                    // 每行结果前面加 └─（与工具调用行对齐）
                    displayResults.forEach { result ->
                        sb.append("    └─ $result\n")
                    }
                }
                sb.toString()
            }
            "ERROR" -> {
                // ERROR 状态不显示，因为摘要 Part 已经包含了错误信息
                ""
            }
            else -> {
                val params = part.data["parameters"] as? Map<*, *>
                val paramsStr = if (params != null && params.isNotEmpty()) {
                    params.entries.joinToString(", ") { (k, v) -> "$k=$v" }
                } else {
                    ""
                }
                "⏺ <b style=\"color: #E5C07B;\">$toolName</b>($paramsStr)\n"
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
    fun renderSystemMessageToDocument(
        text: String,
        doc: StyledDocument,
        colors: ColorPalette = ThemeColors.getCurrentColors(),
        isProcessing: Boolean = false
    ) {
        val content = "\n$text\n"
        // 处理中状态使用更浅的灰色（不使用斜体）
        val textColor = if (isProcessing) colors.textMuted else colors.textPrimary
        val attr = createAttributes(textColor, italic = false)
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
