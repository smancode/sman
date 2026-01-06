package ai.smancode.sman.ide.ui.components

import com.intellij.util.ui.JBUI
import ai.smancode.sman.ide.service.WebSocketService.SuggestedAnswer
import ai.smancode.sman.ide.ui.ChatColors
import java.awt.*
import javax.swing.*

/**
 * 建议答案面板
 * 
 * 显示 AI 的澄清问题和 2-4 个建议答案按钮，
 * 用户可以点击按钮快速选择，或手动输入回答。
 */
class SuggestionPanel(
    private val question: String,
    private val suggestions: List<SuggestedAnswer>,
    private val onSuggestionSelected: (SuggestedAnswer) -> Unit
) : JPanel() {
    
    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        background = ChatColors.assistantBubble
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(ChatColors.borderColor, 1, true),
            BorderFactory.createEmptyBorder(
                JBUI.scale(12), 
                JBUI.scale(16), 
                JBUI.scale(12), 
                JBUI.scale(16)
            )
        )
        
        // 提示标签（提示用户可以点击或输入）
        val hintLabel = JLabel("👆 请点击选择，或直接在输入框输入其他内容").apply {
            font = font.deriveFont(Font.ITALIC, JBUI.scale(12f))
            foreground = ChatColors.textSecondary
        }
        add(hintLabel)
        add(Box.createVerticalStrut(JBUI.scale(8)))
        
        // 问题标签
        val questionLabel = JTextArea(question).apply {
            font = font.deriveFont(Font.PLAIN, JBUI.scale(14f))
            foreground = ChatColors.textPrimary
            background = ChatColors.assistantBubble
            lineWrap = true
            wrapStyleWord = true
            isEditable = false
            isFocusable = false
            border = BorderFactory.createEmptyBorder()
        }
        add(questionLabel)
        add(Box.createVerticalStrut(JBUI.scale(12)))
        
        // 建议按钮面板
        val buttonPanel = JPanel().apply {
            layout = FlowLayout(FlowLayout.LEFT, JBUI.scale(8), JBUI.scale(8))
            background = ChatColors.assistantBubble
            alignmentX = Component.LEFT_ALIGNMENT
        }
        
        for (suggestion in suggestions) {
            val button = createSuggestionButton(suggestion)
            buttonPanel.add(button)
        }
        
        add(buttonPanel)
    }
    
    private fun createSuggestionButton(suggestion: SuggestedAnswer): JButton {
        return JButton(suggestion.label).apply {
            toolTipText = suggestion.text
            isFocusPainted = false
            isContentAreaFilled = false
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(
                    if (suggestion.recommended) ChatColors.accentColor else ChatColors.borderColor,
                    if (suggestion.recommended) 2 else 1,
                    true
                ),
                BorderFactory.createEmptyBorder(
                    JBUI.scale(6),
                    JBUI.scale(12),
                    JBUI.scale(6),
                    JBUI.scale(12)
                )
            )
            
            background = if (suggestion.recommended) {
                ChatColors.accentColor.brighter()
            } else {
                ChatColors.assistantBubble
            }
            foreground = ChatColors.textPrimary
            font = font.deriveFont(JBUI.scale(13f))
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            
            // 悬停效果
            addMouseListener(object : java.awt.event.MouseAdapter() {
                override fun mouseEntered(e: java.awt.event.MouseEvent) {
                    background = ChatColors.accentColor
                    foreground = Color.WHITE
                }
                
                override fun mouseExited(e: java.awt.event.MouseEvent) {
                    background = if (suggestion.recommended) {
                        ChatColors.accentColor.brighter()
                    } else {
                        ChatColors.assistantBubble
                    }
                    foreground = ChatColors.textPrimary
                }
            })
            
            addActionListener {
                onSuggestionSelected(suggestion)
            }
        }
    }
    
    override fun getPreferredSize(): Dimension {
        val preferred = super.getPreferredSize()
        return Dimension(preferred.width, preferred.height)
    }
    
    override fun getMaximumSize(): Dimension {
        return Dimension(Integer.MAX_VALUE, preferredSize.height)
    }
}
