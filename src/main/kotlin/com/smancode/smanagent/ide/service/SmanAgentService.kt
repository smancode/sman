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
        val os = System.getProperty("os.name").lowercase()
        val isWindows = os.contains("win")

        return when (cachedTechStack?.buildType) {
            BuildType.GRADLE_KTS, BuildType.GRADLE -> {
                val wrapper = if (isWindows) "gradlew.bat" else "./gradlew"
                """
                ## 可用构建命令（Gradle 项目）

                - 构建: `$wrapper build`
                - 清理: `$wrapper clean`
                - 测试: `$wrapper test`
                - 运行: `$wrapper bootRun`
                - 打包: `$wrapper bootJar`
                """.trimIndent()
            }
            BuildType.MAVEN -> {
                """
                ## 可用构建命令（Maven 项目）

                - 构建: `mvn clean install`
                - 清理: `mvn clean`
                - 测试: `mvn test`
                - 运行: `mvn spring-boot:run`
                - 打包: `mvn package`
                """.trimIndent()
            }
            else -> {
                """
                ## 构建命令

                未检测到构建工具（Gradle/Maven），请手动指定命令。
                """.trimIndent()
            }
        }
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
     */
    private fun initializeServices() {
        try {
            // 初始化核心服务
            val promptLoader = PromptLoaderService()
            promptDispatcher = PromptDispatcher(promptLoader)
            toolRegistry = ToolRegistry()
            toolExecutor = ToolExecutor(toolRegistry)
            sessionManager = com.smancode.smanagent.smancode.core.SessionManager()

            // 注册本地工具
            val localTools = LocalToolFactory.createTools(project)
            toolRegistry.registerTools(localTools)
            logger.info("已注册 {} 个本地工具", localTools.size)

            // 创建 LLM 服务（用于其他依赖它的组件）
            val llmService = SmanAgentConfig.createLlmService()

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
            dynamicPromptInjector = DynamicPromptInjector(promptLoader)

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
     */
    private fun initializeProjectAnalysisService() {
        // 使用 CoroutineScope
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            try {
                val analysisService = com.smancode.smanagent.analysis.service.ProjectAnalysisService(project)
                analysisService.init()
                logger.info("项目分析服务初始化完成")
            } catch (e: Exception) {
                logger.warn("项目分析服务初始化失败（非关键）", e)
            }
        }
    }

    /**
     * 格式化初始化错误信息
     */
    private fun formatInitializationError(e: Exception): String = when {
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
