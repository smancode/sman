package com.smancode.smanagent.ide.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.smancode.smanagent.ide.service.storageService
import java.awt.BorderLayout
import javax.swing.*

/**
 * 设置对话框
 */
class SettingsDialog(project: Project) : DialogWrapper(true) {

    private val project = project
    private lateinit var backendUrlField: JTextField

    init {
        title = "SmanAgent 设置"
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout())
        val form = JPanel(java.awt.GridLayout(2, 2, 10, 10))

        // 后端 URL
        form.add(JLabel("后端 WebSocket URL:"))
        backendUrlField = JTextField(project.storageService().backendUrl)
        form.add(backendUrlField)

        panel.add(form, BorderLayout.CENTER)
        return panel
    }

    override fun doOKAction() {
        // 保存设置
        project.storageService().backendUrl = backendUrlField.text.trim()
        super.doOKAction()
    }

    companion object {
        fun show(project: Project) {
            val dialog = SettingsDialog(project)
            if (dialog.showAndGet()) {
                JOptionPane.showMessageDialog(
                    null,
                    "设置已保存",
                    "SmanAgent",
                    JOptionPane.INFORMATION_MESSAGE
                )
            }
        }
    }
}
