package ai.smancode.sman.ide.service

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

@Service(Service.Level.PROJECT)
class WebSocketService(private val project: Project) {
    private val logger = Logger.getInstance(WebSocketService::class.java)
    
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS) // WebSocket 需要禁用读取超时
        .pingInterval(30, TimeUnit.SECONDS) // 自动发送 Ping
        .connectTimeout(30, TimeUnit.SECONDS) // 连接超时
        .writeTimeout(2, TimeUnit.MINUTES) // 写入超时（大消息传输需要更长时间）
        .build()
    
    // 🔥 多连接设计：按 localId 管理多个 WebSocket 连接
    private val webSockets = ConcurrentHashMap<String, WebSocket>()
    
    /**
     * 建议答案数据类
     */
    data class SuggestedAnswer(
        val id: String,
        val text: String,
        val label: String,
        val recommended: Boolean = false
    )
    
    /**
     * 澄清请求数据类
     */
    data class ClarificationData(
        val question: String,
        val suggestions: List<SuggestedAnswer>,
        val originalInput: String? = null,
        val requestId: String? = null
    )
    
    /**
     * TODO 数据类
     */
    data class TodoItemData(
        val id: String,
        val content: String,
        val status: String,
        val type: String? = "task",
        val iteration: Int? = null,
        val maxIterations: Int? = null,
        val blockedReason: String? = null
    )
    
    /**
     * TODO 更新数据类
     */
    data class TodoUpdateData(
        val sessionId: String?,
        val stage: String?,
        val totalTodos: Int,
        val completedTodos: Int,
        val pendingTodos: Int,
        val todos: List<TodoItemData>
    )
    
    interface AnalysisListener {
        fun onProgress(thinking: String, round: Int)
        fun onComplete(result: String, requestId: String, process: String = "")
        fun onCancelled(message: String)
        fun onError(message: String)
        fun onClosed()

        /**
         * 🆕 收到流式内容（Markdown 块）
         * @param content Markdown 内容
         * @param chunkIndex 块索引
         * @param isComplete 是否是最终完整内容
         */
        fun onStreamingContent(content: String, chunkIndex: Int, isComplete: Boolean) {
            // 默认空实现，兼容旧代码
        }

        /**
         * 🆕 收到澄清请求（需要用户选择建议答案）
         */
        fun onClarification(clarification: ClarificationData) {
            // 默认空实现，兼容旧代码
        }

        /**
         * 🆕 收到 TODO 状态更新
         */
        fun onTodoUpdate(todoUpdate: TodoUpdateData) {
            // 默认空实现，兼容旧代码
        }

        /**
         * 🔥 收到代码编辑指令（用户确认后实施编码）
         */
        fun onCodeEdit(codeEditData: org.json.JSONObject) {
            // 默认空实现，兼容旧代码
        }
    }
    
    /**
     * 检查指定会话是否有活跃连接
     */
    fun isConnected(localId: String): Boolean = webSockets.containsKey(localId)
    
    /**
     * 获取当前活跃连接数
     */
    fun getActiveConnectionCount(): Int = webSockets.size

    /**
     * 启动分析任务
     *
     * @param localId 本地会话ID，用于管理连接
     * @param serverUrl 服务器地址
     * @param requirementText 需求文本
     * @param projectKey 项目标识
     * @param lastRequestId 用于多轮对话的后端会话ID
     * @param listener 事件监听器
     */
    fun startAnalysis(
        localId: String,
        serverUrl: String,
        requirementText: String,
        projectKey: String,
        lastRequestId: String?,
        listener: AnalysisListener
    ) {
        // 🔥 前端会话 UUID（使用 localId，在整个会话期间保持不变）
        val frontendSessionId = localId  // ✅ 直接使用传入的 localId
        // 🔥 详细日志：追踪 URL 处理全过程
        logger.info("[$localId] ========== START ANALYSIS ==========")
        logger.info("[$localId] 原始 serverUrl: '$serverUrl'")
        logger.info("[$localId] projectKey: '$projectKey'")
        logger.info("[$localId] frontendSessionId: '$frontendSessionId' (会话UUID)")
        
        // 🔥 如果该会话已有连接，先断开（同一会话的重复请求）
        if (webSockets.containsKey(localId)) {
            logger.info("会话 $localId 已有连接，先断开")
            stopAnalysis(localId)
        }
        
        // 1. IP 映射处理：只有 URL 中包含 agent-ip 时才替换
        val agentIpMapping = ai.smancode.sman.ide.service.StorageService.getInstance().getAgentIpMapping()
        val agentIp = agentIpMapping[projectKey] ?: "localhost"
        logger.info("[$localId] IP映射: projectKey='$projectKey' -> agentIp='$agentIp'")
        
        val resolvedUrl = if (serverUrl.contains("agent-ip")) {
            val replaced = serverUrl.replace("agent-ip", agentIp)
            logger.info("[$localId] URL包含agent-ip，替换后: '$replaced'")
            replaced
        } else {
            logger.info("[$localId] URL不包含agent-ip，保持原样: '$serverUrl'")
            serverUrl
        }
        
        // 2. 直接使用用户设置的 URL，只处理协议转换
        var wsUrl = resolvedUrl
        if (wsUrl.startsWith("http://")) {
            wsUrl = wsUrl.replace("http://", "ws://")
        } else if (wsUrl.startsWith("https://")) {
            wsUrl = wsUrl.replace("https://", "wss://")
        }

        // 3. 🔥 添加查询参数（sessionId, projectKey, mode）
        val mode = ai.smancode.sman.ide.service.ProjectStorageService.getInstance(project).getMode()
        val projectPath = project.basePath ?: ""

        // 构建 URL 查询参数
        val queryParams = mutableListOf<String>()
        queryParams.add("sessionId=$frontendSessionId")
        queryParams.add("projectKey=$projectKey")
        queryParams.add("mode=$mode")
        if (projectPath.isNotEmpty()) {
            // URL 编码 projectPath（处理特殊字符）
            val encodedPath = java.net.URLEncoder.encode(projectPath, "UTF-8")
            queryParams.add("projectPath=$encodedPath")
        }

        // 拼接到 URL
        val separator = if (wsUrl.contains("?")) "&" else "?"
        val finalWsUrl = if (wsUrl.contains("?")) {
            "$wsUrl&${queryParams.joinToString("&")}"
        } else {
            "$wsUrl?${queryParams.joinToString("&")}"
        }

        logger.info("[$localId] 最终 WebSocket URL: '$finalWsUrl'")
        logger.info("[$localId] Connecting to WebSocket: $finalWsUrl (active connections: ${webSockets.size})")

        val request = Request.Builder()
            .url(finalWsUrl)
            .build()
            
        val wsListener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                logger.info("[$localId] WebSocket Connected")

                // 发送 AGENT_CHAT 消息
                val contextId = lastRequestId ?: ""  // 多轮对话的后端会话ID（可为空）
                val projectPath = project.basePath ?: ""

                val payload = JSONObject().apply {
                    put("type", "AGENT_CHAT")
                    put("data", JSONObject().apply {
                        put("projectKey", projectKey)
                        put("sessionId", contextId)  // 多轮对话的后端上下文ID
                        put("message", requirementText)
                        put("mode", mode)
                        if (projectPath.isNotEmpty()) {
                            put("projectPath", projectPath)
                        }
                    })
                }
                webSocket.send(payload.toString())
                logger.info("[$localId] Sent AGENT_CHAT request (sessionId=$contextId)")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                logger.info("[$localId] Received: $text")
                try {
                    val json = JSONObject(text)
                    val type = json.optString("type")
                    val data = json.optJSONObject("data") ?: JSONObject()

                    when (type) {
                        "AGENT_RESPONSE" -> {
                            val status = data.optString("status")
                            val content = data.optString("content")  // 🔥 修复：后端发送的是 content 字段
                            val process = data.optString("process", "")  // 🔥 修复：读取 process 字段
                            val stage = data.optString("stage", "")
                            val sessionId = data.optString("sessionId", "")

                            when (status) {
                                "COMPLETED", "SUCCESS" -> {
                                    // 🔥 修复：传递 content 和 process 参数（修复复制和历史会话问题）
                                    listener.onComplete(content, sessionId, process)
                                    webSocket.close(1000, "Completed")
                                }
                                "ERROR", "FAILED" -> {
                                    listener.onError(content)  // 🔥 修复：使用 content
                                    webSocket.close(1000, "Error")
                                }
                                "CANCELLED" -> {
                                    listener.onCancelled(content)  // 🔥 修复：使用 content
                                    webSocket.close(1000, "Cancelled")
                                }
                                else -> {
                                    // PROCESSING, WAITING_CONFIRM 等都视为进度
                                    val thinkingText = if (stage.isNotEmpty()) "[$stage] $content" else content  // 🔥 修复：使用 content
                                    listener.onProgress(thinkingText, 0)
                                }
                            }
                        }
                        "STREAMING_CONTENT" -> {
                            val content = data.optString("content", "")
                            val contentType = data.optString("contentType", "markdown")
                            val chunkIndex = data.optInt("chunkIndex", 0)
                            val isComplete = data.optBoolean("isComplete", false)

                            logger.info("[$localId] 收到流式内容: type=$contentType, chunk=$chunkIndex, complete=$isComplete, length=${content.length}")

                            if (contentType == "markdown") {
                                listener.onStreamingContent(content, chunkIndex, isComplete)
                            }
                        }
                        "TODO_UPDATE" -> {
                            handleTodoUpdate(listener, data)
                        }
                        "CODE_EDIT" -> {
                            logger.info("[$localId] Received CODE_EDIT request")
                            listener.onCodeEdit(data)
                        }
                        "TOOL_CALL" -> {
                            // 🔥 后端请求前端执行工具
                            // 注意：TOOL_CALL 消息的字段在根级别，不在 data 中
                            // 格式：{"type":"TOOL_CALL","toolCallId":"...","toolName":"...","params":{...}}
                            logger.info("[$localId] Received TOOL_CALL request")
                            handleToolCall(webSocket, json)
                        }
                        "PONG" -> {
                            // 心跳响应，忽略
                        }
                        else -> {
                            logger.warn("[$localId] Unknown message type: $type")
                        }
                    }
                } catch (e: Exception) {
                    logger.warn("[$localId] Parse error: ${e.message}")
                    listener.onError("Failed to parse server message: ${e.message}")
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                logger.info("[$localId] WebSocket Closing: $code / $reason")
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                logger.info("[$localId] WebSocket Closed (active connections: ${webSockets.size - 1})")
                // 🔥 从 Map 中移除，释放资源
                webSockets.remove(localId)
                listener.onClosed()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                // Ignore EOFException or null message which usually indicates a race condition during close
                if (t is java.io.EOFException || t.message == null) {
                    logger.info("[$localId] WebSocket Connection closed (EOF/Null)", t)
                } else {
                    logger.error("[$localId] WebSocket Failure", t)
                    listener.onError("Connection error: ${t.message}")
                }
                
                // 🔥 从 Map 中移除
                webSockets.remove(localId)
                listener.onClosed()
            }
        }
        
        val ws = client.newWebSocket(request, wsListener)
        // 🔥 保存到 Map
        webSockets[localId] = ws
    }
    
    /**
     * 停止指定会话的分析任务
     * 
     * @param localId 要停止的会话ID
     */
    fun stopAnalysis(localId: String) {
        webSockets[localId]?.let { ws ->
            logger.info("[$localId] Sending STOP command")
            val payload = JSONObject().apply {
                put("type", "STOP")
            }
            try {
                ws.send(payload.toString())
            } catch (e: Exception) {
                logger.warn("[$localId] Failed to send STOP: ${e.message}")
            }
        }
    }
    
    /**
     * 停止所有分析任务（用于插件卸载时）
     */
    fun stopAllAnalysis() {
        logger.info("Stopping all analysis tasks (count: ${webSockets.size})")
        webSockets.keys.toList().forEach { localId ->
            stopAnalysis(localId)
        }
    }
    
    /**
     * 🆕 处理 TODO 状态更新
     */
    private fun handleTodoUpdate(listener: AnalysisListener, data: JSONObject) {
        val sessionId = data.optString("sessionId", "")
        val stage = data.optString("stage", "")
        val totalTodos = data.optInt("totalTodos", 0)
        val completedTodos = data.optInt("completedTodos", 0)
        val pendingTodos = data.optInt("pendingTodos", 0)
        
        val todosArray = data.optJSONArray("todos")
        val todos = mutableListOf<TodoItemData>()
        
        if (todosArray != null) {
            for (i in 0 until todosArray.length()) {
                val item = todosArray.optJSONObject(i) ?: continue
                todos.add(TodoItemData(
                    id = item.optString("id", ""),
                    content = item.optString("content", ""),
                    status = item.optString("status", "pending"),
                    type = item.optString("type", "task"),
                    iteration = if (item.has("iteration")) item.optInt("iteration") else null,
                    maxIterations = if (item.has("maxIterations")) item.optInt("maxIterations") else null
                ))
            }
        }
        
        val todoUpdate = TodoUpdateData(
            sessionId = sessionId.ifEmpty { null },
            stage = stage.ifEmpty { null },
            totalTodos = totalTodos,
            completedTodos = completedTodos,
            pendingTodos = pendingTodos,
            todos = todos
        )
        
        logger.info("收到 TODO 更新: total=$totalTodos, completed=$completedTodos")
        listener.onTodoUpdate(todoUpdate)
    }
    
    /**
     * 🆕 从 PROGRESS 消息中解析 TODO 列表
     */
    private fun parseTodoItemsFromProgress(data: JSONObject): TodoUpdateData {
        val todoItemsArray = data.optJSONArray("todoItems")
        val todos = mutableListOf<TodoItemData>()
        
        if (todoItemsArray != null) {
            for (i in 0 until todoItemsArray.length()) {
                val item = todoItemsArray.optJSONObject(i) ?: continue
                todos.add(TodoItemData(
                    id = item.optString("id", ""),
                    content = item.optString("content", ""),
                    status = item.optString("status", "pending"),
                    type = item.optString("type", "task"),
                    iteration = if (item.has("iteration")) item.optInt("iteration") else null,
                    maxIterations = if (item.has("maxIterations")) item.optInt("maxIterations") else null,
                    blockedReason = item.optString("blockedReason", null)
                ))
            }
        }
        
        val completedCount = todos.count { it.status == "completed" }
        val pendingCount = todos.count { it.status == "pending" || it.status == "in_progress" }
        
        return TodoUpdateData(
            sessionId = null,
            stage = data.optString("stage", null),
            totalTodos = todos.size,
            completedTodos = completedCount,
            pendingTodos = pendingCount,
            todos = todos
        )
    }
    
    /**
     * 🆕 处理澄清请求
     */
    private fun handleClarification(listener: AnalysisListener, data: JSONObject) {
        val question = data.optString("question", "请问您想做什么？")
        val suggestionsArray = data.optJSONArray("suggestions")
        val originalInput = data.optString("originalInput", "")
        val requestId = data.optString("requestId", "")
        
        val suggestions = mutableListOf<SuggestedAnswer>()
        if (suggestionsArray != null) {
            for (i in 0 until suggestionsArray.length()) {
                val item = suggestionsArray.optJSONObject(i) ?: continue
                suggestions.add(SuggestedAnswer(
                    id = item.optString("id", ""),
                    text = item.optString("text", ""),
                    label = item.optString("label", item.optString("text", "")),
                    recommended = item.optBoolean("recommended", false)
                ))
            }
        }
        
        val clarification = ClarificationData(
            question = question,
            suggestions = suggestions,
            originalInput = originalInput,
            requestId = requestId
        )
        
        logger.info("收到澄清请求: question=$question, suggestions=${suggestions.size}")
        listener.onClarification(clarification)
    }
    
    /**
     * 🆕 发送用户回答（选择建议或自定义输入）
     * 
     * @param localId 会话ID
     * @param answerText 用户的回答文本
     * @param suggestionId 选择的建议ID（如果是选择建议）
     */
    fun sendAnswer(localId: String, answerText: String, suggestionId: String? = null) {
        val ws = webSockets[localId]
        if (ws == null) {
            logger.warn("[$localId] 无法发送回答：连接不存在")
            return
        }
        
        val payload = JSONObject().apply {
            put("type", "ANSWER")
            put("data", JSONObject().apply {
                put("text", answerText)
                if (suggestionId != null) {
                    put("suggestionId", suggestionId)
                }
            })
        }
        
        try {
            ws.send(payload.toString())
            logger.info("[$localId] 发送用户回答: text=${answerText.take(50)}...")
        } catch (e: Exception) {
            logger.warn("[$localId] 发送回答失败: ${e.message}")
        }
    }
    
    /**
     * 🆕 发送 ANSWER 消息并继续分析流程
     * 
     * 专门用于回答澄清问题的场景，建立新连接发送 ANSWER 消息
     * 后端会根据 requestId 恢复会话上下文继续处理
     * 
     * @param localId 本地会话ID
     * @param serverUrl 服务器地址
     * @param answerText 用户回答的文本
     * @param suggestionId 选择的建议ID（可选）
     * @param projectKey 项目标识
     * @param requestId 后端会话ID（用于恢复上下文）
     * @param listener 事件监听器
     */
    fun startAnalysisWithAnswer(
        localId: String,
        serverUrl: String,
        answerText: String,
        suggestionId: String?,
        projectKey: String,
        requestId: String?,
        listener: AnalysisListener
    ) {
        logger.info("[$localId] startAnalysisWithAnswer called. serverUrl='$serverUrl', projectKey='$projectKey'")

        // 如果该会话已有连接，先断开
        if (webSockets.containsKey(localId)) {
            logger.info("会话 $localId 已有连接，先断开")
            stopAnalysis(localId)
        }
        
        // 1. IP 映射处理：只有 URL 中包含 agent-ip 时才替换
        val agentIp = ai.smancode.sman.ide.service.StorageService.getInstance().getAgentIpMapping()[projectKey] ?: "localhost"
        
        val resolvedUrl = if (serverUrl.contains("agent-ip")) {
            serverUrl.replace("agent-ip", agentIp)
        } else {
            serverUrl
        }
        
        // 2. 直接使用用户设置的 URL，只处理协议转换
        var wsUrl = resolvedUrl
        if (wsUrl.startsWith("http://")) {
            wsUrl = wsUrl.replace("http://", "ws://")
        } else if (wsUrl.startsWith("https://")) {
            wsUrl = wsUrl.replace("https://", "wss://")
        }
        
        logger.info("[$localId] Connecting for ANSWER: $wsUrl")
        
        val request = Request.Builder()
            .url(wsUrl)
            .build()
            
        val wsListener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                logger.info("[$localId] WebSocket Connected for ANSWER")
                // 连接成功后，发送 ANSWER 消息
                val payload = JSONObject().apply {
                    put("type", "ANSWER")
                    put("data", JSONObject().apply {
                        put("text", answerText)
                        put("projectKey", projectKey)
                        put("requestId", requestId ?: "")
                        if (suggestionId != null) {
                            put("suggestionId", suggestionId)
                        }
                        val mode = ai.smancode.sman.ide.service.ProjectStorageService.getInstance(project).getMode()
                        put("mode", mode)
                        put("projectPath", project.basePath ?: "")
                    })
                }
                webSocket.send(payload.toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                logger.info("[$localId] Received: $text")
                try {
                    val json = JSONObject(text)
                    val type = json.optString("type")
                    val data = json.optJSONObject("data") ?: JSONObject()
                    
                    when (type) {
                        "PROGRESS" -> {
                            val thinking = data.optString("thinking")
                            val round = data.optInt("round", 0)
                            listener.onProgress(thinking, round)
                            
                            // 🆕 从 PROGRESS 中提取 TODO 列表
                            val todoItemsArray = data.optJSONArray("todoItems")
                            if (todoItemsArray != null && todoItemsArray.length() > 0) {
                                val todoUpdate = parseTodoItemsFromProgress(data)
                                listener.onTodoUpdate(todoUpdate)
                            }
                        }
                        "COMPLETE" -> {
                            val result = data.optString("analysisResult")
                            val process = data.optString("process", "")  // 🔥 解析 process 字段
                            val reqId = data.optString("requestId")
                            listener.onComplete(result, reqId, process)
                            webSocket.close(1000, "Completed")
                        }
                        "CANCELLED" -> {
                            val msg = data.optString("message", "User cancelled")
                            listener.onCancelled(msg)
                            webSocket.close(1000, "Cancelled")
                        }
                        "ERROR" -> {
                            val msg = data.optString("message", "Unknown error")
                            listener.onError(msg)
                            webSocket.close(1000, "Error")
                        }
                        "CLARIFICATION" -> {
                            handleClarification(listener, data)
                        }
                        "TODO_UPDATE" -> {
                            handleTodoUpdate(listener, data)
                        }
                        "CODE_EDIT" -> {
                            // 🔥 收到代码编辑指令
                            logger.info("[$localId] Received CODE_EDIT request")
                            listener.onCodeEdit(data)
                        }
                    }
                } catch (e: Exception) {
                    logger.warn("[$localId] Parse error: ${e.message}")
                    listener.onError("Failed to parse server message: ${e.message}")
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                logger.info("[$localId] WebSocket Closing: $code / $reason")
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                logger.info("[$localId] WebSocket Closed")
                webSockets.remove(localId)
                listener.onClosed()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                logger.error("[$localId] WebSocket Failure", t)
                webSockets.remove(localId)
                listener.onError("Connection error: ${t.message}")
                listener.onClosed()
            }
        }
        
        val ws = client.newWebSocket(request, wsListener)
        webSockets[localId] = ws
    }
    
    /**
     * 处理 Agent 的工具调用请求
     */
    private fun handleToolCall(webSocket: WebSocket, data: JSONObject) {
        logger.info("原始 TOOL_CALL 消息: $data")  // 🔥 添加：打印原始消息

        val toolCallId = data.optString("toolCallId", "")  // 🔥 修改：使用 toolCallId 而不是 callId
        val toolName = data.optString("toolName", "")
        val projectPath = data.optString("projectPath", project.basePath ?: "")

        logger.info("解析后: toolCallId='$toolCallId', toolName='$toolName'")  // 🔥 添加：用引号显示空值

        // 🔥 添加：验证 toolCallId
        if (toolCallId.isEmpty()) {
            logger.error("❌ toolCallId 为空！原始消息: $data")
            val response = JSONObject().apply {
                put("type", "TOOL_RESULT")
                put("data", JSONObject().apply {
                    put("toolCallId", "")
                    put("success", false)
                    put("error", "toolCallId 字段缺失或为空")
                })
            }
            webSocket.send(response.toString())
            return
        }

        val paramsJson = data.optJSONObject("params") ?: JSONObject()  // 🔥 修改：使用 params 而不是 parameters
        val parameters = mutableMapOf<String, Any?>()
        paramsJson.keys().forEach { key ->
            parameters[key] = paramsJson.opt(key)
        }

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val executor = ai.smancode.sman.ide.service.LocalToolExecutor(project)
                val result = executor.execute(toolName, parameters, projectPath)

                val response = JSONObject().apply {
                    put("type", "TOOL_RESULT")
                    put("data", JSONObject().apply {
                        put("toolCallId", toolCallId)
                        put("success", result.success)
                        put("result", result.result)
                        put("error", if (!result.success) result.result else null)
                        put("executionTime", result.executionTime)
                    })
                }

                webSocket.send(response.toString())
                logger.info("工具执行完成: toolCallId=$toolCallId, success=${result.success}, time=${result.executionTime}ms")

            } catch (e: Exception) {
                logger.error("工具执行异常: $toolCallId", e)

                val response = JSONObject().apply {
                    put("type", "TOOL_RESULT")
                    put("data", JSONObject().apply {
                        put("toolCallId", toolCallId)
                        put("success", false)
                        put("error", "工具执行异常: ${e.message}")
                    })
                }
                webSocket.send(response.toString())
            }
        }
    }
    
    companion object {
        fun getInstance(project: Project): WebSocketService {
            return project.getService(WebSocketService::class.java)
        }
    }
}
