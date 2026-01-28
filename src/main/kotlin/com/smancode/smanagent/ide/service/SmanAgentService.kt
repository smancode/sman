package com.smancode.smanagent.ide.service

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.smancode.smanagent.config.SmanAgentConfig
import com.smancode.smanagent.config.SmanCodeProperties
import com.smancode.smanagent.model.part.Part
import com.smancode.smanagent.model.message.Message
import com.smancode.smanagent.model.part.TextPart
import com.smancode.smanagent.model.session.ProjectInfo
import com.smancode.smanagent.model.session.Session
import com.smancode.smanagent.model.session.SessionStatus
import com.smancode.smanagent.smancode.core.ContextCompactor
import com.smancode.smanagent.smancode.core.ResultSummarizer
import com.smancode.smanagent.smancode.core.SmanAgentLoop
import com.smancode.smanagent.smancode.core.StreamingNotificationHandler
import com.smancode.smanagent.smancode.core.SubTaskExecutor
import com.smancode.smanagent.smancode.llm.LlmService
import com.smancode.smanagent.smancode.llm.config.LlmEndpoint
import com.smancode.smanagent.smancode.llm.config.LlmPoolConfig
import com.smancode.smanagent.smancode.llm.config.LlmRetryPolicy
import com.smancode.smanagent.smancode.prompt.DynamicPromptInjector
import com.smancode.smanagent.smancode.prompt.PromptDispatcher
import com.smancode.smanagent.smancode.prompt.PromptLoaderService
import com.smancode.smanagent.tools.ToolExecutor
import com.smancode.smanagent.tools.ToolRegistry
import com.smancode.smanagent.tools.ide.LocalToolFactory
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.function.Consumer

/**
 * SmanAgent 服务管理器
 *
 * 负责初始化和管理所有后端服务
 */
class SmanAgentService(private val project: Project) : Disposable {

    private val logger = LoggerFactory.getLogger(SmanAgentService::class.java)

    // 服务初始化状态
    var initializationError: String? = null
        private set

    // 核心服务组件
    private lateinit var llmService: LlmService
    private lateinit var toolRegistry: ToolRegistry
    private lateinit var toolExecutor: ToolExecutor
    private lateinit var sessionManager: com.smancode.smanagent.smancode.core.SessionManager
    private lateinit var subTaskExecutor: SubTaskExecutor
    private lateinit var notificationHandler: StreamingNotificationHandler
    private lateinit var contextCompactor: ContextCompactor
    private lateinit var promptDispatcher: PromptDispatcher
    private lateinit var dynamicPromptInjector: DynamicPromptInjector
    private lateinit var smanAgentLoop: SmanAgentLoop

    // 会话缓存
    private val sessionCache = ConcurrentHashMap<String, Session>()

    // 项目标识（用于会话隔离）
    private val projectKey: String
        get() = project.name

    init {
        initializeServices()
        // 不再启动时加载所有历史会话，改为按需加载
    }

    /**
     * 初始化所有服务
     */
    private fun initializeServices() {
        try {
            // 获取 LLM 配置
            val llmPoolConfig = createLlmPoolConfig()

            // 初始化核心服务
            llmService = LlmService(llmPoolConfig)
            val promptLoader = PromptLoaderService()
            promptDispatcher = PromptDispatcher(promptLoader)
            toolRegistry = ToolRegistry()
            toolExecutor = ToolExecutor(toolRegistry)
            sessionManager = com.smancode.smanagent.smancode.core.SessionManager()

            // 注册本地工具
            val localTools = LocalToolFactory.createTools(project)
            toolRegistry.registerTools(localTools)
            logger.info("已注册 {} 个本地工具", localTools.size)

            // 初始化高级服务
            val resultSummarizer = ResultSummarizer(llmService)
            notificationHandler = StreamingNotificationHandler(llmService)
            subTaskExecutor = SubTaskExecutor(
                sessionManager = sessionManager,
                toolExecutor = toolExecutor,
                resultSummarizer = resultSummarizer,
                llmService = llmService,
                notificationHandler = notificationHandler
            )
            contextCompactor = ContextCompactor(llmService)
            dynamicPromptInjector = DynamicPromptInjector(promptLoader)

            // 初始化主循环
            smanAgentLoop = SmanAgentLoop(
                llmService = llmService,
                promptDispatcher = promptDispatcher,
                toolRegistry = toolRegistry,
                subTaskExecutor = subTaskExecutor,
                notificationHandler = notificationHandler,
                contextCompactor = contextCompactor,
                smanCodeProperties = SmanCodeProperties(),
                dynamicPromptInjector = dynamicPromptInjector
            )

            logger.info("SmanAgent 服务初始化完成")
        } catch (e: Exception) {
            logger.error("初始化 SmanAgent 服务失败", e)
            initializationError = formatInitializationError(e)
            throw e
        }
    }

