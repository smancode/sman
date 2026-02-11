package com.smancode.sman.ide.components

import com.intellij.icons.AllIcons
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.smancode.sman.ide.service.SessionInfo
import com.smancode.sman.ide.theme.ChatColors
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.text.SimpleDateFormat
import java.util.*
import javax.swing.*
import javax.swing.border.EmptyBorder

/**
 * 历史会话弹窗
 * <p>
 * 复用自 bank-core-analysis-agent 项目，适配 Sman 的数据模型
 */
class HistoryPopup(
    private val history: List<SessionInfo>,
    private val onSelect: (SessionInfo) -> Unit,
    private val onDelete: (SessionInfo) -> Unit
) {

    // 自定义垃圾桶图标
    private class TrashIcon(private val size: Int = 16, private val color: Color = JBColor.GRAY) : Icon {
        override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = color

            // 居中偏移
            val iconSize = 14
            val xOffset = x + (size - iconSize) / 2
            val yOffset = y + (size - iconSize) / 2

            // 绘制比例基于 14x14 网格
            // 盖子把手
            g2.fillRoundRect(xOffset + 5, yOffset, 4, 2, 1, 1)
            // 盖子本体
            g2.fillRoundRect(xOffset + 1, yOffset + 3, 12, 2, 1, 1)
            // 桶身
            g2.fillRoundRect(xOffset + 3, yOffset + 6, 8, 8, 2, 2)

            // 桶身纹理
            g2.color = c?.background ?: JBColor.background()
            g2.fillRect(xOffset + 5, yOffset + 7, 1, 6)
            g2.fillRect(xOffset + 7, yOffset + 7, 1, 6)
            g2.fillRect(xOffset + 9, yOffset + 7, 1, 6)

            g2.dispose()
        }

        override fun getIconWidth(): Int = size
        override fun getIconHeight(): Int = size
    }

    fun show(component: JComponent) {
        val listModel = DefaultListModel<Any>()

        // 计算时间边界
        val calendar = Calendar.getInstance()
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        val todayStart = calendar.timeInMillis

        calendar.add(Calendar.DAY_OF_YEAR, -1)
        val yesterdayStart = calendar.timeInMillis

        calendar.add(Calendar.DAY_OF_YEAR, -5)
        val weekStart = calendar.timeInMillis

        // 分桶
        val todayList = mutableListOf<SessionInfo>()
        val yesterdayList = mutableListOf<SessionInfo>()
        val weekList = mutableListOf<SessionInfo>()
        val historyList = mutableListOf<SessionInfo>()

        history.forEach { conversation ->
            val ts = conversation.updatedTime
            when {
                ts >= todayStart -> todayList.add(conversation)
                ts >= yesterdayStart -> yesterdayList.add(conversation)
                ts >= weekStart -> weekList.add(conversation)
                else -> historyList.add(conversation)
            }
        }

        // 按顺序构建 Model，强制显示所有分组
        listModel.addElement(GroupSeparator("今天"))
        todayList.forEach { listModel.addElement(it) }

        listModel.addElement(GroupSeparator("昨天"))
        yesterdayList.forEach { listModel.addElement(it) }

        listModel.addElement(GroupSeparator("一周内"))
        weekList.forEach { listModel.addElement(it) }

        listModel.addElement(GroupSeparator("历史会话"))
        historyList.forEach { listModel.addElement(it) }

        // 状态变量，用于跟踪悬停项
        var currentHoverIndex = -1

        val list = JBList(listModel)
        list.setExpandableItemsEnabled(false)
        list.cellRenderer = HistoryListCellRenderer(onDelete) { currentHoverIndex }
        list.background = ChatColors.surface
        list.selectionMode = ListSelectionModel.SINGLE_SELECTION

        // 移除默认的 SelectionBackground
        list.selectionBackground = JBColor(Color(0, 0, 0, 0), Color(0, 0, 0, 0))
        list.selectionForeground = ChatColors.textPrimary

        val mouseAdapter = object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                val index = list.locationToIndex(e.point)
                if (index != -1) {
                    val element = listModel.getElementAt(index)
                    if (element is SessionInfo) {
                        val cellBounds = list.getCellBounds(index, index)
                        if (cellBounds != null && cellBounds.contains(e.point)) {
                            // 右侧 40px 为删除按钮热区
                            if (e.point.x > cellBounds.width - JBUI.scale(40)) {
                                onDelete(element)
                                listModel.removeElement(element)
                            } else {
                                onSelect(element)
                            }
                        }
                    }
                }
            }

            override fun mouseMoved(e: MouseEvent) {
                val index = list.locationToIndex(e.point)
                val cellBounds = list.getCellBounds(index, index)
                val actualIndex = if (cellBounds != null && cellBounds.contains(e.point)) index else -1

                if (currentHoverIndex != actualIndex) {
                    val oldIndex = currentHoverIndex
                    currentHoverIndex = actualIndex

                    if (oldIndex != -1) {
                        val bounds = list.getCellBounds(oldIndex, oldIndex)
                        if (bounds != null) list.repaint(bounds)
                    }
                    if (actualIndex != -1) {
                        val bounds = list.getCellBounds(actualIndex, actualIndex)
                        if (bounds != null) list.repaint(bounds)
                    }
                }
            }

            override fun mouseExited(e: MouseEvent) {
                if (currentHoverIndex != -1) {
                    val oldIndex = currentHoverIndex
                    currentHoverIndex = -1
                    val bounds = list.getCellBounds(oldIndex, oldIndex)
                    if (bounds != null) list.repaint(bounds)
                    else list.repaint()
                }
            }
        }

        list.addMouseListener(mouseAdapter)
        list.addMouseMotionListener(mouseAdapter)
        list.isOpaque = false
        list.background = JBColor(Color(0, 0, 0, 0), Color(0, 0, 0, 0))

        // 圆角面板
        val roundedPanel = RoundedPanel(component)
        roundedPanel.border = JBUI.Borders.empty(6)
        roundedPanel.isOpaque = false

        val scrollPane = JBScrollPane(list).apply {
            border = JBUI.Borders.empty()
            viewport.background = null
            viewport.isOpaque = false
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
            verticalScrollBar.preferredSize = Dimension(0, 0)

            isOpaque = false
            background = null
            isWheelScrollingEnabled = true
        }
        roundedPanel.add(scrollPane, BorderLayout.CENTER)

        JBPopupFactory.getInstance()
            .createComponentPopupBuilder(roundedPanel, list)
            .setMovable(true)
            .setResizable(true)
            .setRequestFocus(true)
            .setShowBorder(false)
            .setShowShadow(true)
            .setMinSize(Dimension(JBUI.scale(250), JBUI.scale(300)))
            .setLocateWithinScreenBounds(true)
            .createPopup()
            .apply {
                val contentComponent = content
                contentComponent.isOpaque = false
                contentComponent.background = Color(0, 0, 0, 0)
            }
            .showUnderneathOf(component)
    }

    data class GroupSeparator(val title: String)

    /**
     * 圆角面板容器
     */
    private class RoundedPanel(private val component: JComponent) : JPanel(BorderLayout()) {
        override fun paintComponent(g: Graphics) {
            val g2 = g as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

            // 绘制背景
            g2.color = ChatColors.surface
            g2.fillRoundRect(0, 0, width, height, JBUI.scale(12), JBUI.scale(12))

            // 绘制边框
            g2.color = ChatColors.userBubbleBorder
            val oldStroke = g2.stroke
            g2.stroke = BasicStroke(1.5f)
            g2.drawRoundRect(0, 0, width - 1, height - 1, JBUI.scale(12), JBUI.scale(12))
            g2.stroke = oldStroke
        }

        override fun getPreferredSize(): Dimension {
            val size = super.getPreferredSize()

            // 计算最大宽度
            var refWidth = 0
            var p = component.parent
            var depth = 0

            while (p != null && depth < 10) {
                if (p.width > JBUI.scale(200)) {
                    refWidth = p.width
                    break
                }
                p = p.parent
                depth++
            }

            if (refWidth < JBUI.scale(200)) {
                val window = SwingUtilities.getWindowAncestor(component)
                refWidth = ((window?.width ?: 1200) * 0.3).toInt()
            }

            val maxWidth = (refWidth * 0.75).toInt()

            if (size.width > maxWidth) {
                size.width = maxWidth
            }
            return size
        }
    }

    /**
     * 自定义内容面板，支持悬停和选中状态绘制
     */
    private class ContentPanel(
        private val isSelected: Boolean,
        private val isHovered: Boolean
    ) : JPanel(BorderLayout()) {
        override fun paintComponent(g: Graphics) {
            val g2 = g as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

            // 遮罩层
            g2.color = ChatColors.surface
            g2.fillRect(0, 0, width, height)

            val arc = JBUI.scale(12)

            if (isSelected) {
                g2.color = ChatColors.userBubbleBorder
                g2.fillRoundRect(0, 0, width, height, arc, arc)
            } else if (isHovered) {
                g2.color = ChatColors.userBubbleBorder
                val oldStroke = g2.stroke
                g2.stroke = BasicStroke(1.5f)
                g2.drawRoundRect(1, 1, width - 2, height - 2, arc, arc)
                g2.stroke = oldStroke
            }

            super.paintChildren(g)
        }
    }

    class HistoryListCellRenderer(
        private val onDelete: (SessionInfo) -> Unit,
        private val hoverIndexProvider: () -> Int
    ) : ListCellRenderer<Any> {

        override fun getListCellRendererComponent(
            list: JList<out Any>,
            value: Any,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean
        ): Component {
            val panel = JPanel(BorderLayout())
            panel.border = JBUI.Borders.empty(4, 8)
            panel.isOpaque = false

            if (value is GroupSeparator) {
                val separatorPanel = JPanel(GridBagLayout())
                separatorPanel.isOpaque = false
                val gbc = GridBagConstraints()
                gbc.gridx = 0
                gbc.weightx = 1.0
                gbc.fill = GridBagConstraints.HORIZONTAL
                gbc.gridy = 0

                if (value.title != "今天") {
                    val line = JSeparator(SwingConstants.HORIZONTAL)
                    line.foreground = JBColor(Color(0xE0E0E0), Color(0x454545))
                    gbc.insets = JBUI.insets(8, 0, 12, 0)
                    separatorPanel.add(line, gbc)
                    gbc.gridy++
                    gbc.insets = JBUI.emptyInsets()
                }

                val titleLabel = JLabel(value.title)
                titleLabel.foreground = JBColor.GRAY
                titleLabel.font = UIUtil.getLabelFont().deriveFont(Font.BOLD, JBUI.scale(11).toFloat())
                titleLabel.border = JBUI.Borders.empty(0, 0, 4, 0)

                separatorPanel.add(titleLabel, gbc)
                panel.add(separatorPanel, BorderLayout.CENTER)
                return panel
            }

            if (value is SessionInfo) {
                val isHovered = index == hoverIndexProvider()

                val contentPanel = ContentPanel(isSelected, isHovered)
                contentPanel.isOpaque = false
                contentPanel.border = JBUI.Borders.empty(6, 10, 6, 10)

                val titleText = value.title.takeIf { it.isNotBlank() } ?: "新会话"
                val titleLabel = JLabel(titleText)

                titleLabel.foreground = ChatColors.textPrimary
                titleLabel.putClientProperty("html.disable", java.lang.Boolean.TRUE)

                contentPanel.add(titleLabel, BorderLayout.CENTER)

                if (isHovered || isSelected) {
                    val deleteIcon = TrashIcon(JBUI.scale(16), ChatColors.textSecondary)
                    val deleteLabel = JLabel(deleteIcon)
                    deleteLabel.border = JBUI.Borders.emptyLeft(8)
                    deleteLabel.preferredSize = Dimension(JBUI.scale(24), JBUI.scale(24))
                    contentPanel.add(deleteLabel, BorderLayout.EAST)
                } else {
                    val spacer = JLabel()
                    spacer.preferredSize = Dimension(JBUI.scale(24), JBUI.scale(24))
                    spacer.border = JBUI.Borders.emptyLeft(8)
                    contentPanel.add(spacer, BorderLayout.EAST)
                }

                return contentPanel
            }
            return panel
        }
    }
}
