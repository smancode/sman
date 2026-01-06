package ai.smancode.sman.ide.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.Disposable
import ai.smancode.sman.ide.ui.components.*
import ai.smancode.sman.ide.service.WebSocketService.ClarificationData
import ai.smancode.sman.ide.service.WebSocketService.SuggestedAnswer
import ai.smancode.sman.ide.ui.layout.MessageWrapper
import ai.smancode.sman.ide.service.NetworkUtils
import ai.smancode.sman.ide.service.ProjectStorageService
import ai.smancode.sman.ide.service.WebSocketService
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.*

class ChatPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {
    
    private val webSocketService = project.getService(WebSocketService::class.java)
    private val projectStorage = ProjectStorageService.getInstance(project)
    private val chatHistory = ChatHistoryPanel(project)
    // private val currentCalls = CopyOnWriteArrayList<Call>() // WS 不需要维护 Call 列表
    @Volatile private var isDisposed = false
    
    private lateinit var inputArea: InputArea
    
    // 本地维护的当前会话ID，用于后端通信 (Context ID)
    // 初始值为 null，表示尚未建立会话或新会话
    // 绝对不能自己生成 UUID，必须等待后端返回
    private var currentBackendId: String? = null
    
    // 本地会话唯一标识符 (Local Session ID)
    // 用于本地存储去重、历史记录定位，始终存在
    private var currentLocalId: String = java.util.UUID.randomUUID().toString()

    // 🔥 按会话管理处理状态，支持多连接
    private val processingStates = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    
    // 🆕 当前会话的 TODO 列表（用于持久化）
    private var currentTodoItems: MutableList<ai.smancode.sman.ide.service.ProjectStorageService.TodoItemData> = mutableListOf()

    private val controlBar = ControlBar(
        onClearCallback = { startNewSession() }, // 新建会话逻辑
        onDeleteCallback = { btn -> showHistoryPopup(btn) }, // 显示历史记录
        onSettingsCallback = { showSettings() },
        onRoleToggleCallback = { role -> toggleRole(role) }
    )
    
    private var userMessageCount = 0

    init {
        // 确保面板背景色与历史记录背景色一致，实现无缝衔接
        background = ChatColors.background

        inputArea = InputArea(
            project = project,
            onSendCallback = { message ->
                if (message.isNotBlank()) {
                    addUserMessage(message)
                    startAnalysis(message)
                }
            },
            onStopCallback = {
                // 用户点击停止按钮
                
                // 1. 仅当当前会话正在处理时，才发送停止指令
                if (processingStates[currentLocalId] == true) {
                    webSocketService.stopAnalysis(currentLocalId)
                    processingStates[currentLocalId] = false
                }
                
                // 2. 立即停止 UI 上的打字机效果（如果有）
                chatHistory.stopGenerating()
                
                // 3. 立即复位按钮状态
                inputArea.setSendingState(false)
            }
        )

        add(controlBar, BorderLayout.NORTH)
        
        // 直接添加 chatHistory，因为 ChatHistoryPanel 内部已经包含了 ScrollPane
        // 移除外部的 JBScrollPane 以避免嵌套滚动条导致自动滚动失效
        add(chatHistory, BorderLayout.CENTER)
        
        // 底部：输入框
        val inputWrapper = JPanel(BorderLayout())
        inputWrapper.isOpaque = false
        inputWrapper.border = javax.swing.border.EmptyBorder(JBUI.scale(2), JBUI.scale(2), JBUI.scale(2), JBUI.scale(2))
        inputWrapper.add(inputArea, BorderLayout.CENTER)
        
        add(inputWrapper, BorderLayout.SOUTH)
        
        // 初始化时，尝试从持久化存储中恢复 ID
        // 这里的逻辑改为：如果是首次启动，currentLocalId 已经是新的了；
        // 如果有上次状态，应该在这里恢复。但目前 ProjectStorageService 只存了 history 和 conversationId (backend)
        // 并没有存 currentLocalId。
        // 为了简单，如果是首次启动且有历史记录，我们不自动加载历史，而是作为新会话。
        // 但为了保持 backendId 连续性，我们恢复 backendId
        
        // 恢复本地会话ID
        val storedLocalId = projectStorage.getCurrentLocalId()
        if (storedLocalId != null) {
            currentLocalId = storedLocalId
        } else {
            // 首次运行或无存储，保存当前的默认值
            projectStorage.setCurrentLocalId(currentLocalId)
        }
        
        currentBackendId = projectStorage.getConversationId()
        restoreHistory()
    }

