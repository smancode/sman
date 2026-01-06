package ai.smancode.sman.ide.ui.components

import com.intellij.util.ui.JBUI
import com.intellij.ui.JBColor
import ai.smancode.sman.ide.ui.ChatColors
import java.awt.*
import java.awt.event.KeyEvent
import java.awt.font.TextHitInfo
import java.awt.geom.GeneralPath
import javax.swing.*
import javax.swing.border.AbstractBorder
import javax.swing.border.EmptyBorder

class InputArea(
    private val project: com.intellij.openapi.project.Project?,
    private val onSendCallback: (String) -> Unit,
    private val onStopCallback: () -> Unit = {}
) : JPanel() {
    
    private val cornerRadius = JBUI.scale(12)
    private val placeholderText = "点击 + 新建上下文"
    private var showPlaceholder = true
    private var isWarningState = false

    // 🔥 新增：悬浮和焦点状态跟踪
    private var isHovered = false
    private var isFocused = false

    // 内部文本域 (改为 var 并初始化为 null 以避开 super.updateUI 的坑)
    private var textArea: JTextArea? = null
    
    // 状态控制
    private var isSending = false
    
    // 动作按钮
    private val actionButton = ActionButton()
    
    // 代理属性和方法
    var text: String
        get() = textArea?.text ?: ""
        set(value) { textArea?.text = value }
        
    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        textArea?.isEnabled = enabled
        actionButton.isEnabled = enabled
    }
    
    fun setWarning(enable: Boolean) {
        if (isWarningState != enable) {
            isWarningState = enable
            repaint()
        }
    }
    
    fun setSendingState(sending: Boolean) {
        this.isSending = sending
        actionButton.isStopMode = sending
        
        // Update Visibility
        actionButton.isVisible = sending
        
        // Update TextArea Padding dynamically
        val padding = JBUI.scale(10)
        if (sending) {
            // Button visible: Add right padding to avoid overlap
            val btnSize = JBUI.scale(26)
            textArea?.border = EmptyBorder(padding, padding, padding, btnSize + padding + JBUI.scale(4))
        } else {
            // Button hidden: Symmetric padding
            textArea?.border = EmptyBorder(padding, padding, padding, padding)
        }
        
        actionButton.repaint()
    }
    
    init {
        isOpaque = false
        layout = null // 绝对布局
        border = RoundedBorder(cornerRadius)
        
        // 初始化 textArea（支持中文输入法跟随光标）
        textArea = object : JTextArea() {
            init {
                isOpaque = false
                lineWrap = true
                wrapStyleWord = true
                // 🔥 启用输入法支持
                enableInputMethods(true)
            }
            
            override fun paintComponent(g: Graphics) {
                super.paintComponent(g)
                // 绘制提示文字
                if (showPlaceholder && text.isBlank()) {
                    val g2 = g as Graphics2D
                val oldColor = g2.color
                g2.color = ChatColors.textSecondary
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                val fm = g2.fontMetrics
                val x = insets.left
                val y = insets.top + fm.ascent
                g2.drawString(placeholderText, x, y)
                g2.color = oldColor
            }
        }
    }
        
        val ta = textArea!!
        ta.rows = 4
        ta.columns = 20
        ta.font = com.intellij.util.ui.UIUtil.getLabelFont()
        ta.foreground = ChatColors.textPrimary
        ta.caretColor = ChatColors.textPrimary
        
        // Initial State: Not sending -> Button hidden -> Symmetric padding
        val padding = JBUI.scale(10)
        ta.border = EmptyBorder(padding, padding, padding, padding)
        
        add(ta)
        add(actionButton)
        
        // Initial visibility
        actionButton.isVisible = false
        
        // 添加按钮点击事件
        actionButton.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        actionButton.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent?) {
                if (isSending) {
                    triggerStop()
                } else {
                    triggerSend()
                }
            }
        })
        
        setComponentZOrder(actionButton, 0)
        setComponentZOrder(ta, 1)

        // 🔥 新增：鼠标悬浮监听器
        this@InputArea.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseEntered(e: java.awt.event.MouseEvent?) {
                if (!isHovered) {
                    isHovered = true
                    this@InputArea.repaint()
                }
            }

            override fun mouseExited(e: java.awt.event.MouseEvent?) {
                if (isHovered) {
                    isHovered = false
                    this@InputArea.repaint()
                }
            }
        })

        ta.addFocusListener(object : java.awt.event.FocusAdapter() {
            override fun focusGained(e: java.awt.event.FocusEvent?) {
                // 🔥 修改：跟踪焦点状态
                if (!isFocused) {
                    isFocused = true
                    this@InputArea.repaint()
                }
                showPlaceholder = false
                ta.repaint()
            }

            override fun focusLost(e: java.awt.event.FocusEvent?) {
                // 🔥 修改：清除焦点状态
                if (isFocused) {
                    isFocused = false
                    this@InputArea.repaint()
                }
                if (ta.text.isBlank()) {
                    showPlaceholder = true
                    ta.repaint()
                }
            }
        })
        
        ta.document.addDocumentListener(object : javax.swing.event.DocumentListener {
            override fun insertUpdate(e: javax.swing.event.DocumentEvent?) = updatePlaceholder()
            override fun removeUpdate(e: javax.swing.event.DocumentEvent?) = updatePlaceholder()
            override fun changedUpdate(e: javax.swing.event.DocumentEvent?) = updatePlaceholder()
            
            private fun updatePlaceholder() {
                val shouldShow = ta.text.isBlank() && !ta.hasFocus()
                if (showPlaceholder != shouldShow) {
                    showPlaceholder = shouldShow
                    ta.repaint()
                }
            }
        })
        
        setupActions()
    }
    
    override fun requestFocusInWindow(): Boolean {
        return textArea?.requestFocusInWindow() ?: super.requestFocusInWindow()
    }
    
    override fun getPreferredSize(): Dimension {
        // 关键修复：JPanel 使用 null 布局时，默认返回 10x10 或 0x0
        // 我们必须手动计算首选大小，即内部 textArea 的大小
        // 加上边框（虽然 RoundedBorder 声明无 Insets，但为了保险）
        val d = textArea?.preferredSize ?: Dimension(100, JBUI.scale(80))
        // 确保有一个最小高度，防止压扁
        val minHeight = JBUI.scale(40)
        if (d.height < minHeight) d.height = minHeight
        return d
    }

    override fun getMinimumSize(): Dimension {
        return getPreferredSize()
    }
    
    override fun doLayout() {
        val w = width
        val h = height
        textArea?.setBounds(0, 0, w, h)
        
        if (actionButton.isVisible) {
            val btnSize = actionButton.preferredSize
            val margin = JBUI.scale(6)
            val x = w - btnSize.width - margin
            val y = h - btnSize.height - margin
            actionButton.setBounds(x, y, btnSize.width, btnSize.height)
        }
    }

    override fun updateUI() {
        super.updateUI()
        textArea?.let {
            SwingUtilities.updateComponentTreeUI(it)
            setupActions()
        }
    }
    
    override fun paintComponent(g: Graphics) {
        val g2 = g as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        
        g2.color = ChatColors.inputBackground
        g2.fillRoundRect(0, 0, width - 1, height - 1, cornerRadius, cornerRadius)
    }
    
    override fun paint(g: Graphics) {
        super.paint(g)

        // 🔥 优先级：警告 > 悬浮或焦点
        if (isWarningState) {
            val g2 = g as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = ChatColors.inputBorderWarning
            g2.stroke = BasicStroke(1.5f)
            g2.drawRoundRect(0, 0, width - 1, height - 1, cornerRadius, cornerRadius)
        } else if (isHovered || isFocused) {
            // 🔥 新增：悬浮或焦点时显示高亮边框（用户气泡边框颜色）
            val g2 = g as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            // 使用用户气泡的边框颜色（灰色）作为高亮色
            g2.color = ChatColors.userBubbleBorder
            g2.stroke = BasicStroke(2.0f)  // 稍微加粗一点，更明显
            g2.drawRoundRect(0, 0, width - 1, height - 1, cornerRadius, cornerRadius)
        }
    }
    
    private fun setupActions() {
        val ta = textArea ?: return
        
        val inputMap = ta.getInputMap(JComponent.WHEN_FOCUSED)
        val actionMap = ta.actionMap

        val enterKey = KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0)
        val shiftEnterKey = KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, java.awt.event.InputEvent.SHIFT_DOWN_MASK)
        val ctrlEnterKey = KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, java.awt.event.InputEvent.CTRL_DOWN_MASK)
        val altEnterKey = KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, java.awt.event.InputEvent.ALT_DOWN_MASK)
        val metaEnterKey = KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, java.awt.event.InputEvent.META_DOWN_MASK)

        inputMap.put(enterKey, "sendMessage")
        actionMap.put("sendMessage", object : AbstractAction() {
            override fun actionPerformed(e: java.awt.event.ActionEvent?) {
                if (isSending) return 
                triggerSend()
            }
        })

        inputMap.put(shiftEnterKey, "insert-break")
        inputMap.put(ctrlEnterKey, "insert-break")
        inputMap.put(altEnterKey, "insert-break")
        inputMap.put(metaEnterKey, "insert-break")
    }
    
    private fun triggerSend() {
        val text = textArea?.text?.trim() ?: ""
        if (text.isNotEmpty()) {
            onSendCallback(text)
            textArea?.text = ""
            setSendingState(true)
        }
    }
    
    private fun triggerStop() {
        onStopCallback()
    }
    
    fun clear() {
        textArea?.text = ""
        if (textArea?.hasFocus() == false) {
            showPlaceholder = true
            textArea?.repaint()
        }
    }
}

