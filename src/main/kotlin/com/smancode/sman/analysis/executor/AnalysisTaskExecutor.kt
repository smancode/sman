package com.smancode.sman.analysis.executor

import com.smancode.sman.analysis.model.AnalysisType
import com.smancode.sman.analysis.model.ProjectMapManager
import com.smancode.sman.analysis.model.StepState
import com.smancode.sman.analysis.service.ProjectContextInjector
import com.smancode.sman.analysis.sync.MdToH2SyncService
import com.smancode.sman.ide.service.SmanService
import com.smancode.sman.model.part.TextPart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.UUID
import java.util.function.Consumer

/**
 * 分析任务执行器
 *
 * 负责使用 SmanService 执行各类型的项目分析任务
 */
class AnalysisTaskExecutor(
    private val projectKey: String,
    private val projectBasePath: Path,
    private val smanService: SmanService,
    private val bgeEndpoint: String,
    private val contextInjector: ProjectContextInjector? = null
) {
    private val logger = LoggerFactory.getLogger(AnalysisTaskExecutor::class.java)

    // H2 同步服务
    private val syncService: MdToH2SyncService by lazy {
        MdToH2SyncService(projectKey, bgeEndpoint, projectBasePath)
    }

    /**
     * 获取当前分析任务的前置分析结果（用于渐进式理解）
     */
    private suspend fun getPriorAnalysisContext(type: AnalysisType): String {
        if (contextInjector == null) return ""

        return try {
            val sb = StringBuilder()

            // 项目结构分析：注入已有的基础信息（如 Gradle 模块名）
            if (type == AnalysisType.TECH_STACK || type == AnalysisType.API_ENTRIES ||
                type == AnalysisType.DB_ENTITIES || type == AnalysisType.ENUMS) {
                val structureContent = contextInjector.getProjectContextSummary(projectKey)
                if (structureContent.isNotEmpty()) {
                    sb.append("\n## 已有的项目分析结果\n\n")
                    sb.append(structureContent)
                    sb.append("\n")
                }
            }

            // 技术栈分析完成后，后续分析可以注入技术栈信息
            if (type == AnalysisType.API_ENTRIES || type == AnalysisType.DB_ENTITIES ||
                type == AnalysisType.ENUMS || type == AnalysisType.CONFIG_FILES) {
                val techStackContent = contextInjector.getTechStackSummary(projectKey)
                if (techStackContent.isNotEmpty()) {
                    sb.append("\n## 技术栈信息\n\n")
                    sb.append(techStackContent)
                    sb.append("\n")
                }
            }

            sb.toString()
        } catch (e: Exception) {
            logger.warn("获取前置分析上下文失败: type={}", type.key, e)
            ""
        }
    }

    /**
     * 执行分析任务
     *
     * @param type 分析类型
     * @return AnalysisResult
     */
    suspend fun execute(type: AnalysisType): AnalysisResult {
        return withContext(Dispatchers.IO) {
            logger.info("开始执行分析: type={}, projectKey={}", type.key, projectKey)

            // 更新状态为 RUNNING
            ProjectMapManager.updateAnalysisStepState(projectBasePath, projectKey, type, StepState.RUNNING)

            val result = try {
                // 1. 加载分析提示词
                val prompt = loadAnalysisPrompt(type)

                // 2. 获取前置分析上下文（渐进式理解）
                val priorContext = getPriorAnalysisContext(type)

                // 3. 创建临时会话
                val sessionId = "analysis-${type.key}-${UUID.randomUUID()}"
                smanService.getOrCreateSession(sessionId)

                // 4. 构建用户输入（包含提示词和前置上下文）
                val priorContextSection = if (priorContext.isNotEmpty()) {
                    "\n# 已有的分析结果\n\n$priorContext\n"
                } else {
                    ""
                }

                val userInput = """
                    # 任务目标

                    请执行 ${type.displayName} 分析。

                    $priorContextSection
                    # 提示词

                    $prompt

                    # 输出要求

                    请将分析结果以 Markdown 格式输出，包含完整的分析内容。
                """.trimIndent()

                // 4. 执行分析（Part 收集器暂时不使用，保留回调接口）
                val partPusher = Consumer<com.smancode.sman.model.part.Part> { /* 预留扩展 */ }
                val response = smanService.processMessage(sessionId, userInput, partPusher)

                // 6. 提取 Markdown 内容
                val mdContent = extractMarkdownFromResponse(response)

                // 7. 保存 MD 文件
                val mdFile = saveMdFile(type, mdContent)

                // 8. 同步到 H2
                val fragmentsCount = syncService.syncAnalysisResult(type)

                // 9. 更新状态为 COMPLETED
                ProjectMapManager.updateAnalysisStepState(projectBasePath, projectKey, type, StepState.COMPLETED)

                AnalysisResult.Success(
                    type = type,
                    mdFile = mdFile,
                    fragmentsCount = fragmentsCount
                )

            } catch (e: Exception) {
                logger.error("分析执行失败: type={}", type.key, e)
                ProjectMapManager.updateAnalysisStepState(projectBasePath, projectKey, type, StepState.FAILED)

                AnalysisResult.Failure(
                    type = type,
                    error = e.message ?: "执行失败"
                )
            }

            result
        }
    }

    /**
     * 带重试机制的分析任务执行
     *
     * 核心方法：支持指数退避重试，确保分析任务健壮性
     *
     * @param type 分析类型
     * @param maxRetries 最大重试次数（默认 3 次）
     * @param baseDelayMs 基础延迟毫秒（默认 2000ms，指数退避）
     * @return AnalysisResult
     */
    suspend fun executeWithRetry(
        type: AnalysisType,
        maxRetries: Int = 3,
        baseDelayMs: Long = 2000
    ): AnalysisResult {
        var lastError: String? = null

        repeat(maxRetries + 1) { attempt ->
            val result = execute(type)

            when (result) {
                is AnalysisResult.Success -> {
                    if (attempt > 0) {
                        logger.info("分析任务重试成功: type={}, attempt={}/{}", type.key, attempt + 1, maxRetries + 1)
                    }
                    return result
                }
                is AnalysisResult.Failure -> {
                    lastError = result.error
                    if (attempt < maxRetries) {
                        // 指数退避：2s, 4s, 8s
                        val delayMs = baseDelayMs * (1L shl attempt)
                        logger.warn("分析任务失败，准备重试: type={}, attempt={}/{}, delay={}ms, error={}",
                            type.key, attempt + 1, maxRetries + 1, delayMs, lastError)
                        ProjectMapManager.updateAnalysisStepState(projectBasePath, projectKey, type, StepState.PENDING)
                        delay(delayMs)
                    } else {
                        logger.error("分析任务最终失败: type={}, attempts={}, error={}", type.key, maxRetries + 1, lastError)
                    }
                }
                is AnalysisResult.Skipped -> return result
            }
        }

        return AnalysisResult.Failure(type, "重试 $maxRetries 次后仍失败: $lastError")
    }

    /**
     * 加载分析提示词
     *
     * @param type 分析类型
     * @return 提示词内容
     */
    private fun loadAnalysisPrompt(type: AnalysisType): String {
        return try {
            val promptPath = "prompts/${type.getPromptPath()}"
            val promptResource = javaClass.classLoader.getResourceAsStream(promptPath)
                ?: throw IllegalStateException("提示词文件不存在: $promptPath")

            promptResource.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            logger.error("加载分析提示词失败: type={}", type.key, e)
            throw IllegalStateException("无法加载分析提示词: ${type.key}", e)
        }
    }

    /**
     * 从响应消息中提取 Markdown 内容
     *
     * @param message 响应消息
     * @return 提取的 Markdown 内容
     */
    private fun extractMarkdownFromResponse(message: com.smancode.sman.model.message.Message): String {
        val mdContent = StringBuilder()

        for (part in message.parts) {
            when (part) {
                is TextPart -> {
                    mdContent.append(part.text).append("\n\n")
                }
                is com.smancode.sman.model.part.ToolPart -> {
                    // 工具调用结果可能包含有用信息
                    mdContent.append("<!-- Tool: ${part.toolName} -->\n")
                }
                is com.smancode.sman.model.part.ReasoningPart -> {
                    // 推理过程通常不包含在最终输出中
                }
                is com.smancode.sman.model.part.TodoPart -> {
                    // Todo 列表可以转换为 Markdown
                    mdContent.append("## 任务列表\n")
                    part.items.forEach { item ->
                        val status = when (item.status) {
                            com.smancode.sman.model.part.TodoPart.TodoStatus.COMPLETED -> "✓"
                            else -> "○"
                        }
                        mdContent.append("- [$status] ${item.content}\n")
                    }
                    mdContent.append("\n")
                }
                else -> {
                    // 其他类型忽略
                }
            }
        }

        // 【关键】清理 Markdown 内容（过滤 thinking 标签、工具调用格式等）
        return cleanMarkdownContent(mdContent.toString().trim())
    }

    /**
     * 清理 Markdown 内容
     *
     * 过滤以下内容：
     * 1. thinking 标签：`<thinking>...</thinking>`、`<thinkData>...</thinkData>`、`THINKING...THINKING_END`
     * 2. 工具调用格式：`[TOOL_CALL]...[/TOOL_CALL]`、`<minimax:tool_call>...</minimax:tool_call>`
     * 3. JSON 格式的工具调用：`{"tool": "...", "parameters": {...}}`
     * 4. LLM 的思考过程：`## 第一步`、`## Step`、`我将...` 等元语言
     * 5. 多余的空行
     *
     * @param content 原始 Markdown 内容
     * @return 清理后的 Markdown 内容
     */
    private fun cleanMarkdownContent(content: String): String {
        var cleaned = content

        // 1. 过滤 thinking 标签（标准格式）
        // 匹配 <thinking>...</thinking>
        cleaned = cleaned.replace(Regex("""<thinking>[\s\S]*?</thinking>""", RegexOption.IGNORE_CASE), "")
        // 匹配 <thinkData>...</thinkData>
        cleaned = cleaned.replace(Regex("""<thinkData>[\s\S]*?</thinkData>""", RegexOption.IGNORE_CASE), "")
        // 匹配 <think...</think（通用格式）
        cleaned = cleaned.replace(Regex("""<think(?:ing|Data)?>[\s\S]*?</think(?:ing|Data)?>""", RegexOption.IGNORE_CASE), "")

        // 2. 过滤备用 thinking 格式
        cleaned = cleaned.replace(Regex("""THINKING[\s\S]*?THINKING_END""", RegexOption.IGNORE_CASE), "")
        cleaned = cleaned.replace(Regex("""<reasoning>[\s\S]*?</reasoning>""", RegexOption.IGNORE_CASE), "")

        // 3. 过滤工具调用格式
        // 匹配 [TOOL_CALL]...[/TOOL_CALL]
        cleaned = cleaned.replace(Regex("""\[TOOL_CALL\][\s\S]*?\[/TOOL_CALL\]""", RegexOption.IGNORE_CASE), "")
        // 匹配 <minimax:tool_call>...</minimax:tool_call>
        cleaned = cleaned.replace(Regex("""<minimax:tool_call>[\s\S]*?</minimax:tool_call>""", RegexOption.IGNORE_CASE), "")
        // 匹配 {tool => "..."} 格式
        cleaned = cleaned.replace(Regex("""\{tool\s*=>[^}]+\}""", RegexOption.IGNORE_CASE), "")
        // 匹配 {parameter => "..."} 格式
        cleaned = cleaned.replace(Regex("""\{parameter[^}]+\}""", RegexOption.IGNORE_CASE), "")
        // 匹配 --filePattern "xxx" 格式
        cleaned = cleaned.replace(Regex("""--\w+\s+["'][^"']+["']""", RegexOption.IGNORE_CASE), "")
        // 匹配 <invoke name="..."> 格式
        cleaned = cleaned.replace(Regex("""<invoke[\s\S]*?</invoke>""", RegexOption.IGNORE_CASE), "")
        // 匹配 <parameter name="..."> 格式
        cleaned = cleaned.replace(Regex("""<parameter[\s\S]*?</parameter>""", RegexOption.IGNORE_CASE), "")

        // 4. 过滤 LLM 的思考过程标记（元语言）
        // 移除包含"第X步"或"Step X"的标题行（X可以是数字或中文数字）
        cleaned = cleaned.replace(Regex("""##\s*第[一二三四五六七八九十\d]+\s*步[^\n]*\n?"""), "")
        cleaned = cleaned.replace(Regex("""##\s*Step\s*\d+[^\n]*\n?""", RegexOption.IGNORE_CASE), "")
        // 移除思考语句
        cleaned = cleaned.replace(Regex("""我将按照[^。\n]*[。\n]?"""), "")
        cleaned = cleaned.replace(Regex("""让我[^。\n]*[。\n]?"""), "")
        cleaned = cleaned.replace(Regex("""我来[^。\n]*[。\n]?"""), "")
        cleaned = cleaned.replace(Regex("""首先[^。\n]*[。\n]?"""), "")

        // 5. 过滤 JSON 格式的工具调用（单独一行）
        cleaned = cleaned.lines().filter { line ->
            val trimmedLine = line.trim()
            // 过滤单独一行的 JSON 对象（可能是工具调用）
            !(trimmedLine.startsWith("{") && trimmedLine.endsWith("}") &&
              (trimmedLine.contains("\"tool\"") || trimmedLine.contains("\"parameters\"") ||
               trimmedLine.contains("\"invoke\"") || trimmedLine.contains("\"name\"")))
        }.joinToString("\n")

        // 6. 清理多余的空行（3个以上连续空行 -> 2个）
        cleaned = cleaned.replace(Regex("""\n{3,}"""), "\n\n")

        // 7. 清理行首行尾空白
        cleaned = cleaned.trim()

        return cleaned
    }

    /**
     * 保存 MD 文件
     *
     * @param type 分析类型
     * @param content Markdown 内容
     * @return 保存的文件路径
     */
    private fun saveMdFile(type: AnalysisType, content: String): Path {
        val baseDir = projectBasePath.resolve(".sman").resolve("base")

        // 确保目录存在
        if (!Files.exists(baseDir)) {
            Files.createDirectories(baseDir)
        }

        val mdFile = baseDir.resolve(type.mdFileName)

        // 写入文件
        Files.writeString(
            mdFile,
            content,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE
        )

        logger.info("MD 文件已保存: {}", mdFile)
        return mdFile
    }

    /**
     * 批量执行多个分析任务
     *
     * @param types 分析类型列表
     * @return AnalysisResult 列表
     */
    suspend fun executeBatch(types: List<AnalysisType>): List<AnalysisResult> {
        val results = mutableListOf<AnalysisResult>()

        for (type in types) {
            val result = execute(type)
            results.add(result)

            // 如果失败，可以选择继续或中止
            if (result is AnalysisResult.Failure) {
                logger.warn("分析失败，继续执行其他任务: type={}", type.key)
            }
        }

        return results
    }

    /**
     * 执行核心分析（项目结构 + 技术栈）
     *
     * @return AnalysisResult 列表
     */
    suspend fun executeCoreAnalysis(): List<AnalysisResult> {
        logger.info("开始执行核心分析: projectKey={}", projectKey)
        return executeBatch(AnalysisType.coreTypes())
    }

    /**
     * 执行标准分析（API 入口、DB 实体、枚举、配置文件）
     *
     * @return AnalysisResult 列表
     */
    suspend fun executeStandardAnalysis(): List<AnalysisResult> {
        logger.info("开始执行标准分析: projectKey={}", projectKey)
        return executeBatch(AnalysisType.standardTypes())
    }

    /**
     * 检查是否需要执行分析
     *
     * @param type 分析类型
     * @return true 如果需要执行
     */
    fun needsExecution(type: AnalysisType): Boolean {
        val entry = ProjectMapManager.getProjectEntry(projectBasePath, projectKey)

        if (entry == null) {
            logger.debug("项目未注册，需要执行所有分析")
            return true
        }

        // 检查该类型是否已完成
        return !entry.isAnalysisComplete(type)
    }
}

/**
 * 分析任务结果
 */
sealed class AnalysisResult {
    abstract val type: AnalysisType

    /**
     * 成功结果
     */
    data class Success(
        override val type: AnalysisType,
        val mdFile: Path,
        val fragmentsCount: Int
    ) : AnalysisResult()

    /**
     * 失败结果
     */
    data class Failure(
        override val type: AnalysisType,
        val error: String
    ) : AnalysisResult()

    /**
     * 跳过结果
     */
    data class Skipped(
        override val type: AnalysisType,
        val reason: String
    ) : AnalysisResult()
}
