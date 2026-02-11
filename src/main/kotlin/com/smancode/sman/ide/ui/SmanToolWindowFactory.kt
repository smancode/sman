package com.smancode.sman.ide.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.awt.BorderLayout
import javax.swing.*

/**
 * Sman 工具窗口工厂
 */
class SmanToolWindowFactory : ToolWindowFactory {

    private val logger = LoggerFactory.getLogger(SmanToolWindowFactory::class.java)

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        logger.info("Creating Sman tool window for project: {}", project.name)

        try {
            val chatPanel = SmanChatPanel(project)
            val contentFactory = ContentFactory.getInstance()
            val content = contentFactory.createContent(chatPanel, "", false)  // 空字符串作为 tab 名称
            content.isCloseable = false

            toolWindow.contentManager.addContent(content)
            // 移除 setTitle，让工具窗口只显示一个标题
            // toolWindow.setTitle("Sman")
            toolWindow.show(null)

            logger.info("Sman tool window created successfully")

        } catch (e: Exception) {
            logger.error("Failed to create Sman tool window", e)

            // 添加 fallback UI，避免显示 "Nothing to show"
            val errorPanel = JPanel(BorderLayout()).apply {
                border = BorderFactory.createEmptyBorder(20, 20, 20, 20)

                val errorMessage = StringBuilder()
                errorMessage.append("<html><center>")
                errorMessage.append("<h2>Sman 初始化失败</h2>")
                errorMessage.append("<p style='color:red;'><b>${e.message}</b></p>")
                errorMessage.append("<p>可能的原因：</p>")
                errorMessage.append("<ul style='text-align:left;'>")
                errorMessage.append("<li>后端服务未启动（请先启动 sman 后端）</li>")
                errorMessage.append("<li>插件配置错误</li>")
                errorMessage.append("<li>IDE 版本不兼容</li>")
                errorMessage.append("</ul>")
                errorMessage.append("<p>请查看 IDEA 日志获取详细信息</p>")
                errorMessage.append("</center></html>")

                add(JLabel(errorMessage.toString()), BorderLayout.CENTER)

                // 添加重试按钮
                val retryButton = JButton("重试")
                retryButton.addActionListener {
                    toolWindow.contentManager.removeAllContents(true)
                    createToolWindowContent(project, toolWindow)
                }

                val buttonPanel = JPanel()
                buttonPanel.add(retryButton)
                add(buttonPanel, BorderLayout.SOUTH)
            }

            val errorContent = ContentFactory.getInstance().createContent(errorPanel, "Error", false)
            toolWindow.contentManager.addContent(errorContent)
        }
    }
}
