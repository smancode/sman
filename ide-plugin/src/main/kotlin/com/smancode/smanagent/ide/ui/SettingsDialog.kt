package com.smancode.smanagent.ide.ui

import com.intellij.openapi.project.Project
import com.smancode.smanagent.ide.service.storageService
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.*

/**
 * 设置对话框
 *
 * 配置项：
 * - 服务器 URL
 * - 项目名称
 * - 保存对话历史
 */
class SettingsDialog(private val project: Project) : JDialog() {

    private val storage = project.storageService()

    private val serverUrlField = JTextField(storage.backendUrl, 30)
    private val projectKeyField = JTextField(project.name, 30)
    private val saveHistoryCheckBox = JCheckBox("保存对话历史", true)

    init {
        title = "SmanAgent 设置"
        isModal = true
        defaultCloseOperation = DISPOSE_ON_CLOSE

        val panel = JPanel(GridBagLayout())
        val gbc = GridBagConstraints().apply {
            insets = Insets(5, 5, 5, 5)
            anchor = GridBagConstraints.WEST
        }

        // 服务器 URL
        gbc.gridx = 0
        gbc.gridy = 0
        panel.add(JLabel("服务器 URL:"), gbc)
        gbc.gridx = 1
        gbc.weightx = 1.0
        gbc.fill = GridBagConstraints.HORIZONTAL
        panel.add(serverUrlField, gbc)

        // 项目名称
        gbc.gridx = 0
        gbc.gridy = 1
        gbc.weightx = 0.0
        gbc.fill = GridBagConstraints.NONE
        panel.add(JLabel("项目名称:"), gbc)
        gbc.gridx = 1
        gbc.weightx = 1.0
        gbc.fill = GridBagConstraints.HORIZONTAL
        panel.add(projectKeyField, gbc)

        // 保存历史
        gbc.gridx = 0
        gbc.gridy = 2
        gbc.gridwidth = 2
        panel.add(saveHistoryCheckBox, gbc)

        // 按钮面板
        val buttonPanel = JPanel(BorderLayout()).apply {
            val saveButton = JButton("保存").apply {
                addActionListener { saveSettings() }
            }
            val cancelButton = JButton("取消").apply {
                addActionListener { dispose() }
            }

            val buttonWrapper = JPanel(FlowLayout(FlowLayout.RIGHT))
            buttonWrapper.add(saveButton)
            buttonWrapper.add(cancelButton)
            add(buttonWrapper, BorderLayout.EAST)
        }

        add(panel, BorderLayout.CENTER)
        add(buttonPanel, BorderLayout.SOUTH)

        pack()
        setLocationRelativeTo(null)
        minimumSize = java.awt.Dimension(400, 180)
        isResizable = false
    }

    private fun saveSettings() {
        val serverUrl = serverUrlField.text.trim()
        val projectKey = projectKeyField.text.trim()

        // 基础验证
        if (serverUrl.isEmpty()) {
            JOptionPane.showMessageDialog(this, "服务器URL不能为空！", "错误", JOptionPane.ERROR_MESSAGE)
            return
        }

        if (projectKey.isEmpty()) {
            JOptionPane.showMessageDialog(this, "项目名称不能为空！", "错误", JOptionPane.ERROR_MESSAGE)
            return
        }

        // 保存设置
        storage.backendUrl = serverUrl
        // TODO: 添加更多配置到 StorageService

        JOptionPane.showMessageDialog(this, "设置已保存！\n服务器URL: $serverUrl")
        dispose()
    }

    companion object {
        fun show(project: Project) {
            val dialog = SettingsDialog(project)
            dialog.isVisible = true
        }
    }
}
