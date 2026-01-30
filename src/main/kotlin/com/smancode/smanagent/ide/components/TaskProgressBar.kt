package com.smancode.smanagent.ide.components

import com.smancode.smanagent.ide.model.PartData
import com.smancode.smanagent.ide.model.TodoItem
import com.smancode.smanagent.ide.renderer.TodoRenderer
import com.smancode.smanagent.ide.theme.ThemeColors
import com.smancode.smanagent.ide.theme.ColorPalette
import com.smancode.smanagent.ide.ui.FontManager
import java.awt.BorderLayout
import java.awt.GridLayout
import javax.swing.*
import javax.swing.border.EmptyBorder

/**
 * 任务进度栏组件（固定在底部显示）
 * <p>
 * 职责：
 * - 管理底部栏 UI 组件
 * - 显示和更新任务列表
 * - 应用主题
 * <p>
 * 设计原则：
 * - 单一职责：只负责 UI 显示，不负责格式化
 * - 委托给 TodoRenderer 进行数据提取和格式化
 */
class TaskProgressBar : JPanel(BorderLayout()) {

    private val tasksPanel: JPanel
    private var currentItems: List<TodoItem> = emptyList()

    init {
        border = EmptyBorder(8, 12, 8, 12)

        tasksPanel = JPanel().apply {
            layout = GridLayout(0, 1, 0, 4)
            isOpaque = false
        }

        val contentPanel = JPanel(BorderLayout()).apply {
            isOpaque = false
            val scrollPane = JScrollPane(tasksPanel).apply {
                border = null
                horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
                verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
                isOpaque = false
                viewport.isOpaque = false
            }
            add(scrollPane, BorderLayout.CENTER)
        }

        add(contentPanel, BorderLayout.CENTER)
        isVisible = false
        applyTheme()
    }

    /**
     * 更新任务列表
     */
    fun updateTasks(part: PartData) {
        val items = TodoRenderer.extractItems(part)
        currentItems = items

        if (items.isEmpty()) {
            isVisible = false
            return
        }

        isVisible = true
        refreshTasksPanel(items)

        if (isAllCompleted(items)) {
            autoHideAfterDelay()
        }
    }

    /**
     * 清空任务（用于新建会话）
     */
    fun clear() {
        currentItems = emptyList()
        tasksPanel.removeAll()
        isVisible = false
    }

    /**
     * 应用主题
     */
    fun applyTheme() {
        val colors = ThemeColors.getCurrentColors()
        background = colors.background

        tasksPanel.components.forEach { component ->
            if (component is JLabel) {
                val item = findItemByLabel(component)
                if (item != null) {
                    component.foreground = getColorForItem(item, colors)
                }
            }
        }
    }

    /**
     * 刷新任务面板
     */
    private fun refreshTasksPanel(items: List<TodoItem>) {
        val colors = ThemeColors.getCurrentColors()
        tasksPanel.removeAll()
        items.forEach { item ->
            val taskLabel = JLabel(TodoRenderer.formatTodoItem(item)).apply {
                // 使用编辑器字体，稍微缩小一点（0.9倍）
                val scaledSize = (FontManager.getEditorFontSize() * 0.9).toInt().coerceAtLeast(10)
                font = java.awt.Font(FontManager.getEditorFontName(), java.awt.Font.PLAIN, scaledSize)
                isOpaque = true
                background = colors.background
            }
            tasksPanel.add(taskLabel)
        }
        tasksPanel.revalidate()
        tasksPanel.repaint()
    }

    /**
     * 判断是否全部完成
     */
    private fun isAllCompleted(items: List<TodoItem>): Boolean {
        return items.all { it.status == "COMPLETED" }
    }

    /**
     * 延迟自动隐藏
     */
    private fun autoHideAfterDelay() {
        Timer(2000) {
            isVisible = false
        }.apply {
            isRepeats = false
            start()
        }
    }

    /**
     * 根据标签文本查找对应的 TodoItem
     */
    private fun findItemByLabel(label: JLabel): TodoItem? {
        return currentItems.find { label.text.contains(it.content) }
    }

    /**
     * 获取 TodoItem 对应的颜色
     */
    private fun getColorForItem(item: TodoItem, colors: ColorPalette): java.awt.Color {
        return when (TodoRenderer.getItemColorType(item)) {
            "muted" -> colors.textMuted
            else -> colors.textPrimary
        }
    }
}
