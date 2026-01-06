package ai.smancode.sman.ide.service

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.*

@State(
    name = "SiliconManStorage",
    storages = [Storage("siliconman.xml")]
)
@Service
class StorageService : PersistentStateComponent<StorageService.State> {
    data class ChatMessage(
        var role: String = "", // "user" or "assistant"
        var content: String = "",
        var timestamp: Long = 0
    )

    data class State(
        var serverUrl: String = "http://localhost:8080/api/lite/analyze",
        var projectKey: String = "mcfcm-core",
        var aiName: String? = null,
        var conversationId: String? = null,
        var saveHistory: Boolean = true,
        var messages: MutableList<ChatMessage> = mutableListOf(),
        var connectTimeoutSeconds: Int = 30,
        var readTimeoutSeconds: Int = 1800,
        var writeTimeoutSeconds: Int = 1800,
        var callTimeoutSeconds: Int = 1800,
        var agentIpMapping: MutableMap<String, String> = mutableMapOf(
            "mcfcm-core" to "10.59.36.15",
            "mcfcm-adm" to "10.59.36.15",
            "mefs-core" to "10.58.32.219"
        )
    )

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }

    fun getServerUrl(): String = state.serverUrl
    fun setServerUrl(url: String) {
        state.serverUrl = url
    }

    fun getProjectKey(): String = state.projectKey
    fun setProjectKey(key: String) {
        state.projectKey = key
    }

    fun getAiName(): String? = state.aiName
    fun setAiName(name: String?) {
        state.aiName = name
    }

    fun getConversationId(): String? = state.conversationId
    fun setConversationId(id: String?) {
        state.conversationId = id
    }

    fun clearConversationId() {
        state.conversationId = null
    }

    fun shouldSaveHistory(): Boolean = state.saveHistory
    fun setSaveHistory(save: Boolean) {
        state.saveHistory = save
    }

    fun addMessage(role: String, content: String) {
        if (state.saveHistory) {
            state.messages.add(ChatMessage(role, content, System.currentTimeMillis()))
        }
    }

    fun getMessages(): List<ChatMessage> = state.messages

    fun clearMessages() {
        state.messages.clear()
    }

    fun getConnectTimeoutSeconds(): Int = state.connectTimeoutSeconds
    fun setConnectTimeoutSeconds(v: Int) { state.connectTimeoutSeconds = v }
    fun getReadTimeoutSeconds(): Int = state.readTimeoutSeconds
    fun setReadTimeoutSeconds(v: Int) { state.readTimeoutSeconds = v }
    fun getWriteTimeoutSeconds(): Int = state.writeTimeoutSeconds
    fun setWriteTimeoutSeconds(v: Int) { state.writeTimeoutSeconds = v }
    fun getCallTimeoutSeconds(): Int = state.callTimeoutSeconds
    fun setCallTimeoutSeconds(v: Int) { state.callTimeoutSeconds = v }

    fun getAgentIpMapping(): MutableMap<String, String> = state.agentIpMapping
    fun setAgentIpMapping(mapping: MutableMap<String, String>) { state.agentIpMapping = mapping }

    companion object {
        fun getInstance(): StorageService {
            return ApplicationManager.getApplication().getService(StorageService::class.java)
        }
    }
}