    /**
     * 格式化初始化错误信息
     */
    private fun formatInitializationError(e: Exception): String {
        return when {
            e.message?.contains("LLM_API_KEY") == true -> """
                ⚠️ 配置错误：缺少 API Key

                请设置 LLM_API_KEY 环境变量：

                1. 打开 Run → Edit Configurations...
                2. 选择你的插件运行配置
                3. 在 Environment variables 中添加：
                   LLM_API_KEY=your_api_key_here

                或在终端中执行：
                export LLM_API_KEY=your_api_key_here
            """.trimIndent()

            e.message?.contains("Connection") == true ||
            e.message?.contains("timeout") == true -> """
                ⚠️ 网络错误：无法连接到 LLM 服务

                请检查：
                • 网络连接是否正常
                • API Key 是否有效
                • LLM 服务是否可用
            """.trimIndent()

            else -> """
                ⚠️ 初始化失败：${e.message}

                请查看日志获取详细信息。
            """.trimIndent()
        }
    }

    /**
     * 创建 LLM 连接池配置
     */
    private fun createLlmPoolConfig(): LlmPoolConfig {
        // 使用配置对象
        val config = SmanAgentConfig

        logger.info(config.getConfigSummary())

        return LlmPoolConfig().apply {
            endpoints.add(
                LlmEndpoint().apply {
                    this.baseUrl = config.llmBaseUrl
                    this.apiKey = config.llmApiKey
                    this.model = config.llmModelName
                    this.maxTokens = config.llmResponseMaxTokens
                    this.isEnabled = true
                }
            )
            retry = LlmRetryPolicy().apply {
                maxRetries = config.llmRetryMax
                baseDelay = config.llmRetryBaseDelay
            }
        }
    }

    // ========== 公共 API ==========

    /**
     * 处理用户消息（自动保存会话）
     */
    fun processMessage(
        sessionId: String,
        userInput: String,
        partPusher: Consumer<Part>
    ): Message {
        logger.info("处理消息: sessionId={}, input={}", sessionId, userInput)
        val session = getOrCreateSession(sessionId)

        return try {
            smanAgentLoop.process(session, userInput, partPusher).also {
                SessionFileService.saveSession(session, projectKey)
            }
        } catch (e: Exception) {
            SessionFileService.saveSession(session, projectKey)
            throw e
        }
    }

    /**
     * 获取或创建会话（只创建，不加载历史）
     */
    fun getOrCreateSession(sessionId: String): Session {
        return sessionCache[sessionId] ?: sessionCache.computeIfAbsent(sessionId) { id ->
            logger.info("创建新会话: sessionId={}", id)

            ProjectInfo().apply {
                projectKey = this@SmanAgentService.projectKey
                projectPath = project.basePath
            }.let { projectInfo ->
                Session(id, projectInfo).apply {
                    status = SessionStatus.IDLE
                }
            }
        }.also { session ->
            // 【关键】注册到 SessionManager，否则创建子会话时会找不到父会话
            sessionManager.registerSession(session)
        }
    }

    /**
     * 加载历史会话（按需加载）
     */
    fun loadSession(sessionId: String): Session? {
        return sessionCache[sessionId] ?: run {
            val session = SessionFileService.loadSession(sessionId, projectKey)
            session?.also {
                sessionCache[sessionId] = it
                // 【关键】注册到 SessionManager，否则创建子会话时会找不到父会话
                sessionManager.registerSession(it)
                logger.info("从文件加载会话: projectKey={}, sessionId={}, 消息数={}", projectKey, sessionId, it.messages.size)
            }
        }
    }

    /**
     * 获取会话
     */
    fun getSession(sessionId: String): Session? = sessionCache[sessionId]

    /**
     * 从缓存移除会话（不删除文件）
     */
    fun unloadSession(sessionId: String) {
        sessionCache.remove(sessionId)?.also {
            logger.info("卸载会话: sessionId={}（文件仍保留）", sessionId)
        }
    }

    /**
     * 获取所有会话 ID（只获取 ID 列表，不加载内容）
     */
    fun getAllSessionIds(): Set<String> {
        return sessionCache.keys + SessionFileService.getAllSessionIds(projectKey).toSet()
    }

    /**
     * 获取所有会话（只获取缓存的会话）
     */
    fun getAllSessions(): List<Session> = sessionCache.values.toList()

    // ========== Disposable 实现 ==========

    override fun dispose() {
        logger.info("释放 SmanAgent 服务资源")
        sessionCache.clear()
    }

    companion object {
        private val instances = ConcurrentHashMap<Project, SmanAgentService>()

        /**
         * 获取项目的 SmanAgentService 实例
         */
        fun getInstance(project: Project): SmanAgentService {
            return instances.computeIfAbsent(project) { SmanAgentService(it) }
        }

        /**
         * 释放项目的 SmanAgentService 实例
         */
        fun disposeInstance(project: Project) {
            instances.remove(project)?.dispose()
        }
    }
}
