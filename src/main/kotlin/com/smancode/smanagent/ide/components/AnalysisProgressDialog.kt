package com.smancode.smanagent.ide.components

import com.intellij.openapi.editor.colors.EditorColorsManager
import com.smancode.smanagent.analysis.model.StepResult
import com.smancode.smanagent.analysis.model.StepStatus
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.*

/**
 * 项目分析进度对话框
 *
 * 显示分析进度和状态
 */
class AnalysisProgressDialog(
    parent: JDialog?,
    private val projectKey: String
) : JDialog(parent, "项目分析进度", true) {

    private val logger = org.slf4j.LoggerFactory.getLogger(AnalysisProgressDialog::class.java)

    // UI 组件
    private val progressBar = JProgressBar(0, 100)
    private val statusLabel = JLabel("准备开始分析...")
    private val stepsPanel = JPanel()
    private val stepLabels = mutableMapOf<String, JLabel>()
    private val resultLabels = mutableMapOf<String, JLabel>()

    // 步骤配置
    private val stepConfig = listOf(
        "project_structure" to "项目结构扫描",
        "tech_stack_detection" to "技术栈识别",
        "ast_scanning" to "AST 扫描",
        "db_entity_detection" to "数据库实体扫描",
        "api_entry_scanning" to "API 入口扫描",
        "enum_scanning" to "枚举扫描",
        "common_class_scanning" to "公共类扫描",
        "xml_code_scanning" to "XML 代码扫描"
    )

    private val totalSteps = stepConfig.size

    init {
        initComponents()
        pack()
        setLocationRelativeTo(parent)
        isResizable = false
    }

    private fun initComponents() {
        layout = BorderLayout(10, 10)

        add(createTitleLabel(), BorderLayout.NORTH)
        add(createContentPanel(), BorderLayout.CENTER)
        add(createButtonPanel(), BorderLayout.SOUTH)

        initStepLabels()
    }

    private fun createTitleLabel() = JLabel("正在分析项目: $projectKey").apply {
        val editorFont = EditorColorsManager.getInstance().globalScheme
        font = java.awt.Font(editorFont.editorFontName, java.awt.Font.BOLD, editorFont.editorFontSize)
    }

    private fun createContentPanel(): JPanel {
        val contentPanel = JPanel(java.awt.GridBagLayout()).apply {
            preferredSize = Dimension(400, 300)
        }

        val gbc = createGridBagConstraints()
        gbc.fill = java.awt.GridBagConstraints.HORIZONTAL
        gbc.weightx = 1.0

        gbc.gridy = 0
        contentPanel.add(progressBar, gbc)

        gbc.gridy = 1
        contentPanel.add(statusLabel, gbc)

        gbc.gridy = 2
        gbc.weighty = 1.0
        gbc.fill = java.awt.GridBagConstraints.BOTH
        contentPanel.add(JScrollPane(stepsPanel).apply {
            preferredSize = Dimension(380, 200)
        }, gbc)

        return contentPanel
    }

    private fun createButtonPanel() = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.RIGHT)).apply {
        add(JButton("取消").apply { addActionListener { onCancel() } })
    }

    private fun createGridBagConstraints() = java.awt.GridBagConstraints().apply {
        insets = java.awt.Insets(5, 5, 5, 5)
    }

    private fun initStepLabels() {
        stepsPanel.layout = java.awt.GridBagLayout()
        val gbc = createStepGridBagConstraints()

        stepConfig.forEachIndexed { index, (stepName, stepDesc) ->
            val iconLabel = createIconLabel()
            val descLabel = createDescLabel(stepDesc)
            val resultLabel = createResultLabel()

            addStepLabels(gbc, index, iconLabel, descLabel, resultLabel)

            stepLabels[stepName] = iconLabel
            resultLabels[stepName] = resultLabel
        }
    }

    private fun createStepGridBagConstraints() = java.awt.GridBagConstraints().apply {
        insets = java.awt.Insets(3, 5, 3, 5)
        anchor = java.awt.GridBagConstraints.WEST
        fill = java.awt.GridBagConstraints.HORIZONTAL
        weightx = 1.0
    }

    private fun createIconLabel() = JLabel("⏸").apply {
        preferredSize = Dimension(20, 20)
    }

    private fun createDescLabel(text: String) = JLabel(text).apply {
        preferredSize = Dimension(150, 20)
    }

    private fun createResultLabel() = JLabel("").apply {
        preferredSize = Dimension(100, 20)
    }

    private fun addStepLabels(gbc: java.awt.GridBagConstraints, index: Int, icon: JLabel, desc: JLabel, result: JLabel) {
        gbc.gridx = 0
        gbc.gridy = index
        stepsPanel.add(icon, gbc)

        gbc.gridx = 1
        stepsPanel.add(desc, gbc)

        gbc.gridx = 2
        stepsPanel.add(result, gbc)
    }

    /**
     * 步骤开始
     */
    fun onStepStart(stepName: String, description: String) {
        SwingUtilities.invokeLater {
            updateStepStatus(stepName, StepStatus.RUNNING)
            statusLabel.text = "正在执行: $description"
            updateProgress()
        }
    }

    /**
     * 步骤完成
     */
    fun onStepComplete(stepName: String, result: StepResult) {
        SwingUtilities.invokeLater {
            updateStepStatus(stepName, result.status)
            updateResultLabel(stepName, result)
            statusLabel.text = "步骤完成: ${getResultText(result)}"
            updateProgress()
        }
    }

    /**
     * 步骤失败
     */
    fun onStepFailed(stepName: String, error: String) {
        SwingUtilities.invokeLater {
            updateStepStatus(stepName, StepStatus.FAILED)
            statusLabel.text = "步骤失败: $stepName"
            updateProgress()
        }
    }

    /**
     * 分析完成
     */
    fun onAnalysisComplete(success: Boolean) {
        SwingUtilities.invokeLater {
            statusLabel.text = if (success) "分析完成！" else "分析失败"
            if (success) progressBar.value = 100
        }
    }

    private fun updateStepStatus(stepName: String, status: StepStatus) {
        stepLabels[stepName]?.text = status.icon
    }

    private fun updateResultLabel(stepName: String, result: StepResult) {
        resultLabels[stepName]?.text = getResultText(result)
    }

    private fun getResultText(result: StepResult): String {
        val duration = result.getDuration()?.let { "(${it}ms)" } ?: ""
        return when (result.status) {
            StepStatus.COMPLETED -> "✓ 完成 $duration"
            StepStatus.FAILED -> "✗ 失败"
            StepStatus.SKIPPED -> "⊘ 跳过"
            else -> ""
        }
    }

    private fun updateProgress() {
        val completed = stepLabels.values.count { it.text.isTerminalStatus() }
        progressBar.value = completed * 100 / totalSteps
    }

    private val StepStatus.icon: String
        get() = when (this) {
            StepStatus.PENDING -> "⏸"
            StepStatus.RUNNING -> "⏳"
            StepStatus.COMPLETED -> "✓"
            StepStatus.FAILED -> "✗"
            StepStatus.SKIPPED -> "⊘"
        }

    private fun String.isTerminalStatus() = this == "✓" || this == "✗"

    private fun onCancel() {
        logger.info("用户取消分析")
        dispose()
    }
}
