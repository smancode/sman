package com.smancode.smanagent.ide.service

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.smancode.smanagent.analysis.techstack.BuildType
import com.smancode.smanagent.analysis.techstack.TechStack
import com.smancode.smanagent.analysis.techstack.TechStackDetector
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
import com.smancode.smanagent.smancode.llm.config.LlmPoolConfig
import com.smancode.smanagent.smancode.prompt.DynamicPromptInjector
import com.smancode.smanagent.smancode.prompt.PromptDispatcher
import com.smancode.smanagent.smancode.prompt.PromptLoaderService
import com.smancode.smanagent.tools.ToolExecutor
import com.smancode.smanagent.tools.ToolRegistry
import com.smancode.smanagent.tools.ide.LocalToolFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.nio.file.Paths.get
import java.util.concurrent.ConcurrentHashMap
import java.util.function.Consumer

/**
 * SmanAgent 服务管理器
 *
 * 负责初始化和管理所有后端服务
 */
class SmanAgentService(private val project: Project) : Disposable {

    private val logger = LoggerFactory.getLogger(SmanAgentService::class.java)

    // 存储服务
    private val storageService = project.storageService()

    // 服务初始化状态
    var initializationError: String? = null
        private set

    // 核心服务组件
    private lateinit var toolRegistry: ToolRegistry
    private lateinit var toolExecutor: ToolExecutor
    private lateinit var sessionManager: com.smancode.smanagent.smancode.core.SessionManager
    private lateinit var promptDispatcher: PromptDispatcher
    private lateinit var dynamicPromptInjector: DynamicPromptInjector
    private lateinit var smanAgentLoop: SmanAgentLoop

    // 会话缓存
    private val sessionCache = ConcurrentHashMap<String, Session>()

    // 技术栈缓存（启动时检测一次）
    private var cachedTechStack: TechStack? = null

    // 项目标识（用于会话隔离）
    private val projectKey: String
        get() = project.name

    // 代码引用回调（用于通知 UI 插入代码引用）
    var onCodeReferenceCallback: ((com.smancode.smanagent.ide.components.CodeReference) -> Unit)? = null

    init {
        loadUserConfig()
        initializeServices()
        detectAndCacheTechStack()
    }

    /**
     * 检测并缓存技术栈信息
     */
    private fun detectAndCacheTechStack() {
        try {
            val projectPath: Path = get(project.basePath)
            val detector = TechStackDetector()
            cachedTechStack = detector.detect(projectPath)
            logger.info("技术栈检测完成: buildType={}, frameworks={}",
                cachedTechStack?.buildType,
                cachedTechStack?.frameworks?.map { it.name })
        } catch (e: Exception) {
            logger.warn("技术栈检测失败（非关键）", e)
        }
    }

    /**
     * 获取缓存的构建命令（基于检测到的技术栈）
     */
    fun getBuildCommands(): String {
        return when (cachedTechStack?.buildType) {
            BuildType.GRADLE_KTS, BuildType.GRADLE -> getGradleCommands()
            BuildType.MAVEN -> getMavenCommands()
            else -> getUnknownCommands()
        }
    }

    private fun getGradleCommands(): String {
        val wrapper = if (System.getProperty("os.name").lowercase().contains("win")) "gradlew.bat" else "./gradlew"
        return """
                ## 可用构建命令（Gradle 项目）

                - 构建: `$wrapper build`
                - 清理: `$wrapper clean`
                - 测试: `$wrapper test`
                - 运行: `$wrapper bootRun`
                - 打包: `$wrapper bootJar`
                """.trimIndent()
    }

    private fun getMavenCommands(): String {
        return """
                ## 可用构建命令（Maven 项目）

                - 构建: `mvn clean install`
                - 清理: `mvn clean`
                - 测试: `mvn test`
                - 运行: `mvn spring-boot:run`
                - 打包: `mvn package`
                """.trimIndent()
    }

    private fun getUnknownCommands(): String {
        return """
                ## 构建命令

                未检测到构建工具（Gradle/Maven），请手动指定命令。
                """.trimIndent()
    }

    /**
     * 获取项目上下文信息（用于注入到 User Prompt）
     */
    fun getProjectContext(): String {
        val os = System.getProperty("os.name")
        val buildType = cachedTechStack?.buildType ?: BuildType.UNKNOWN

        return """
        ## 项目环境信息

        - 操作系统: $os
        - 构建工具: $buildType

        ${getBuildCommands()}
        """.trimIndent()
    }

    /**
     * 加载用户配置
     */
    private fun loadUserConfig() {
        val userConfig = SmanAgentConfig.UserConfig(
            llmApiKey = storageService.llmApiKey,
            llmBaseUrl = storageService.llmBaseUrl,
            llmModelName = storageService.llmModelName
        )
        SmanAgentConfig.setUserConfig(userConfig)
    }

    /**
     * 重新加载用户配置（只更新配置，不重建服务）
     */
    fun reloadUserConfig() {
        logger.info("重新加载用户配置")
        loadUserConfig()
    }

