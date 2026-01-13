package com.smancode.smanagent.ide.components

import com.smancode.smanagent.ide.model.PartData
import com.smancode.smanagent.ide.model.TodoItem
import com.smancode.smanagent.ide.model.GraphModels.TodoPartData
import com.smancode.smanagent.ide.theme.ThemeColors
import com.smancode.smanagent.ide.theme.ColorPalette
import java.awt.BorderLayout
import java.awt.GridLayout
import javax.swing.*
import javax.swing.border.EmptyBorder

/**
 * 任务进度栏组件（固定在底部显示）
 * <p>
 * 设计原则：
 * - 始终固定在底部，不随消息流滚动
 * - 有任务时显示，全部完成时自动隐藏
 * - 支持实时更新任务状态
 */
class TaskProgressBar : JPanel(BorderLayout()) {

    private val titleLabel: JLabel
    private val tasksPanel: JPanel
    private val progressBar: JProgressBar

    private var currentItems: List<TodoItem> = emptyList()

    init {
        // 基础样式
        border = EmptyBorder(8, 12, 8, 12)

        // 标题栏
        titleLabel = JLabel("📝 任务列表").apply {
            font = java.awt.Font("JetBrains Mono", java.awt.Font.BOLD, 12)
        }

        // 任务列表面板
        tasksPanel = JPanel().apply {
            layout = GridLayout(0, 1, 0, 4) // 垂直布局，间距4px
            isOpaque = false
        }

        // 进度条
        progressBar = JProgressBar(0, 100).apply {
            isStringPainted = true
        }

        // 布局
        val headerPanel = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(titleLabel, BorderLayout.WEST)
            add(progressBar, BorderLayout.EAST)
        }

        val contentPanel = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(headerPanel, BorderLayout.NORTH)
            add(JScrollPane(tasksPanel).apply {
                border = null
                horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
                verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
            }, BorderLayout.CENTER)
        }

        add(contentPanel, BorderLayout.CENTER)

        // 默认隐藏
        isVisible = false

        applyTheme()
    }

    /**
     * 更新任务列表
     */
    fun updateTasks(part: PartData) {
        // 只有 TodoPartData 才有 items
        val items = when (part) {
            is TodoPartData -> part.items
            else -> {
                // 从通用 data 中提取
                @Suppress("UNCHECKED_CAST")
                val itemsList = part.data["items"] as? List<Map<String, Any>>
                itemsList?.map { itemData ->
                    TodoItem(
                        id = itemData["id"] as? String ?: "",
                        content = itemData["content"] as? String ?: "",
                        status = itemData["status"] as? String ?: "PENDING"
                    )
                } ?: emptyList()
            }
        }

        currentItems = items

        if (items.isEmpty()) {
            isVisible = false
            return
        }

        // 显示进度栏
        isVisible = true

        // 清空现有任务
        tasksPanel.removeAll()

        // 添加任务项
        for (item in items) {
            val taskLabel = JLabel(formatTaskItem(item)).apply {
                font = java.awt.Font("JetBrains Mono", java.awt.Font.PLAIN, 11)
            }
            tasksPanel.add(taskLabel)
        }

        // 更新进度条
        updateProgress()

        // 刷新显示
        tasksPanel.revalidate()
        tasksPanel.repaint()
    }

    /**
     * 格式化任务项
     */
    private fun formatTaskItem(item: TodoItem): String {
        val icon = when (item.status) {
            "PENDING" -> "⏳"
            "IN_PROGRESS" -> "▶"
            "COMPLETED" -> "✓"
            "CANCELLED" -> "❌"
            else -> "⏳"
        }
        return "$icon ${item.content}"
    }

    /**
     * 更新进度条
     */
    private fun updateProgress() {
        val total = currentItems.size
        if (total == 0) {
            progressBar.value = 0
            progressBar.string = ""
            return
        }

        val completed = currentItems.count { it.status == "COMPLETED" }
        val progress = (completed * 100) / total

        progressBar.value = progress
        progressBar.string = "$completed/$total"

        // 全部完成时自动隐藏（延迟2秒，让用户看到完成状态）
        if (completed == total) {
            Timer(2000) {
                isVisible = false
            }.apply {
                isRepeats = false
                start()
            }
        }
    }

    /**
     * 清空任务（用于新建会话）
     */
    fun clear() {
        currentItems = emptyList()
        tasksPanel.removeAll()
        progressBar.value = 0
        progressBar.string = ""
        isVisible = false
    }

    /**
     * 应用主题
     */
    fun applyTheme() {
        val colors = ThemeColors.getCurrentColors()

        background = colors.background
        titleLabel.foreground = colors.textPrimary

        // 更新所有任务标签颜色
        tasksPanel.components.forEach { component ->
            if (component is JLabel) {
                val text = component.text
                val item = currentItems.find { text.contains(it.content) }
                if (item != null) {
                    component.foreground = getItemColor(item.status, colors)
                }
            }
        }

        progressBar.foreground = colors.info
    }

    /**
     * 获取任务项颜色
     */
    private fun getItemColor(status: String, colors: ColorPalette): java.awt.Color {
        return when (status) {
            "PENDING" -> colors.textMuted
            "IN_PROGRESS" -> colors.info
            "COMPLETED" -> colors.success
            "CANCELLED" -> colors.error
            else -> colors.textMuted
        }
    }
}
