package ai.smancode.sman.ide.ui

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.IconLoader
import javax.swing.JLabel

class SiliconManToolWindowFactory : ToolWindowFactory, DumbAware {
    
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        try {
            // 动态设置 toolWindow 图标，使用专门的大尺寸图标（24x24）用于侧边栏显示
            // 优先尝试多种路径格式，提高跨平台兼容性
            try {
                // 使用专门为 toolWindow 设计的大尺寸图标
                // 方式1: 使用相对于 resources 的路径（不带开头的斜杠）- Windows 常用格式
                var icon = IconLoader.getIcon("META-INF/pluginIconToolWindow.svg", javaClass)
                
                // 方式2: 如果方式1失败，尝试带斜杠的路径
                if (icon == null || icon.iconWidth <= 0) {
                    icon = IconLoader.getIcon("/META-INF/pluginIconToolWindow.svg", javaClass)
                }
                
                // 方式3: 如果大图标加载失败，回退到小图标
                if (icon == null || icon.iconWidth <= 0) {
                    icon = IconLoader.getIcon("META-INF/pluginIcon.svg", javaClass)
                    if (icon == null || icon.iconWidth <= 0) {
                        icon = IconLoader.getIcon("/META-INF/pluginIcon.svg", javaClass)
                    }
                }
                
                // 如果成功加载图标，设置到 toolWindow
                if (icon != null && icon.iconWidth > 0) {
                    toolWindow.setIcon(icon)
                }
            } catch (iconException: Exception) {
                // 图标加载失败不影响主功能，使用 plugin.xml 中配置的默认图标
                iconException.printStackTrace()
            }
            
            val chatPanel = ChatPanel(project)
            val content = ContentFactory.getInstance().createContent(
                chatPanel,
                "",
                false
            )
            toolWindow.contentManager.addContent(content)
            
            // 注册清理逻辑：当工具窗口关闭时自动清理资源
            Disposer.register(content, chatPanel)
        } catch (e: Exception) {
            // 即使出错也显示错误信息，避免 "Nothing to show"
            val errorPanel = JLabel("Error loading SiliconMan: ${e.message}")
            val content = ContentFactory.getInstance().createContent(errorPanel, "Error", false)
            toolWindow.contentManager.addContent(content)
            e.printStackTrace()
        }
    }
}