    private fun addUserMessage(message: String) {
        val bubble = MessageBubble(message, isUser = true, project = project)
        val wrapper = MessageWrapper(bubble)
        chatHistory.addUserMessage(wrapper)
        userMessageCount++

        if (userMessageCount >= 7) {
            inputArea.setWarning(true)
        }

        projectStorage.addMessage("user", message)
        SwingUtilities.invokeLater { chatHistory.scrollToBottom() }
    }

    private fun addAssistantMessage(message: String, animate: Boolean = false) {
        val bubble = MessageBubble(message, isUser = false, project = project, animate = animate)
        val wrapper = MessageWrapper(bubble)
        chatHistory.addAssistantMessage(wrapper)
        
        projectStorage.addMessage("assistant", message)
        SwingUtilities.invokeLater { chatHistory.scrollToBottom() }
    }

    private fun addLoadingMessage() {
        chatHistory.addLoadingMessage()
        SwingUtilities.invokeLater { chatHistory.scrollToBottom() }
    }
    
    // 核心逻辑变更：不再是简单的 KV 存消息，而是管理 Conversation 对象
    
    // 启动新会话（对应 + 号）
    private fun startNewSession() {
        // 1. 保存当前会话
        saveCurrentSession()
        
        // 2. 清空界面和状态
        clearChatState()
        
        // 3. 重置后端 ID
        projectStorage.clearConversationId()
        
        // 4. 重置 ID
        currentBackendId = null
        currentLocalId = java.util.UUID.randomUUID().toString()
        projectStorage.setCurrentLocalId(currentLocalId)
    }
    
    // 显示历史记录弹窗（对应时钟图标）
    private fun showHistoryPopup(component: javax.swing.JComponent) {
        // 先保存当前可能未保存的会话
        saveCurrentSession()
        
        // 重新从存储中获取最新的历史记录列表
        // 之前的逻辑中，saveCurrentSession 可能更新了 history，但这里必须确保取到的是最新状态
        val history = projectStorage.getHistory()
        val popup = HistoryPopup(
            history = history,
            onSelect = { conversation -> loadSession(conversation) },
            onDelete = { conversation -> 
                projectStorage.removeHistory(conversation.id)
                // 如果删除的是当前会话，则清空界面
                // 我们通过 id (Local ID) 来判断
                if (currentLocalId == conversation.id) {
                    clearChatState()
                    currentLocalId = java.util.UUID.randomUUID().toString()
                    projectStorage.setCurrentLocalId(currentLocalId)
                    currentBackendId = null
                    projectStorage.clearConversationId()
                }
            }
        )
        popup.show(component)
    }
    
