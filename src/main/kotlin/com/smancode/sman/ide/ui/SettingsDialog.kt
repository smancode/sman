package com.smancode.sman.ide.ui

import com.intellij.openapi.project.Project
import com.smancode.sman.config.SmanConfig
import com.smancode.sman.ide.SmanPlugin
import com.smancode.sman.ide.service.storageService
import org.slf4j.LoggerFactory
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.Color
import java.awt.Dimension
import java.awt.Cursor
import java.awt.event.ActionEvent
import javax.swing.*
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
 * - 自动分析开关
 * - 项目名称
 * - 保存对话历史
 * - 分析结果
 */
class SettingsDialog(
    private val project: Project,
    private val onAnalysisResultsCallback: (() -> Unit)? = null
) : JDialog() {

    private val logger = LoggerFactory.getLogger(SettingsDialog::class.java)
    private val storage = project.storageService()

    // HTTP 客户端（复用）
    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }

    // LLM 配置字段
    private val llmApiKeyField = createTextFieldWithStorage(storage.llmApiKey)
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
    private val bgeApiKeyField = createTextFieldWithStorage(storage.bgeApiKey)

    // BGE-Reranker 配置字段
    private val rerankerEndpointField = createTextFieldWithDefault(
        storage.rerankerEndpoint,
        "http://localhost:8001"
    )
    private val rerankerApiKeyField = createTextFieldWithStorage(storage.rerankerApiKey)

    // 其他配置字段
    private val saveHistoryCheckBox = JCheckBox("保存对话历史", true)

    // 自动分析开关（现代扁平化风格）
    private val autoAnalysisSwitch = object : JToggleButton() {
        // 动画相关
        private var animationProgress = if (storage.autoAnalysisEnabled) 1.0 else 0.0
        private val animationTimer = javax.swing.Timer(15, null)

        init {
            isSelected = storage.autoAnalysisEnabled
            isContentAreaFilled = false
            isOpaque = false
            isBorderPainted = false
            isFocusPainted = false
            border = null
            preferredSize = Dimension(48, 26)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)

            // 动画计时器
            animationTimer.addActionListener {
                val target = if (isSelected) 1.0 else 0.0
                val diff = target - animationProgress
                if (kotlin.math.abs(diff) < 0.05) {
                    animationProgress = target
                    animationTimer.stop()
                } else {
                    animationProgress += diff * 0.25
                }
                repaint()
            }

            addItemListener { e ->
                storage.autoAnalysisEnabled = (e.source as JToggleButton).isSelected
                logger.debug("自动分析开关状态已更新: {}", storage.autoAnalysisEnabled)
                animationTimer.start()
            }
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g as Graphics2D

            // 高质量渲染设置
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
            g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)

            val switchWidth = 44
            val switchHeight = 24
            val switchX = (width - switchWidth) / 2
            val switchY = (height - switchHeight) / 2
            val cornerRadius = switchHeight / 2

            // 计算颜色插值
            val onColor = Color(52, 199, 89)      // iOS 绿色
            val offColor = Color(229, 229, 234)  // 浅灰色
            val trackColor = interpolateColor(offColor, onColor, animationProgress)

            // 绘制阴影（增加立体感）
            g2.color = Color(0, 0, 0, 15)
            g2.fillRoundRect(switchX + 1, switchY + 2, switchWidth, switchHeight, cornerRadius, cornerRadius)

            // 绘制背景轨道
            g2.color = trackColor
            g2.fillRoundRect(switchX, switchY, switchWidth, switchHeight, cornerRadius, cornerRadius)

            // 绘制滑块
            val sliderSize = 20
            val sliderPadding = 2
            val sliderRange = switchWidth - sliderSize - sliderPadding * 2
            val sliderX = switchX + sliderPadding + (sliderRange * animationProgress).toInt()
            val sliderY = switchY + (switchHeight - sliderSize) / 2

            // 滑块阴影
            g2.color = Color(0, 0, 0, 30)
            g2.fillOval(sliderX + 1, sliderY + 1, sliderSize, sliderSize)

            // 滑块本体
            g2.color = Color.WHITE
            g2.fillOval(sliderX, sliderY, sliderSize, sliderSize)
        }

        private fun interpolateColor(c1: Color, c2: Color, fraction: Double): Color {
            val r = (c1.red + (c2.red - c1.red) * fraction).toInt()
            val g = (c1.green + (c2.green - c1.green) * fraction).toInt()
            val b = (c1.blue + (c2.blue - c1.blue) * fraction).toInt()
            return Color(r.coerceIn(0, 255), g.coerceIn(0, 255), b.coerceIn(0, 255))
        }
    }

    private fun createTextFieldWithDefault(value: String, default: String): JTextField {
        return JTextField(value.takeIf { it.isNotEmpty() } ?: default, 30)
    }

    init {
        title = "Sman 设置"
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
        minimumSize = java.awt.Dimension(480, 500)
        preferredSize = java.awt.Dimension(480, 550)
        isResizable = true
    }

    /**
     * 创建 API Key 文本框（明文存储和显示）
     */
    private fun createTextFieldWithStorage(storedValue: String): JTextField {
        return JTextField(storedValue, 30)
    }

    /**
     * 获取 API Key 字段的值（明文）
     */
    private fun getApiKeyFieldValue(field: JTextField): String {
        return field.text.trim()
    }

    private fun createMainPanel(): JPanel {
        val panel = JPanel(GridBagLayout())
        val gbc = createGridBagConstraints()
        var row = 0

        row = addAnalysisButtonsSection(panel, gbc, row)
        row = addLlmConfigSection(panel, gbc, row)
        row = addBgeM3ConfigSection(panel, gbc, row)
        row = addRerankerConfigSection(panel, gbc, row)
        addOtherConfigSection(panel, gbc, row)

        return panel
    }

    private fun addAnalysisButtonsSection(panel: JPanel, gbc: GridBagConstraints, startRow: Int): Int {
        var row = startRow

        addSeparator(panel, gbc, row++)

        // 自动分析区域：标签 + 开关 + 分析结果按钮（同一行）
        val buttonPanel = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
            add(JLabel("自动分析:"))
            add(autoAnalysisSwitch)
            add(JLabel("(后台每 5 分钟扫描)"))

            // 添加水平间距
            add(Box.createHorizontalStrut(30))

            // 分析结果按钮（如果有回调）
            if (onAnalysisResultsCallback != null) {
                add(JButton("分析结果").apply {
                    toolTipText = "查看项目分析结果状态"
                    addActionListener {
                        onAnalysisResultsCallback?.invoke()
                    }
                })
            }
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

    /**
     * 创建微信风格开关按钮
     */
    private fun createSwitchButton(): JToggleButton {
        return object : JToggleButton() {
            init {
                isSelected = storage.autoAnalysisEnabled
                isContentAreaFilled = false
                isOpaque = false
                isBorderPainted = false
                isFocusPainted = false
                border = null
                preferredSize = Dimension(50, 26)
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)

                addItemListener { e ->
                    storage.autoAnalysisEnabled = (e.source as JToggleButton).isSelected
                    logger.debug("自动分析开关状态已更新: {}", storage.autoAnalysisEnabled)
                }
            }

            override fun paintComponent(g: Graphics) {
                val g2 = g as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

                val switchWidth = 44
                val switchHeight = 22
                val switchX = 0
                val switchY = (height - switchHeight) / 2

                // 绘制背景轨道（圆角矩形）
                val trackColor = if (isSelected) Color(76, 217, 100) else Color(200, 200, 200)
                g2.color = trackColor
                g2.fillRoundRect(switchX, switchY, switchWidth, switchHeight, 11, 11)

                // 绘制滑块（圆形）
                val sliderSize = 18
                val sliderX = if (isSelected) switchX + switchWidth - sliderSize - 3 else switchX + 3
                val sliderColor = Color.WHITE
                g2.color = sliderColor
                g2.fillOval(sliderX, switchY + 2, sliderSize, sliderSize - 4)
            }
        }
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

    private fun addOtherConfigSection(panel: JPanel, gbc: GridBagConstraints, startRow: Int): Int {
        var row = startRow

        addSeparator(panel, gbc, row++)
        addSectionTitle(panel, gbc, row++, "其他配置")

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

        // RULES 配置提示
        val rulesHintLabel = JLabel("""<html><body style='width: 280px; font-size: 11px; color: #666;'>
            <b>自定义规则 (RULES):</b><br>
            项目级: <code>.sman/RULES.md</code><br>
            全局级: <code>~/.sman/RULES.md</code>
        </body></html>""".trimIndent())

        return JPanel(BorderLayout()).apply {
            add(rulesHintLabel, BorderLayout.WEST)
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
        val llmApiKey = getApiKeyFieldValue(llmApiKeyField)
        val llmBaseUrl = llmBaseUrlField.text.trim()
        val llmModelName = llmModelNameField.text.trim()
        val bgeEndpoint = bgeEndpointField.text.trim()
        val bgeApiKey = getApiKeyFieldValue(bgeApiKeyField)
        val rerankerEndpoint = rerankerEndpointField.text.trim()
        val rerankerApiKey = getApiKeyFieldValue(rerankerApiKeyField)

        // 验证必填字段
        if (!validateRequiredFields(llmBaseUrl, "LLM Base URL") ||
            !validateRequiredFields(llmModelName, "LLM 模型名称") ||
            !validateRequiredFields(bgeEndpoint, "BGE-M3 端点") ||
            !validateRequiredFields(rerankerEndpoint, "Reranker 端点")) {
            return null
        }

        return ConfigData(
            llmApiKey, llmBaseUrl, llmModelName,
            bgeEndpoint, bgeApiKey, rerankerEndpoint, rerankerApiKey,
            autoAnalysisEnabled = autoAnalysisSwitch.isSelected
        )
    }

    private fun validateRequiredFields(value: String, fieldName: String): Boolean {
        if (value.isEmpty()) {
            showError("$fieldName 不能为空！")
            return false
        }
        return true
    }

    /**
     * 保存配置到存储服务
     */
    private fun saveConfig(config: ConfigData) {
        // LLM 配置：明文保存
        storage.llmApiKey = config.llmApiKey
        storage.llmBaseUrl = config.llmBaseUrl
        storage.llmModelName = config.llmModelName

        // BGE-M3 配置：明文保存
        storage.bgeEndpoint = config.bgeEndpoint
        storage.bgeApiKey = config.bgeApiKey

        // BGE-Reranker 配置：明文保存
        storage.rerankerEndpoint = config.rerankerEndpoint
        storage.rerankerApiKey = config.rerankerApiKey

        // 自动分析配置
        storage.autoAnalysisEnabled = config.autoAnalysisEnabled

        // 更新配置到 SmanConfig（下次 LLM/BGE 调用会使用新配置）
        val userConfig = SmanConfig.UserConfig(
            llmApiKey = storage.llmApiKey,
            llmBaseUrl = storage.llmBaseUrl,
            llmModelName = storage.llmModelName
        )
        SmanConfig.setUserConfig(userConfig)

        logger.info("设置已保存: autoAnalysisEnabled={}", config.autoAnalysisEnabled)

        // 核心优化：如果自动分析启用且 API Key 已配置，立即触发分析
        if (config.autoAnalysisEnabled && config.llmApiKey.isNotBlank()) {
            logger.info("配置保存完成，立即触发项目分析")
            try {
                SmanPlugin.getAnalysisScheduler(project)?.triggerImmediateAnalysis()
            } catch (e: Exception) {
                logger.warn("触发立即分析失败: ${e.message}")
            }
        }
    }

    /**
     * 显示结果消息（包含测试结果）
     */
    private fun showResultMessage(config: ConfigData, testResults: ServiceTestResults) {
        val message = buildResultMessage(config, testResults)
        val messageType = if (testResults.allSuccess()) JOptionPane.INFORMATION_MESSAGE else JOptionPane.WARNING_MESSAGE
        JOptionPane.showConfirmDialog(this, message, "保存结果", JOptionPane.DEFAULT_OPTION, messageType)
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
        - 自动分析已${if (config.autoAnalysisEnabled) "启用" else "禁用"}

    """.trimIndent()

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
        // 自动分析配置
        val autoAnalysisEnabled: Boolean
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
        private const val SUCCESS_MESSAGE = "✓ 连接成功"
        private const val ERROR_HTTP_PREFIX = "✗ HTTP"
        private const val ERROR_PREFIX = "✗"
        private const val CONNECTION_FAILED_MESSAGE = "连接失败"

        // JSON 媒体类型（复用）
        private val JSON_MEDIA_TYPE = "application/json".toMediaTypeOrNull()

        fun show(project: Project, onAnalysisResultsCallback: (() -> Unit)? = null) {
            val dialog = SettingsDialog(project, onAnalysisResultsCallback)
            dialog.isVisible = true
        }
    }
}
