package com.smancode.sman.ide.components

import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*
import javax.swing.border.EmptyBorder

/**
 * 代码引用数据模型
 *
 * @property filePath 文件相对路径
 * @property fileName 文件名
 * @property startLine 起始行号（1-based）
 * @property endLine 结束行号（1-based）
 * @property codeContent 代码内容
 */
data class CodeReference(
    val filePath: String,
    val fileName: String,
    val startLine: Int,
    val endLine: Int,
    val codeContent: String
) {
    /**
     * 获取显示文本（例如：DisburseHandler.java 10-15）
     */
    fun getDisplayText(): String = "$fileName $startLine-$endLine"

    /**
     * 获取完整描述
     */
    fun getFullDescription(): String = "$filePath:$startLine-$endLine"
}

/**
 * 代码引用标签组件
 *
 * 特点：
 * - 灰色背景
 * - 显示文件名和行号范围
 * - 右侧有删除按钮（×）
 * - 不可编辑，但可删除
 * - Hover 时高亮
 */
class CodeReferenceTag(
    private val codeReference: CodeReference,
    private val onDelete: () -> Unit
) : JPanel() {

    private val label: JLabel
    private val deleteButton: JLabel

    // 颜色配置
    private val normalBackground = Color(240, 240, 240)
    private val hoverBackground = Color(230, 230, 230)
    private val borderColor = Color(200, 200, 200)
    private val textColor = Color(80, 80, 80)
    private val deleteColor = Color(150, 150, 150)
    private val deleteHoverColor = Color(200, 50, 50)

    init {
        isOpaque = true
        background = normalBackground
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(borderColor, 1),
            EmptyBorder(4, 8, 4, 6)
        )
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        cursor = Cursor.getDefaultCursor()

        // 文件名和行号标签
        label = JLabel(codeReference.getDisplayText()).apply {
            foreground = textColor
            font = font.deriveFont(11f)
        }
        add(label)

        // 间距
        add(Box.createHorizontalStrut(8))

        // 删除按钮
        deleteButton = JLabel("×").apply {
            foreground = deleteColor
            font = font.deriveFont(16f)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            border = EmptyBorder(0, 4, 0, 0)

            // Hover 效果
            addMouseListener(object : MouseAdapter() {
                override fun mouseEntered(e: MouseEvent?) {
                    foreground = deleteHoverColor
                }

                override fun mouseExited(e: MouseEvent?) {
                    foreground = deleteColor
                }

                override fun mouseClicked(e: MouseEvent?) {
                    onDelete()
                }
            })
        }
        add(deleteButton)

        // 整体 Hover 效果
        addMouseListener(object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent?) {
                background = hoverBackground
                repaint()
            }

            override fun mouseExited(e: MouseEvent?) {
                background = normalBackground
                repaint()
            }
        })

        // 设置首选大小
        preferredSize = Dimension(preferredSize.width, 28)
        maximumSize = Dimension(Int.MAX_VALUE, 28)
    }

    /**
     * 获取代码引用数据
     */
    fun getCodeReference(): CodeReference = codeReference
}