    // 保存当前会话
    private fun saveCurrentSession() {
        val messages = projectStorage.getMessages()
        if (messages.isEmpty()) return // 空会话不保存

        // 检查是否已经存在内容完全一致的会话，避免重复保存
        val history = projectStorage.getHistory()
        val existingExactMatch = history.find { h ->
            // 如果 ID 相同 (Local ID)
            h.id == currentLocalId
            // 移除内容完全一致的检查，因为这会导致“多条变2条”的问题：
            // 如果用户继续聊天，ID没变，但内容变了，我们希望更新这个ID的记录。
            // 但如果逻辑错误地匹配到了另一个旧记录（比如内容相似？不，这里是完全一致），
            // 或者更糟糕的是，如果ID没变，我们应该直接更新ID对应的记录。
            // 只有当ID找不到时（比如首次保存），才需要考虑是否重复？
            // 不，既然引入了 LocalID，我们应该严格信任 LocalID。
            // 之前的“内容一致”检查是为了防止后端ID未返回时的重复，但现在有了 LocalID，这个检查可能是多余甚至有害的。
            // 尤其是当 messages 变多了，zip check 肯定不匹配，于是创建新的？
            // 不，如果 ID 匹配，就直接更新。
            // 如果 ID 不匹配（新会话），才创建新的。
        }
        
        // 如果找到了 ID 匹配的会话，直接更新它
        if (existingExactMatch != null) {
             // 更新时间戳和消息列表
             val updatedConversation = existingExactMatch.copy(
                 timestamp = System.currentTimeMillis(),
                 messages = java.util.ArrayList(messages), // 确保保存完整的最新消息列表
                 backendConversationId = currentBackendId ?: existingExactMatch.backendConversationId
             )
             // 移除旧的，添加更新后的（置顶）
             projectStorage.removeHistory(existingExactMatch.id)
             projectStorage.addHistory(updatedConversation)
             return
        }

        // 如果没找到 ID 匹配的，说明是新会话（或者 LocalID 丢失了？）
        // 此时直接创建新的。由于 currentLocalId 是唯一的，不会误判。
        
        val idToSave = currentLocalId
        val title = messages.firstOrNull { it.role == "user" }?.content?.take(50) ?: "新会话"
        val timestamp = System.currentTimeMillis()
        
        val conversation = ProjectStorageService.Conversation(
            id = idToSave,
            title = title,
            timestamp = timestamp,
            messages = java.util.ArrayList(messages),
            backendConversationId = currentBackendId
        )
        
        projectStorage.addHistory(conversation)
    }
    
    // 加载历史会话
    private fun loadSession(conversation: ProjectStorageService.Conversation) {
        // 1. 保存当前正在进行的会话（如果需要）
        saveCurrentSession()
        
        // 2. 清空当前界面状态
        clearChatState()
        
        // 3. 恢复数据
        // 恢复本地 ID
        currentLocalId = conversation.id
        projectStorage.setCurrentLocalId(currentLocalId)
        // 恢复后端 ID，确保后续请求能接上
        currentBackendId = conversation.backendConversationId
        projectStorage.setConversationId(conversation.backendConversationId)
        
        // 恢复消息列表到暂存区
        // 注意：这里使用深拷贝，防止直接修改引用导致历史记录被意外篡改
        projectStorage.clearMessages()
        conversation.messages.forEach {
            projectStorage.addMessage(it.role, it.content, it.thinkingText, it.todoItems?.toMutableList(), it.thinkingDuration, it.process)
        }
        
        // 4. 重新渲染界面
        restoreHistory()
    }

    // 清空界面和内部状态（不涉及持久化删除）
    private fun clearChatState() {
        projectStorage.clearMessages()
        chatHistory.clearAllMessages()
        userMessageCount = 0
        inputArea.setWarning(false)
        // 🆕 清空 TODO 列表
        currentTodoItems.clear()
    }

