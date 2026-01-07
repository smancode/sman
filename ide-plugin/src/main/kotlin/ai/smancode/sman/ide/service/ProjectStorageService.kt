package ai.smancode.sman.ide.service

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project

@Service(Service.Level.PROJECT)
@State(
    name = "SiliconManProjectStorage",
    storages = [Storage("siliconman-project.xml")]
)
class ProjectStorageService(private val project: Project) : PersistentStateComponent<ProjectStorageService.ProjectState> {

    data class ChatMessage(
        var role: String = "",
        var content: String = "",
        var timestamp: Long = 0,
        var thinkingText: String? = null,
        // 🆕 TODO 列表（用于持久化和恢复）
        var todoItems: MutableList<TodoItemData>? = null,
        // 🆕 思考耗时 (毫秒)
        var thinkingDuration: Long? = null,
        // 🔥 分析过程（用于持久化和恢复）
        var process: String? = null
    )
    
    /**
     * 🆕 TODO 项数据（用于持久化）
     */
    data class TodoItemData(
        var id: String = "",
        var content: String = "",
        var status: String = "pending",
        var type: String = "task",
        var iteration: Int? = null,
        var maxIterations: Int? = null,
        var blockedReason: String? = null
    )
    
    data class Conversation(
        var id: String = "",
        var title: String = "",
        var timestamp: Long = 0,
        var messages: MutableList<ChatMessage> = mutableListOf(),
        // 存储后端返回的 conversationId，以便恢复会话上下文
        var backendConversationId: String? = null
    )

    data class ProjectState(
        // 默认值设为空，确保任何设置的值都会被保存到配置文件
        var serverUrl: String = "",
        var projectKey: String = "",
        var aiName: String = "SiliconMan",
        var mode: String = "intellij",
        var conversationId: String? = null,
        // 当前会话的本地唯一ID
        var currentLocalId: String? = null,
        var saveHistory: Boolean = true,
        // 当前活动的会话消息（暂存）
        var messages: MutableList<ChatMessage> = mutableListOf(),
        // 历史会话列表
        var history: MutableList<Conversation> = mutableListOf(),
        var connectTimeoutSeconds: Int = 30,
        var readTimeoutSeconds: Int = 1860,
        var writeTimeoutSeconds: Int = 1860,
        var callTimeoutSeconds: Int = 1860
    )

    private var state = ProjectState()

    override fun getState(): ProjectState = state

    override fun loadState(state: ProjectState) {
        this.state = state
    }
    
    // ... (existing methods)

    fun addHistory(conversation: Conversation) {
        // 如果已存在相同 ID 的会话，先移除
        state.history.removeIf { it.id == conversation.id }
        state.history.add(0, conversation) // 添加到头部
    }
    
    fun getHistory(): List<Conversation> = state.history
    
    fun removeHistory(id: String) {
        state.history.removeIf { it.id == id }
    }
    
    fun getConversation(id: String): Conversation? = state.history.find { it.id == id }
    
    fun clearHistory() {
        state.history.clear()
    }
    
    // Initialize default project key from project name if empty
    fun initDefaults() {
        if (state.projectKey.isEmpty()) {
            state.projectKey = project.name
        }
    }

    fun getServerUrl(): String = state.serverUrl.ifEmpty { "ws://10.58.32.15:8080/ws/agent/chat" }
    fun setServerUrl(url: String) {
        state.serverUrl = url
    }

    fun getProjectKey(): String {
        if (state.projectKey.isEmpty()) {
            initDefaults()
        }
        return state.projectKey
    }
    fun setProjectKey(key: String) {
        state.projectKey = key
    }

    fun getAiName(): String = state.aiName
    fun setAiName(name: String) {
        state.aiName = name
    }

    fun getMode(): String = state.mode
    fun setMode(mode: String) {
        state.mode = mode
    }

    fun getConversationId(): String? = state.conversationId
    fun setConversationId(id: String?) {
        state.conversationId = id
    }

    fun clearConversationId() {
        state.conversationId = null
    }

    fun getCurrentLocalId(): String? = state.currentLocalId
    fun setCurrentLocalId(id: String?) {
        state.currentLocalId = id
    }

    fun shouldSaveHistory(): Boolean = state.saveHistory
    fun setSaveHistory(save: Boolean) {
        state.saveHistory = save
    }

    fun addMessage(role: String, content: String, thinkingText: String? = null, todoItems: MutableList<TodoItemData>? = null, thinkingDuration: Long? = null, process: String? = null) {
        if (state.saveHistory) {
            state.messages.add(ChatMessage(role, content, System.currentTimeMillis(), thinkingText, todoItems, thinkingDuration, process))
        }
    }

    fun getMessages(): List<ChatMessage> = state.messages

    fun clearMessages() {
        state.messages.clear()
    }
    
    /**
     * 🆕 更新最后一条消息的 Thinking 耗时
     */
    fun updateLastMessageThinkingDuration(duration: Long) {
        val lastMessage = state.messages.lastOrNull()
        if (lastMessage != null && lastMessage.role == "assistant") {
            lastMessage.thinkingDuration = duration
        }
    }

    /**
     * 🆕 更新最后一条消息的 TODO 列表
     */
    fun updateLastMessageTodos(todoItems: List<TodoItemData>) {
        val lastMessage = state.messages.lastOrNull()
        if (lastMessage != null && lastMessage.role == "assistant") {
            lastMessage.todoItems = todoItems.toMutableList()
        }
    }
    
    /**
     * 🆕 获取最后一条消息的 TODO 列表
     */
    fun getLastMessageTodos(): List<TodoItemData>? {
        return state.messages.lastOrNull()?.todoItems
    }

    fun getConnectTimeoutSeconds(): Int = state.connectTimeoutSeconds
    fun setConnectTimeoutSeconds(v: Int) { state.connectTimeoutSeconds = v }
    fun getReadTimeoutSeconds(): Int = state.readTimeoutSeconds
    fun setReadTimeoutSeconds(v: Int) { state.readTimeoutSeconds = v }
    fun getWriteTimeoutSeconds(): Int = state.writeTimeoutSeconds
    fun setWriteTimeoutSeconds(v: Int) { state.writeTimeoutSeconds = v }
    fun getCallTimeoutSeconds(): Int = state.callTimeoutSeconds
    fun setCallTimeoutSeconds(v: Int) { state.callTimeoutSeconds = v }

    companion object {
        fun getInstance(project: Project): ProjectStorageService {
            return project.getService(ProjectStorageService::class.java)
        }
    }
}
