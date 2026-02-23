package com.smancode.sman.infra.ide

import com.intellij.openapi.project.Project
import com.smancode.sman.ide.components.HistoryPopup
import com.smancode.sman.ide.components.CliControlBar
import com.smancode.sman.ide.service.storageService
import org.slf4j.Logger
import java.awt.Dimension
import javax.swing.JScrollPane
import javax.swing.SwingUtilities

/**
 * 聊天工具栏处理器
 *
 * 负责会话管理功能：
 * - 新建/加载/删除会话
 * - 历史记录显示
 * - 设置对话框
 */
class ChatToolbar(
    private val project: Project,
    private val logger: Logger,
    private val storageService: com.smancode.sman.ide.service.StorageService,
    private val controlBar: CliControlBar
) {
    private val projectKey: String
        get() = project.name

    // 分析报告构建器
    private val analysisReportBuilder = AnalysisReportBuilder(project, logger)

    // 回调接口
    interface Callback {
        fun getCurrentSessionId(): String?
        fun setCurrentSessionId(sessionId: String?)
        fun clearChatUI()
        fun showWelcome()
        fun showChat()
        fun appendPartToUI(part: com.smancode.sman.ide.model.PartData)
        fun getOutputArea(): javax.swing.JTextPane
        fun showAnalysisResults()
    }

    private var callback: Callback? = null
    private var currentSessionId: String? = null

    fun setCallback(callback: Callback) {
        this.callback = callback
    }

    fun getCurrentSessionId(): String? = currentSessionId

    fun setCurrentSessionId(sessionId: String?) {
        currentSessionId = sessionId
    }

    /**
     * 新建会话
     */
    fun startNewSession() {
        logger.info("新建会话")
        saveCurrentSessionIfNeeded()
        currentSessionId = null
        storageService.setCurrentSessionId(null)
        callback?.clearChatUI()
        callback?.showWelcome()
    }

    /**
     * 保存当前会话（如果需要）
     */
    fun saveCurrentSessionIfNeeded() {
        val sessionId = currentSessionId ?: return
        val session = storageService.getSession(sessionId)
        if (session != null && session.parts.isNotEmpty()) {
            logger.info("保存当前会话: sessionId={}, parts={}", sessionId, session.parts.size)
            storageService.updateSessionTimestamp(sessionId)
        }
    }

    /**
     * 显示历史记录
     */
    fun showHistory() {
        logger.info("显示历史记录")
        saveCurrentSessionIfNeeded()

        val history = storageService.getHistorySessions(projectKey)
        HistoryPopup(
            history = history,
            onSelect = { sessionInfo -> loadSession(sessionInfo.id) },
            onDelete = { sessionInfo -> deleteSession(sessionInfo.id) }
        ).show(controlBar.getHistoryButton() ?: return)
    }

    /**
     * 加载会话
     */
    fun loadSession(sessionId: String) {
        logger.info("加载会话: sessionId={}", sessionId)
        callback?.clearChatUI()

        val session = storageService.getSession(sessionId)
        if (session != null) {
            currentSessionId = session.id
            storageService.setCurrentSessionId(session.id)

            if (session.parts.isNotEmpty()) {
                val outputArea = callback?.getOutputArea()
                outputArea?.text = "<html><body></body></html>"
                session.parts.forEach { part -> callback?.appendPartToUI(part) }
                callback?.showChat()
            } else {
                callback?.showWelcome()
            }
            logger.info("会话加载完成: sessionId={}, parts={}", sessionId, session.parts.size)
        } else {
            logger.warn("会话不存在: sessionId={}", sessionId)
            callback?.showWelcome()
        }
    }

    /**
     * 删除会话
     */
    fun deleteSession(sessionId: String) {
        logger.info("删除会话: sessionId={}", sessionId)
        storageService.deleteSession(sessionId)

        if (currentSessionId == sessionId) {
            currentSessionId = null
            storageService.setCurrentSessionId(null)
            callback?.clearChatUI()
            callback?.showWelcome()
        }
    }

    /**
     * 加载最后一次会话
     */
    fun loadLastSession(outputArea: javax.swing.JTextPane, scrollPane: JScrollPane) {
        try {
            val lastSessionId = storageService.getCurrentSessionId() ?: return
            val session = storageService.getSession(lastSessionId)

            if (session != null && session.parts.isNotEmpty()) {
                currentSessionId = session.id
                outputArea.text = "<html><body></body></html>"
                session.parts.forEach { part -> callback?.appendPartToUI(part) }
                callback?.showChat()

                // 延迟触发重绘，确保布局完全就绪
                SwingUtilities.invokeLater {
                    val currentSize = outputArea.size
                    outputArea.size = Dimension(1, 1)
                    outputArea.revalidate()
                    outputArea.repaint()
                    scrollPane.revalidate()
                    scrollPane.repaint()
                    outputArea.size = currentSize
                    outputArea.revalidate()
                    outputArea.repaint()
                }

                logger.info("加载上次会话: sessionId={}, parts={}", lastSessionId, session.parts.size)
            } else {
                logger.info("无上次会话内容，显示欢迎面板")
            }
        } catch (e: Exception) {
            logger.error("加载会话失败", e)
        }
    }

    /**
     * 显示设置对话框
     */
    fun showSettings() {
        try {
            com.smancode.sman.ide.ui.SettingsDialog.show(project) {
                callback?.showAnalysisResults()
            }
        } catch (e: Exception) {
            logger.error("打开设置失败", e)
        }
    }

    /**
     * 显示分析结果（委托给 AnalysisReportBuilder）
     */
    fun showAnalysisResults() {
        analysisReportBuilder.showAnalysisResults()
    }
}
