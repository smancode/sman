package com.smancode.sman.ide.service

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.smancode.sman.analysis.techstack.BuildType
import com.smancode.sman.analysis.techstack.TechStack
import com.smancode.sman.analysis.techstack.TechStackDetector
import com.smancode.sman.analysis.service.ProjectContextInjector
import com.smancode.sman.analysis.config.VectorDatabaseConfig
import com.smancode.sman.analysis.config.VectorDbType
import com.smancode.sman.analysis.config.JVectorConfig
import com.smancode.sman.config.SmanConfig
import com.smancode.sman.config.SmanCodeProperties
import com.smancode.sman.model.part.Part
import com.smancode.sman.model.message.Message
import com.smancode.sman.model.part.TextPart
import com.smancode.sman.model.session.ProjectInfo
import com.smancode.sman.model.session.Session
import com.smancode.sman.model.session.SessionStatus
import com.smancode.sman.smancode.core.ContextCompactor
import com.smancode.sman.smancode.core.ResultSummarizer
import com.smancode.sman.smancode.core.SmanLoop
import com.smancode.sman.smancode.core.StreamingNotificationHandler
import com.smancode.sman.smancode.core.SubTaskExecutor
import com.smancode.sman.smancode.llm.LlmService
import com.smancode.sman.smancode.llm.config.LlmPoolConfig
import com.smancode.sman.smancode.prompt.DynamicPromptInjector
import com.smancode.sman.smancode.prompt.PromptDispatcher
import com.smancode.sman.smancode.prompt.PromptLoaderService
import com.smancode.sman.tools.ToolExecutor
import com.smancode.sman.tools.ToolRegistry
import com.smancode.sman.tools.ide.LocalToolFactory
import com.smancode.sman.skill.SkillRegistry
import com.smancode.sman.skill.SkillTool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.nio.file.Paths.get
import java.util.concurrent.ConcurrentHashMap
import java.util.function.Consumer

/**
 * Sman 服务管理器
 *
 * 负责初始化和管理所有后端服务
 */
class SmanService(private val project: Project) : Disposable {

    private val logger = LoggerFactory.getLogger(SmanService::class.java)

    // 存储服务
    private val storageService = project.storageService()

    // 服务初始化状态
    var initializationError: String? = null
        private set

    // 核心服务组件
    private lateinit var toolRegistry: ToolRegistry
    private lateinit var toolExecutor: ToolExecutor
    private lateinit var sessionManager: com.smancode.sman.smancode.core.SessionManager
    private lateinit var promptDispatcher: PromptDispatcher
    private lateinit var dynamicPromptInjector: DynamicPromptInjector
    private lateinit var smanAgentLoop: SmanLoop
    private lateinit var skillRegistry: SkillRegistry

    // 暴露给 ArchitectAgent 使用
    fun getToolRegistry(): ToolRegistry = toolRegistry
    fun getToolExecutor(): ToolExecutor = toolExecutor

    // 会话缓存
    private val sessionCache = ConcurrentHashMap<String, Session>()

    // 技术栈缓存（启动时检测一次）
    private var cachedTechStack: TechStack? = null

    // 项目标识（用于会话隔离）
    private val projectKey: String
        get() = project.name

    // 代码引用回调（用于通知 UI 插入代码引用）
    var onCodeReferenceCallback: ((com.smancode.sman.ide.components.CodeReference) -> Unit)? = null

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
     *
     * 包含：
     * - 项目结构（模块、分层）
     * - 技术栈（框架、数据库、语言）
     * - 构建工具信息
     */
    fun getProjectContext(): String {
        val os = System.getProperty("os.name")
        val buildType = cachedTechStack?.buildType ?: BuildType.UNKNOWN

        // 尝试从 H2 数据库加载详细的项目上下文
        val detailedContext = try {
            val jdbcUrl = buildJdbcUrl()
            val injector = ProjectContextInjector(jdbcUrl)
            runBlocking {
                injector.getProjectContextSummary(projectKey)
            }
        } catch (e: Exception) {
            logger.debug("获取详细项目上下文失败（可能项目分析未完成）: {}", e.message)
            ""
        }

        return if (detailedContext.isNotEmpty()) {
            // 有详细分析数据，返回完整上下文
            """
            ## 项目环境信息

            - 操作系统: $os
            - 构建工具: $buildType

            ${getBuildCommands()}

            $detailedContext
            """.trimIndent()
        } else {
            // 没有详细分析数据，只返回基础信息
            """
            ## 项目环境信息

            - 操作系统: $os
            - 构建工具: $buildType

            ${getBuildCommands()}
            """.trimIndent()
        }
    }

    /**
     * 构建 H2 JDBC URL
     * 数据库存储在项目目录下的 .sman 文件夹中
     */
    private fun buildJdbcUrl(): String {
        // 获取项目基础路径
        val projectBasePath = project.basePath?.let { java.nio.file.Paths.get(it) }
            ?: throw IllegalStateException("项目基础路径为空，无法创建向量数据库配置")

        val paths = com.smancode.sman.analysis.paths.ProjectPaths.forProject(projectBasePath)
        return paths.getDatabaseJdbcUrl()
    }

