package com.smancode.smanagent.ide.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * SmanAgent 工具窗口工厂
 */
class SmanAgentToolWindowFactory : ToolWindowFactory {

    private val logger: Logger = LoggerFactory.getLogger(SmanAgentToolWindowFactory::class.java)

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        logger.info("Creating SmanAgent tool window for project: {}", project.name)

        val chatPanel = SmanAgentChatPanel(project)
        val contentFactory = ContentFactory.getInstance()
        val content = contentFactory.createContent(chatPanel, "", "SmanAgent")

        toolWindow.contentManager.addContent(content)
    }
}
