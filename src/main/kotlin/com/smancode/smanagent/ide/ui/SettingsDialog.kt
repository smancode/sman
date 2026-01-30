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

    // HTTP 客户端（复用）
    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }

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
        maskApiKeyIfExists(storage.llmApiKey, llmApiKeyField)
        maskApiKeyIfExists(storage.bgeApiKey, bgeApiKeyField)
        maskApiKeyIfExists(storage.rerankerApiKey, rerankerApiKeyField)

        val panel = createMainPanel()
        val buttonPanel = createButtonPanel()

        add(panel, BorderLayout.CENTER)
        add(buttonPanel, BorderLayout.SOUTH)

        pack()
        setLocationRelativeTo(null)
        minimumSize = java.awt.Dimension(500, 400)
        isResizable = false
    }

    private fun maskApiKeyIfExists(apiKey: String, field: JPasswordField) {
        if (apiKey.isNotEmpty()) {
            field.text = API_KEY_MASK
        }
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

    /**
     * 测试 LLM 服务
     */
    private fun testLlmService(config: ConfigData): ServiceTestResult {
        val request = Request.Builder()
            .url("${config.llmBaseUrl}/chat/completions")
            .addHeader("Authorization", "Bearer ${config.llmApiKey}")
            .post(createLlmTestPayload(config.llmModelName))
            .build()

        return executeServiceTest(request)
    }

    /**
     * 测试 BGE-M3 服务
     */
    private fun testBgeM3Service(config: ConfigData): ServiceTestResult {
        val request = Request.Builder()
            .url("${config.bgeEndpoint}/v1/embeddings")
            .post(createBgeTestPayload())
            .build()

        return executeServiceTest(request)
    }

    /**
     * 测试 Reranker 服务
     */
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
    """.trimIndent().toRequestBody("application/json".toMediaTypeOrNull())

    private fun createBgeTestPayload() = """
        {
            "input": "$BGE_TEST_INPUT",
            "model": "$BGE_MODEL"
        }
    """.trimIndent().toRequestBody("application/json".toMediaTypeOrNull())

    private fun createRerankerTestPayload() = """
        {
            "model": "$RERANKER_MODEL",
            "query": "$RERANKER_QUERY",
            "documents": ["$RERANKER_DOCUMENT"],
            "top_k": $RERANKER_TOP_K
        }
    """.trimIndent().toRequestBody("application/json".toMediaTypeOrNull())

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

        fun show(project: Project) {
            val dialog = SettingsDialog(project)
            dialog.isVisible = true
        }
    }
}
