package ai.smancode.sman.ide.ui.layout

import com.intellij.util.ui.JBUI
import ai.smancode.sman.ide.ui.ChatColors
import ai.smancode.sman.ide.ui.components.MessageBubble
import ai.smancode.sman.ide.ui.components.TodoListPanel
import java.awt.*
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.border.EmptyBorder

class MessageWrapper(val content: JComponent, private val centered: Boolean = false) : JPanel(GridBagLayout()) {

    val bubble: MessageBubble?
        get() = content as? MessageBubble

    init {
        isOpaque = false
        background = ChatColors.background
        
        val gbc = GridBagConstraints()
        gbc.gridx = 0
        gbc.gridy = 0
        gbc.weightx = 1.0 // 占满横向空间
        gbc.weighty = 0.0
        
        if (centered) {
            // 居中布局（Loading 等）
            gbc.anchor = GridBagConstraints.CENTER
            gbc.fill = GridBagConstraints.HORIZONTAL // Loading 也铺满吧，或者保持 NONE
            gbc.insets = JBUI.insets(8)
            add(content, gbc)
        } else {
            // 全宽布局：不再区分左右，统一铺满
            gbc.anchor = GridBagConstraints.NORTHWEST
            gbc.fill = GridBagConstraints.HORIZONTAL
            
            // 恢复左右边距，以便显示圆角效果
            // 增加左右边距到 25px，减少留白浪费
            gbc.insets = JBUI.insets(4, 20, 4, 20)
            
            add(content, gbc)
        }
    }
    
    /**
     * 🆕 更新 TODO List - 委托给 MessageBubble
     * TODO List 现在内嵌在 MessageBubble 中，位于 Thinking 和 Content 之间
     */
    fun updateTodoList(todos: List<TodoListPanel.TodoData>) {
        bubble?.updateTodoList(todos)
    }
    
    /**
     * 🆕 移除 TODO List
     */
    fun removeTodoList() {
        bubble?.updateTodoList(emptyList())
    }
    
    // 兼容旧接口
    fun updateForMaxWidth(width: Int) {}
}
