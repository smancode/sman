package com.smancode.sman.infra.ide

import com.intellij.openapi.project.Project
import com.smancode.sman.ide.model.GraphModels
import com.smancode.sman.ide.model.PartData
import com.smancode.sman.ide.renderer.MarkdownRenderer
import com.smancode.sman.ide.ui.LinkNavigationHandler
import com.smancode.sman.ide.renderer.StyledMessageRenderer
import com.smancode.sman.ide.theme.ThemeColors
import org.slf4j.Logger
import java.awt.Color
import java.io.StringReader
import java.time.Instant
import java.util.UUID
import javax.swing.JTextPane
import javax.swing.SwingUtilities
import javax.swing.text.html.HTMLEditorKit

/**
 * 聊天输出处理器
 *
 * 负责消息渲染功能：
 * - Part 转换与渲染
 * - 主题应用
 * - 滚动控制
 * - 系统消息追加
 */
class ChatOutput(
    private val project: Project,
    private val logger: Logger,
    private val outputArea: JTextPane
) {
    // 回调接口 - 用于获取当前会话 ID 和保存消息
    interface Callback {
        fun getCurrentSessionId(): String?
        fun getStorageService(): com.smancode.sman.ide.service.StorageService
    }

    private var callback: Callback? = null

    fun setCallback(callback: Callback) {
        this.callback = callback
    }

    /**
     * 应用主题配色
     */
    fun applyTheme(scrollPane: javax.swing.JScrollPane, taskProgressBar: com.smancode.sman.ide.components.TaskProgressBar) {
        val colors = ThemeColors.getCurrentColors()
        val editorFont = com.smancode.sman.ide.ui.FontManager.getEditorFont()

        outputArea.editorKit = MarkdownRenderer.createStyledEditorKit(colors)
        outputArea.font = editorFont
        outputArea.background = colors.background
        outputArea.foreground = colors.textPrimary

        // 强制设置 JTextPane 的默认字体到编辑器字体
        outputArea.putClientProperty("font", editorFont)

        scrollPane.verticalScrollBar.apply {
            background = colors.background
            foreground = colors.textMuted
        }
    }

    /**
     * 重新应用主题（当 IDE 主题切换时调用）
     */
    fun refreshTheme(taskProgressBar: com.smancode.sman.ide.components.TaskProgressBar) {
        // 重新应用输出区域的背景色
        val colors = ThemeColors.getCurrentColors()
        outputArea.background = colors.background
        outputArea.foreground = colors.textPrimary
        outputArea.repaint()
        taskProgressBar.applyTheme()
    }

    /**
     * 追加 Part 到 UI
     */
    fun appendPartToUI(
        part: PartData,
        taskProgressBar: com.smancode.sman.ide.components.TaskProgressBar
    ) {
        logger.info("=== appendPartToUI === type={}, data={}", part.type, part.data.keys)

        // TodoPart 特殊处理：更新任务栏而非插入消息流
        if (part.type == GraphModels.PartType.TODO) {
            taskProgressBar.updateTasks(part)
            return
        }

        // 其他 Part 使用 Markdown 渲染（传入 project 用于代码链接处理）
        StyledMessageRenderer.renderToTextPane(part, outputArea, project)
    }

    /**
     * 追加系统消息
     * @param text 消息文本
     * @param isProcessing 是否是处理中状态（灰色）
     * @param saveToHistory 是否保存到历史记录（默认 true）
     */
    fun appendSystemMessage(text: String, isProcessing: Boolean = false, saveToHistory: Boolean = true) {
        val colors = ThemeColors.getCurrentColors()

        // 将文本转换为 HTML 格式（保留换行）
        var htmlText = text.replace("\n", "<br>")

        // 处理特殊颜色标记（与 StyledMessageRenderer 保持一致）
        // 替换 "Commit:" 为蓝色
        htmlText = htmlText.replace("Commit:", "<span style='color: ${toHexString(colors.codeFunction)};'>Commit:</span>")
        // 替换 "文件变更:" 为黄色
        htmlText = htmlText.replace("文件变更:", "<span style='color: ${toHexString(colors.warning)};'>文件变更:</span>")

        val colorHex = if (isProcessing) {
            String.format("#%06X", colors.textMuted.rgb and 0xFFFFFF)
        } else {
            String.format("#%06X", colors.textPrimary.rgb and 0xFFFFFF)
        }

        // 使用与 StyledMessageRenderer 相同的方式追加 HTML，避免覆盖已有样式
        // 使用编辑器字体设置
        val fontFamily = com.smancode.sman.ide.ui.FontManager.getEditorFontFamily()
        val fontSize = com.smancode.sman.ide.ui.FontManager.getEditorFontSize()

        val html = if (outputArea.text.contains("<body>")) {
            // 已有 HTML 内容，在 </body> 前插入
            val currentHtml = outputArea.text
            currentHtml.replace(
                "</body>",
                "<div style='color:$colorHex; font-family: \"$fontFamily\", monospace; font-size: ${fontSize}px; margin: 4px 0;'>$htmlText</div></body>"
            )
        } else {
            // 空 HTML，初始化
            "<html><body><div style='color:$colorHex; font-family: \"$fontFamily\", monospace; font-size: ${fontSize}px; margin: 4px 0;'>$htmlText</div></body></html>"
        }

        // 使用 HTMLEditorKit 的方式来追加内容，避免覆盖已有样式
        val kit = outputArea.editorKit as? HTMLEditorKit
        val doc = outputArea.styledDocument

        if (kit != null && outputArea.text.contains("<body>")) {
            // 使用 HTMLEditorKit.read() 追加内容（与 StyledMessageRenderer 一致）
            try {
                val reader = StringReader(html)
                // 先清空，然后重新写入整个文档
                doc.remove(0, doc.length)
                kit.read(reader, doc, 0)
            } catch (e: Exception) {
                logger.error("追加系统消息失败", e)
            }
        } else {
            // 回退到直接设置
            outputArea.text = html
        }

        // 滚动到底部
        outputArea.caretPosition = outputArea.document.length

        // 保存到历史记录
        if (saveToHistory) {
            val sessionId = callback?.getCurrentSessionId()
            if (sessionId != null) {
                val now = Instant.now()
                // 如果是处理中消息，添加特殊标记，用于历史加载时识别
                val savedText = if (isProcessing) {
                    "[PROCESSING]$text"
                } else {
                    text
                }
                val systemPart = GraphModels.TextPartData(
                    id = UUID.randomUUID().toString(),
                    messageId = UUID.randomUUID().toString(),
                    sessionId = sessionId,
                    createdTime = now,
                    updatedTime = now,
                    data = mapOf("text" to savedText)
                )
                callback?.getStorageService()?.addPartToSession(sessionId, systemPart)
            }
        }
    }

    /**
     * 将 Color 转换为十六进制字符串
     */
    private fun toHexString(color: Color): String {
        return "#${color.red.toString(16).padStart(2, '0')}${color.green.toString(16).padStart(2, '0')}${color.blue.toString(16).padStart(2, '0')}"
    }

    /**
     * 将后端 Part 转换为 UI PartData
     */
    fun convertPartToData(part: com.smancode.sman.model.part.Part): PartData {
        val commonData = CommonPartData(
            id = part.id ?: UUID.randomUUID().toString(),
            messageId = part.messageId ?: UUID.randomUUID().toString(),
            sessionId = part.sessionId ?: "",
            createdTime = part.createdTime,
            updatedTime = part.updatedTime
        )

        return when (part.type) {
            com.smancode.sman.model.part.PartType.TEXT -> {
                val textPart = part as com.smancode.sman.model.part.TextPart
                GraphModels.TextPartData(
                    id = commonData.id,
                    messageId = commonData.messageId,
                    sessionId = commonData.sessionId,
                    createdTime = commonData.createdTime,
                    updatedTime = commonData.updatedTime,
                    data = mapOf("text" to (textPart.text ?: ""))
                )
            }
            com.smancode.sman.model.part.PartType.TOOL -> {
                val toolPart = part as com.smancode.sman.model.part.ToolPart
                GraphModels.ToolPartData(
                    id = commonData.id,
                    messageId = commonData.messageId,
                    sessionId = commonData.sessionId,
                    createdTime = commonData.createdTime,
                    updatedTime = commonData.updatedTime,
                    data = buildToolPartData(toolPart)
                )
            }
            com.smancode.sman.model.part.PartType.REASONING -> {
                val reasoningPart = part as com.smancode.sman.model.part.ReasoningPart
                GraphModels.ReasoningPartData(
                    id = commonData.id,
                    messageId = commonData.messageId,
                    sessionId = commonData.sessionId,
                    createdTime = commonData.createdTime,
                    updatedTime = commonData.updatedTime,
                    data = mapOf("text" to (reasoningPart.text ?: ""))
                )
            }
            else -> {
                GraphModels.TextPartData(
                    id = commonData.id,
                    messageId = commonData.messageId,
                    sessionId = commonData.sessionId,
                    createdTime = commonData.createdTime,
                    updatedTime = commonData.updatedTime,
                    data = mapOf("text" to "[${part.type}]")
                )
            }
        }
    }

    /**
     * 构建 ToolPart 数据
     */
    private fun buildToolPartData(toolPart: com.smancode.sman.model.part.ToolPart): Map<String, Any> {
        val data = mutableMapOf<String, Any>()
        data["toolName"] = toolPart.toolName ?: ""
        data["state"] = toolPart.state?.name ?: "PENDING"
        toolPart.parameters?.let { data["parameters"] = it }
        toolPart.result?.error?.let { data["error"] = it }
        toolPart.result?.displayTitle?.let { data["title"] = it }
        toolPart.result?.displayContent?.let { data["content"] = it }
        return data
    }

    /**
     * 通用 Part 数据
     */
    private data class CommonPartData(
        val id: String,
        val messageId: String,
        val sessionId: String,
        val createdTime: Instant,
        val updatedTime: Instant
    )

    /**
     * 设置代码链接导航功能
     */
    fun setupLinkNavigation(linkNavigationHandler: LinkNavigationHandler) {
        outputArea.addHyperlinkListener(linkNavigationHandler.hyperlinkListener)
        outputArea.addMouseListener(linkNavigationHandler.mouseClickListener)
        outputArea.addMouseMotionListener(linkNavigationHandler.mouseMotionListener)
    }

    /**
     * 清空输出区域
     */
    fun clearOutput() {
        // 对于 HTML 文档，需要使用 setText 设置空 HTML
        outputArea.text = "<html><body></body></html>"
    }
}
