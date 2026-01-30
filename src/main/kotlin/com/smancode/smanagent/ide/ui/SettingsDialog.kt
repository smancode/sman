package com.smancode.smanagent.ide.ui

import com.intellij.openapi.project.Project
import com.smancode.smanagent.config.SmanAgentConfig
import com.smancode.smanagent.ide.service.SmanAgentService
import com.smancode.smanagent.ide.service.storageService
import org.slf4j.LoggerFactory
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
 * - LLM API 配置（API Key、Base URL、模型名称）
 * - BGE-M3 配置（端点、API Key）
 * - BGE-Reranker 配置（端点、API Key）
 * - 项目名称
 * - 保存对话历史
 */
class SettingsDialog(private val project: Project) : JDialog() {

    private val logger = LoggerFactory.getLogger(SettingsDialog::class.java)
    private val storage = project.storageService()

    // LLM 配置字段
    private val llmApiKeyField = JPasswordField("", 30)
    private val llmBaseUrlField = JTextField(
        storage.llmBaseUrl.takeIf { it.isNotEmpty() }
            ?: "https://open.bigmodel.cn/api/coding/paas/v4",
        30
    )
    private val llmModelNameField = JTextField(
        storage.llmModelName.takeIf { it.isNotEmpty() }
            ?: "GLM-4.7",
        30
    )

    // BGE-M3 配置字段
    private val bgeEndpointField = JTextField(
        storage.bgeEndpoint.takeIf { it.isNotEmpty() }
            ?: "http://localhost:8000",
        30
    )
    private val bgeApiKeyField = JPasswordField(storage.bgeApiKey, 30)

    // BGE-Reranker 配置字段
    private val rerankerEndpointField = JTextField(
        storage.rerankerEndpoint.takeIf { it.isNotEmpty() }
            ?: "http://localhost:8001",
        30
    )
    private val rerankerApiKeyField = JPasswordField(storage.rerankerApiKey, 30)

    // 其他配置字段
    private val projectKeyField = JTextField(project.name, 30)
    private val saveHistoryCheckBox = JCheckBox("保存对话历史", true)

    init {
        title = "SmanAgent 设置"
        isModal = true
        defaultCloseOperation = DISPOSE_ON_CLOSE

        // 如果已有配置，填充掩码后的值
        if (storage.llmApiKey.isNotEmpty()) {
            llmApiKeyField.text = "****"
        }
        if (storage.bgeApiKey.isNotEmpty()) {
            bgeApiKeyField.text = "****"
        }
        if (storage.rerankerApiKey.isNotEmpty()) {
            rerankerApiKeyField.text = "****"
        }

        val panel = createMainPanel()
        val buttonPanel = createButtonPanel()

        add(panel, BorderLayout.CENTER)
        add(buttonPanel, BorderLayout.SOUTH)

        pack()
        setLocationRelativeTo(null)
        minimumSize = java.awt.Dimension(500, 400)
        isResizable = false
    }

    private fun createMainPanel(): JPanel {
        val panel = JPanel(GridBagLayout())
        val gbc = createGridBagConstraints()
        var row = 0

        // LLM API 配置
        row = addLlmConfigSection(panel, gbc, row)

        // BGE-M3 配置
        row = addBgeM3ConfigSection(panel, gbc, row)

        // BGE-Reranker 配置
        row = addRerankerConfigSection(panel, gbc, row)

        // 其他配置
        addOtherConfigSection(panel, gbc, row)

        return panel
    }

    private fun createGridBagConstraints() = GridBagConstraints().apply {
        insets = Insets(5, 5, 5, 5)
        anchor = GridBagConstraints.WEST
    }

    private fun addLlmConfigSection(panel: JPanel, gbc: GridBagConstraints, startRow: Int): Int {
        var row = startRow

        // 分隔线
        gbc.gridx = 0
        gbc.gridy = row
        gbc.gridwidth = 2
        panel.add(JSeparator(), gbc)
        row++

        // 标题
        gbc.gridx = 0
        gbc.gridy = row
        gbc.gridwidth = 2
        panel.add(JLabel("<html><b>LLM API 配置</b></html>"), gbc)
        row++

        // API Key
        row = addLabeledField(panel, gbc, row, "API Key:", llmApiKeyField)

        // Base URL
        row = addLabeledField(panel, gbc, row, "Base URL:", llmBaseUrlField)

        // 模型名称
        row = addLabeledField(panel, gbc, row, "模型名称:", llmModelNameField)

        return row
    }

