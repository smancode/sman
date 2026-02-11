package com.smancode.sman.ide.ui

import com.intellij.openapi.project.Project
import com.smancode.sman.ide.service.storageService
import org.slf4j.LoggerFactory
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.*

/**
 * 项目分析配置对话框
 *
 * 在点击"项目分析"时弹出，询问用户项目相关信息：
 * - 框架类型
 * - 入口包路径
 * - 自定义注解
 * - 是否保存配置
 *
 * 用户可以选择"我也不知道"使用穷尽扫描模式
 */
class ProjectAnalysisConfigDialog(
    private val project: Project,
    private val onConfirm: (ProjectAnalysisConfig) -> Unit
) : JDialog() {

    private val logger = LoggerFactory.getLogger(ProjectAnalysisConfigDialog::class.java)
    private val storage = project.storageService()

    // 框架类型选择
    private val frameworkTypeCombo = JComboBox<FrameworkType>().apply {
        FrameworkType.entries.forEach { addItem(it) }
        selectedIndex = 0
    }

    // 入口包路径（支持多个，用逗号分隔）
    private val entryPackagesField = JTextArea(3, 30).apply {
        text = storage.lastEntryPackages
        wrapStyleWord = true
        lineWrap = true
    }

    // 自定义注解（用逗号分隔）
    private val customAnnotationsField = JTextField(storage.lastCustomAnnotations, 30)

    // 是否包含 DTO 扫描
    private val includeDtoScanCheckBox = JCheckBox("扫描 DTO 类", true)

    // 是否包含 Entity 扫描
    private val includeEntityScanCheckBox = JCheckBox("扫描 Entity 类", true)

    // 是否使用穷尽扫描模式
    private val exhaustiveScanCheckBox = JCheckBox("我也不知道（穷尽扫描所有可能）", false).apply {
        addActionListener { toggleExhaustiveMode(isSelected) }
    }

    // 保存配置
    private val saveConfigCheckBox = JCheckBox("保存此配置（下次不再询问）", true)

    init {
        title = "项目分析配置"
        isModal = true
        defaultCloseOperation = DISPOSE_ON_CLOSE

        val panel = createMainPanel()
        val buttonPanel = createButtonPanel()

        add(panel, BorderLayout.CENTER)
        add(buttonPanel, BorderLayout.SOUTH)

        pack()
        setLocationRelativeTo(null)
        minimumSize = java.awt.Dimension(500, 450)
        isResizable = true
    }

    private fun createMainPanel(): JPanel {
        val panel = JPanel(GridBagLayout())
        val gbc = GridBagConstraints().apply {
            insets = Insets(8, 8, 8, 8)
            anchor = GridBagConstraints.WEST
            fill = GridBagConstraints.HORIZONTAL
        }
        var row = 0

        // 说明文字
        gbc.gridx = 0
        gbc.gridy = row++
        gbc.gridwidth = 2
        gbc.weightx = 1.0
        val descLabel = JLabel("<html><div style='width:450px;'>请提供项目信息以优化扫描效果。<br>如果不确定，可以勾选\"我也不知道\"，系统将穷尽扫描所有可能。</div></html>")
        panel.add(descLabel, gbc)

        // 分隔线
        row = addSeparator(panel, gbc, row)

        // 框架类型
        gbc.gridx = 0
        gbc.gridy = row
        gbc.gridwidth = 1
        gbc.weightx = 0.0
        panel.add(JLabel("框架类型:"), gbc)
        gbc.gridx = 1
        gbc.weightx = 1.0
        panel.add(frameworkTypeCombo, gbc)
        row++

        // 入口包路径
        gbc.gridx = 0
        gbc.gridy = row
        gbc.gridwidth = 1
        gbc.weightx = 0.0
        gbc.anchor = GridBagConstraints.NORTHWEST
        panel.add(JLabel("入口包路径:"), gbc)
        gbc.gridx = 1
        gbc.weightx = 1.0
        gbc.fill = GridBagConstraints.BOTH
        val entryPackagesScrollPane = JScrollPane(entryPackagesField).apply {
            border = UIManager.getBorder("TextField.border")
        }
        panel.add(entryPackagesScrollPane, gbc)
        row++

        // 提示文字
        gbc.gridx = 1
        gbc.gridy = row
        gbc.weightx = 1.0
        gbc.fill = GridBagConstraints.HORIZONTAL
        val entryHintLabel = JLabel("<html><font color='gray'>例如：com.example.controller,com.example.handler<br>留空则扫描所有包</font></html>")
        panel.add(entryHintLabel, gbc)
        row++

        // 自定义注解
        gbc.gridx = 0
        gbc.gridy = row
        gbc.gridwidth = 1
        gbc.weightx = 0.0
        gbc.anchor = GridBagConstraints.WEST
        panel.add(JLabel("自定义注解:"), gbc)
        gbc.gridx = 1
        gbc.weightx = 1.0
        panel.add(customAnnotationsField, gbc)
        row++

        // 提示文字
        gbc.gridx = 1
        gbc.gridy = row++
        gbc.weightx = 1.0
        val annotationsHintLabel = JLabel("<html><font color='gray'>例如：@com.example.CustomApi<br>留空则使用标准注解（@RestController 等）</font></html>")
        panel.add(annotationsHintLabel, gbc)

        // 分隔线
        row = addSeparator(panel, gbc, row)

        // 扫描选项
        gbc.gridx = 0
        gbc.gridy = row++
        gbc.gridwidth = 2
        panel.add(includeDtoScanCheckBox, gbc)

        gbc.gridx = 0
        gbc.gridy = row++
        gbc.gridwidth = 2
        panel.add(includeEntityScanCheckBox, gbc)

        // 分隔线
        row = addSeparator(panel, gbc, row)

        // 穷尽扫描模式
        gbc.gridx = 0
        gbc.gridy = row++
        gbc.gridwidth = 2
        panel.add(exhaustiveScanCheckBox, gbc)

        // 保存配置
        gbc.gridx = 0
        gbc.gridy = row
        gbc.gridwidth = 2
        panel.add(saveConfigCheckBox, gbc)

        return panel
    }

    private fun addSeparator(panel: JPanel, gbc: GridBagConstraints, row: Int): Int {
        gbc.gridx = 0
        gbc.gridy = row
        gbc.gridwidth = 2
        gbc.weightx = 1.0
        gbc.fill = GridBagConstraints.HORIZONTAL
        panel.add(JSeparator(), gbc)
        return row + 1
    }

    private fun createButtonPanel(): JPanel {
        val confirmButton = JButton("开始分析").apply {
            addActionListener { onConfirmClick() }
        }
        val cancelButton = JButton("取消").apply {
            addActionListener { dispose() }
        }

        val buttonWrapper = JPanel(FlowLayout(FlowLayout.RIGHT)).apply {
            add(confirmButton)
            add(cancelButton)
        }

        return JPanel(BorderLayout()).apply {
            add(buttonWrapper, BorderLayout.EAST)
        }
    }

    /**
     * 切换穷尽扫描模式
     */
    private fun toggleExhaustiveMode(enabled: Boolean) {
        // 禁用其他配置项
        frameworkTypeCombo.isEnabled = !enabled
        entryPackagesField.isEnabled = !enabled
        customAnnotationsField.isEnabled = !enabled
        includeDtoScanCheckBox.isEnabled = !enabled
        includeEntityScanCheckBox.isEnabled = !enabled
    }

    private fun onConfirmClick() {
        val config = ProjectAnalysisConfig(
            frameworkType = frameworkTypeCombo.selectedItem as FrameworkType,
            entryPackages = entryPackagesField.text.trim().split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() },
            customAnnotations = customAnnotationsField.text.trim().split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() },
            includeDtoScan = includeDtoScanCheckBox.isSelected,
            includeEntityScan = includeEntityScanCheckBox.isSelected,
            exhaustiveScan = exhaustiveScanCheckBox.isSelected,
            saveConfig = saveConfigCheckBox.isSelected
        )

        // 保存配置
        if (config.saveConfig) {
            storage.lastEntryPackages = entryPackagesField.text.trim()
            storage.lastCustomAnnotations = customAnnotationsField.text.trim()
            storage.skipAnalysisConfigDialog = true
        }

        logger.info("项目分析配置: frameworkType={}, exhaustiveScan={}, entryPackages={}",
            config.frameworkType, config.exhaustiveScan, config.entryPackages)

        dispose()
        onConfirm(config)
    }

    /**
     * 框架类型枚举
     */
    enum class FrameworkType(val displayName: String, val description: String) {
        SPRING_MVC("Spring MVC", "标准 Spring Web 项目"),
        SPRING_BOOT("Spring Boot", "Spring Boot 独立应用"),
        GRPC("gRPC", "基于 gRPC 的 RPC 服务"),
        DUBBO("Dubbo", "基于 Dubbo 的 RPC 服务"),
        OTHER("其他", "其他类型或混合框架");

        override fun toString() = displayName
    }

    /**
     * 项目分析配置
     */
    data class ProjectAnalysisConfig(
        val frameworkType: FrameworkType,
        val entryPackages: List<String>,
        val customAnnotations: List<String>,
        val includeDtoScan: Boolean,
        val includeEntityScan: Boolean,
        val exhaustiveScan: Boolean,
        val saveConfig: Boolean
    )

    companion object {
        /**
         * 显示对话框并返回用户配置
         * @return 用户配置，如果用户取消则返回 null
         */
        fun show(
            project: Project,
            onConfirm: (ProjectAnalysisConfig) -> Unit
        ) {
            val dialog = ProjectAnalysisConfigDialog(project, onConfirm)
            dialog.isVisible = true
        }

        /**
         * 检查是否应该跳过配置对话框
         */
        fun shouldSkipDialog(project: Project): Boolean {
            val storage = project.storageService()
            return storage.skipAnalysisConfigDialog
        }

        /**
         * 获取保存的配置（如果有的话）
         */
        fun getSavedConfig(project: Project): ProjectAnalysisConfig? {
            val storage = project.storageService()
            if (!storage.skipAnalysisConfigDialog) return null

            return ProjectAnalysisConfig(
                frameworkType = FrameworkType.SPRING_BOOT,
                entryPackages = storage.lastEntryPackages.split(",")
                    .map { it.trim() }
                    .filter { it.isNotEmpty() },
                customAnnotations = storage.lastCustomAnnotations.split(",")
                    .map { it.trim() }
                    .filter { it.isNotEmpty() },
                includeDtoScan = true,
                includeEntityScan = true,
                exhaustiveScan = false,
                saveConfig = true
            )
        }
    }
}
