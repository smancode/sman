package com.smancode.sman.ide.component

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.awt.RelativePoint
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.awt.Point
import javax.swing.*

/**
 * 代码引用提示管理器
 *
 * 在编辑器中选中代码时显示浮动提示按钮
 */
object CodeReferenceHintProvider {

    private val logger: Logger = LoggerFactory.getLogger(CodeReferenceHintProvider::class.java)
    private var currentPopup: Any? = null

    /**
     * 显示代码引用提示
     *
     * @param editor 当前编辑器
     * @param onInsertClick 点击时的回调
     */
    fun showHint(editor: Editor, onInsertClick: () -> Unit) {
        logger.debug("showHint 被调用")

        // 隐藏之前的提示
        hideHint()

        val isMac = System.getProperty("os.name").lowercase().contains("mac")
        val shortcut = if (isMac) "⌘I" else "Ctrl+I"

        // 创建提示面板
        val hintPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = true
            background = java.awt.Color(60, 120, 180)
            border = javax.swing.border.EmptyBorder(6, 12, 6, 12)

            // 添加图标
            val iconLabel = JLabel("⌨️").apply {
                foreground = java.awt.Color.WHITE
            }
            add(iconLabel)

            // 添加间距
            add(Box.createHorizontalStrut(8))

            // 添加文本
            val textLabel = JLabel("添加到 SmanAgent ($shortcut)").apply {
                foreground = java.awt.Color.WHITE
                font = font.deriveFont(11f)
            }
            add(textLabel)

            // 鼠标悬停效果
            addMouseListener(object : java.awt.event.MouseAdapter() {
                override fun mouseEntered(e: java.awt.event.MouseEvent?) {
                    background = java.awt.Color(80, 140, 200)
                }

                override fun mouseExited(e: java.awt.event.MouseEvent?) {
                    background = java.awt.Color(60, 120, 180)
                }

                override fun mouseClicked(e: java.awt.event.MouseEvent?) {
                    logger.info("提示被点击，执行回调")
                    onInsertClick()
                    hideHint()
                }
            })

            cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
        }

        // 计算提示位置（在选区下方）
        val selectionModel = editor.selectionModel
        if (!selectionModel.hasSelection()) {
            logger.warn("showHint: 没有选区，取消显示")
            return
        }

        val startPosition = editor.visualPositionToXY(
            editor.offsetToVisualPosition(selectionModel.selectionStart)
        )
        val endPosition = editor.visualPositionToXY(
            editor.offsetToVisualPosition(selectionModel.selectionEnd)
        )

        // 提示显示在选区下方中央
        val point = Point(
            (startPosition.x + endPosition.x) / 2 - 100,
            endPosition.y + editor.lineHeight + 5
        )

        logger.debug("提示位置: x={}, y={}", point.x, point.y)

        // 使用 JBPopupFactory 创建弹出提示
        val popup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(hintPanel, null)
            .setRequestFocus(false)
            .setResizable(false)
            .setMovable(false)
            .setCancelOnClickOutside(true)
            .createPopup()

        // 显示在指定位置
        val relativePoint = RelativePoint(editor.component, point)
        popup.show(relativePoint)

        currentPopup = popup
        logger.info("代码引用提示已显示")
    }

    /**
     * 隐藏提示
     */
    fun hideHint() {
        if (currentPopup != null) {
            logger.debug("隐藏提示")
            try {
                // 使用反射调用 cancel() 方法
                val method = currentPopup!!.javaClass.getMethod("cancel")
                method.invoke(currentPopup)
            } catch (e: Exception) {
                logger.debug("隐藏提示时出错: {}", e.message)
            }
            currentPopup = null
        }
    }

    /**
     * 检查是否正在显示提示
     */
    fun isShowing(): Boolean {
        return currentPopup?.let {
            try {
                val method = it.javaClass.getMethod("isVisible")
                method.invoke(it) as? Boolean ?: false
            } catch (e: Exception) {
                false
            }
        } ?: false
    }
}
