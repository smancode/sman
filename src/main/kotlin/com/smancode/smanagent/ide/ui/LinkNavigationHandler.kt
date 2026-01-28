package com.smancode.smanagent.ide.ui

import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFileManager
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.awt.Cursor
import java.awt.Point
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
import java.io.File
import java.net.URL
import javax.swing.JTextPane
import javax.swing.text.StyledDocument

/**
 * 链接导航处理器
 *
 * 负责处理聊天面板中的链接点击和导航功能
 */
class LinkNavigationHandler(
    private val project: Project,
    private val textPane: JTextPane
) {
    private val logger = LoggerFactory.getLogger(LinkNavigationHandler::class.java)

    companion object {
        private const val CURSOR_DEBOUNCE_MS = 16L // 约 60FPS
        private const val TEXT_CONTEXT_RADIUS = 20 // 鼠标周围文本半径
    }

    /**
     * 超链接监听器
     */
    val hyperlinkListener = javax.swing.event.HyperlinkListener { e ->
        if (e.eventType == javax.swing.event.HyperlinkEvent.EventType.ACTIVATED) {
            val href = extractHref(e)
            if (href != null) {
                handleHyperlinkClick(href)
            } else {
                logger.error("无法获取链接: url={}, desc={}, element={}",
                    e.url, e.description, e.sourceElement)
            }
        }
    }

    /**
     * 鼠标点击监听器
     */
    val mouseClickListener = object : MouseAdapter() {
        override fun mouseClicked(e: MouseEvent) {
            if (e.button == MouseEvent.BUTTON1) {
                val point = e.point
                val pos = textPane.getUI().viewToModel(textPane, point)
                if (pos >= 0) {
                    val linkUrl = extractLinkAtPosition(pos)
                    if (linkUrl != null) {
                        logger.info("MouseListener 检测到链接点击: pos={}, url={}", pos, linkUrl)
                        handleHyperlinkClick(linkUrl)
                    }
                }
            }
        }
    }

    /**
     * 鼠标移动监听器（光标效果）
     */
    val mouseMotionListener = object : MouseMotionAdapter() {
        private var lastUpdateTime = 0L

        override fun mouseMoved(e: MouseEvent) {
            val now = System.currentTimeMillis()
            if (now - lastUpdateTime < CURSOR_DEBOUNCE_MS) return

            lastUpdateTime = now
            updateCursorForPosition(e.point)
        }
    }

    /**
     * 从 HyperlinkEvent 提取 href
     */
    private fun extractHref(e: javax.swing.event.HyperlinkEvent): String? {
        return when {
            e.description != null -> e.description.toString()
            e.url != null -> e.url.toExternalForm()
            else -> null
        }
    }

    /**
     * 处理链接点击
     */
    private fun handleHyperlinkClick(href: String) {
        try {
            when {
                href.startsWith("psi_location://") -> {
                    val location = href.removePrefix("psi_location://")
                    logger.info("PSI 导航: location={}", location)
                    val success = com.smancode.smanagent.ide.core.PsiNavigationHelper.navigateToLocation(project, location)
                    logger.info("PSI 导航结果: success={}", success)
                }
                href.startsWith("file://") -> {
                    navigateToFile(URL(href))
                }
                href.startsWith("http://") || href.startsWith("https://") -> {
                    com.intellij.ide.BrowserUtil.browse(URL(href))
                }
                else -> {
                    logger.warn("不支持的协议: {}", href.take(50))
                }
            }
        } catch (ex: Exception) {
            logger.error("处理链接失败: href={}", href, ex)
        }
    }

    /**
     * 从指定位置提取链接 URL
     */
    private fun extractLinkAtPosition(pos: Int): String? {
        return try {
            val doc = textPane.styledDocument
            val text = doc.getText(0, doc.length)

            // 查找包含 pos 的 <a href="..."> 标签
            var searchStart = 0
            while (searchStart < text.length) {
                val tagStart = text.indexOf("<a", searchStart)
                if (tagStart == -1 || tagStart > pos) break

                val hrefStart = text.indexOf("href=\"", tagStart)
                if (hrefStart == -1) {
                    searchStart = tagStart + 2
                    continue
                }

                val hrefValueStart = hrefStart + 6
                val hrefValueEnd = text.indexOf("\"", hrefValueStart)
                if (hrefValueEnd == -1) {
                    searchStart = tagStart + 2
                    continue
                }

                val href = text.substring(hrefValueStart, hrefValueEnd)

                val tagEnd = text.indexOf(">", hrefValueEnd)
                if (tagEnd == -1) {
                    searchStart = tagStart + 2
                    continue
                }

                val linkContentStart = tagEnd + 1
                val linkContentEnd = text.indexOf("</a>", linkContentStart)
                if (linkContentEnd == -1) {
                    searchStart = tagStart + 2
                    continue
                }

                if (pos >= linkContentStart && pos <= linkContentEnd) {
                    return href
                }

                searchStart = linkContentEnd + 4
            }

            null
        } catch (e: Exception) {
            logger.error("提取链接失败: pos={}", pos, e)
            null
        }
    }

    /**
     * 导航到文件并跳转到指定行
     */
    private fun navigateToFile(url: URL) {
        try {
            val file = File(url.toURI())
            val virtualFile = VirtualFileManager.getInstance()
                .findFileByUrl(url.toExternalForm())
                ?: LocalFileSystem.getInstance().findFileByIoFile(file)

            if (virtualFile == null) {
                logger.warn("文件不存在: {}", file.path)
                return
            }

            val fileEditorManager = FileEditorManager.getInstance(project)
            fileEditorManager.openFile(virtualFile, true)

            url.ref?.let { ref ->
                val lineNumber = ref.removePrefix("L").toIntOrNull()
                if (lineNumber != null && lineNumber > 0) {
                    jumpToLine(virtualFile, lineNumber)
                }
            }

            logger.info("成功导航到文件: {}", file.path)
        } catch (e: Exception) {
            logger.error("文件导航失败: url={}", url, e)
        }
    }

    /**
     * 跳转到指定行
     */
    private fun jumpToLine(virtualFile: com.intellij.openapi.vfs.VirtualFile, lineNumber: Int) {
        try {
            val fileEditorManager = FileEditorManager.getInstance(project)
            val editor = fileEditorManager.selectedTextEditor

            if (editor == null || editor.virtualFile != virtualFile) {
                logger.warn("编辑器未找到或文件不匹配")
                return
            }

            val document = editor.document
            if (lineNumber > document.lineCount) {
                logger.warn("行号超出范围: lineNumber={}, lineCount={}", lineNumber, document.lineCount)
                return
            }

            val lineStartOffset = document.getLineStartOffset(lineNumber - 1)
            editor.caretModel.moveToOffset(lineStartOffset)
            editor.selectionModel.removeSelection()
            editor.scrollingModel.scrollToCaret(com.intellij.openapi.editor.ScrollType.CENTER)

            logger.info("跳转到行成功: file={}, line={}", virtualFile.path, lineNumber)
        } catch (e: Exception) {
            logger.error("跳转失败: file={}, line={}", virtualFile.path, lineNumber, e)
        }
    }

    /**
     * 根据鼠标位置更新光标样式
     */
    private fun updateCursorForPosition(point: Point) {
        try {
            val pos = textPane.getUI().viewToModel(textPane, point)
            if (pos < 0) {
                textPane.cursor = Cursor.getDefaultCursor()
                return
            }

            val isLikelyLink = isTextLikelyLink(pos)
            textPane.cursor = if (isLikelyLink) {
                Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            } else {
                Cursor.getDefaultCursor()
            }
        } catch (e: Exception) {
            textPane.cursor = Cursor.getDefaultCursor()
        }
    }

    /**
     * 检查指定位置的文本是否可能是链接
     */
    private fun isTextLikelyLink(pos: Int): Boolean {
        return try {
            val doc: StyledDocument = textPane.styledDocument
            val start = maxOf(0, pos - TEXT_CONTEXT_RADIUS.toInt())
            val end = minOf(doc.length, pos + TEXT_CONTEXT_RADIUS.toInt())
            val text = doc.getText(start, end - start)
            val relativePos = pos - start

            if (relativePos >= text.length) return false

            val charBefore = if (relativePos > 0) text[relativePos - 1] else ' '
            val charAt = text[relativePos]
            val charAfter = if (relativePos < text.length - 1) text[relativePos + 1] else ' '

            charAt.isUpperCase() ||
                   (charAt.isLetter() && charBefore.isUpperCase()) ||
                   (charAt.isLowerCase() && (charBefore.isLetter() || charAfter.isLetter()))
        } catch (e: Exception) {
            false
        }
    }
}