    // 修改原有的 restoreHistory，不再处理 split，而是直接渲染所有消息
    private fun restoreHistory() {
        chatHistory.clearAllMessages() // 确保干净
        val messages = projectStorage.getMessages()
        
        for (msg in messages) {
            when (msg.role) {
                "user" -> {
                    val bubble = MessageBubble(msg.content, isUser = true, project = project)
                    val wrapper = MessageWrapper(bubble)
                    chatHistory.addUserMessage(wrapper)
                    userMessageCount++
                }
                "assistant" -> {
                    val bubble = MessageBubble(
                        msg.content,
                        isUser = false,
                        project = project,
                        initialThinkingText = null,  // 🔥 隐藏 thinking 框
                        initialThinkingDuration = null,
                        initialProcess = null  // 🔥 隐藏分析过程
                    )
                    val wrapper = MessageWrapper(bubble)

                    // 🔥 隐藏 TODO list 恢复逻辑
                    // msg.todoItems?.takeIf { it.isNotEmpty() }?.let { todoItems ->
                    //     val todos = todoItems.map { item ->
                    //         ai.smancode.sman.ide.ui.components.TodoListPanel.TodoData(
                    //             id = item.id,
                    //             content = item.content,
                    //             status = item.status,
                    //             type = item.type,
                    //             iteration = item.iteration,
                    //             maxIterations = item.maxIterations,
                    //             blockedReason = item.blockedReason
                    //         )
                    //     }
                    //     wrapper.updateTodoList(todos)
                    // }

                    chatHistory.addAssistantMessage(wrapper)
                }
            }
        }

        if (userMessageCount >= 7) {
            inputArea.setWarning(true)
        }

        // 🔧 修复历史消息换行问题：批量加载完成后，延迟触发布局刷新
        // 确保所有消息气泡在界面有正确宽度后重新计算布局
        SwingUtilities.invokeLater { 
            chatHistory.scrollToBottom()
            // 延迟 100ms 后强制刷新所有消息气泡
            javax.swing.Timer(100) {
                // 🔧 关键：遍历所有消息，强制 invalidate 触发重新布局
                chatHistory.forceRelayoutAllMessages()
            }.apply { 
                isRepeats = false 
                start() 
            }
        }
    }
    
    // 移除旧的 clearChat 和 deleteAllHistory，用上面的新方法替代
    // 需要调整 ControlBar 的回调签名以支持传递组件 (用于 Popup 定位)

    
    private fun showSettings() {
        val settingsDialog = SettingsDialog(project)
        settingsDialog.showDialog()
    }
    
    private fun toggleRole(role: String) {
        // TODO: 切换AI角色
        println("切换到角色: $role")
    }
    
