package com.smancode.smanagent.ide.service

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.util.xmlb.XmlSerializerUtil
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * 存储服务
 * <p>
 * 管理插件的持久化数据。
 */
@Service(Service.Level.PROJECT)
@State(name = "SmanAgentSettings", storages = [com.intellij.openapi.components.Storage("SmanAgentSettings.xml")])
class StorageService : PersistentStateComponent<StorageService> {

    private val logger: Logger = LoggerFactory.getLogger(StorageService::class.java)

    var backendUrl: String = "ws://localhost:8080/ws/agent"
    var lastSessionId: String = ""

    override fun getState(): StorageService = this

    override fun loadState(state: StorageService) {
        XmlSerializerUtil.copyBean(state, this)
    }

    companion object {
        fun getInstance(project: Project): StorageService {
            return project.service()
        }
    }
}

// 扩展函数
fun Project.storageService(): StorageService {
    return StorageService.getInstance(this)
}
