package ai.smancode.sman.ide.ui.components

import com.intellij.icons.AllIcons
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import ai.smancode.sman.ide.ui.ChatColors
import java.awt.*
import java.awt.event.ActionEvent
import java.awt.event.ActionListener
import javax.swing.*
import javax.swing.Timer
import javax.swing.border.EmptyBorder

class ControlBar(
    private val onClearCallback: () -> Unit,
    private val onDeleteCallback: (JComponent) -> Unit,
    private val onSettingsCallback: () -> Unit,
    private val onRoleToggleCallback: (String) -> Unit
) : JPanel(BorderLayout()) {
    
    private val currentRoleType = "plan"
    
    init {
        background = ChatColors.surface
        // 移除黑线，只保留 padding，大幅减小垂直边距以显干练
        border = EmptyBorder(JBUI.scale(2), JBUI.scale(8), JBUI.scale(2), JBUI.scale(8))
        
        val controlPanel = JPanel(BorderLayout()).apply {
            background = ChatColors.surface
            // 移除多余的垂直 padding
            border = EmptyBorder(0, JBUI.scale(4), 0, JBUI.scale(4))
        }
        
        // 调整 hgap 为 0，使按钮更靠左
        val leftPanel = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            background = ChatColors.surface
            // 往中间移动一点点 (左侧增加间距)
            add(Box.createHorizontalStrut(JBUI.scale(4)))
            add(createClearButton())
            add(Box.createHorizontalStrut(JBUI.scale(8))) // 手动控制间距
            add(createHistoryButton())
        }
        
        val rightPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0)).apply {
            background = ChatColors.surface
            add(createSettingsButton())
            // 往中间移动一点点 (右侧增加间距)
            add(Box.createHorizontalStrut(JBUI.scale(4)))
        }
        
        controlPanel.add(leftPanel, BorderLayout.WEST)
        controlPanel.add(rightPanel, BorderLayout.EAST)
        
        add(controlPanel, BorderLayout.CENTER)
    }
    
    private var clearButton: JButton? = null
    
    private fun createClearButton(): JButton {
        // 新建会话 -> Add Icon
        val btn = createIconButton(AllIcons.General.Add, "新建会话")
        clearButton = btn
        btn.addActionListener {
            animateAddButton(btn)
            onClearCallback()
        }
        return btn
    }

    private fun createHistoryButton(): JButton {
        // 历史记录 -> Clock Icon
        val btn = createIconButton(AllIcons.Vcs.History, "历史记录")
        btn.addActionListener {
            animateHistoryButton(btn)
            onDeleteCallback(btn)
        }
        return btn
    }
    
    private fun createSettingsButton(): JButton {
        // 设置 -> 自定义粗体水平三点图标
        return createIconButton(BoldEllipsisIcon(), "设置").apply {
            addActionListener { onSettingsCallback() }
        }
    }

    // 自定义粗体水平三点图标
    private class BoldEllipsisIcon : Icon {
        override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            
            // 使用深灰/浅灰，适配深色模式
            g2.color = JBColor(Color(0x606060), Color(0xBBBBBB))
            
            // 直径 3.5px，比默认的 2px 粗很多
            val dotSize = JBUI.scale(3.5f).toInt() 
            val gap = JBUI.scale(3) // 间距
            
            // 计算绘制起始点，确保整体居中
            // 整体宽度 = 3个点 + 2个间距
            val totalWidth = dotSize * 3 + gap * 2
            
            val startX = x + (iconWidth - totalWidth) / 2
            val startY = y + (iconHeight - dotSize) / 2
            
            // 左点
            g2.fillOval(startX, startY, dotSize, dotSize)
            
            // 中点
            g2.fillOval(startX + dotSize + gap, startY, dotSize, dotSize)
            
            // 右点
            g2.fillOval(startX + (dotSize + gap) * 2, startY, dotSize, dotSize)
            
            g2.dispose()
        }

        override fun getIconWidth(): Int = JBUI.scale(16)
        override fun getIconHeight(): Int = JBUI.scale(16)
    }
    
    // 新建会话按钮动画：缩放 + 变亮
    private fun animateAddButton(btn: JButton) {
        val animationDuration = 300
        val steps = 20
        val delay = animationDuration / steps
        val minScale = 0.85
        
        val timer = Timer(delay, null)
        var currentStep = 0
        
        timer.addActionListener {
            currentStep++
            val progress = currentStep.toDouble() / steps
            
            // 抛物线运动：0 -> 1 -> 0
            val parabola = Math.sin(progress * Math.PI)
            
            // 缩放：1.0 -> minScale -> 1.0
            val scale = 1.0 - (parabola * (1.0 - minScale))
            
            // 变亮：0.0 -> 1.0 -> 0.0 (强度)
            // 增加强度系数，让变亮更明显
            val flashIntensity = parabola * 3.0
            
            btn.putClientProperty("animBounceScale", scale)
            btn.putClientProperty("animFlash", flashIntensity)
            btn.repaint()
            
            if (currentStep >= steps) {
                timer.stop()
                btn.putClientProperty("animBounceScale", 1.0)
                btn.putClientProperty("animFlash", 0.0)
                btn.repaint()
            }
        }
        timer.start()
    }
    
    // 历史记录按钮动画：缩放
    private fun animateHistoryButton(btn: JButton) {
        val animationDuration = 300
        val steps = 20
        val delay = animationDuration / steps
        val minScale = 0.85
        
        val timer = Timer(delay, null)
        var currentStep = 0
        
        timer.addActionListener {
            currentStep++
            val progress = currentStep.toDouble() / steps
            
            // 抛物线运动：0 -> 1 -> 0
            val parabola = Math.sin(progress * Math.PI)
            
            // 缩放：1.0 -> minScale -> 1.0
            val scale = 1.0 - (parabola * (1.0 - minScale))
            
            btn.putClientProperty("animBounceScale", scale)
            btn.repaint()
            
            if (currentStep >= steps) {
                timer.stop()
                btn.putClientProperty("animBounceScale", 1.0)
                btn.repaint()
            }
        }
        timer.start()
    }
    
    // 现代化动画：轻微缩放 + 轻微上移（类似iOS/Material Design的微交互）
    fun animateButtonBounce() {
        val btn = clearButton ?: return
        val bounceHeight = JBUI.scale(3) // 轻微上移，更微妙
        val scaleAmount = 0.05 // 轻微放大5%
        val animationDuration = 300 // 稍长的持续时间，更优雅
        val steps = 24 // 更多帧数，更流畅
        val delay = animationDuration / steps
        
        val timer = Timer(delay, null)
        var currentStep = 0
        
        timer.addActionListener {
            currentStep++
            val progress = currentStep.toDouble() / steps
            
            // 计算缩放值：从1.0 -> 1.05 -> 1.0，使用正弦波让过渡更平滑
            // 正弦波在0到π之间，从0到1再到0，完美适合缩放动画
            val scale = 1.0 + (Math.sin(progress * Math.PI) * scaleAmount)
            
            // 计算垂直偏移：向上移动再落下，使用相同的正弦波
            val offset = -(Math.sin(progress * Math.PI) * bounceHeight).toInt()
            
            btn.putClientProperty("animBounceOffset", offset)
            btn.putClientProperty("animBounceScale", scale)
            btn.repaint()
            
            if (currentStep >= steps) {
                timer.stop()
                btn.putClientProperty("animBounceOffset", 0)
                btn.putClientProperty("animBounceScale", 1.0)
                btn.repaint()
            }
        }
        timer.start()
    }
    
    // 实现点击时的位移动画
    private fun animateButtonSlide(btn: JButton) {
        val animationDuration = 300 // 时长翻倍
        val steps = 20 // 帧数翻倍保持流畅
        val delay = animationDuration / steps
        val maxOffset = JBUI.scale(12) // 位移增加
        
        val timer = Timer(delay, null)
        var currentStep = 0
        
        timer.addActionListener {
            currentStep++
            val progress = currentStep.toDouble() / steps
            
            // 1. 位移动画 (0 -> -max -> 0)
            val offset = if (progress < 0.5) {
                (progress * 2 * maxOffset).toInt()
            } else {
                ((1.0 - progress) * 2 * maxOffset).toInt()
            }
            
            // 2. 闪烁动画 (0 -> 1.0 -> 0)
            // 在中间点 (progress=0.5) 最亮
            val flashIntensity = if (progress < 0.5) {
                progress * 2
            } else {
                (1.0 - progress) * 2
            }
            
            btn.putClientProperty("animOffset", -offset)
            btn.putClientProperty("animFlash", flashIntensity)
            btn.repaint()
            
            if (currentStep >= steps) {
                timer.stop()
                btn.putClientProperty("animOffset", 0)
                btn.putClientProperty("animFlash", 0.0)
                btn.repaint()
            }
        }
        timer.start()
    }
    
    private fun createIconButton(icon: Icon, tooltip: String): JButton {
        val cornerRadius = JBUI.scale(6)
        return object : JButton(icon) {
            // 覆盖 paintBorder 确保完全不绘制任何边框
            override fun paintBorder(g: Graphics?) {
                // Do nothing
            }

            override fun paintComponent(g: Graphics) {
                val g2 = g as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                
                // 应用动画偏移（水平滑动）
                val animOffset = (getClientProperty("animOffset") as? Int) ?: 0
                // 应用跳动偏移（垂直跳动）
                val animBounceOffset = (getClientProperty("animBounceOffset") as? Int) ?: 0
                // 应用缩放效果
                val animScale = (getClientProperty("animBounceScale") as? Double) ?: 1.0
                
                // 先移动到中心点，应用缩放，再移回
                val centerX = width / 2.0
                val centerY = height / 2.0
                g2.translate(centerX, centerY)
                g2.scale(animScale, animScale)
                g2.translate(-centerX, -centerY)
                
                // 应用位置偏移
                g2.translate(animOffset, animBounceOffset)
                
                // 仅在按下或悬停时显示背景
                if (model.isPressed || model.isRollover) {
                    val currentBg = Color(ChatColors.surface.red.coerceAtMost(255), ChatColors.surface.green.coerceAtMost(255), ChatColors.surface.blue.coerceAtMost(255), 200)
                    
                    // 应用闪烁效果 (变亮)
                    val flashIntensity = (getClientProperty("animFlash") as? Double) ?: 0.0
                    val finalBg = if (flashIntensity > 0) {
                        val alpha = (flashIntensity * 0.08).coerceIn(0.0, 1.0)
                        val r = (currentBg.red * (1 - alpha) + 255 * alpha).toInt()
                        val g = (currentBg.green * (1 - alpha) + 255 * alpha).toInt()
                        val b = (currentBg.blue * (1 - alpha) + 255 * alpha).toInt()
                        Color(r, g, b)
                    } else {
                        currentBg
                    }
                    
                    // 不绘制背景矩形，只绘制图标的交互效果
                    // 如果需要Hover背景，可以取消下面这行的注释，但用户要求去除外框（通常也指去除背景块）
                    // g2.color = finalBg
                    // g2.fillRoundRect(0, 0, width, height, cornerRadius, cornerRadius)
                }
                
                // 绘制图标 (居中)
                val iconX = (width - icon.iconWidth) / 2
                val iconY = (height - icon.iconHeight) / 2
                icon.paintIcon(this, g2, iconX, iconY)
                
                // 恢复变换（逆序）
                g2.translate(-animOffset, -animBounceOffset)
                g2.translate(centerX, centerY)
                g2.scale(1.0 / animScale, 1.0 / animScale)
                g2.translate(-centerX, -centerY)
            }
        }.apply {
            toolTipText = tooltip
            background = ChatColors.surface
            isContentAreaFilled = false
            isOpaque = false
            isFocusPainted = false
            // 强制移除边框
            border = null
            // 强制不绘制默认边框
            isBorderPainted = false
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            // 统一大小，减小高度以匹配更干练的风格 (26x26)
            preferredSize = Dimension(JBUI.scale(30), JBUI.scale(26))
        }
    }
    
    fun getCurrentRoleType(): String = currentRoleType
    
    fun isCodingMode(): Boolean = false
}