    private fun startAnalysis(message: String) {
        // 捕获发起请求时的本地会话 ID
        val requestLocalId = currentLocalId
        
        // 🔥 按会话设置处理状态
        processingStates[requestLocalId] = true
        
        SwingUtilities.invokeLater { 
            addLoadingMessage() 
            inputArea.setSendingState(true)
        }
        
        val projectKey = projectStorage.getProjectKey()
        val serverUrl = projectStorage.getServerUrl()
        val lastRequestId = currentBackendId
        
        // 用于收集 Thinking 过程数据
        val thinkingAccumulator = StringBuilder()
        
        webSocketService.startAnalysis(
            localId = requestLocalId,  // 🔥 传入会话ID，支持多连接
            serverUrl = serverUrl,
            requirementText = message,
            projectKey = projectKey,
            lastRequestId = lastRequestId,
            listener = object : ai.smancode.sman.ide.service.WebSocketService.AnalysisListener {
                override fun onStreamingContent(content: String, chunkIndex: Int, isComplete: Boolean) {
                    // 🆕 流式 Markdown 内容更新
                    SwingUtilities.invokeLater {
                        if (isDisposed) return@invokeLater
                        if (currentLocalId == requestLocalId) {
                            // 实时更新正在加载的消息内容
                            chatHistory.updateStreamingContent(content)
                        }
                    }
                }

                override fun onProgress(thinking: String, round: Int) {
                    if (thinking.isNotBlank()) {
                        // 修复：与 MessageBubble 的显示逻辑保持一致，在每段思考内容之间插入空行
                        if (thinkingAccumulator.isNotEmpty()) {
                            if (!thinking.startsWith("\n")) {
                                thinkingAccumulator.append("\n\n")
                            } else if (!thinking.startsWith("\n\n")) {
                                thinkingAccumulator.append("\n")
                            }
                        }
                        thinkingAccumulator.append(thinking)
                    }
                    SwingUtilities.invokeLater {
                            if (isDisposed) return@invokeLater
                            if (currentLocalId == requestLocalId && thinking.isNotBlank()) {
                                // 修复：交由 MessageBubble 内部处理换行，这里不再手动追加换行符
                                chatHistory.updateLoadingMessage(thinking)
                            }
                        }
                }

                override fun onComplete(result: String, requestId: String, process: String) {
                    processingStates[requestLocalId] = false
                    val finalThinking = thinkingAccumulator.toString()

                    // 🔥 调试日志：检查 process 字段
                    println("🔍 [DEBUG] onComplete called:")
                    println("  - requestId: $requestId")
                    println("  - result length: ${result.length}")
                    println("  - process is blank: ${process.isBlank()}")
                    println("  - process length: ${process.length}")
                    println("  - process preview: ${process.take(100)}")

                    SwingUtilities.invokeLater {
                        if (isDisposed) return@invokeLater

                        if (currentLocalId == requestLocalId) {
                            // 🔥 修复：始终调用 finishLoadingMessage，确保 process 能正确显示
                            // 如果没有 thinking 内容，Thinking 框会自动隐藏
                            chatHistory.finishLoadingMessage(result, process, onTypingComplete = {
                                SwingUtilities.invokeLater {
                                    if (!isDisposed && currentLocalId == requestLocalId) {
                                        inputArea.setSendingState(false)
                                        chatHistory.scrollToBottom()
                                    }
                                }
                            })
                            
                            // 更新后端 ID
                            currentBackendId = requestId
                            projectStorage.setConversationId(requestId)
                            
                            // 🔥 始终保存 TODO 列表（用于持久化显示执行过程）
                            val todoItemsToSave = if (currentTodoItems.isNotEmpty()) currentTodoItems.toMutableList() else null

                            // 保存消息到存储 (包含 Thinking、TODO 和 Process)
                            projectStorage.addMessage("assistant", result, finalThinking, todoItemsToSave, process = process)

                            // 🔥🔥🔥 DEBUG: 确认保存的内容
                            println("🔍 [DEBUG] Saving message to storage:")
                            println("  - result.length: ${result.length}")
                            println("  - result preview: ${result.take(100)}")

                            // 🔥 立即保存会话到历史记录（避免用户切换会话或重启IDE时丢失消息）
                            saveCurrentSession()

                            // 清空当前 TODO 列表（内存中的，已经保存到存储了）
                            currentTodoItems.clear()
                        } else {
                            // 用户已切换会话：更新历史记录中的对应会话
                            // 1. 尝试在历史记录中找到该会话
                            val targetConversation = projectStorage.getHistory().find { it.id == requestLocalId }
                            if (targetConversation != null) {
                                // 2. 追加回复消息 (包含 Thinking)
                                targetConversation.messages.add(
                                    ai.smancode.sman.ide.service.ProjectStorageService.ChatMessage(
                                        role = "assistant",
                                        content = result,
                                        timestamp = System.currentTimeMillis(),
                                        thinkingText = finalThinking
                                    )
                                )
                                // 3. 更新会话的 backendId
                                targetConversation.backendConversationId = requestId
                                // 4. 强制保存/刷新存储（虽然对象是引用的，但最好触发一下状态更新如果需要）
                            }
                        }
                    }
                }

                override fun onCancelled(message: String) {
                    processingStates[requestLocalId] = false
                    SwingUtilities.invokeLater {
                        if (isDisposed) return@invokeLater
                        if (currentLocalId == requestLocalId) {
                            chatHistory.removeLoadingMessage()
                            // 显示取消提示，或者直接作为一条系统消息
                            addAssistantMessage("🚫 $message") 
                            inputArea.setSendingState(false)
                        }
                    }
                }

                override fun onError(message: String) {
                    // 忽略 "Socket closed" 错误，因为这是后端关闭连接时的常见伴随现象，不应视为异常
                    if (message.contains("Socket closed", ignoreCase = true)) {
                        return
                    }
                    
                    processingStates[requestLocalId] = false
                    SwingUtilities.invokeLater {
                        if (isDisposed) return@invokeLater
                        if (currentLocalId == requestLocalId) {
                            chatHistory.removeLoadingMessage()
                            addAssistantMessage("⚠️ 错误: $message")
                            inputArea.setSendingState(false)
                        }
                    }
                }

                override fun onTodoUpdate(todoUpdate: ai.smancode.sman.ide.service.WebSocketService.TodoUpdateData) {
                    // 🆕 在 loading 消息下方显示 TODO list
                    SwingUtilities.invokeLater {
                        if (isDisposed) return@invokeLater
                        if (currentLocalId == requestLocalId) {
                            val todos = todoUpdate.todos.map { todo ->
                                ai.smancode.sman.ide.ui.components.TodoListPanel.TodoData(
                                    id = todo.id,
                                    content = todo.content,
                                    status = todo.status,
                                    type = todo.type ?: "task",
                                    iteration = todo.iteration,
                                    maxIterations = todo.maxIterations,
                                    blockedReason = todo.blockedReason
                                )
                            }
                            chatHistory.updateTodoList(todos)
                            
                            // 🆕 保存到临时变量（onComplete 时会持久化）
                            currentTodoItems = todoUpdate.todos.map { todo ->
                                ai.smancode.sman.ide.service.ProjectStorageService.TodoItemData(
                                    id = todo.id,
                                    content = todo.content,
                                    status = todo.status,
                                    type = todo.type ?: "task",
                                    iteration = todo.iteration,
                                    maxIterations = todo.maxIterations,
                                    blockedReason = todo.blockedReason
                                )
                            }.toMutableList()
                        }
                    }
                }
                
                override fun onClosed() {
                    // 连接关闭时的清理工作
                    SwingUtilities.invokeLater {
                        if (!isDisposed && currentLocalId == requestLocalId) {
                            // 只有当后端异常关闭（即仍处于处理状态）时，才强制重置按钮
                            // 如果该会话已完成，则交由 finishLoadingMessage 的回调来处理按钮重置
                            if (processingStates[requestLocalId] == true) {
                                inputArea.setSendingState(false)
                                processingStates[requestLocalId] = false
                            }
                        }
                    }
                }
                
                override fun onClarification(clarification: ClarificationData) {
                    processingStates[requestLocalId] = false
                    SwingUtilities.invokeLater {
                        if (isDisposed) return@invokeLater
                        if (currentLocalId == requestLocalId) {
                            // 移除 loading 消息
                            chatHistory.removeLoadingMessage()
                            inputArea.setSendingState(false)
                            
                            // 显示澄清问题和建议按钮
                            showClarificationPanel(clarification, requestLocalId)
                        }
                    }
                }
                
                // 🔥 处理代码编辑指令
                override fun onCodeEdit(codeEditData: org.json.JSONObject) {
                    SwingUtilities.invokeLater {
                        if (isDisposed) return@invokeLater
                        executeCodeEdits(codeEditData)
                    }
                }
            }
        )
    }
    
