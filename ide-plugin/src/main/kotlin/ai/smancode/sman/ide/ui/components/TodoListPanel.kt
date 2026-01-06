package ai.smancode.sman.ide.ui.components

import com.intellij.util.ui.JBUI
import com.intellij.icons.AllIcons
import ai.smancode.sman.ide.ui.ChatColors
import java.awt.*
import javax.swing.*
import javax.swing.border.EmptyBorder

/**
 * TODO List 展示面板（Cursor 风格）
 * 
 * 参照 Cursor 的设计：
 * - 圆形勾选框：⊙ 完成 / ○ 待处理
 * - 删除线样式表示已完成
 * - 紧凑的单行布局
 * - 可折叠
 */
class TodoListPanel : JPanel() {
    
    private val todoContainer = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        border = EmptyBorder(0, JBUI.scale(4), 0, 0)
    }
    
    private val headerPanel = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
        isOpaque = false
    }
    
    private val progressLabel = JLabel().apply {
        font = font.deriveFont(JBUI.scale(11f))
        foreground = ChatColors.textSecondary
    }
    
    private var isCollapsed = false
    private var currentTodos: List<TodoData> = emptyList()  // 🆕 保存当前数据
    
    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        background = ChatColors.surface
        border = EmptyBorder(JBUI.scale(6), JBUI.scale(8), JBUI.scale(6), JBUI.scale(12))
        isOpaque = false
        
        // 头部
        headerPanel.apply {
            add(Box.createHorizontalStrut(JBUI.scale(2)))
            add(progressLabel)
            
            addMouseListener(object : java.awt.event.MouseAdapter() {
                override fun mouseClicked(e: java.awt.event.MouseEvent?) {
                    toggleCollapse()
                }
            })
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            alignmentX = Component.LEFT_ALIGNMENT
        }
        
        add(headerPanel)
        add(todoContainer)
    }
    
    private fun toggleCollapse() {
        isCollapsed = !isCollapsed
        progressLabel.icon = if (isCollapsed) AllIcons.General.ArrowRight else AllIcons.General.ArrowDown
        todoContainer.isVisible = !isCollapsed
        revalidate()
        repaint()
    }

    /**
     * 自动折叠 TODO List（供外部调用）
     * 用于收到 COMPLETE 消息时自动折叠
     */
    fun collapse() {
        if (!isCollapsed) {
            isCollapsed = true
            progressLabel.icon = AllIcons.General.ArrowRight
            todoContainer.isVisible = false
            revalidate()
            repaint()
        }
    }
    
    fun updateTodos(todos: List<TodoData>) {
        // 🆕 如果内容没变，不刷新（避免闪烁）
        if (isSameTodoList(currentTodos, todos)) {
            return
        }
        
        currentTodos = todos  // 保存当前数据
        todoContainer.removeAll()
        
        if (todos.isEmpty()) {
            isVisible = false
            revalidate()
            repaint()
            return
        }
        
        isVisible = true
        
        // 🔧 按 ID 排序，确保顺序一致
        // ID 通常是 "1", "2", "3" 或 "todo_1", "todo_2" 格式
        val sortedTodos = todos.sortedBy { todo ->
            // 尝试从 ID 中提取数字
            val numMatch = Regex("\\d+").find(todo.id)
            numMatch?.value?.toIntOrNull() ?: Int.MAX_VALUE
        }
        
        for (todo in sortedTodos) {
            todoContainer.add(createTodoRow(todo))
        }
        
        val completed = todos.count { it.status == "completed" }
        val total = todos.size
        // 保持当前的折叠状态图标
        progressLabel.icon = if (isCollapsed) AllIcons.General.ArrowRight else AllIcons.General.ArrowDown
        progressLabel.text = "$completed of $total Done"
        
        revalidate()
        repaint()
    }
    
    /**
     * 🆕 比较两个 TODO 列表是否相同（用于避免无效刷新）
     */
    private fun isSameTodoList(old: List<TodoData>, new: List<TodoData>): Boolean {
        if (old.size != new.size) return false
        for (i in old.indices) {
            val o = old[i]
            val n = new[i]
            if (o.id != n.id || o.content != n.content || o.status != n.status || 
                o.blockedReason != n.blockedReason) {
                return false
            }
        }
        return true
    }
    
    /**
     * 🆕 获取当前 TODO 列表（用于持久化）
     */
    fun getCurrentTodos(): List<TodoData> = currentTodos
    
    /**
     * 创建单行 TODO（Cursor 风格）
     */
    private fun createTodoRow(todo: TodoData): JPanel {
        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            border = EmptyBorder(JBUI.scale(1), 0, JBUI.scale(1), 0)
            
            // 🆕 勾选框图标（区分不同状态）
            val (icon, iconColor) = when (todo.status) {
                "completed" -> "◉" to Color(0x4CAF50)  // 绿色实心
                "in_progress" -> "◉" to Color(0x2196F3)  // 蓝色实心
                "blocked" -> "◉" to Color(0xF44336)  // 红色实心表示阻塞
                "cancelled" -> "◉" to ChatColors.textSecondary  // 灰色实心表示已取消
                else -> "○" to ChatColors.textSecondary  // pending: 灰色空心
            }
            
            val iconLabel = JLabel(icon).apply {
                font = font.deriveFont(JBUI.scale(12f))
                foreground = iconColor
                border = EmptyBorder(0, 0, 0, JBUI.scale(6))
            }
            add(iconLabel)
            
            // 内容文字（blocked 时在同一行追加阻塞原因）
            val isBlocked = todo.status == "blocked"
            
            // 🔧 改用 JTextArea，支持选中和复制
            val textColor = when {
                isBlocked -> Color(0xF44336)
                todo.status == "completed" -> Color(0xA5A5AA)
                todo.status == "cancelled" -> ChatColors.textSecondary
                else -> ChatColors.textPrimary
            }
            
            // 纯文本内容（不用 HTML）
            val plainContent = if (isBlocked && !todo.blockedReason.isNullOrBlank()) {
                "${todo.content} ⚠️ ${todo.blockedReason}"
            } else {
                todo.content
            }
            
            val contentArea = JTextArea(plainContent).apply {
                font = font.deriveFont(JBUI.scale(12f))
                foreground = textColor
                background = null  // 透明背景
                isOpaque = false
                isEditable = false
                lineWrap = true
                wrapStyleWord = true
                border = null
                // 右键菜单支持复制
                componentPopupMenu = JPopupMenu().apply {
                    add(JMenuItem("复制").apply {
                        addActionListener {
                            if (selectedText != null) {
                                copy()
                            } else {
                                selectAll()
                                copy()
                                select(0, 0)
                            }
                        }
                    })
                }
            }
            add(contentArea)
            
            add(Box.createHorizontalGlue())
        }
    }
    
    private fun escapeHtml(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
    }
    
    data class TodoData(
        val id: String,
        val content: String,
        val status: String,
        val type: String = "task",
        val iteration: Int? = null,
        val maxIterations: Int? = null,
        val blockedReason: String? = null
    )
    
    companion object {
        fun parseTodosFromJson(data: org.json.JSONObject): List<TodoData> {
            val todos = mutableListOf<TodoData>()
            val todosArray = data.optJSONArray("todos") ?: return todos
            
            for (i in 0 until todosArray.length()) {
                val item = todosArray.optJSONObject(i) ?: continue
                todos.add(TodoData(
                    id = item.optString("id", ""),
                    content = item.optString("content", ""),
                    status = item.optString("status", "pending"),
                    type = item.optString("type", "task"),
                    iteration = if (item.has("iteration")) item.optInt("iteration") else null,
                    maxIterations = if (item.has("maxIterations")) item.optInt("maxIterations") else null,
                    blockedReason = item.optString("blockedReason", null)
                ))
            }
            
            return todos
        }
    }
}
