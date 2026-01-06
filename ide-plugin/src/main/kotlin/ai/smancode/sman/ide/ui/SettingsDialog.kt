package ai.smancode.sman.ide.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import ai.smancode.sman.ide.service.ProjectStorageService
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.*

class SettingsDialog(private val project: Project) : JDialog() {
    private val projectStorage = ProjectStorageService.getInstance(project)
    
    private val serverUrlField = JTextField(projectStorage.getServerUrl(), 30)
    private val projectKeyField = JTextField(projectStorage.getProjectKey(), 30)
    private val aiNameField = JTextField(projectStorage.getAiName(), 30)
    private val modeField = JTextField(projectStorage.getMode(), 30)
    private val saveHistoryCheckBox = JCheckBox("保存对话历史", projectStorage.shouldSaveHistory())
    
    init {
        title = "设置"
        isModal = true
        defaultCloseOperation = DISPOSE_ON_CLOSE
        
        val panel = JPanel(GridBagLayout())
        val gbc = GridBagConstraints().apply {
            insets = java.awt.Insets(5, 5, 5, 5)
            anchor = GridBagConstraints.WEST
        }
        
        gbc.gridx = 0
        gbc.gridy = 0
        panel.add(JLabel("服务器 URL:"), gbc)
        gbc.gridx = 1
        panel.add(serverUrlField, gbc)
        
        gbc.gridx = 0
        gbc.gridy = 1
        panel.add(JLabel("项目名称:"), gbc)
        gbc.gridx = 1
        panel.add(projectKeyField, gbc)
        
        gbc.gridx = 0
        gbc.gridy = 2
        panel.add(JLabel("AI名称:"), gbc)
        gbc.gridx = 1
        panel.add(aiNameField, gbc)

        gbc.gridx = 0
        gbc.gridy = 3
        panel.add(JLabel("模式:"), gbc)
        gbc.gridx = 1
        panel.add(modeField, gbc)
        
        gbc.gridx = 0
        gbc.gridy = 4
        gbc.gridwidth = 2
        panel.add(saveHistoryCheckBox, gbc)
        
        val buttonPanel = JPanel(FlowLayout(FlowLayout.RIGHT))
        val saveButton = JButton("保存").apply {
            addActionListener { saveSettings() }
        }
        val cancelButton = JButton("取消").apply {
            addActionListener { dispose() }
        }
        buttonPanel.add(saveButton)
        buttonPanel.add(cancelButton)
        
        add(panel, BorderLayout.CENTER)
        add(buttonPanel, BorderLayout.SOUTH)
        
        pack()
        setLocationRelativeTo(null)
        
        // 设置对话框大小和模态
        minimumSize = java.awt.Dimension(400, 250)
        isResizable = false
    }
    
    private fun saveSettings() {
        val serverUrl = serverUrlField.text.trim()
        val projectKey = projectKeyField.text.trim()
        val aiName = aiNameField.text.trim()
        val mode = modeField.text.trim()
        
        // 基础验证
        if (serverUrl.isEmpty()) {
            JOptionPane.showMessageDialog(this, "服务器URL不能为空！", "错误", JOptionPane.ERROR_MESSAGE)
            return
        }
        
        if (projectKey.isEmpty()) {
            JOptionPane.showMessageDialog(this, "项目名称不能为空！", "错误", JOptionPane.ERROR_MESSAGE)
            return
        }

        if (mode.isEmpty()) {
            JOptionPane.showMessageDialog(this, "模式不能为空！", "错误", JOptionPane.ERROR_MESSAGE)
            return
        }
        
        // 保存设置
        projectStorage.setServerUrl(serverUrl)
        projectStorage.setProjectKey(projectKey)
        projectStorage.setAiName(aiName.ifEmpty { "SiliconMan" })
        projectStorage.setMode(mode)
        projectStorage.setSaveHistory(saveHistoryCheckBox.isSelected)
        
        // 强制立即持久化到磁盘
        try {
            // 保存项目设置
            project.save()
            // 保存应用全局设置
            ApplicationManager.getApplication().saveSettings()
        } catch (e: Exception) {
            // 忽略保存异常
        }
        
        JOptionPane.showMessageDialog(this, "设置已保存！\n服务器URL: $serverUrl")
        dispose()
    }
    
    fun showDialog() {
        setVisible(true)
    }
}

