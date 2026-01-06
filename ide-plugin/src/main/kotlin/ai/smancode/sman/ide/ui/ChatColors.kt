package ai.smancode.sman.ide.ui

import com.intellij.ui.JBColor
import com.intellij.util.ui.UIUtil
import java.awt.Color

object ChatColors {
    // 基础背景 - 适配 IDE 主题
    val background = JBColor.namedColor("Editor.background", JBColor(Color(0xffffff), Color(0x1e1e1e)))
    val surface = JBColor.namedColor("ToolWindow.background", JBColor(Color(0xf2f2f2), Color(0x252525)))
    
    // 分割线
    val divider = JBColor.border()
    
    // 文本颜色
    val textPrimary = JBColor.namedColor("Label.foreground", JBColor(Color(0x000000), Color(0xffffff)))
    val textSecondary = JBColor.namedColor("Component.infoForeground", JBColor(Color(0x808080), Color(0x8e8e93)))
    // 用户气泡上的反色文字（通常是白色）
    val textInverse = JBColor(Color(0xffffff), Color(0xffffff))
    
    // 气泡颜色
    // User: 改为浅蓝色背景，更加柔和且有区分度 (Light: 淡蓝, Dark: 深蓝灰)
    val userBubble = JBColor(Color(0xEBF5FF), Color(0x253240))
    // User Hover: 淡淡的灰白色用于悬浮高亮 (Light: 极浅灰, Dark: 极深灰)
    val userBubbleHover = JBColor(Color(0xF8F8F8), Color(0x2C2C2C))
    // User Border: 改为灰色以更加现代化和百搭 (Light: 中灰, Dark: 深灰)
    val userBubbleBorder = JBColor(Color(0xCCCCCC), Color(0x454545))
    
    // AI: 亮色下用浅灰，深色下用深灰
    val assistantBubble = JBColor(Color(0xe5e5ea), Color(0x3c3c3e))
    
    // 头像背景
    val userAvatar = JBColor(Color(0x007aff), Color(0x3574f0))
    val aiAvatar = JBColor(Color(0x8e8e93), Color(0x6e6e73))
    
    // 代码块背景
    val codeBackground = JBColor(Color(0xf0f0f0), Color(0x2b2b2b))
    
    // 引用块竖线颜色
    val quoteBar = JBColor(Color(0xDDDDDD), Color(0x505050))
    
    // 输入框背景 (比 Surface 更深以区分)
    val inputBackground = JBColor(Color(0xffffff), Color(0x1e1e1e))
    // 输入框警告边框 (暗金色，低调内敛)
    val inputBorderWarning = JBColor(Color(0x9C7C38), Color(0x856A2E))
    
    // 行内代码颜色 (改为柔和的品红/紫红色，减少视觉攻击性)
    val inlineCode = JBColor(Color(0xC7254E), Color(0xCE9178))

    // 链接颜色 (橙色系)
    val linkColor = JBColor(Color(0xE67E22), Color(0xE67E22))
    // 类名链接颜色 (青色/蓝绿色系)
    val classLinkColor = JBColor(Color(0x007ACC), Color(0x4EC9B0))
    // 方法名链接颜色 (淡黄色/奶油色系)
    val methodLinkColor = JBColor(Color(0x795E26), Color(0xDCDCAA))
    // 变量/位置链接颜色 (淡蓝色/天蓝色系)
    val locationLinkColor = JBColor(Color(0x005CC5), Color(0x9CDCFE))
    
    // 按钮激活状态背景 (深邃蓝)
    val activeButton = JBColor(Color(0x0d47a1), Color(0x2196f3))
    
    // 🆕 强调色 (用于推荐按钮、高亮等)
    val accentColor = JBColor(Color(0x007AFF), Color(0x3574F0))
    
    // 🆕 边框颜色 (用于建议按钮边框等)
    val borderColor = JBColor(Color(0xD1D1D6), Color(0x48484A))
}
