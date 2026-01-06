package ai.smancode.sman.ide

import com.intellij.openapi.components.ServiceManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManagerListener
import com.intellij.openapi.startup.ProjectActivity
import org.jetbrains.annotations.NotNull

/**
 * SiliconMan 插件主类
 *
 * 功能：
 * - 监听项目打开/关闭事件
 * - 初始化插件服务
 * - 清理资源
 *
 * @author SiliconMan Team
 * @since 2.0
 */
class SiliconManPlugin : ProjectActivity {

    override suspend fun execute(@NotNull project: Project) {
        // 插件项目级别初始化
        // 注意：现代 IntelliJ 插件不再需要显式注册服务
        // 服务通过 @Service 注解自动注册
    }
}

/**
 * 项目管理器监听器（用于插件卸载时清理资源）
 */
class SiliconManProjectManagerListener : ProjectManagerListener {

    override fun projectClosingBeforeSave(@NotNull project: Project) {
        // 项目即将关闭，清理资源
        val webSocketService = ServiceManager.getService(project, ai.smancode.sman.ide.service.WebSocketService::class.java)
        webSocketService?.stopAllAnalysis()
    }

    override fun projectClosed(@NotNull project: Project) {
        // 项目已关闭
        super.projectClosed(project)
    }
}
