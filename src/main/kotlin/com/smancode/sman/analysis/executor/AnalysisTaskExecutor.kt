package com.smancode.sman.analysis.executor

import com.smancode.sman.analysis.model.AnalysisStatus
import com.smancode.sman.analysis.model.AnalysisType
import com.smancode.sman.analysis.model.ProjectMapManager
import com.smancode.sman.analysis.model.StepState
import com.smancode.sman.analysis.sync.MdToH2SyncService
import com.smancode.sman.ide.service.SmanService
import com.smancode.sman.model.message.Role
import com.smancode.sman.model.part.TextPart
import kotlinx.coroutines.Dispatchers
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
    private val bgeEndpoint: String
) {
    private val logger = LoggerFactory.getLogger(AnalysisTaskExecutor::class.java)

    // H2 同步服务
    private val syncService: MdToH2SyncService by lazy {
        MdToH2SyncService(projectKey, bgeEndpoint, projectBasePath)
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
            ProjectMapManager.updateAnalysisStepState(projectKey, type, StepState.RUNNING)

            val result = try {
                // 1. 加载分析提示词
                val prompt = loadAnalysisPrompt(type)

                // 2. 创建临时会话
                val sessionId = "analysis-${type.key}-${UUID.randomUUID()}"
                val session = smanService.getOrCreateSession(sessionId)

                // 3. 构建用户输入（包含提示词）
                val userInput = """
                    # 任务目标

                    请执行 ${type.displayName} 分析。

                    # 提示词

                    $prompt

                    # 输出要求

                    请将分析结果以 Markdown 格式输出，包含完整的分析内容。
                """.trimIndent()

                // 4. 收集响应 Part
                val parts = mutableListOf<com.smancode.sman.model.part.Part>()
                val partPusher = Consumer<com.smancode.sman.model.part.Part> { part ->
                    parts.add(part)
                }

                // 5. 执行分析
                val response = smanService.processMessage(sessionId, userInput, partPusher)

                // 6. 提取 Markdown 内容
                val mdContent = extractMarkdownFromResponse(response)

                // 7. 保存 MD 文件
                val mdFile = saveMdFile(type, mdContent)

                // 8. 同步到 H2
                val fragmentsCount = syncService.syncAnalysisResult(type)

                // 9. 更新状态为 COMPLETED
                ProjectMapManager.updateAnalysisStepState(projectKey, type, StepState.COMPLETED)

                AnalysisResult.Success(
                    type = type,
                    mdFile = mdFile,
                    fragmentsCount = fragmentsCount
                )

            } catch (e: Exception) {
                logger.error("分析执行失败: type={}", type.key, e)
                ProjectMapManager.updateAnalysisStepState(projectKey, type, StepState.FAILED)

                AnalysisResult.Failure(
                    type = type,
                    error = e.message ?: "执行失败"
                )
            }

            result
        }
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

        return mdContent.toString().trim()
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
        val entry = ProjectMapManager.getProjectEntry(projectKey)

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
