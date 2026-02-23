package com.smancode.sman.infra.ide

import com.intellij.openapi.project.Project
import com.smancode.sman.analysis.model.ProjectEntry
import com.smancode.sman.analysis.model.StepState
import org.slf4j.Logger
import java.awt.BorderLayout
import java.awt.Dimension
import java.nio.file.Paths
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.JButton
import javax.swing.JDialog
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.SwingUtilities

/**
 * 分析报告构建器
 *
 * 负责构建和显示项目分析报告
 */
class AnalysisReportBuilder(
    private val project: Project,
    private val logger: Logger
) {
    private val projectKey: String
        get() = project.name

    /**
     * 显示分析结果（弹窗方式）
     */
    fun showAnalysisResults() {
        logger.info("显示分析结果: projectKey={}", projectKey)

        // 获取项目根目录
        val projectRoot = project.basePath?.let { Paths.get(it) }
        if (projectRoot == null) {
            showAnalysisDialog("项目分析结果", "无法获取项目路径。")
            return
        }

        // 获取项目分析状态
        val entry = com.smancode.sman.analysis.model.ProjectMapManager.getProjectEntry(projectRoot, projectKey)

        if (entry == null) {
            showAnalysisDialog("项目分析结果", """
                项目尚未注册到分析系统。

                可能的原因：
                1. 插件刚启动，后台分析尚未开始
                2. 自动分析已禁用（可在设置中开启）
                3. LLM API Key 未配置

                请检查设置并等待后台自动分析完成。
            """.trimIndent())
            return
        }

        // 构建分析结果报告并显示弹窗
        val report = buildAnalysisReport(entry)
        showAnalysisDialog("项目分析结果 - $projectKey", report)
    }

    /**
     * 显示分析结果弹窗（自定义大小）
     */
    private fun showAnalysisDialog(title: String, message: String) {
        SwingUtilities.invokeLater {
            val textArea = JTextArea(message).apply {
                isEditable = false
                lineWrap = true
                wrapStyleWord = true
                font = java.awt.Font("JetBrains Mono", java.awt.Font.PLAIN, 13)
                margin = java.awt.Insets(10, 10, 10, 10)
            }

            val scrollPane = JScrollPane(textArea).apply {
                preferredSize = Dimension(500, 400)
                verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
                horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
            }

            val dialog = JDialog().apply {
                setTitle(title)
                isModal = true
                contentPane.add(scrollPane, BorderLayout.CENTER)

                val closeButton = JButton("关闭").apply {
                    addActionListener { dispose() }
                }
                val buttonPanel = JPanel().apply { add(closeButton) }
                contentPane.add(buttonPanel, BorderLayout.SOUTH)

                pack()
                setLocationRelativeTo(null)
            }

            dialog.isVisible = true
        }
    }

    /**
     * 构建分析报告
     */
    private fun buildAnalysisReport(entry: ProjectEntry): String {
        val sb = StringBuilder()
        sb.appendLine("📊 项目分析结果")
        sb.appendLine("═════════════════════════════")
        sb.appendLine()
        sb.appendLine("**项目**: ${projectKey}")
        sb.appendLine("**路径**: ${entry.path}")
        sb.appendLine()

        sb.appendLine("📋 分析状态:")
        sb.appendLine("  • 项目结构: ${statusIcon(entry.analysisStatus.projectStructure)}")
        sb.appendLine("  • 技术栈: ${statusIcon(entry.analysisStatus.techStack)}")
        sb.appendLine("  • API 入口: ${statusIcon(entry.analysisStatus.apiEntries)}")
        sb.appendLine("  • DB 实体: ${statusIcon(entry.analysisStatus.dbEntities)}")
        sb.appendLine("  • 枚举: ${statusIcon(entry.analysisStatus.enums)}")
        sb.appendLine("  • 配置文件: ${statusIcon(entry.analysisStatus.configFiles)}")
        sb.appendLine()

        sb.appendLine("🕐 最后分析: ${formatTimestamp(entry.lastAnalyzed)}")
        sb.appendLine()

        sb.appendLine("📈 统计:")
        val completedCount = countCompleted(entry)
        val failedCount = countFailed(entry)
        sb.appendLine("  • 已完成: $completedCount / 6 项")
        if (failedCount > 0) {
            sb.appendLine("  • 失败: $failedCount 项（将在下次循环重试）")
        }
        sb.appendLine()

        return sb.toString()
    }

    /**
     * 格式化时间戳为可读格式
     */
    private fun formatTimestamp(timestamp: Long?): String {
        if (timestamp == null || timestamp <= 0) return "尚未分析"
        return try {
            Instant.ofEpochMilli(timestamp)
                .atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
        } catch (e: Exception) {
            "时间格式错误"
        }
    }

    /**
     * 获取状态图标
     */
    private fun statusIcon(state: StepState): String = when (state) {
        StepState.COMPLETED -> "✅ 已完成"
        StepState.RUNNING -> "🔄 进行中"
        StepState.PENDING -> "⏳ 待处理"
        StepState.FAILED -> "❌ 失败"
        StepState.SKIPPED -> "⏭️ 跳过"
    }

    /**
     * 统计失败的分析项
     */
    private fun countFailed(entry: ProjectEntry): Int {
        val status = entry.analysisStatus
        return listOf(
            status.projectStructure, status.techStack, status.apiEntries,
            status.dbEntities, status.enums, status.configFiles
        ).count { it == StepState.FAILED }
    }

    /**
     * 统计已完成的分析项
     */
    private fun countCompleted(entry: ProjectEntry): Int {
        val status = entry.analysisStatus
        return listOf(
            status.projectStructure, status.techStack, status.apiEntries,
            status.dbEntities, status.enums, status.configFiles
        ).count { it == StepState.COMPLETED }
    }
}
