package com.smancode.smanagent.ide.ui

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.smancode.smanagent.ide.model.GraphModels.PartData
import com.smancode.smanagent.ide.renderer.CliMessageRenderer
import com.smancode.smanagent.ide.service.AgentWebSocketClient
import com.smancode.smanagent.ide.service.storageService
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.awt.BorderLayout
import java.awt.Component
import java.awt.FlowLayout
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import javax.swing.*

/**
 * CLI 风格聊天面板
 * <p>
 * 提供平铺直叙的 CLI 风格界面。
 */
class SmanAgentChatPanel(private val project: Project) : SimpleToolWindowPanel(false, true) {

    private val logger = LoggerFactory.getLogger(SmanAgentChatPanel::class.java)

    private val outputArea = JTextArea().apply {
        isEditable = false
        font = java.awt.Font("Monospaced", java.awt.Font.PLAIN, 12)
    }

    private val inputField = JTextField().apply {
        font = java.awt.Font("Monospaced", java.awt.Font.PLAIN, 12)
    }

    private val sendButton = JButton("发送").apply {
        addActionListener { sendMessage() }
    }

    private val settingsButton = JButton("⚙").apply {
        toolTipText = "设置"
        addActionListener { SettingsDialog.show(project) }
    }

    private val scrollPane = JScrollPane(outputArea).apply {
        verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
        horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
    }

    private val storageService = project.storageService()
    private var webSocketClient: AgentWebSocketClient? = null

    private var sessionId: String? = null
    private val projectKey: String
        get() = project.name

    init {
        initComponents()
        loadSession()
        connectToBackend()
    }

    private fun initComponents() {
        // 输出区域
        outputArea.background = java.awt.Color.BLACK
        outputArea.foreground = java.awt.Color.GREEN

        // 输入区域
        val inputPanel = JPanel(FlowLayout(FlowLayout.LEFT)).apply {
            add(inputField)
            add(sendButton)
            add(settingsButton)
        }

        // 回车发送
        inputField.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER && !e.isShiftDown) {
                    e.consume()
                    sendMessage()
                }
            }
        })

        // 布局
        setContent(scrollPane)
        setToolbar(inputPanel)
    }

    private fun loadSession() {
        sessionId = storageService.getLastSessionId()
        if (sessionId.isNullOrBlank()) {
            sessionId = java.util.UUID.randomUUID().toString()
            storageService.saveSessionId(sessionId!!)
        }
    }

    private fun connectToBackend() {
        val serverUrl = storageService.getBackendUrl()
        if (serverUrl.isNullOrBlank()) {
            appendOutput("错误: 未配置后端 URL\n请在设置中配置后端服务地址。")
            return
        }

        appendOutput("正在连接到后端: $serverUrl")

        webSocketClient = AgentWebSocketClient(serverUrl).apply {
            onConnected = { message ->
                SwingUtilities.invokeLater {
                    appendOutput("✓ 已连接到后端\n")
                    appendOutput(CliMessageRenderer.renderSeparator())
                }
            }

            onPart = { data ->
                SwingUtilities.invokeLater {
                    val partData = parsePartData(data["part"] as? Map<*, *> ?: emptyMap<String, Any>())
                    if (partData != null) {
                        val rendered = CliMessageRenderer.renderPart(partData)
                        appendOutput(rendered)
                    }
                }
            }

            onComplete = { data ->
                SwingUtilities.invokeLater {
                    appendOutput("\n")
                    appendOutput(CliMessageRenderer.renderSeparator())
                }
            }

            onError = { data ->
                SwingUtilities.invokeLater {
                    val message = data["message"] as? String ?: "未知错误"
                    appendOutput("❌ 错误: $message\n")
                }
            }

            onDisconnected = {
                SwingUtilities.invokeLater {
                    appendOutput("⚠ 连接已断开\n")
                }
            }

            onError = { e ->
                SwingUtilities.invokeLater {
                    appendOutput("❌ 连接错误: ${e.message}\n")
                }
            }
        }

        webSocketClient?.connect()
    }

    private fun sendMessage() {
        val input = inputField.text.trim()
        if (input.isEmpty()) return

        inputField.text = ""

        // 显示用户输入
        appendOutput("👤 $input\n")
        appendOutput(CliMessageRenderer.renderSeparator())

        // 发送到后端
        webSocketClient?.let { client ->
            if (client.isConnected()) {
                client.analyze(sessionId!!, projectKey, input)
            } else {
                appendOutput("⚠ 未连接到后端\n")
            }
        }
    }

    private fun appendOutput(text: String) {
        outputArea.append(text)
        outputArea.caretPosition = outputArea.document.length
    }

    private fun parsePartData(data: Map<*, *>): PartData? {
        return try {
            val json = jacksonObjectMapper().writeValueAsString(data)
            jacksonObjectMapper().readValue(json)
        } catch (e: Exception) {
            logger.error("解析 PartData 失败", e)
            null
        }
    }

    fun dispose() {
        webSocketClient?.disconnect()
    }
}