private class ActionButton : JComponent() {
    var isStopMode = false
        set(value) {
            field = value
            repaint()
        }
        
    init {
        preferredSize = Dimension(JBUI.scale(26), JBUI.scale(26))
        isOpaque = false
    }
    
    private val iconColor = JBColor(Color(0x555555), Color(0xCCCCCC))

    override fun paintComponent(g: Graphics) {
        val g2 = g as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        
        val fullSize = Math.min(width, height)
        // Scale to 80% as requested
        val size = (fullSize * 0.8).toInt()
        
        val x = (width - size) / 2
        val y = (height - size) / 2
        
        val centerX = x + size / 2
        val centerY = y + size / 2
        
        if (isStopMode) {
            // Stop Mode: Filled Circle (High Contrast) + Hollow-like Square
            
            // Background: Use softer color
            g2.color = iconColor
            g2.fillOval(x, y, size, size)
            
            // Icon: Rounded Square
            g2.color = ChatColors.inputBackground
            // Scaled down from 10 to 8
            val rectSize = JBUI.scale(8)
            val corner = JBUI.scale(2)
            val rx = centerX - rectSize / 2
            val ry = centerY - rectSize / 2
            g2.fillRoundRect(rx, ry, rectSize, rectSize, corner, corner)
            
        } else {
            // Send Mode: Outlined Circle + Line Arrow
            
            // Border
            g2.color = ChatColors.textSecondary
            val borderStrokeWidth = JBUI.scale(1f)
            g2.stroke = BasicStroke(borderStrokeWidth)
            g2.drawOval(x, y, size - 1, size - 1)
            
            // Icon: Arrow Up (Line Style)
            g2.color = iconColor
            // Scaled down from 1.5 to 1.2
            val arrowStrokeWidth = JBUI.scale(1.2f)
            g2.stroke = BasicStroke(arrowStrokeWidth, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            
            // Scaled down: 10->8, 12->9.6(approx 10)
            val iconW = JBUI.scale(8)
            val iconH = JBUI.scale(10)
            val ix = centerX
            val iy = centerY
            
            val topY = (iy - iconH / 2).toDouble()
            val bottomY = (iy + iconH / 2).toDouble()
            val leftX = (ix - iconW / 2).toDouble()
            val rightX = (ix + iconW / 2).toDouble()
            val centerXDouble = ix.toDouble()
            
            val path = GeneralPath()
            // Shaft
            path.moveTo(centerXDouble, bottomY)
            path.lineTo(centerXDouble, topY)
            
            // Wings
            val wingY = topY + iconH * 0.4
            
            path.moveTo(leftX, wingY)
            path.lineTo(centerXDouble, topY)
            path.lineTo(rightX, wingY)
            
            g2.draw(path)
        }
    }
}

private class RoundedBorder(private val radius: Int) : AbstractBorder() {
    override fun paintBorder(c: Component?, g: Graphics?, x: Int, y: Int, width: Int, height: Int) {
        val g2 = g as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.color = com.intellij.ui.JBColor.border()
        g2.drawRoundRect(x, y, width - 1, height - 1, radius, radius)
    }
    
    override fun getBorderInsets(c: Component?): Insets {
        return JBUI.emptyInsets()
    }
    
    override fun getBorderInsets(c: Component?, insets: Insets): Insets {
        insets.left = 0
        insets.top = 0
        insets.right = 0
        insets.bottom = 0
        return insets
    }
}
