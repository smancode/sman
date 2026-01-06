package ai.smancode.sman.ide.ui.components

import com.intellij.icons.AllIcons
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import javax.swing.plaf.ListUI
import javax.swing.plaf.basic.BasicListUI
import ai.smancode.sman.ide.service.ProjectStorageService
import ai.smancode.sman.ide.ui.ChatColors
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.text.SimpleDateFormat
import java.util.*
import javax.swing.*
import javax.swing.border.EmptyBorder

class HistoryPopup(
    private val history: List<ProjectStorageService.Conversation>,
    private val onSelect: (ProjectStorageService.Conversation) -> Unit,
    private val onDelete: (ProjectStorageService.Conversation) -> Unit
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
            
            // 桶身纹理（用背景色擦除出竖线效果，或者绘制线条）
            // 这里用绘制线条的方式，需要设置背景色对比，或者简单点，直接画空心桶身 + 竖线
            // 为了简洁实心风格：
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

        // "一周内"定义为：从昨天往前推5天（即不包含今天和昨天的最近5天）
        // 或者按照用户描述 "3-6天"，这里我们取 logical 7 days window excluding today/yesterday
        // 也就是 todayStart - 6天 (覆盖 Day-2 到 Day-6)
        calendar.add(Calendar.DAY_OF_YEAR, -5) 
        val weekStart = calendar.timeInMillis

        // 分桶
        val todayList = mutableListOf<ProjectStorageService.Conversation>()
        val yesterdayList = mutableListOf<ProjectStorageService.Conversation>()
        val weekList = mutableListOf<ProjectStorageService.Conversation>()
        val historyList = mutableListOf<ProjectStorageService.Conversation>()

        history.forEach { conversation ->
            val ts = conversation.timestamp
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
        // 禁用自动展开长文本的特性，防止悬停时弹出超长气泡
        list.setExpandableItemsEnabled(false)
        list.cellRenderer = HistoryListCellRenderer(onDelete) { currentHoverIndex }
        list.background = ChatColors.surface
        list.selectionMode = ListSelectionModel.SINGLE_SELECTION
        
        // 移除默认的 SelectionBackground，完全由 Renderer 接管
        list.selectionBackground = JBColor(Color(0, 0, 0, 0), Color(0, 0, 0, 0))
        list.selectionForeground = ChatColors.textPrimary
        
        val mouseAdapter = object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                val index = list.locationToIndex(e.point)
                if (index != -1) {
                    val element = listModel.getElementAt(index)
                    if (element is ProjectStorageService.Conversation) {
                        // Check if delete button was clicked
                        val deleteBtnWidth = JBUI.scale(24)
                        val cellBounds = list.getCellBounds(index, index)
                        if (cellBounds != null && cellBounds.contains(e.point)) {
                             // 右侧 24px (图标容器) + 10px (Padding) 区域
                             // 为了更好的点击体验，我们将点击热区稍微扩大一点 (比如 40px)
                             if (e.point.x > cellBounds.width - JBUI.scale(40)) {
                                 onDelete(element)
                                 // 从 Model 中移除，实现即时刷新
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
                // 只有当鼠标真正在该项的边界内时才高亮 (locationToIndex 在空白处也会返回最近项)
                val cellBounds = list.getCellBounds(index, index)
                val actualIndex = if (cellBounds != null && cellBounds.contains(e.point)) index else -1
                
                if (currentHoverIndex != actualIndex) {
                    val oldIndex = currentHoverIndex
                    currentHoverIndex = actualIndex
                    
                    // 优化重绘：只重绘受影响的区域
                    if (oldIndex != -1) {
                         val bounds = list.getCellBounds(oldIndex, oldIndex)
                         if (bounds != null) list.repaint(bounds)
                    }
                    if (actualIndex != -1) {
                         val bounds = list.getCellBounds(actualIndex, actualIndex)
                         if (bounds != null) list.repaint(bounds)
                    }
                    
                    // 如果没有选中任何项或者 index 变化大，简单起见也可以全量重绘，但局部重绘性能更好且闪烁更少
                    if (oldIndex == -1 && actualIndex == -1) {
                        // do nothing
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
        // Ensure list is transparent to show rounded panel background
        list.isOpaque = false
        list.background = JBColor(Color(0, 0, 0, 0), Color(0, 0, 0, 0))

        // Wrap list in a rounded panel
        val roundedPanel = object : JPanel(BorderLayout()) {
            override fun paintComponent(g: Graphics) {
                val g2 = g as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                
                // 1. 绘制背景
                g2.color = ChatColors.surface
                g2.fillRoundRect(0, 0, width, height, JBUI.scale(12), JBUI.scale(12))
                
                // 2. 绘制边框 (用户气泡颜色)
                g2.color = ChatColors.userBubbleBorder
                val oldStroke = g2.stroke
                g2.stroke = BasicStroke(1.5f)
                g2.drawRoundRect(0, 0, width - 1, height - 1, JBUI.scale(12), JBUI.scale(12))
                g2.stroke = oldStroke
            }

            override fun getPreferredSize(): Dimension {
                val size = super.getPreferredSize()
                
                // 计算最大宽度：参考用户气泡宽度，通常为 ToolWindow 宽度的 75%
                // 核心逻辑修正：不能直接使用 WindowAncestor (那是整个 IDE 窗口)，
                // 而是应该向上寻找最近的宽容器 (通常是 ChatPanel 或 ContentPanel)
                
                var refWidth = 0
                var p = component.parent
                var depth = 0
                
                // 向上遍历寻找第一个宽度合理的容器 (> 200px)
                while (p != null && depth < 10) {
                    if (p.width > JBUI.scale(200)) {
                        refWidth = p.width
                        break
                    }
                    p = p.parent
                    depth++
                }
                
                // 兜底：如果找不到合适的父容器 (refWidth 仍为 0 或太小)，
                // 尝试获取 IDE 窗口宽度并按 ToolWindow 常见比例 (25%-30%) 估算
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
        roundedPanel.border = JBUI.Borders.empty(6) // 增加 Padding 防止内容贴边
        roundedPanel.isOpaque = false
        
        val scrollPane = JBScrollPane(list).apply {
            border = JBUI.Borders.empty()
            viewport.background = null 
            viewport.isOpaque = false
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            // 改回 AS_NEEDED 以恢复滚轮支持，但通过设置大小为 0 来隐藏它
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
                // 尝试强制设置 Popup 内容的透明背景
                val contentComponent = content
                contentComponent.isOpaque = false
                contentComponent.background = Color(0, 0, 0, 0)
            }
            .showUnderneathOf(component)
    }

    data class GroupSeparator(val title: String)

    class HistoryListCellRenderer(
        private val onDelete: (ProjectStorageService.Conversation) -> Unit,
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
            panel.isOpaque = false // 确保透明
            
            if (value is GroupSeparator) {
                val separatorPanel = JPanel(GridBagLayout())
                separatorPanel.isOpaque = false
                val gbc = GridBagConstraints()
                gbc.gridx = 0
                gbc.weightx = 1.0
                gbc.fill = GridBagConstraints.HORIZONTAL
                gbc.gridy = 0

                // 只有非"今天"的分组才显示顶部分隔线
                if (value.title != "今天") {
                    val line = JSeparator(SwingConstants.HORIZONTAL)
                    line.foreground = JBColor(Color(0xE0E0E0), Color(0x454545)) // 浅灰色线条
                    
                    // 使用 GridBagConstraints 的 insets 来控制外部间距，确保分隔线和下方标题有足够的距离
                    // Top 8: 与上一组的间距
                    // Bottom 16: 与本组标题的间距
                    gbc.insets = JBUI.insets(8, 0, 12, 0)
                    separatorPanel.add(line, gbc)
                    gbc.gridy++
                    
                    // 重置 insets，避免影响后续组件（标题）
                    gbc.insets = JBUI.emptyInsets()
                }

                val titleLabel = JLabel(value.title)
                titleLabel.foreground = JBColor.GRAY // 暗色
                titleLabel.font = UIUtil.getLabelFont().deriveFont(Font.BOLD, JBUI.scale(11).toFloat())
                // 标题下方留一点间距，与第一个列表项隔开
                titleLabel.border = JBUI.Borders.empty(0, 0, 4, 0)
                
                separatorPanel.add(titleLabel, gbc)
                
                panel.add(separatorPanel, BorderLayout.CENTER)
                return panel
            }

            if (value is ProjectStorageService.Conversation) {
                val isHovered = index == hoverIndexProvider()
                
                // 强制使用透明背景，如果是 Hover 状态则绘制高亮背景
                // 直接设置 panel.background 可能被 JList 机制覆盖，或者因为 isOpaque=true 而绘制矩形
                // 我们使用自定义绘制来确保圆角高亮或者正确的背景
                
                val contentPanel = object : JPanel(BorderLayout()) {
                    override fun paintComponent(g: Graphics) {
                        val g2 = g as Graphics2D
                        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                        
                        // 0. 遮罩层：先绘制与 Popup 背景一致的颜色，覆盖可能存在的 JList 默认选中背景（如亮蓝色矩形）
                        // 这样可以确保我们的圆角外部是看起来是“透明”的（实际上是 Surface 色）
                        g2.color = ChatColors.surface
                        g2.fillRect(0, 0, width, height)
                        
                        val arc = JBUI.scale(12) // 圆角大小
                        
                        if (isSelected) {
                            // 1. 选中状态：背景色采用用户气泡的边框颜色 (淡淡的灰色)
                            g2.color = ChatColors.userBubbleBorder
                            g2.fillRoundRect(0, 0, width, height, arc, arc)
                        } else if (isHovered) {
                            // 2. 悬停状态：使用柔和的边框
                            g2.color = ChatColors.userBubbleBorder
                            val oldStroke = g2.stroke
                            g2.stroke = BasicStroke(1.5f)
                            // 边框绘制在边界内，防止被裁剪
                            g2.drawRoundRect(1, 1, width - 2, height - 2, arc, arc)
                            g2.stroke = oldStroke
                        } else {
                           // 正常状态透明
                        }
                        super.paintChildren(g) // 只需要绘制子组件
                    }
                }
                contentPanel.isOpaque = false
                // 减少右侧 Padding (25 -> 10)，让垃圾桶靠右
                contentPanel.border = JBUI.Borders.empty(6, 10, 6, 10)
                
                // 截断过长的标题
                val titleText = value.title.takeIf { it.isNotBlank() } ?: "新会话"
                val titleLabel = JLabel(titleText)
                
                // 字体颜色逻辑
                if (isSelected) {
                    // 选中状态：背景是浅色，文字保持深色
                    titleLabel.foreground = ChatColors.textPrimary
                } else if (isHovered) {
                    // 悬停状态：保持原色，仅通过边框区分，避免"亮蓝色"刺眼
                    titleLabel.foreground = ChatColors.textPrimary
                } else {
                    // 默认状态
                    titleLabel.foreground = ChatColors.textPrimary
                }

                // 关键：强制单行并截断
                titleLabel.putClientProperty("html.disable", java.lang.Boolean.TRUE)
                
                // 使用一个中间容器来限制 Label 的宽度，或者直接依赖 BorderLayout.CENTER 的自动截断特性
                // 但为了更精确的控制（如省略号），JLabel 默认支持
                // 我们只需要确保 contentPanel 布局正确
                contentPanel.add(titleLabel, BorderLayout.CENTER)
                
                // 只有在 Hover 或 选中 状态下才显示删除按钮
                if (isHovered || isSelected) {
                    // 3. 使用自定义垃圾桶图标
                    val deleteIcon = TrashIcon(JBUI.scale(16), ChatColors.textSecondary)
                    val deleteLabel = JLabel(deleteIcon)
                    // 增加左侧间距，防止误触
                    deleteLabel.border = JBUI.Borders.emptyLeft(8)
                    // 显式设置 PreferredSize 确保占位
                    deleteLabel.preferredSize = Dimension(JBUI.scale(24), JBUI.scale(24))
                    contentPanel.add(deleteLabel, BorderLayout.EAST)
                } else {
                    // 占位，保持布局稳定
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