    /**
     * 🔥 执行代码编辑
     */
    private fun executeCodeEdits(codeEditData: org.json.JSONObject) {
        try {
            val codeEditService = project.getService(ai.smancode.sman.ide.service.CodeEditService::class.java)
            val result = codeEditService.executeEdits(codeEditData)
            
            // 显示执行结果
            val resultMessage = buildString {
                append("## 🔧 代码修改完成\n\n")
                append("- 总修改: ${result.totalEdits} 个\n")
                append("- 成功: ${result.successCount} 个\n")
                if (result.failedCount > 0) {
                    append("- 失败: ${result.failedCount} 个\n")
                }
                append("\n")
                
                result.results.forEachIndexed { index, editResult ->
                    val icon = if (editResult.success) "✅" else "❌"
                    append("${index + 1}. $icon ${editResult.message}\n")
                }
                
                if (result.allSuccess) {
                    append("\n---\n**提示**：所有修改已应用，支持 `Ctrl+Z` 撤销。")
                }
            }
            
            addAssistantMessage(resultMessage)
            
        } catch (e: Exception) {
            addAssistantMessage("❌ 代码修改失败: ${e.message}")
        }
    }
    
    /**
     * 🆕 显示澄清面板（带建议按钮）
     */
    private fun showClarificationPanel(clarification: ClarificationData, requestLocalId: String) {
        // 1. 添加澄清问题作为助手消息
        val questionBubble = MessageBubble(
            "❓ ${clarification.question}",
            isUser = false,
            project = project
        )
        val questionWrapper = MessageWrapper(questionBubble)
        chatHistory.addAssistantMessage(questionWrapper)
        
        // 2. 添加建议按钮面板
        val suggestionPanel = SuggestionPanel(
            question = "请选择以下选项，或直接输入您的回答：",
            suggestions = clarification.suggestions,
            onSuggestionSelected = { selectedSuggestion ->
                handleSuggestionSelection(selectedSuggestion, clarification, requestLocalId)
            }
        )
        
        // 包装成 MessageWrapper 添加到历史
        val suggestionWrapper = MessageWrapper(suggestionPanel)
        chatHistory.addAssistantMessage(suggestionWrapper)
        
        // 保存澄清问题到消息历史
        projectStorage.addMessage("assistant", "❓ ${clarification.question}")
        
        SwingUtilities.invokeLater { chatHistory.scrollToBottom() }
    }
    
