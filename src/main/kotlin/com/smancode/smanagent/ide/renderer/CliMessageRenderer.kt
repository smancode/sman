package com.smancode.smanagent.ide.renderer

import com.smancode.smanagent.ide.model.PartData
import com.smancode.smanagent.ide.model.GraphModels.PartType
import com.smancode.smanagent.ide.theme.ThemeColors
import com.smancode.smanagent.ide.theme.ColorPalette

/**
 * CLI 消息渲染器（专业配色版）
 * <p>
 * 设计原则：
 * - 使用颜色编码实现清晰的视觉层次
 * - 支持 Light/Dark 主题自动适配
 * - 高可读性的代码显示
 * - 符合 WCAG AAA 可访问性标准
 */
object CliMessageRenderer {

    /**
     * 渲染 Part 为 CLI 风格文本（带颜色）
     */
    fun render(part: PartData, colors: ColorPalette = ThemeColors.getCurrentColors()): String {
        return when (part.type) {
            PartType.TEXT -> renderTextPart(part, colors)
            PartType.USER -> renderUserPart(part, colors)
            PartType.TOOL -> renderToolPart(part, colors)
            PartType.REASONING -> renderReasoningPart(part, colors)
            PartType.GOAL -> renderGoalPart(part, colors)
            PartType.PROGRESS -> renderProgressPart(part, colors)
            PartType.TODO -> renderTodoPart(part, colors)
        }
    }

    /**
     * 渲染用户消息
     */
    fun renderUserMessage(text: String, colors: ColorPalette = ThemeColors.getCurrentColors()): String {
        val divider = "═".repeat(50)
        return """
            |${colorize(divider, colors.textMuted)}
            |${colorize("You:", colors.info)} ${colorize(text, colors.textPrimary)}
            |${colorize(divider, colors.textMuted)}
        """.trimMargin().replace("\n", "")
    }

    /**
     * 渲染系统消息
     */
    fun renderSystemMessage(text: String, colors: ColorPalette = ThemeColors.getCurrentColors()): String {
        return "\n${colorize("[SYSTEM] $text", colors.textMuted)}\n"
    }

    /**
     * 渲染用户消息 Part
     */
    private fun renderUserPart(part: PartData, colors: ColorPalette): String {
        val text = part.data["text"] as? String ?: ""
        return "\n${colorize(">>> $text", colors.warning)}\n"
    }

    /**
     * 渲染文本 Part
     */
    private fun renderTextPart(part: PartData, colors: ColorPalette): String {
        val text = part.data["text"] as? String ?: ""
        return "\n${colorize(text, colors.textPrimary)}"
    }

    /**
     * 渲染工具 Part（带状态颜色）
     */
    private fun renderToolPart(part: PartData, colors: ColorPalette): String {
        val toolName = part.data["toolName"] as? String ?: "unknown"
        val state = part.data["state"] as? String ?: "PENDING"

        return when (state) {
            "PENDING" -> {
                "▶ ${colorize("调用工具:", colors.textSecondary)} ${colorize(toolName, colors.codeFunction)}\n"
            }
            "RUNNING" -> {
                "⏳ ${colorize("执行中:", colors.textSecondary)} ${colorize(toolName, colors.codeFunction)}\n"
            }
            "COMPLETED" -> {
                val title = part.data["title"] as? String ?: ""
                val content = part.data["content"] as? String ?: ""

                val sb = StringBuilder()
                sb.append("✓ ${colorize("工具完成:", colors.textSecondary)} ${colorize(toolName, colors.codeFunction)}\n")
                if (title.isNotEmpty()) {
                    sb.append("  └─ ${colorize(title, colors.textSecondary)}\n")
                }
                if (content.isNotEmpty()) {
                    val preview = if (content.length > 100) content.substring(0, 100) + "..." else content
                    sb.append("  └─ ${colorize(preview, colors.textMuted)}\n")
                }
                sb.toString()
            }
            "ERROR" -> {
                val error = part.data["error"] as? String ?: ""
                "✗ ${colorize("工具失败:", colors.error)} ${colorize(toolName, colors.codeFunction)}\n" +
                "  └─ ${colorize("原因: ", colors.textMuted)}${colorize(error, colors.textSecondary)}\n"
            }
            else -> {
                "▶ ${colorize("调用工具:", colors.textSecondary)} ${colorize(toolName, colors.codeFunction)} ($state)\n"
            }
        }
    }

    /**
     * 渲染推理 Part
     */
    private fun renderReasoningPart(part: PartData, colors: ColorPalette): String {
        val text = part.data["text"] as? String
        return if (!text.isNullOrBlank()) {
            "${colorize("🤔", colors.info)} ${colorize(text, colors.textSecondary)}\n"
        } else {
            ""
        }
    }