    private fun addBgeM3ConfigSection(panel: JPanel, gbc: GridBagConstraints, startRow: Int): Int {
        var row = startRow

        // 分隔线
        gbc.gridx = 0
        gbc.gridy = row
        gbc.gridwidth = 2
        panel.add(JSeparator(), gbc)
        row++

        // 标题
        gbc.gridx = 0
        gbc.gridy = row
        gbc.gridwidth = 2
        panel.add(JLabel("<html><b>BGE-M3 向量化配置</b></html>"), gbc)
        row++

        // 端点
        row = addLabeledField(panel, gbc, row, "端点:", bgeEndpointField)

        // API Key（可选）
        row = addLabeledField(panel, gbc, row, "API Key (可选):", bgeApiKeyField)

        return row
    }

    private fun addRerankerConfigSection(panel: JPanel, gbc: GridBagConstraints, startRow: Int): Int {
        var row = startRow

        // 分隔线
        gbc.gridx = 0
        gbc.gridy = row
        gbc.gridwidth = 2
        panel.add(JSeparator(), gbc)
        row++

        // 标题
        gbc.gridx = 0
        gbc.gridy = row
        gbc.gridwidth = 2
        panel.add(JLabel("<html><b>BGE-Reranker 重排配置</b></html>"), gbc)
        row++

        // 端点
        row = addLabeledField(panel, gbc, row, "端点:", rerankerEndpointField)

        // API Key（可选）
        row = addLabeledField(panel, gbc, row, "API Key (可选):", rerankerApiKeyField)

        return row
    }

    private fun addOtherConfigSection(panel: JPanel, gbc: GridBagConstraints, startRow: Int): Int {
        var row = startRow

        // 分隔线
        gbc.gridx = 0
        gbc.gridy = row
        gbc.gridwidth = 2
        panel.add(JSeparator(), gbc)
        row++

        // 标题
        gbc.gridx = 0
        gbc.gridy = row
        gbc.gridwidth = 2
        panel.add(JLabel("<html><b>其他配置</b></html>"), gbc)
        row++

        // 项目名称
        row = addLabeledField(panel, gbc, row, "项目名称:", projectKeyField)

        // 保存历史
        gbc.gridx = 0
        gbc.gridy = row
        gbc.gridwidth = 2
        gbc.weightx = 0.0
        gbc.fill = GridBagConstraints.NONE
        panel.add(saveHistoryCheckBox, gbc)

        return row
    }

    private fun addLabeledField(panel: JPanel, gbc: GridBagConstraints, row: Int, label: String, field: JTextField): Int {
        gbc.gridx = 0
        gbc.gridy = row
        gbc.gridwidth = 1
        gbc.weightx = 0.0
        gbc.fill = GridBagConstraints.NONE
        panel.add(JLabel(label), gbc)

        gbc.gridx = 1
        gbc.weightx = 1.0
        gbc.fill = GridBagConstraints.HORIZONTAL
        panel.add(field, gbc)

        return row + 1
    }

    private fun createButtonPanel(): JPanel {
        val saveButton = JButton("保存").apply {
            addActionListener { saveSettings() }
        }
        val cancelButton = JButton("取消").apply {
            addActionListener { dispose() }
        }

        val buttonWrapper = JPanel(FlowLayout(FlowLayout.RIGHT)).apply {
            add(saveButton)
            add(cancelButton)
        }

        return JPanel(BorderLayout()).apply {
            add(buttonWrapper, BorderLayout.EAST)
        }
    }

    private fun saveSettings() {
        val config = collectConfig() ?: return

        saveConfig(config)
        showSuccessMessage(config)
        dispose()
    }