    /**
     * 🆕 处理用户选择的建议答案
     * 
     * 修复：只发送 ANSWER 消息，等待后端处理后返回结果
     * 不再重复调用 startAnalysis()
     */
    private fun handleSuggestionSelection(
        suggestion: SuggestedAnswer, 
        clarification: ClarificationData,
        requestLocalId: String
    ) {
        // 1. 显示用户选择的答案作为用户消息
        addUserMessage("✅ ${suggestion.text}")
        
        // 2. 保存选择到消息历史
        projectStorage.addMessage("user", "✅ ${suggestion.text}")
        
        // 3. 显示 loading 状态
        processingStates[requestLocalId] = true
        SwingUtilities.invokeLater { 
            addLoadingMessage() 
            inputArea.setSendingState(true)
        }
        
        // 4. 发送 ANSWER 消息并继续分析（后端会自动继续处理）
        continueWithAnswer(requestLocalId, suggestion.text, suggestion.id, clarification.requestId)
    }
    
    /**
     * 🆕 发送用户回答并继续分析流程
     * 
     * 通过发送 ANSWER 消息让后端继续处理，避免重新建立连接
     */
    private fun continueWithAnswer(
        localId: String, 
        answerText: String, 
        suggestionId: String?,
        backendRequestId: String?
    ) {
        val projectKey = projectStorage.getProjectKey()
        val serverUrl = projectStorage.getServerUrl()
        
        // 用于收集 Thinking 过程数据
        val thinkingAccumulator = StringBuilder()
        
        webSocketService.startAnalysisWithAnswer(
            localId = localId,
            serverUrl = serverUrl,
            answerText = answerText,
            suggestionId = suggestionId,
            projectKey = projectKey,
            requestId = backendRequestId ?: currentBackendId,
            listener = object : ai.smancode.sman.ide.service.WebSocketService.AnalysisListener {
                override fun onProgress(thinking: String, round: Int) {
                    if (thinking.isNotBlank()) {
                        if (thinkingAccumulator.isNotEmpty()) {
                            if (!thinking.startsWith("\n")) {
                                thinkingAccumulator.append("\n\n")
                            } else if (!thinking.startsWith("\n\n")) {
                                thinkingAccumulator.append("\n")
                            }
                        }
                        thinkingAccumulator.append(thinking)
                    }
                    SwingUtilities.invokeLater {
                        if (isDisposed) return@invokeLater
                        if (currentLocalId == localId && thinking.isNotBlank()) {
                            chatHistory.updateLoadingMessage(thinking)
                        }
                    }
                }

                override fun onComplete(result: String, requestId: String, process: String) {
                    processingStates[localId] = false
                    val finalThinking = thinkingAccumulator.toString()

                    SwingUtilities.invokeLater {
                        if (isDisposed) return@invokeLater

                        if (currentLocalId == localId) {
                            chatHistory.finishLoadingMessage(result, process, onTypingComplete = {
                                SwingUtilities.invokeLater {
                                    if (!isDisposed && currentLocalId == localId) {
                                        inputArea.setSendingState(false)
                                        chatHistory.scrollToBottom()
                                    }
                                }
                            })
                            
                            currentBackendId = requestId
                            projectStorage.setConversationId(requestId)
                            
                            // 🔥 始终保存 TODO 列表（用于持久化显示执行过程）
                            val todoItemsToSave = if (currentTodoItems.isNotEmpty()) currentTodoItems.toMutableList() else null

                            projectStorage.addMessage("assistant", result, finalThinking, todoItemsToSave, process = process)

                            // 🔥 立即保存会话到历史记录（避免用户切换会话或重启IDE时丢失消息）
                            saveCurrentSession()

                            // 清空当前 TODO 列表（内存中的，已经保存到存储了）
                            currentTodoItems.clear()
                        }
                    }
                }

                override fun onCancelled(message: String) {
                    processingStates[localId] = false
                    SwingUtilities.invokeLater {
                        if (isDisposed) return@invokeLater
                        if (currentLocalId == localId) {
                            chatHistory.removeLoadingMessage()
                            addAssistantMessage("🚫 $message") 
                            inputArea.setSendingState(false)
                        }
                    }
                }

                override fun onError(message: String) {
                    if (message.contains("Socket closed", ignoreCase = true)) {
                        return
                    }
                    processingStates[localId] = false
                    SwingUtilities.invokeLater {
                        if (isDisposed) return@invokeLater
                        if (currentLocalId == localId) {
                            chatHistory.removeLoadingMessage()
                            addAssistantMessage("⚠️ 错误: $message")
                            inputArea.setSendingState(false)
                        }
                    }
                }

                override fun onClosed() {
                    SwingUtilities.invokeLater {
                        if (!isDisposed && currentLocalId == localId) {
                            if (processingStates[localId] == true) {
                                inputArea.setSendingState(false)
                                processingStates[localId] = false
                            }
                        }
                    }
                }
                
                override fun onClarification(clarification: ClarificationData) {
                    processingStates[localId] = false
                    SwingUtilities.invokeLater {
                        if (isDisposed) return@invokeLater
                        if (currentLocalId == localId) {
                            chatHistory.removeLoadingMessage()
                            inputArea.setSendingState(false)
                            showClarificationPanel(clarification, localId)
                        }
                    }
                }
                
                // 🔥 处理代码编辑指令
                override fun onCodeEdit(codeEditData: org.json.JSONObject) {
                    SwingUtilities.invokeLater {
                        if (isDisposed) return@invokeLater
                        executeCodeEdits(codeEditData)
                    }
                }
                
                // 🆕 处理 TODO 更新
                override fun onTodoUpdate(todoUpdate: ai.smancode.sman.ide.service.WebSocketService.TodoUpdateData) {
                    SwingUtilities.invokeLater {
                        if (isDisposed) return@invokeLater
                        if (currentLocalId == localId) {
                            val todos = todoUpdate.todos.map { todo ->
                                ai.smancode.sman.ide.ui.components.TodoListPanel.TodoData(
                                    id = todo.id,
                                    content = todo.content,
                                    status = todo.status,
                                    type = todo.type ?: "task",
                                    iteration = todo.iteration,
                                    maxIterations = todo.maxIterations,
                                    blockedReason = todo.blockedReason
                                )
                            }
                            chatHistory.updateTodoList(todos)
                            
                            // 保存到临时变量
                            currentTodoItems = todoUpdate.todos.map { todo ->
                                ai.smancode.sman.ide.service.ProjectStorageService.TodoItemData(
                                    id = todo.id,
                                    content = todo.content,
                                    status = todo.status,
                                    type = todo.type ?: "task",
                                    iteration = todo.iteration,
                                    maxIterations = todo.maxIterations,
                                    blockedReason = todo.blockedReason
                                )
                            }.toMutableList()
                        }
                    }
                }
            }
        )
    }
    
    override fun dispose() {
        isDisposed = true
        // 🔥 停止当前会话的分析任务（不影响其他会话）
        webSocketService.stopAnalysis(currentLocalId)
        removeAll()
    }
}