    /**
     * 加载用户配置
     */
    private fun loadUserConfig() {
        val userConfig = SmanConfig.UserConfig(
            llmApiKey = storageService.llmApiKey,
            llmBaseUrl = storageService.llmBaseUrl,
            llmModelName = storageService.llmModelName
        )
        SmanConfig.setUserConfig(userConfig)
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
            sessionManager = com.smancode.sman.smancode.core.SessionManager()
            dynamicPromptInjector = DynamicPromptInjector(promptLoader, project.basePath?.let { java.nio.file.Paths.get(it) }
                ?: throw IllegalStateException("项目基础路径为空，无法创建 DynamicPromptInjector"))

            // 注册本地工具
            val localTools = LocalToolFactory.createTools(project)
            toolRegistry.registerTools(localTools)
            logger.info("已注册 {} 个本地工具", localTools.size)

            // 初始化 Skill 系统
            initializeSkillSystem()

            // 尝试创建 LLM 服务（如果配置不可用，不会抛出异常）
            val llmService = try {
                SmanConfig.createLlmService()
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
            smanAgentLoop = SmanLoop(
                promptDispatcher = promptDispatcher,
                toolRegistry = toolRegistry,
                subTaskExecutor = subTaskExecutor,
                notificationHandler = notificationHandler,
                contextCompactor = contextCompactor,
                smanCodeProperties = SmanCodeProperties(),
                dynamicPromptInjector = dynamicPromptInjector
            )

            // 项目分析已由 ProjectAnalysisScheduler 后台自动执行
            logger.info("Sman 服务初始化完成（项目分析由后台调度器处理）")

            logger.info("Sman 服务初始化完成")
        } catch (e: Exception) {
            logger.error("初始化 Sman 服务失败", e)
            initializationError = formatInitializationError(e)
            throw e
        }
    }

    /**
     * 初始化项目分析服务
     *
     * 注意：项目分析已由 ProjectAnalysisScheduler 后台自动处理
     * 此函数已废弃，保留仅为兼容性
     */
    private fun initializeProjectAnalysisService() {
        // 项目分析已由 ProjectAnalysisScheduler 后台自动执行
        logger.info("项目分析由后台调度器处理，此初始化已废弃")
    }

    /**
     * 初始化 Skill 系统
     *
     * 扫描多个目录加载 Skill，并注册 SkillTool
     */
    private fun initializeSkillSystem() {
        try {
            val projectPath = project.basePath
                ?: throw IllegalStateException("项目路径为空")

            // 初始化 SkillRegistry
            skillRegistry = SkillRegistry()
            skillRegistry.initialize(projectPath)

            // 注册 SkillTool
            val skillTool = SkillTool(skillRegistry)
            toolRegistry.registerTool(skillTool)

            logger.info("Skill 系统初始化完成，共加载 {} 个 Skill", skillRegistry.size())
        } catch (e: Exception) {
            logger.error("Skill 系统初始化失败: {}", e.message)
            // 不抛出异常，允许其他功能正常工作
        }
    }

    /**
     * 格式化初始化错误信息
     */
    private fun formatInitializationError(e: Exception): String {
        return InitializationErrorFormatter.format(e)
    }

    /**
     * 创建占位符 SmanLoop（当 LLM 未配置时）
     * 这个循环会在用户尝试发送消息时提示配置 API Key
     */
    private fun createPlaceholderLoop(): SmanLoop {
        // 创建一个最小配置的 LlmPoolConfig
        val placeholderConfig = LlmPoolConfig()

        // 创建一个占位符的 LlmService
        val placeholderLlmService = com.smancode.sman.smancode.llm.LlmService(placeholderConfig)

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

        return SmanLoop(
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
                role = com.smancode.sman.model.message.Role.ASSISTANT
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
                projectKey = this@SmanService.projectKey
                projectPath = project.basePath
                rules = storageService.rules  // 从 StorageService 读取用户配置的 RULES
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
     * 创建架构师专用 Session
     *
     * 与普通 Session 的区别：
     * 1. 独立的 sessionId 前缀（architect-{type}-{uuid}）
     * 2. 注入架构师专用的上下文
     * 3. 不使用用户规则
     *
     * @param analysisType 分析类型
     * @return 架构师 Session
     */
    fun createArchitectSession(analysisType: com.smancode.sman.analysis.model.AnalysisType): Session {
        val sessionId = "architect-${analysisType.key}-${java.util.UUID.randomUUID()}"

        val projectInfo = ProjectInfo().apply {
            projectKey = this@SmanService.projectKey
            projectPath = project.basePath
            rules = ""  // 架构师不使用用户规则
        }

        return Session(sessionId, projectInfo).apply {
            status = SessionStatus.IDLE
            // 注入项目上下文
            metadata["projectContext"] = getProjectContext()
            // 标记为架构师 Session
            metadata["isArchitectSession"] = true
            metadata["analysisType"] = analysisType.key
        }.also { session ->
            // 注册到 SessionManager
            sessionManager.registerSession(session)
            logger.info("创建架构师 Session: sessionId={}, analysisType={}", sessionId, analysisType.key)
        }
    }

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
    fun notifyInsertCodeReference(codeReference: com.smancode.sman.ide.components.CodeReference) {
        onCodeReferenceCallback?.invoke(codeReference)
    }

    // ========== Disposable 实现 ==========

    override fun dispose() {
        logger.info("释放 Sman 服务资源")
        sessionCache.clear()
    }

    companion object {
        private val instances = ConcurrentHashMap<Project, SmanService>()

        /**
         * 获取项目的 SmanService 实例
         */
        fun getInstance(project: Project): SmanService {
            return instances.computeIfAbsent(project) { SmanService(it) }
        }

        /**
         * 释放项目的 SmanService 实例
         */
        fun disposeInstance(project: Project) {
            instances.remove(project)?.dispose()
        }
    }
}