    /**
     * 收集并验证配置
     * @return 配置对象，验证失败返回 null
     */
    private fun collectConfig(): ConfigData? {
        val llmApiKey = String(llmApiKeyField.password).trim()
        val llmBaseUrl = llmBaseUrlField.text.trim()
        val llmModelName = llmModelNameField.text.trim()
        val bgeEndpoint = bgeEndpointField.text.trim()
        val bgeApiKey = String(bgeApiKeyField.password).trim()
        val rerankerEndpoint = rerankerEndpointField.text.trim()
        val rerankerApiKey = String(rerankerApiKeyField.password).trim()
        val projectKey = projectKeyField.text.trim()

        // 验证必填字段
        if (llmBaseUrl.isEmpty()) {
            showError("LLM Base URL 不能为空！")
            return null
        }

        if (llmModelName.isEmpty()) {
            showError("LLM 模型名称不能为空！")
            return null
        }

        if (bgeEndpoint.isEmpty()) {
            showError("BGE-M3 端点不能为空！")
            return null
        }

        if (rerankerEndpoint.isEmpty()) {
            showError("Reranker 端点不能为空！")
            return null
        }

        if (projectKey.isEmpty()) {
            showError("项目名称不能为空！")
            return null
        }

        return ConfigData(
            llmApiKey = llmApiKey,
            llmBaseUrl = llmBaseUrl,
            llmModelName = llmModelName,
            bgeEndpoint = bgeEndpoint,
            bgeApiKey = bgeApiKey,
            rerankerEndpoint = rerankerEndpoint,
            rerankerApiKey = rerankerApiKey,
            projectKey = projectKey
        )
    }

    /**
     * 保存配置到存储服务
     */
    private fun saveConfig(config: ConfigData) {
        // LLM 配置：只有用户输入了新值才覆盖
        if (config.llmApiKey.isNotEmpty()) {
            storage.llmApiKey = config.llmApiKey
        }
        storage.llmBaseUrl = config.llmBaseUrl
        storage.llmModelName = config.llmModelName

        // BGE-M3 配置：只有用户输入了新值才覆盖
        storage.bgeEndpoint = config.bgeEndpoint
        if (config.bgeApiKey.isNotEmpty()) {
            storage.bgeApiKey = config.bgeApiKey
        }

        // BGE-Reranker 配置：只有用户输入了新值才覆盖
        storage.rerankerEndpoint = config.rerankerEndpoint
        if (config.rerankerApiKey.isNotEmpty()) {
            storage.rerankerApiKey = config.rerankerApiKey
        }

        // 更新配置到 SmanAgentConfig（下次 LLM 调用会使用新配置）
        val userConfig = SmanAgentConfig.UserConfig(
            llmApiKey = storage.llmApiKey,
            llmBaseUrl = storage.llmBaseUrl,
            llmModelName = storage.llmModelName
        )
        SmanAgentConfig.setUserConfig(userConfig)
    }

    /**
     * 显示成功消息
     */
    private fun showSuccessMessage(config: ConfigData) {
        val message = buildString {
            append("设置已保存！\n\n")
            append("LLM 配置:\n")
            append("  - Base URL: ${config.llmBaseUrl}\n")
            append("  - 模型: ${config.llmModelName}\n")
            append("  - API Key: ${if (config.llmApiKey.isNotEmpty()) "****" else "(使用配置文件或环境变量)"}\n")
            append("\nBGE-M3 配置:\n")
            append("  - 端点: ${config.bgeEndpoint}\n")
            append("  - API Key: ${if (config.bgeApiKey.isNotEmpty()) "****" else "(未设置)"}\n")
            append("\nBGE-Reranker 配置:\n")
            append("  - 端点: ${config.rerankerEndpoint}\n")
            append("  - API Key: ${if (config.rerankerApiKey.isNotEmpty()) "****" else "(未设置)"}\n")
            append("\n其他配置:\n")
            append("  - 项目名称: ${config.projectKey}")
        }
        JOptionPane.showMessageDialog(this, message)
    }

    /**
     * 显示错误消息
     */
    private fun showError(message: String) {
        JOptionPane.showMessageDialog(this, message, "错误", JOptionPane.ERROR_MESSAGE)
    }

    /**
     * 配置数据
     */
    private data class ConfigData(
        val llmApiKey: String,
        val llmBaseUrl: String,
        val llmModelName: String,
        val bgeEndpoint: String,
        val bgeApiKey: String,
        val rerankerEndpoint: String,
        val rerankerApiKey: String,
        val projectKey: String
    )

    companion object {
        fun show(project: Project) {
            val dialog = SettingsDialog(project)
            dialog.isVisible = true
        }
    }
}