    /**
     * 渲染目标 Part
     */
    private fun renderGoalPart(part: PartData, colors: ColorPalette): String {
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
        sb.append("$icon ").append(colorize("目标: ", colors.textMuted)).append(colorize(title, colors.textPrimary)).append("\n")

        if (description.isNotEmpty()) {
            sb.append("  ").append(colorize("描述: ", colors.textMuted)).append(colorize(description, colors.textSecondary)).append("\n")
        }

        return sb.toString()
    }

    /**
     * 渲染进度 Part
     */
    private fun renderProgressPart(part: PartData, colors: ColorPalette): String {
        val currentStep = part.data["currentStep"] as? Int ?: 0
        val totalSteps = part.data["totalSteps"] as? Int ?: 0
        val stepName = part.data["stepName"] as? String ?: ""

        return if (totalSteps > 0) {
            "[${colorize("$currentStep/$totalSteps", colors.info)}] ${colorize(stepName, colors.textPrimary)}\n"
        } else {
            "${colorize("⏳", colors.warning)} ${colorize(stepName, colors.textPrimary)}\n"
        }
    }

    /**
     * 渲染 Todo Part
     */
    private fun renderTodoPart(part: PartData, colors: ColorPalette): String {
        val items = part.data["items"] as? List<*> ?: emptyList<Any>()

        val sb = StringBuilder()
        sb.append("\n")
        sb.append("📝 ").append(colorize("任务列表", colors.textPrimary)).append("\n")

        for (item in items) {
            @Suppress("UNCHECKED_CAST")
            val map = item as? Map<String, Any> ?: continue
            val content = map["content"] as? String ?: ""
            val status = map["status"] as? String ?: "PENDING"

            val icon = when (status) {
                "PENDING" ->"⏳"
                "IN_PROGRESS" ->"▶"
                "COMPLETED" ->"✓"
                else ->"⏳"
            }

            val iconColor = when (status) {
                "PENDING" -> colors.textMuted
                "IN_PROGRESS" -> colors.info
                "COMPLETED" -> colors.success
                else -> colors.textMuted
            }

            sb.append("$icon ").append(colorize(content, iconColor)).append("\n")
        }

        return sb.toString()
    }

    /**
     * 渲染完整消息（包含所有 Part）
     */
    fun renderMessage(parts: List<PartData>, colors: ColorPalette = ThemeColors.getCurrentColors()): String {
        val sb = StringBuilder()

        for (part in parts) {
            sb.append(render(part, colors))
            sb.append("\n")
        }

        return sb.toString()
    }

    /**
     * 渲染分隔线
     */
    fun renderSeparator(colors: ColorPalette = ThemeColors.getCurrentColors()): String {
        return colorize("─".repeat(60), colors.textMuted) + "\n"
    }

    /**
     * 渲染代码块（带语法高亮）
     */
    fun renderCodeBlock(code: String, language: String, filePath: String = "",
                         colors: ColorPalette = ThemeColors.getCurrentColors()): String {
        val codeStyle = com.smancode.smanagent.ide.theme.CodeBlockStyle.forTheme(ThemeColors.isDarkTheme())

        val sb = StringBuilder()

        // 标题栏
        if (filePath.isNotEmpty()) {
            sb.append(colorize("┌─ $filePath ─┐", colors.codeComment)).append("\n")
        }

        // 代码内容（简单高亮）
        val highlighted = highlightSyntax(code, language, colors)
        sb.append(highlighted)

        // 底部栏
        if (filePath.isNotEmpty()) {
            val width = filePath.length + 4
            sb.append(colorize("└${"─".repeat(width)}┘", colors.codeComment)).append("\n")
        }

        return sb.toString()
    }

    /**
     * 简单语法高亮
     */
    private fun highlightSyntax(code: String, language: String, colors: ColorPalette): String {
        val lines = code.lines()

        return lines.joinToString("\n") { line ->
            when (language.lowercase()) {
                "java", "kotlin" -> highlightJavaKotlin(line, colors)
                "python" -> highlightPython(line, colors)
                "javascript", "typescript", "js", "ts" -> highlightJavaScript(line, colors)
                "json", "xml" -> highlightMarkup(line, colors)
                else -> colorize(line, colors.textPrimary)
            }
        }
    }

    private fun highlightJavaKotlin(line: String, colors: ColorPalette): String {
        // 简化版：直接返回原文，实际语法高亮由 StyledMessageRenderer 处理
        return line
    }

    private fun highlightPython(line: String, colors: ColorPalette): String {
        return line
    }

    private fun highlightJavaScript(line: String, colors: ColorPalette): String {
        return line
    }

    private fun highlightMarkup(line: String, colors: ColorPalette): String {
        return line
    }

    /**
     * 给字符串上色（简化版，直接返回文本）
     * 注意：JTextArea 不支持 ANSI 颜色码，所以这个渲染器主要用于 CLI 风格输出
     */
    private fun colorize(text: String, color: java.awt.Color): String {
        // JTextArea 不支持 ANSI 转义码，所以直接返回文本
        // 实际的颜色应该由 StyledMessageRenderer 处理
        return text
    }
}