    /**
     * 初始化所有服务
     *
     * 重要：即使 LLM 未配置，也要确保基础服务可用
     */
    private fun initializeServices() {
        try {
            // 初始化核心服务（不依赖 LLM）
            val promptLoader = PromptLoaderService()
            promptDispatcher = PromptDispatcher(promptLoader)
            toolRegistry = ToolRegistry()
            toolExecutor = ToolExecutor(toolRegistry)
            sessionManager = com.smancode.smanagent.smancode.core.SessionManager()
            dynamicPromptInjector = DynamicPromptInjector(promptLoader)

            // 注册本地工具
            val localTools = LocalToolFactory.createTools(project)
            toolRegistry.registerTools(localTools)
            logger.info("已注册 {} 个本地工具", localTools.size)

            // 尝试创建 LLM 服务（如果配置不可用，不会抛出异常）
            val llmService = try {
                SmanAgentConfig.createLlmService()
            } catch (e: Exception) {
                logger.warn("LLM 服务初始化失败（非致命），本地工具仍可用: {}", e.message)
                initializationError = formatInitializationError(e)
                null
            }

            // 如果 LLM 服务不可用，只初始化基础功能
            if (llmService == null) {
                logger.info("LLM 服务未配置，插件将以有限模式运行（仅本地工具可用）")
                // 创建一个占位的 smanAgentLoop（实际使用时会提示配置 API Key）
                smanAgentLoop = createPlaceholderLoop()
                return
            }

            // 初始化高级服务
            val resultSummarizer = ResultSummarizer(llmService)
            val notificationHandler = StreamingNotificationHandler(llmService)
            val subTaskExecutor = SubTaskExecutor(
                sessionManager = sessionManager,
                toolExecutor = toolExecutor,
                resultSummarizer = resultSummarizer,
                llmService = llmService,
                notificationHandler = notificationHandler
            )
            val contextCompactor = ContextCompactor(llmService)

            // 初始化主循环（不再需要传递 llmService）
            smanAgentLoop = SmanAgentLoop(
                promptDispatcher = promptDispatcher,
                toolRegistry = toolRegistry,
                subTaskExecutor = subTaskExecutor,
                notificationHandler = notificationHandler,
                contextCompactor = contextCompactor,
                smanCodeProperties = SmanCodeProperties(),
                dynamicPromptInjector = dynamicPromptInjector
            )

            // 初始化项目分析服务
            initializeProjectAnalysisService()

            logger.info("SmanAgent 服务初始化完成")
        } catch (e: Exception) {
            logger.error("初始化 SmanAgent 服务失败", e)
            initializationError = formatInitializationError(e)
            throw e
        }
    }

    /**
     * 初始化项目分析服务
     *
     * 在启动时自动加载已有的分析结果到内存缓存
     */
    private fun initializeProjectAnalysisService() {
        // 使用 CoroutineScope
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            try {
                val analysisService = com.smancode.smanagent.analysis.service.ProjectAnalysisService(project)

                // 1. 初始化数据库表
                analysisService.init()

                // 2. 加载已有的分析结果（如果有）
                val cachedResult = analysisService.getAnalysisResult(forceReload = false)
                if (cachedResult != null) {
                    logger.info("已加载分析结果: projectKey={}, status={}, steps={}",
                        project.name,
                        cachedResult.status,
                        cachedResult.steps.size
                    )
                } else {
                    logger.info("暂无分析结果: projectKey={}", project.name)
                }
            } catch (e: Exception) {
                logger.warn("项目分析服务初始化失败（非关键）", e)
            }
        }
    }

    /**
     * 格式化初始化错误信息
     */
    private fun formatInitializationError(e: Exception): String {
        return InitializationErrorFormatter.format(e)
    }

    /**
     * 创建占位符 SmanAgentLoop（当 LLM 未配置时）
     * 这个循环会在用户尝试发送消息时提示配置 API Key
     */
    private fun createPlaceholderLoop(): SmanAgentLoop {
        // 创建一个最小配置的 LlmPoolConfig
        val placeholderConfig = LlmPoolConfig()

        // 创建一个占位符的 LlmService
        val placeholderLlmService = com.smancode.smanagent.smancode.llm.LlmService(placeholderConfig)

        val resultSummarizer = ResultSummarizer(placeholderLlmService)
        val notificationHandler = StreamingNotificationHandler(placeholderLlmService)
        val subTaskExecutor = SubTaskExecutor(
            sessionManager = sessionManager,
            toolExecutor = toolExecutor,
            resultSummarizer = resultSummarizer,
            llmService = placeholderLlmService,
            notificationHandler = notificationHandler
        )
        val contextCompactor = ContextCompactor(placeholderLlmService)

        return SmanAgentLoop(
            promptDispatcher = promptDispatcher,
            toolRegistry = toolRegistry,
            subTaskExecutor = subTaskExecutor,
            notificationHandler = notificationHandler,
            contextCompactor = contextCompactor,
            smanCodeProperties = SmanCodeProperties(),
            dynamicPromptInjector = dynamicPromptInjector
        )
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

        // 检查 LLM 是否已配置
        if (initializationError != null) {
            // 返回一个错误提示消息
            val errorText = """
                ⚠️ ${initializationError}

                请先配置 API Key 后再使用。
                """.trimIndent()

            val errorMessage = TextPart().apply {
                this.text = errorText
                this.id = java.util.UUID.randomUUID().toString()
                this.messageId = "error-${System.currentTimeMillis()}"
                this.sessionId = sessionId
            }
            partPusher.accept(errorMessage)

            // 创建一个失败的消息
            return Message().apply {
                id = java.util.UUID.randomUUID().toString()
                this.sessionId = sessionId
                role = com.smancode.smanagent.model.message.Role.ASSISTANT
                this.parts.add(errorMessage)
                this.content = errorText
            }
        }

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
                    // 注入项目上下文到 metadata
                    metadata["projectContext"] = getProjectContext()
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
                // 更新项目上下文（可能技术栈有变化）
                it.metadata["projectContext"] = getProjectContext()
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

    /**
     * 通知插入代码引用
     */
    fun notifyInsertCodeReference(codeReference: com.smancode.smanagent.ide.components.CodeReference) {
        onCodeReferenceCallback?.invoke(codeReference)
    }

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
