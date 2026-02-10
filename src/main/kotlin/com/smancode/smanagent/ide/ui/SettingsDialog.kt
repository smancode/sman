package com.smancode.smanagent.ide.ui

import com.intellij.openapi.project.Project
import com.smancode.smanagent.config.SmanAgentConfig
import com.smancode.smanagent.ide.components.AnalysisProgressDialog
import com.smancode.smanagent.ide.service.SmanAgentService
import com.smancode.smanagent.ide.service.storageService
import com.smancode.smanagent.analysis.service.ProjectAnalysisService
import com.smancode.smanagent.analysis.model.ProjectAnalysisResult
import com.smancode.smanagent.analysis.model.AnalysisStatus
import org.slf4j.LoggerFactory
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities

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
    private val analysisService = ProjectAnalysisService(project)
    private val analysisScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // HTTP 客户端（复用）
    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }

    // LLM 配置字段
    private val llmApiKeyField = createPasswordFieldWithStorage(storage.llmApiKey)
    private val llmBaseUrlField = createTextFieldWithDefault(
        storage.llmBaseUrl,
        "https://open.bigmodel.cn/api/coding/paas/v4"
    )
    private val llmModelNameField = createTextFieldWithDefault(
        storage.llmModelName,
        "GLM-4.7"
    )

    // BGE-M3 配置字段
    private val bgeEndpointField = createTextFieldWithDefault(
        storage.bgeEndpoint,
        "http://localhost:8000"
    )
    private val bgeApiKeyField = createPasswordFieldWithStorage(storage.bgeApiKey)

    // BGE-Reranker 配置字段
    private val rerankerEndpointField = createTextFieldWithDefault(
        storage.rerankerEndpoint,
        "http://localhost:8001"
    )
    private val rerankerApiKeyField = createPasswordFieldWithStorage(storage.rerankerApiKey)

    // 其他配置字段
    private val projectKeyField = JTextField(project.name, 30)
    private val saveHistoryCheckBox = JCheckBox("保存对话历史", true)

    // 性能配置字段
    private val bgeMaxTokensField = createTextFieldWithDefault(storage.bgeMaxTokens, "8192")
    private val bgeTruncationStrategyCombo = JComboBox(arrayOf("HEAD", "TAIL", "MIDDLE", "SMART")).apply {
        selectedItem = storage.bgeTruncationStrategy.takeIf { it.isNotEmpty() } ?: "TAIL"
    }
    private val bgeTruncationStepSizeField = createTextFieldWithDefault(storage.bgeTruncationStepSize, "1000")
    private val bgeMaxTruncationRetriesField = createTextFieldWithDefault(storage.bgeMaxTruncationRetries, "10")
    private val bgeRetryMaxField = createTextFieldWithDefault(storage.bgeRetryMax, "3")
    private val bgeRetryBaseDelayField = createTextFieldWithDefault(storage.bgeRetryBaseDelay, "1000")
    private val bgeConcurrentLimitField = createTextFieldWithDefault(storage.bgeConcurrentLimit, "3")
    private val bgeCircuitBreakerThresholdField = createTextFieldWithDefault(storage.bgeCircuitBreakerThreshold, "5")

    // RULES 配置字段（多行文本框）
    private val rulesTextArea = JTextArea(storage.rules.takeIf { it.isNotEmpty() } ?: DEFAULT_RULES, 15, 50).apply {
        font = Font(Font.MONOSPACED, Font.PLAIN, 12)
        lineWrap = true
        wrapStyleWord = true
    }

    private fun createTextFieldWithDefault(value: String, default: String): JTextField {
        return JTextField(value.takeIf { it.isNotEmpty() } ?: default, 30)
    }

    init {
        title = "SmanAgent 设置"
        isModal = true
        defaultCloseOperation = DISPOSE_ON_CLOSE

        // 创建主内容面板并包裹在滚动面板中
        val mainContent = createMainPanel()
        val scrollPane = JScrollPane(mainContent).apply {
            verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
            border = null
        }

        val buttonPanel = createButtonPanel()

        add(scrollPane, BorderLayout.CENTER)
        add(buttonPanel, BorderLayout.SOUTH)

        pack()
        setLocationRelativeTo(null)
        minimumSize = java.awt.Dimension(600, 500)
        preferredSize = java.awt.Dimension(600, 600)
        isResizable = true
    }

    private fun createPasswordFieldWithStorage(storedValue: String): JPasswordField {
        return JPasswordField(
            if (storedValue.isNotEmpty()) API_KEY_MASK else "",
            30
        )
    }

    /**
     * 获取密码字段的值
     * 如果字段显示的是掩码，则返回存储的真实值；否则返回字段输入的值
     */
    private fun getPasswordFieldValue(field: JPasswordField, storedValue: String): String {
        val fieldValue = String(field.password)
        // 如果字段值是掩码，使用存储的真实值
        return if (fieldValue == API_KEY_MASK) {
            storedValue
        } else {
            fieldValue
        }
    }

    private fun createMainPanel(): JPanel {
        val panel = JPanel(GridBagLayout())
        val gbc = createGridBagConstraints()
        var row = 0

        row = addAnalysisButtonsSection(panel, gbc, row)
        row = addLlmConfigSection(panel, gbc, row)
        row = addBgeM3ConfigSection(panel, gbc, row)
        row = addRerankerConfigSection(panel, gbc, row)
        row = addPerformanceConfigSection(panel, gbc, row)
        row = addRulesConfigSection(panel, gbc, row)
        addOtherConfigSection(panel, gbc, row)

        return panel
    }

    private fun addAnalysisButtonsSection(panel: JPanel, gbc: GridBagConstraints, startRow: Int): Int {
        var row = startRow

        addSeparator(panel, gbc, row++)

        val buttonPanel = JPanel(FlowLayout(FlowLayout.LEFT)).apply {
            add(JButton("项目分析").apply { addActionListener { onProjectAnalysisClick() } })
            add(JButton("定时分析").apply { isEnabled = false })
            add(JButton("查看分析结果").apply { addActionListener { onShowAnalysisResultsClick() } })
        }

        gbc.gridx = 0
        gbc.gridy = row
        gbc.gridwidth = 2
        gbc.weightx = 0.0
        gbc.fill = GridBagConstraints.HORIZONTAL
        panel.add(buttonPanel, gbc)
        row++

        return row
    }

    private fun addLlmConfigSection(panel: JPanel, gbc: GridBagConstraints, startRow: Int): Int {
        return addConfigSection(
            panel, gbc, startRow,
            title = "LLM API 配置",
            fields = listOf(
                "API Key:" to llmApiKeyField,
                "Base URL:" to llmBaseUrlField,
                "模型名称:" to llmModelNameField
            )
        )
    }

    private fun addBgeM3ConfigSection(panel: JPanel, gbc: GridBagConstraints, startRow: Int): Int {
        return addConfigSection(
            panel, gbc, startRow,
            title = "BGE-M3 向量化配置",
            fields = listOf(
                "端点:" to bgeEndpointField,
                "API Key (可选):" to bgeApiKeyField
            )
        )
    }

    private fun addRerankerConfigSection(panel: JPanel, gbc: GridBagConstraints, startRow: Int): Int {
        return addConfigSection(
            panel, gbc, startRow,
            title = "BGE-Reranker 重排配置",
            fields = listOf(
                "端点:" to rerankerEndpointField,
                "API Key (可选):" to rerankerApiKeyField
            )
        )
    }

    private fun addPerformanceConfigSection(panel: JPanel, gbc: GridBagConstraints, startRow: Int): Int {
        var row = startRow

        addSeparator(panel, gbc, row++)
        addSectionTitle(panel, gbc, row++, "性能配置（并发控制和重试）")

        // Token 配置
        row = addLabeledField(panel, gbc, row, "Token 限制:", bgeMaxTokensField)

        // 截断配置
        gbc.apply {
            gridx = 0
            gridy = row
            gridwidth = 1
            weightx = 0.0
            fill = GridBagConstraints.NONE
        }
        panel.add(JLabel("截断策略:"), gbc)

        gbc.apply {
            gridx = 1
            weightx = 1.0
            fill = GridBagConstraints.HORIZONTAL
        }
        panel.add(bgeTruncationStrategyCombo, gbc)
        row++

        row = addLabeledField(panel, gbc, row, "截断步长:", bgeTruncationStepSizeField)
        row = addLabeledField(panel, gbc, row, "最大截断重试:", bgeMaxTruncationRetriesField)

        // 重试配置
        row = addLabeledField(panel, gbc, row, "最大重试次数:", bgeRetryMaxField)
        row = addLabeledField(panel, gbc, row, "重试基础延迟(ms):", bgeRetryBaseDelayField)

        // 并发和熔断器配置
        row = addLabeledField(panel, gbc, row, "并发限制:", bgeConcurrentLimitField)
        row = addLabeledField(panel, gbc, row, "熔断器阈值:", bgeCircuitBreakerThresholdField)

        // 添加提示标签
        gbc.apply {
            gridx = 0
            gridy = row
            gridwidth = 2
            weightx = 0.0
            fill = GridBagConstraints.HORIZONTAL
        }
        val hintLabel = JLabel("<html><font color='gray' size='2'>提示：并发限制控制同时处理的请求数，熔断器阈值控制连续失败多少次后暂停请求</font></html>")
        panel.add(hintLabel, gbc)
        row++

        return row
    }

    private fun addConfigSection(
        panel: JPanel,
        gbc: GridBagConstraints,
        startRow: Int,
        title: String,
        fields: List<Pair<String, JTextField>>
    ): Int {
        var row = startRow
        addSeparator(panel, gbc, row++)
        addSectionTitle(panel, gbc, row++, title)
        fields.forEach { (label, field) ->
            row = addLabeledField(panel, gbc, row, label, field)
        }
        return row
    }

    private fun addRulesConfigSection(panel: JPanel, gbc: GridBagConstraints, startRow: Int): Int {
        var row = startRow

        addSeparator(panel, gbc, row++)
        addSectionTitle(panel, gbc, row++, "RULES 配置（追加到 System Prompt 后面）")

        // 添加多行文本框（带滚动条）
        gbc.apply {
            gridx = 0
            gridy = row
            gridwidth = 2
            weightx = 1.0
            weighty = 0.0
            fill = GridBagConstraints.HORIZONTAL
        }
        val scrollPane = JScrollPane(rulesTextArea).apply {
            verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED
            preferredSize = java.awt.Dimension(500, 300)
        }
        panel.add(scrollPane, gbc)
        row++

        // 添加提示标签
        gbc.apply {
            gridx = 0
            gridy = row
            gridwidth = 2
            weightx = 0.0
            weighty = 0.0
            fill = GridBagConstraints.HORIZONTAL
        }
        val hintLabel = JLabel("<html><font color='gray' size='2'>提示：这里配置的规则将追加到 System Prompt 后面，用于指导 LLM 的行为模式</font></html>")
        panel.add(hintLabel, gbc)
        row++

        return row
    }

    private fun addOtherConfigSection(panel: JPanel, gbc: GridBagConstraints, startRow: Int): Int {
        var row = startRow

        addSeparator(panel, gbc, row++)
        addSectionTitle(panel, gbc, row++, "其他配置")

        row = addLabeledField(panel, gbc, row, "项目名称:", projectKeyField)

        gbc.gridx = 0
        gbc.gridy = row
        gbc.gridwidth = 2
        gbc.weightx = 0.0
        gbc.fill = GridBagConstraints.NONE
        panel.add(saveHistoryCheckBox, gbc)

        return row
    }

    private fun addSeparator(panel: JPanel, gbc: GridBagConstraints, row: Int) {
        gbc.gridx = 0
        gbc.gridy = row
        gbc.gridwidth = 2
        gbc.weightx = 0.0
        gbc.fill = GridBagConstraints.HORIZONTAL
        panel.add(JSeparator(), gbc)
    }

    private fun addSectionTitle(panel: JPanel, gbc: GridBagConstraints, row: Int, title: String) {
        gbc.gridx = 0
        gbc.gridy = row
        gbc.gridwidth = 2
        panel.add(JLabel("<html><b>$title</b></html>"), gbc)
    }

    private fun addAnalysisButtons(panel: JPanel, gbc: GridBagConstraints): Int {
        val buttonPanel = JPanel(FlowLayout(FlowLayout.LEFT)).apply {
            add(JButton("项目分析").apply { addActionListener { onProjectAnalysisClick() } })
            add(JButton("定时分析").apply { isEnabled = false })
        }

        gbc.gridx = 0
        gbc.gridy = 0
        gbc.gridwidth = 2
        gbc.weightx = 0.0
        gbc.fill = GridBagConstraints.HORIZONTAL
        panel.add(buttonPanel, gbc)

        return 1
    }

    /**
     * 项目分析按钮点击处理 - 后台运行
     */
    private fun onProjectAnalysisClick() {
        logger.info("点击项目分析按钮: projectKey={}", project.name)

        // 先保存配置
        val config = collectConfig()
        if (config == null) {
            return
        }

        saveConfig(config)

        // 检查是否需要显示配置对话框
        if (!ProjectAnalysisConfigDialog.shouldSkipDialog(project)) {
            // 显示配置对话框
            ProjectAnalysisConfigDialog.show(project) { analysisConfig ->
                startAnalysisWithConfig(analysisConfig)
            }
        } else {
            // 使用保存的配置或默认配置
            val savedConfig = ProjectAnalysisConfigDialog.getSavedConfig(project)
            startAnalysisWithConfig(savedConfig ?: ProjectAnalysisConfigDialog.ProjectAnalysisConfig(
                frameworkType = ProjectAnalysisConfigDialog.FrameworkType.SPRING_BOOT,
                entryPackages = emptyList(),
                customAnnotations = emptyList(),
                includeDtoScan = true,
                includeEntityScan = true,
                exhaustiveScan = false,
                saveConfig = false
            ))
        }
    }

    /**
     * 使用指定配置开始分析
     */
    private fun startAnalysisWithConfig(analysisConfig: ProjectAnalysisConfigDialog.ProjectAnalysisConfig) {
        logger.info("开始项目分析: projectKey={}, frameworkType={}, exhaustiveScan={}",
            project.name, analysisConfig.frameworkType, analysisConfig.exhaustiveScan)

        // 后台执行分析
        analysisScope.launch {
            try {
                analysisService.init()

                // TODO: 将 analysisConfig 传递给分析 Pipeline
                // 目前先记录日志，后续需要修改 Pipeline 接受配置参数

                // 检查是否需要分析
                val currentMd5 = com.smancode.smanagent.analysis.util.ProjectHashCalculator.calculate(project)
                val cached = analysisService.getAnalysisResult(forceReload = false)

                val shouldAnalyze = cached == null || cached.projectMd5 != currentMd5

                SwingUtilities.invokeLater {
                    val message = if (shouldAnalyze) {
                        "开始项目分析，将在后台执行"
                    } else {
                        "项目未变化，使用已有分析结果"
                    }
                    showTemporaryMessage(message, delayMs = 3000)
                }

                if (shouldAnalyze) {
                    analysisService.executeAnalysis(null)
                    logger.info("项目分析完成: projectKey={}", project.name)
                } else {
                    logger.info("跳过分析，使用缓存结果: projectKey={}", project.name)
                }
            } catch (e: Exception) {
                logger.error("项目分析失败", e)
            }
        }
    }

    /**
     * 查看分析结果按钮点击处理
     */
    private fun onShowAnalysisResultsClick() {
        logger.info("点击查看分析结果按钮: projectKey={}", project.name)

        analysisScope.launch {
            try {
                val result = analysisService.getAnalysisResult(forceReload = true)

                SwingUtilities.invokeLater {
                    if (result == null) {
                        JOptionPane.showMessageDialog(
                            this@SettingsDialog,
                            "暂无分析结果，请先执行项目分析",
                            "提示",
                            JOptionPane.INFORMATION_MESSAGE
                        )
                    } else {
                        showAnalysisResultsDetail(result)
                    }
                }
            } catch (e: Exception) {
                logger.error("获取分析结果失败", e)
                SwingUtilities.invokeLater {
                    showError("获取分析结果失败：${e.message}")
                }
            }
        }
    }

    private fun createProgressCallback(dialog: AnalysisProgressDialog) = object : com.smancode.smanagent.analysis.pipeline.ProjectAnalysisPipeline.ProgressCallback {
        override fun onStepStart(stepName: String, description: String) {
            SwingUtilities.invokeLater { dialog.onStepStart(stepName, description) }
        }

        override fun onStepComplete(stepName: String, result: com.smancode.smanagent.analysis.model.StepResult) {
            SwingUtilities.invokeLater { dialog.onStepComplete(stepName, result) }
        }

        override fun onStepFailed(stepName: String, error: String) {
            SwingUtilities.invokeLater { dialog.onStepFailed(stepName, error) }
        }
    }

    /**
     * 显示分析结果详情
     */
    private fun showAnalysisResultsDetail(result: ProjectAnalysisResult) {
        val duration = result.endTime?.let { (it - result.startTime) / 1000 }?.let { "${it}秒" } ?: "进行中"

        val message = buildString {
            append("═══════════════════════════════════════\n")
            append("          项目分析结果详情\n")
            append("═══════════════════════════════════════\n\n")
            append("项目: ${result.projectKey}\n")
            append("状态: ${result.status.text}\n")
            append("耗时: $duration\n")
            append("开始时间: ${java.util.Date(result.startTime)}\n")
            result.endTime?.let { append("结束时间: ${java.util.Date(it)}\n") }
            append("\n")

            append("───────────────────────────────────────\n")
            append("步骤状态 (${result.steps.size}/${result.steps.size})\n")
            append("───────────────────────────────────────\n\n")

            result.steps.forEach { (stepName, stepResult) ->
                val statusIcon = when (stepResult.status) {
                    com.smancode.smanagent.analysis.model.StepStatus.COMPLETED -> "✅"
                    com.smancode.smanagent.analysis.model.StepStatus.RUNNING -> "⏳"
                    com.smancode.smanagent.analysis.model.StepStatus.FAILED -> "❌"
                    com.smancode.smanagent.analysis.model.StepStatus.SKIPPED -> "⏭️"
                    else -> "⏸️"
                }

                append("$statusIcon ${stepResult.stepDescription}\n")

                // 显示耗时
                stepResult.getDuration()?.let { append("   耗时: ${it}ms\n") }

                // 显示数据量
                stepResult.data?.let { data ->
                    try {
                        val mapper = com.fasterxml.jackson.module.kotlin.jacksonObjectMapper()

                        // 尝试解析为 Map
                        try {
                            val map = mapper.readValue(data, object : com.fasterxml.jackson.core.type.TypeReference<Map<String, Any>>() {})
                            displayMapData(map, this)
                        } catch (e: Exception) {
                            // 尝试解析为 List
                            try {
                                val list = mapper.readValue(data, object : com.fasterxml.jackson.core.type.TypeReference<List<Any>>() {})
                                append("   数据项: ${list.size} 个\n")
                                // 显示前几个条目
                                list.take(3).forEach { item ->
                                    when (item) {
                                        is Map<*, *> -> {
                                            val name = item["name"] ?: item["qualifiedName"] ?: item["className"]
                                            append("   - $name\n")
                                        }
                                        else -> append("   - $item\n")
                                    }
                                }
                                if (list.size > 3) {
                                    append("   - ... (共 ${list.size} 项)\n")
                                }
                            } catch (e2: Exception) {
                                // 解析失败，显示原始数据
                                showDataPreview(data, this)
                            }
                        }
                    } catch (e: Exception) {
                        showDataPreview(data, this)
                    }
                }

                // 显示错误
                stepResult.error?.let { append("   ❌ 错误: $it\n") }

                append("\n")
            }
        }

        // 使用带滚动条的文本区域显示分析结果
        showScrollableTextDialog("分析结果详情", message, result.status.messageType)
    }

    /**
     * 显示带滚动条的文本对话框
     */
    private fun showScrollableTextDialog(title: String, content: String, messageType: Int) {
        val textArea = JTextArea(content).apply {
            isEditable = false
            font = java.awt.Font(Font.MONOSPACED, Font.PLAIN, 12)
            lineWrap = false
            wrapStyleWord = true
            tabSize = 2
        }

        val scrollPane = JScrollPane(textArea).apply {
            preferredSize = java.awt.Dimension(700, 500)
            verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED
        }

        JOptionPane.showMessageDialog(
            this,
            scrollPane,
            title,
            messageType
        )
    }

    /**
     * 显示分析总结
     */

    /**
     * 显示分析总结
     */
    private fun showAnalysisSummary(result: ProjectAnalysisResult) {
        val completedSteps = result.steps.values.count { it.status == com.smancode.smanagent.analysis.model.StepStatus.COMPLETED }
        val duration = result.endTime?.let { (it - result.startTime) / 1000 }?.let { "${it}秒" } ?: "未知"

        val message = buildString {
            append("项目分析完成！\n\n")
            append("项目: ${result.projectKey}\n")
            append("状态: ${result.status.text}\n")
            append("耗时: $duration\n")
            append("步骤: $completedSteps/${result.steps.size} 完成\n\n")

            append("步骤详情:\n")
            result.steps.forEach { (_, stepResult) ->
                append("  ${stepResult.status.icon} ${stepResult.stepDescription}")
                stepResult.error?.let { append(" - $it") }
                append("\n")
            }
        }

        JOptionPane.showMessageDialog(this, message, "分析结果", result.status.messageType)
    }

    /**
     * 显示 Map 类型的数据
     */
    private fun displayMapData(map: Map<String, Any>, sb: StringBuilder) {
        map.forEach { (key, value) ->
            when (value) {
                is List<*> -> {
                    val size = value.size
                    sb.append("   $key: $size 项\n")
                    // 显示前几个条目
                    if (key == "enums" || key == "entities" || key == "externalApis" || key == "classes") {
                        value.take(3).forEach { item ->
                            when (item) {
                                is Map<*, *> -> {
                                    val name = item["name"] ?: item["qualifiedName"]
                                    sb.append("     - $name\n")
                                }
                                is String -> sb.append("     - $item\n")
                            }
                        }
                        if (size > 3) {
                            sb.append("     - ... (共 $size 项)\n")
                        }
                    }
                }
                is Map<*, *> -> {
                    // 处理嵌套 Map
                    val nestedMap = value as Map<*, *>
                    nestedMap.forEach { (nestedKey, nestedValue) ->
                        when (nestedValue) {
                            is List<*> -> {
                                val size = nestedValue.size
                                sb.append("   $key.$nestedKey: $size 项\n")
                            }
                            is Number -> sb.append("   $key.$nestedKey: $nestedValue\n")
                            is String -> sb.append("   $key.$nestedKey: $nestedValue\n")
                            else -> sb.append("   $key.$nestedKey: $nestedValue\n")
                        }
                    }
                }
                is Number -> sb.append("   $key: $value\n")
                is String -> sb.append("   $key: $value\n")
                else -> sb.append("   $key: $value\n")
            }
        }
    }

    /**
     * 显示数据预览（当解析失败时）
     */
    private fun showDataPreview(data: String, sb: StringBuilder) {
        val lines = data.lines().take(10)
        sb.append("   数据预览:\n")
        lines.forEach { line ->
            if (line.isNotEmpty()) {
                sb.append("   $line\n")
            }
        }
        if (data.lines().size > 10) {
            sb.append("   ...\n")
        }
    }

    private val AnalysisStatus.text: String
        get() = when (this) {
            AnalysisStatus.COMPLETED -> "全部完成"
            AnalysisStatus.PARTIAL -> "部分完成"
            AnalysisStatus.FAILED -> "分析失败"
            else -> "未知状态"
        }

    private val AnalysisStatus.messageType: Int
        get() = when (this) {
            AnalysisStatus.COMPLETED -> JOptionPane.INFORMATION_MESSAGE
            AnalysisStatus.PARTIAL -> JOptionPane.WARNING_MESSAGE
            else -> JOptionPane.ERROR_MESSAGE
        }

    private val com.smancode.smanagent.analysis.model.StepStatus.icon: String
        get() = when (this) {
            com.smancode.smanagent.analysis.model.StepStatus.COMPLETED -> "✓"
            com.smancode.smanagent.analysis.model.StepStatus.FAILED -> "✗"
            com.smancode.smanagent.analysis.model.StepStatus.SKIPPED -> "⊘"
            else -> "⏸"
        }

    private fun createGridBagConstraints() = GridBagConstraints().apply {
        insets = Insets(5, 5, 5, 5)
        anchor = GridBagConstraints.WEST
    }

    private fun addLabeledField(panel: JPanel, gbc: GridBagConstraints, row: Int, label: String, field: JTextField): Int {
        gbc.apply {
            gridx = 0
            gridy = row
            gridwidth = 1
            weightx = 0.0
            fill = GridBagConstraints.NONE
        }
        panel.add(JLabel(label), gbc)

        gbc.apply {
            gridx = 1
            weightx = 1.0
            fill = GridBagConstraints.HORIZONTAL
        }
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

        // 保存配置（即使测试失败也保存）
        saveConfig(config)

        // 显示进度对话框
        val progressLabel = JLabel("正在测试服务可用性，请稍候...")
        val progressPane = JOptionPane(progressLabel, JOptionPane.INFORMATION_MESSAGE)
        val dialog = progressPane.createDialog(this, "测试中")
        dialog.isModal = false
        dialog.isVisible = true

        // 在后台线程测试服务
        Thread {
            val testResults = testServices(config)

            // 在 EDT 线程更新 UI
            SwingUtilities.invokeLater {
                dialog.dispose()
                showResultMessage(config, testResults)
                dispose()
            }
        }.start()
    }

    /**
     * 测试服务可用性（并发测试，超时 TEST_TIMEOUT_MS 毫秒）
     */
    private fun testServices(config: ConfigData): ServiceTestResults {
        val results = ServiceTestResults()

        // 并发测试所有服务
        val llmThread = Thread { results.llmResult = testLlmService(config) }
        val bgeThread = Thread { results.bgeM3Result = testBgeM3Service(config) }
        val rerankerThread = Thread { results.rerankerResult = testRerankerService(config) }

        llmThread.start()
        bgeThread.start()
        rerankerThread.start()

        // 等待所有测试完成（最多 TEST_TIMEOUT_MS 毫秒）
        llmThread.join(TEST_TIMEOUT_MS)
        bgeThread.join(TEST_TIMEOUT_MS)
        rerankerThread.join(TEST_TIMEOUT_MS)

        return results
    }

    private fun testLlmService(config: ConfigData): ServiceTestResult {
        val request = Request.Builder()
            .url("${config.llmBaseUrl}/chat/completions")
            .addHeader("Authorization", "Bearer ${config.llmApiKey}")
            .post(createLlmTestPayload(config.llmModelName))
            .build()
        return executeServiceTest(request)
    }

    private fun testBgeM3Service(config: ConfigData): ServiceTestResult {
        val request = Request.Builder()
            .url("${config.bgeEndpoint}/v1/embeddings")
            .post(createBgeTestPayload())
            .build()
        return executeServiceTest(request)
    }

    private fun testRerankerService(config: ConfigData): ServiceTestResult {
        val request = Request.Builder()
            .url("${config.rerankerEndpoint}/rerank")
            .post(createRerankerTestPayload())
            .build()
        return executeServiceTest(request)
    }

    /**
     * 通用服务测试执行方法
     */
    private fun executeServiceTest(request: Request): ServiceTestResult {
        return try {
            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    ServiceTestResult(success = true, message = SUCCESS_MESSAGE)
                } else {
                    ServiceTestResult(success = false, message = "$ERROR_HTTP_PREFIX ${response.code}")
                }
            }
        } catch (e: Exception) {
            ServiceTestResult(success = false, message = "$ERROR_PREFIX ${e.message?.take(50) ?: CONNECTION_FAILED_MESSAGE}")
        }
    }

    private fun createLlmTestPayload(modelName: String) = """
        {
            "model": "$modelName",
            "messages": [{"role": "user", "content": "$LLM_TEST_MESSAGE"}],
            "max_tokens": 1
        }
    """.trimIndent().toRequestBody(JSON_MEDIA_TYPE)

    private fun createBgeTestPayload() = """
        {
            "input": "$BGE_TEST_INPUT",
            "model": "$BGE_MODEL"
        }
    """.trimIndent().toRequestBody(JSON_MEDIA_TYPE)

    private fun createRerankerTestPayload() = """
        {
            "model": "$RERANKER_MODEL",
            "query": "$RERANKER_QUERY",
            "documents": ["$RERANKER_DOCUMENT"],
            "top_k": $RERANKER_TOP_K
        }
    """.trimIndent().toRequestBody(JSON_MEDIA_TYPE)

    /**
     * 收集并验证配置
     * @return 配置对象，验证失败返回 null
     */
    private fun collectConfig(): ConfigData? {
        val llmApiKey = getPasswordFieldValue(llmApiKeyField, storage.llmApiKey).trim()
        val llmBaseUrl = llmBaseUrlField.text.trim()
        val llmModelName = llmModelNameField.text.trim()
        val bgeEndpoint = bgeEndpointField.text.trim()
        val bgeApiKey = getPasswordFieldValue(bgeApiKeyField, storage.bgeApiKey).trim()
        val rerankerEndpoint = rerankerEndpointField.text.trim()
        val rerankerApiKey = getPasswordFieldValue(rerankerApiKeyField, storage.rerankerApiKey).trim()
        val projectKey = projectKeyField.text.trim()

        // 性能配置
        val bgeMaxTokens = bgeMaxTokensField.text.trim()
        val bgeTruncationStrategy = bgeTruncationStrategyCombo.selectedItem as String
        val bgeTruncationStepSize = bgeTruncationStepSizeField.text.trim()
        val bgeMaxTruncationRetries = bgeMaxTruncationRetriesField.text.trim()
        val bgeRetryMax = bgeRetryMaxField.text.trim()
        val bgeRetryBaseDelay = bgeRetryBaseDelayField.text.trim()
        val bgeConcurrentLimit = bgeConcurrentLimitField.text.trim()
        val bgeCircuitBreakerThreshold = bgeCircuitBreakerThresholdField.text.trim()

        // RULES 配置
        val rules = rulesTextArea.text.trim()

        // 验证必填字段
        if (!validateRequiredFields(llmBaseUrl, "LLM Base URL") ||
            !validateRequiredFields(llmModelName, "LLM 模型名称") ||
            !validateRequiredFields(bgeEndpoint, "BGE-M3 端点") ||
            !validateRequiredFields(rerankerEndpoint, "Reranker 端点") ||
            !validateRequiredFields(projectKey, "项目名称")) {
            return null
        }

        // 验证性能配置数值字段
        if (!validateNumericField(bgeMaxTokens, "Token 限制", 1, 8192) ||
            !validateNumericField(bgeTruncationStepSize, "截断步长", 100, 5000) ||
            !validateNumericField(bgeMaxTruncationRetries, "最大截断重试", 1, 20) ||
            !validateNumericField(bgeRetryMax, "最大重试次数", 0, 10) ||
            !validateNumericField(bgeRetryBaseDelay, "重试基础延迟", 100, 60000) ||
            !validateNumericField(bgeConcurrentLimit, "并发限制", 1, 16) ||
            !validateNumericField(bgeCircuitBreakerThreshold, "熔断器阈值", 1, 20)) {
            return null
        }

        return ConfigData(
            llmApiKey, llmBaseUrl, llmModelName,
            bgeEndpoint, bgeApiKey, rerankerEndpoint, rerankerApiKey, projectKey,
            bgeMaxTokens, bgeTruncationStrategy, bgeTruncationStepSize, bgeMaxTruncationRetries,
            bgeRetryMax, bgeRetryBaseDelay, bgeConcurrentLimit, bgeCircuitBreakerThreshold,
            rules
        )
    }

    private fun validateRequiredFields(value: String, fieldName: String): Boolean {
        if (value.isEmpty()) {
            showError("$fieldName 不能为空！")
            return false
        }
        return true
    }

    private fun validateNumericField(value: String, fieldName: String, min: Int, max: Int): Boolean {
        val num = value.toIntOrNull()
        if (num == null) {
            showError("$fieldName 必须是有效的数字！")
            return false
        }
        if (num < min || num > max) {
            showError("$fieldName 必须在 $min 到 $max 之间！")
            return false
        }
        return true
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

        // 性能配置
        storage.bgeMaxTokens = config.bgeMaxTokens
        storage.bgeTruncationStrategy = config.bgeTruncationStrategy
        storage.bgeTruncationStepSize = config.bgeTruncationStepSize
        storage.bgeMaxTruncationRetries = config.bgeMaxTruncationRetries
        storage.bgeRetryMax = config.bgeRetryMax
        storage.bgeRetryBaseDelay = config.bgeRetryBaseDelay
        storage.bgeConcurrentLimit = config.bgeConcurrentLimit
        storage.bgeCircuitBreakerThreshold = config.bgeCircuitBreakerThreshold

        // RULES 配置
        storage.rules = config.rules

        // 更新配置到 SmanAgentConfig（下次 LLM/BGE 调用会使用新配置）
        val userConfig = SmanAgentConfig.UserConfig(
            llmApiKey = storage.llmApiKey,
            llmBaseUrl = storage.llmBaseUrl,
            llmModelName = storage.llmModelName,
            // BGE 性能配置
            bgeMaxTokens = storage.bgeMaxTokens,
            bgeTruncationStrategy = storage.bgeTruncationStrategy,
            bgeTruncationStepSize = storage.bgeTruncationStepSize,
            bgeMaxTruncationRetries = storage.bgeMaxTruncationRetries,
            bgeRetryMax = storage.bgeRetryMax,
            bgeRetryBaseDelay = storage.bgeRetryBaseDelay,
            bgeConcurrentLimit = storage.bgeConcurrentLimit,
            bgeCircuitBreakerThreshold = storage.bgeCircuitBreakerThreshold
        )
        SmanAgentConfig.setUserConfig(userConfig)
    }

    /**
     * 显示结果消息（包含测试结果）
     */
    private fun showResultMessage(config: ConfigData, testResults: ServiceTestResults) {
        val message = buildResultMessage(config, testResults)
        val messageType = if (testResults.allSuccess()) JOptionPane.INFORMATION_MESSAGE else JOptionPane.WARNING_MESSAGE
        JOptionPane.showMessageDialog(this, message, "保存结果", messageType)
    }

    private fun buildResultMessage(config: ConfigData, testResults: ServiceTestResults) = """
        设置已保存！

        服务可用性测试结果:
        ━━━━━━━━━━━━━━━━━━━━━━━━━━━━

        【LLM API】
          Base URL: ${config.llmBaseUrl}
          模型: ${config.llmModelName}
          状态: ${testResults.llmResult.message}

        【BGE-M3 向量化】
          端点: ${config.bgeEndpoint}
          状态: ${testResults.bgeM3Result.message}

        【BGE-Reranker 重排】
          端点: ${config.rerankerEndpoint}
          状态: ${testResults.rerankerResult.message}

        ━━━━━━━━━━━━━━━━━━━━━━━━━━━━

        提示：
        - ✓ 表示服务可用
        - ✗ 表示服务不可用，请检查端点是否正确
        - 配置已保存，即使服务不可用也会保存
    """.trimIndent()

    /**
     * 显示错误消息
     */
    private fun showError(message: String) {
        JOptionPane.showMessageDialog(this, message, "错误", JOptionPane.ERROR_MESSAGE)
    }

    /**
     * 显示临时提示消息（自动关闭）
     */
    private fun showTemporaryMessage(message: String, delayMs: Int = 3000) {
        val dialog = JDialog(this@SettingsDialog, "提示", false)
        val label = JLabel(message, SwingConstants.CENTER)
        label.border = javax.swing.BorderFactory.createEmptyBorder(20, 20, 20, 20)
        dialog.contentPane.add(label)
        dialog.pack()
        dialog.setLocationRelativeTo(this@SettingsDialog)

        javax.swing.Timer(delayMs) { _ ->
            dialog.dispose()
        }.apply { isRepeats = false }.start()

        dialog.isVisible = true
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
        val projectKey: String,
        // 性能配置
        val bgeMaxTokens: String,
        val bgeTruncationStrategy: String,
        val bgeTruncationStepSize: String,
        val bgeMaxTruncationRetries: String,
        val bgeRetryMax: String,
        val bgeRetryBaseDelay: String,
        val bgeConcurrentLimit: String,
        val bgeCircuitBreakerThreshold: String,
        // RULES 配置
        val rules: String
    )

    /**
     * 服务测试结果
     */
    private data class ServiceTestResults(
        var llmResult: ServiceTestResult = ServiceTestResult(),
        var bgeM3Result: ServiceTestResult = ServiceTestResult(),
        var rerankerResult: ServiceTestResult = ServiceTestResult()
    ) {
        fun allSuccess(): Boolean = llmResult.success && bgeM3Result.success && rerankerResult.success
    }

    /**
     * 单个服务测试结果
     */
    private data class ServiceTestResult(
        val success: Boolean = false,
        val message: String = ""
    )

    companion object {
        // 测试配置
        private const val CONNECT_TIMEOUT_SECONDS = 5L
        private const val READ_TIMEOUT_SECONDS = 10L
        private const val TEST_TIMEOUT_MS = 10000L

        // 测试数据
        private const val LLM_TEST_MESSAGE = "hi"
        private const val BGE_TEST_INPUT = "test"
        private const val RERANKER_QUERY = "test"
        private const val RERANKER_DOCUMENT = "test document"
        private const val RERANKER_TOP_K = 1

        // 测试模型
        private const val BGE_MODEL = "BAAI/bge-m3"
        private const val RERANKER_MODEL = "BAAI/bge-reranker-v2-m3"

        // UI 消息常量
        private const val API_KEY_MASK = "****"
        private const val SUCCESS_MESSAGE = "✓ 连接成功"
        private const val ERROR_HTTP_PREFIX = "✗ HTTP"
        private const val ERROR_PREFIX = "✗"
        private const val CONNECTION_FAILED_MESSAGE = "连接失败"

        // 默认 RULES
        private const val DEFAULT_RULES = """## 🔄 三阶段工作流 (The Workflow)

### 1️⃣ 阶段一：深度分析 (Analyze)
**回答声明**：`【分析问题】`

**目标**：在动手之前，先确保"做正确的事"。

**必须执行的动作**：
1.  **全景扫描**：搜索并阅读所有相关文件，建立上下文。
2.  **领域对齐 (DDD Lite)**：
    *   确认本次修改涉及的核心业务名词（Ubiquitous Language）定义是否一致。
    *   检查是否破坏了现有的业务不变量（Invariants）。
3.  **根因分析**：从底层逻辑推导问题本质，而非仅修复表面报错。
4.  **方案构思**：提供 1~3 个解决方案。
    *   每个方案需评估：复杂度、副作用、技术债务风险。
    *   如果方案与用户目标冲突，必须直言相告。

**🚫 禁止**：写任何实现代码、急于给出最终方案。
---

### 2️⃣ 阶段二：方案蓝图 (Plan)
**回答声明**：`【制定方案】`

**前置条件**：用户已明确选择或确认了一个方案。

**目标**：将模糊的需求转化为精确的施工图纸 (SDD + TDD)。

**必须执行的动作**：
1.  **契约定义 (Spec-First)**：
    *   如果涉及数据结构变更，**必须**先列出修改后的 Interface/Type 定义。
    *   如果涉及 API 变更，**必须**先列出函数签名。
2.  **验证策略 (Test Plan)**：
    *   列出 3-5 个关键测试场景（包含 Happy Path 和 边缘情况）。
    *   *示例：* "验证当库存不足时，抛出 `InsufficientStockError` 而不是返回 false。"
3.  **文件变更清单**：
    *   列出所有受影响的文件及简要修改逻辑。

**🚫 禁止**：使用硬编码、模糊的描述。

---

### 3️⃣ 阶段三：稳健执行 (Execute)
**回答声明**：`【执行方案】`

**前置条件**：用户已确认方案蓝图。

**目标**：高质量、无坏味道地实现代码。

**必须执行的动作**：
1.  **分步实现**：严格按照既定方案编码，不要夹带私货。
2.  **代码优化**：使用 Task 工具调用 code-simplifier agent 优化代码。
    *   调用格式：`Use the Task tool to launch the code-simplifier agent to refine the implementation`
    *   等待 code-simplifier 完成后再继续
3.  **自我审查 (Self-Review)**：
    *   检查是否引入了新的"坏味道"（见下文）。
    *   检查是否破坏了单一职责原则。
4.  **验证闭环**：
    *   自动运行或编写对应的测试代码，证明代码是工作的。
    *   如果无法运行测试，请提供手动验证的步骤。

**🚫 禁止**：提交未经验证的代码、随意添加非给定内容。

---

## 📏 代码质量公约 (Code Quality Covenant)

### 🧱 物理约束 (必须遵守)
1.  **单一职责**：一个文件只做一件事。如果一个文件既做 UI 又做逻辑，必须拆分。
2.  **行数熔断**：
    *   动态语言 (JS/TS/Py)：单文件上限 **300 行**。
    *   静态语言 (Java/Go)：单文件上限 **500 行**。
    *   *超过限制必须重构拆分，无例外。*
3.  **目录结构**：单文件夹内文件不超过 **8 个**，超过则建立子目录归档。

### ☠️ 必须根除的"坏味道" (Bad Smells)
一旦发现以下迹象，必须在【阶段一】或【阶段二】提出重构建议：

1.  **僵化 (Rigidity)**：改一个地方需要改动很多关联文件。（解法：依赖倒置）
2.  **脆弱 (Fragility)**：改动这里导致无关的地方报错。（解法：解耦、高内聚）
3.  **重复 (DRY Violation)**：同样的逻辑复制粘贴。（解法：提取公共函数/组合模式）
4.  **数据泥团 (Data Clumps)**：总是结伴出现的参数列表。（解法：封装为 Value Object）
5.  **基本类型偏执 (Primitive Obsession)**：用字符串/数字代表复杂的业务概念。（解法：使用 Enum 或专用类型）

---

## ⚠️ 每次回复前的自我检查清单

```text
[ ] 我是否声明了当前所处的阶段？
[ ] (如果是阶段一) 我是否检查了业务名词和领域边界？
[ ] (如果是阶段二) 我是否列出了 Interface 定义和测试用例？
[ ] (如果是阶段三) 我是否遵守了 300/500 行限制？
[ ] 我是否在等待用户的确认指令？
```

---

"""

        // JSON 媒体类型（复用）
        private val JSON_MEDIA_TYPE = "application/json".toMediaTypeOrNull()

        fun show(project: Project) {
            val dialog = SettingsDialog(project)
            dialog.isVisible = true
        }
    }
}
