package com.smancode.smanagent.ide.renderer

import com.smancode.smanagent.ide.model.GraphModels.PartData
import com.smancode.smanagent.ide.model.GraphModels.PartType

/**
 * CLI 风格消息渲染器
 * <p>
 * 将 Part 转换为 CLI 风格的文本输出。
 */
object CliMessageRenderer {

    /**
     * 渲染 Part 为 CLI 风格文本
     */
    fun renderPart(part: PartData): String {
        return when (part.type) {
            PartType.TEXT -> renderTextPart(part)
            PartType.TOOL -> renderToolPart(part)
            PartType.REASONING -> renderReasoningPart(part)
            PartType.GOAL -> renderGoalPart(part)
            PartType.PROGRESS -> renderProgressPart(part)
            PartType.TODO -> renderTodoPart(part)
        }
    }

    /**
     * 渲染文本 Part
     */
    private fun renderTextPart(part: PartData): String {
        val text = part.data["text"] as? String ?: ""
        return text
    }

    /**
     * 渲染工具 Part
     */
    private fun renderToolPart(part: PartData): String {
        val toolName = part.data["toolName"] as? String ?: "unknown"
        val state = part.data["state"] as? String ?: "PendingState"

        val sb = StringBuilder()

        when {
            state.contains("Pending") -> {
                sb.append("▶ 调用工具: $toolName\n")
            }
            state.contains("Running") -> {
                sb.append("⏳ 执行中: $toolName\n")
            }
            state.contains("Completed") -> {
                val title = part.data["title"] as? String ?: ""
                val content = part.data["content"] as? String ?: ""
                sb.append("✓ 工具完成: $toolName\n")
                if (title.isNotEmpty()) {
                    sb.append("  └─ $title\n")
                }
                if (content.isNotEmpty()) {
                    sb.append("  └─ $content\n")
                }
            }
            state.contains("Error") -> {
                val error = part.data["error"] as? String ?: ""
                sb.append("✗ 工具失败: $toolName\n")
                if (error.isNotEmpty()) {
                    sb.append("  └─ $error\n")
                }
            }
        }

        return sb.toString()
    }

    /**
     * 渲染推理 Part
     */
    private fun renderReasoningPart(part: PartData): String {
        val text = part.data["text"] as? String ?: ""
        return "🤔 $text\n"
    }

    /**
     * 渲染目标 Part
     */
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
        sb.append("═".repeat(60)).append("\n")
        sb.append("$icon 目标: $title\n")

        if (description.isNotEmpty()) {
            sb.append("  描述: $description\n")
        }

        sb.append("═".repeat(60)).append("\n")

        return sb.toString()
    }

    /**
     * 渲染进度 Part
     */
    private fun renderProgressPart(part: PartData): String {
        val currentStep = part.data["currentStep"] as? Int ?: 0
        val totalSteps = part.data["totalSteps"] as? Int ?: 0
        val stepName = part.data["stepName"] as? String ?: ""

        return if (totalSteps > 0) {
            "[$currentStep/$totalSteps] $stepName\n"
        } else {
            "⏳ $stepName\n"
        }
    }

    /**
     * 渲染 Todo Part
     */
    private fun renderTodoPart(part: PartData): String {
        val items = part.data["items"] as? List<*> ?: emptyList<Any>()

        val sb = StringBuilder()
        sb.append("═".repeat(60)).append("\n")
        sb.append("📝 任务列表\n")
        sb.append("═".repeat(60)).append("\n")

        for (item in items) {
            @Suppress("UNCHECKED_CAST")
            val map = item as? Map<String, Any> ?: continue
            val content = map["content"] as? String ?: ""
            val status = map["status"] as? String ?: "PENDING"

            val icon = when (status) {
                "PENDING" → "⏳"
                "IN_PROGRESS" → "▶"
                "COMPLETED" → "✓"
                else → "⏳"
            }

            sb.append("$icon $content\n")
        }

        sb.append("═".repeat(60)).append("\n")

        return sb.toString()
    }

    /**
     * 渲染完整消息（包含所有 Part）
     */
    fun renderMessage(parts: List<PartData>): String {
        val sb = StringBuilder()

        for (part in parts) {
            sb.append(renderPart(part))
            sb.append("\n")
        }

        return sb.toString()
    }

    /**
     * 渲染分隔线
     */
    fun renderSeparator(): String {
        return "━".repeat(60) + "\n"
    }
}
