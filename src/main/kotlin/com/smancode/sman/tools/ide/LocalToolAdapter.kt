package com.smancode.sman.tools.ide

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.intellij.openapi.project.Project
import com.smancode.sman.ide.service.LocalToolExecutor
import com.smancode.sman.model.part.Part
import com.smancode.sman.tools.AbstractTool
import com.smancode.sman.tools.ParameterDef
import com.smancode.sman.tools.Tool
import com.smancode.sman.tools.ToolResult
import org.slf4j.LoggerFactory
import java.util.function.Consumer

/**
 * 本地工具适配器
 */
class LocalToolAdapter(
    private val project: Project,
    private val toolName: String,
    private val toolDescription: String,
    private val parameterDefs: Map<String, ParameterDef>,
    private val supportsStreaming: Boolean = false  // 是否支持流式输出
) : AbstractTool(), Tool {

    private val localToolExecutor = LocalToolExecutor(project)

    override fun getName() = toolName

    override fun getDescription() = toolDescription

    override fun getParameters() = parameterDefs

    override fun supportsStreaming(): Boolean = supportsStreaming

    override fun execute(projectKey: String, params: Map<String, Any>): ToolResult {
        val startTime = System.currentTimeMillis()
        val result = localToolExecutor.execute(toolName, params, project.basePath)
        return buildResult(result, startTime)
    }

    override fun executeStreaming(
        projectKey: String,
        params: Map<String, Any>,
        partPusher: Consumer<Part>
    ): ToolResult {
        val startTime = System.currentTimeMillis()
        val result = localToolExecutor.executeStreaming(toolName, params, project.basePath, partPusher)
        return buildResult(result, startTime)
    }

    /**
     * 构建工具执行结果（公共逻辑）
     */
    private fun buildResult(result: LocalToolExecutor.ToolResult, startTime: Long): ToolResult {
        val duration = System.currentTimeMillis() - startTime
        val resultText = result.result.toString()

        return if (result.success) {
            ToolResult.success(resultText, toolName, resultText).also {
                it.executionTimeMs = duration
                it.relatedFilePaths = result.relatedFilePaths
                it.relativePath = result.relativePath
                it.metadata = result.metadata
                // batch 工具的子结果
                if (toolName == "batch" && result.batchSubResults != null) {
                    it.metadata = (it.metadata ?: mutableMapOf()).also { meta ->
                        @Suppress("UNCHECKED_CAST")
                        (meta as MutableMap<String, Any>)["batchSubResults"] = result.batchSubResults
                    }
                }
            }
        } else {
            ToolResult.failure(resultText).also {
                it.executionTimeMs = duration
            }
        }
    }
}

/**
 * 专家咨询工具（本地版本）
 *
 * 直接在插件进程中调用，不再依赖外部验证服务
 * 复用 ReAct Loop 中的服务：LlmService、BgeM3Client、RerankerClient、TieredVectorStore
 */
class ExpertConsultTool(
    private val project: Project
) : AbstractTool(), Tool {

    private val logger = LoggerFactory.getLogger(ExpertConsultTool::class.java)

    // 懒加载本地服务
    private val consultService by lazy {
        try {
            LocalExpertConsultService(project)
        } catch (e: Exception) {
            logger.warn("本地专家服务初始化失败: ${e.message}")
            null
        }
    }

    override fun getName() = "expert_consult"

    override fun getDescription() = "Expert consultation tool with bidirectional Business ↔ Code understanding. Use for business/rule/code analysis."

    override fun getParameters() = mapOf(
        "query" to ParameterDef("query", String::class.java, true, "Query/question to ask"),
        "projectKey" to ParameterDef("projectKey", String::class.java, false, "Project key (auto-detected from current project if empty)"),
        "topK" to ParameterDef("topK", Int::class.java, false, "Number of results to retrieve", 10),
        "enableRerank" to ParameterDef("enableRerank", Boolean::class.java, false, "Enable reranking", true),
        "rerankTopN" to ParameterDef("rerankTopN", Int::class.java, false, "Number of results after reranking", 5)
    )

    override fun execute(projectKey: String, params: Map<String, Any>): ToolResult {
        val startTime = System.currentTimeMillis()

        // 白名单参数校验
        val query = params["query"]?.toString()
            ?: return ToolResult.failure("缺少 query 参数")

        // 自动检测 projectKey（如果未提供，使用当前项目名）
        val actualProjectKey = params["projectKey"]?.toString() ?: project.name

        val topK = (params["topK"] as? Number)?.toInt() ?: 10
        val enableRerank = params["enableRerank"] as? Boolean ?: true
        val rerankTopN = (params["rerankTopN"] as? Number)?.toInt() ?: 5

        // 参数校验
        if (query.isBlank()) {
            return ToolResult.failure("query 不能为空")
        }
        if (topK <= 0) {
            return ToolResult.failure("topK 必须大于 0")
        }

        // 检查服务是否可用
        val service = consultService
        if (service == null) {
            val duration = System.currentTimeMillis() - startTime
            return ToolResult.failure(buildServiceUnavailableMessage()).also {
                it.executionTimeMs = duration
            }
        }

        return try {
            // 调用本地服务
            val request = LocalExpertConsultService.ConsultRequest(
                question = query,
                projectKey = actualProjectKey,
                topK = topK,
                enableRerank = enableRerank,
                rerankTopN = rerankTopN
            )

            val response = service.consult(request)

            // 构建结果文本
            val resultText = buildResultText(response, query)

            logger.info("本地专家咨询完成: sources={}, confidence={}, time={}ms",
                response.sources.size, response.confidence,
                System.currentTimeMillis() - startTime)

            ToolResult.success(resultText, "expert_consult", resultText).also {
                it.executionTimeMs = System.currentTimeMillis() - startTime
                it.metadata = mapOf(
                    "confidence" to response.confidence,
                    "processingTimeMs" to response.processingTimeMs,
                    "sourcesCount" to response.sources.size
                )
            }

        } catch (e: IllegalStateException) {
            val duration = System.currentTimeMillis() - startTime
            logger.error("专家咨询执行失败: {}", e.message)
            ToolResult.failure("专家咨询服务未就绪: ${e.message}\n\n${buildServiceUnavailableMessage()}").also {
                it.executionTimeMs = duration
            }
        } catch (e: Exception) {
            val duration = System.currentTimeMillis() - startTime
            logger.error("专家咨询执行失败", e)
            ToolResult.failure("专家咨询执行失败: ${e.message}").also {
                it.executionTimeMs = duration
            }
        }
    }

    /**
     * 构建服务不可用的错误消息
     */
    private fun buildServiceUnavailableMessage(): String =
        """**专家咨询服务不可用**
          |
          |本地专家服务需要以下配置：
          |
          |### 必需配置
          |- **BGE-M3 端点**: 在设置中配置 bge.endpoint
          |
          |### 使用说明
          |1. 确保项目已完成向量化分析
          |2. 检查 BGE 端点配置是否正确
          |3. 如需更高精度，可启用 Reranker（可选）
          |
          |配置方式：Settings → Sman Configuration → BGE Configuration
        """.trimMargin()

    /**
     * 构建结果文本
     */
    private fun buildResultText(response: LocalExpertConsultService.ConsultResponse, query: String): String =
        buildString {
            appendLine("## 专家咨询结果")
            appendLine()
            appendLine("**查询**: $query")
            appendLine("**置信度**: ${(response.confidence * 100).toInt()}%")
            appendLine("**处理时间**: ${response.processingTimeMs}ms")
            appendLine()
            appendLine("### 答案")
            appendLine(response.answer)
            appendLine()

            // 添加来源信息
            if (response.sources.isNotEmpty()) {
                appendLine("### 来源代码")
                response.sources.take(10).forEach { source ->
                    if (source.filePath.isNotEmpty()) {
                        appendLine("- `${source.filePath}` (相似度: ${"%.2f".format(source.score)})")
                    }
                }
                if (response.sources.size > 10) {
                    appendLine("... 还有 ${response.sources.size - 10} 条结果未显示")
                }
            }
        }
}

/**
 * 本地工具工厂
 */
object LocalToolFactory {

    fun createTools(project: Project): List<Tool> = listOf(
        ExpertConsultTool(project),  // 核心工具：专家咨询
        readFileTool(project),
        grepFileTool(project),
        findFileTool(project),
        listDirectoryTool(project),  // 列出目录工具（ls/list_directory）
        callChainTool(project),
        extractXmlTool(project),
        applyChangeTool(project),
        runShellCommandTool(project),  // Shell 命令执行工具
        batchTool(project),  // 批量工具执行
        WebSearchTool()  // Web 搜索工具
    )

    private fun readFileTool(project: Project) = LocalToolAdapter(
        project,
        "read_file",
        "Read file content. Default: lines 1-300. Set endLine=999999 for full file.",
        mapOf(
            "simpleName" to ParameterDef("simpleName", String::class.java, false, "Class name (auto-find file)"),
            "relativePath" to ParameterDef("relativePath", String::class.java, false, "File relative path"),
            "startLine" to ParameterDef("startLine", Int::class.java, false, "Start line (default: 1)", 1),
            "endLine" to ParameterDef("endLine", Int::class.java, false, "End line (default: 300)", 300)
        )
    )

    private fun grepFileTool(project: Project) = LocalToolAdapter(
        project,
        "grep_file",
        "Search for pattern in file contents.",
        mapOf(
            "pattern" to ParameterDef("pattern", String::class.java, true, "Regex pattern"),
            "relativePath" to ParameterDef("relativePath", String::class.java, false, "File path (default: all source files)"),
            "filePattern" to ParameterDef("filePattern", String::class.java, false, "File name pattern (regex)")
        )
    )

    private fun findFileTool(project: Project) = LocalToolAdapter(
        project,
        "find_file",
        "Find files by name pattern (regex).",
        mapOf(
            "pattern" to ParameterDef("pattern", String::class.java, false, "File name pattern (regex)"),
            "filePattern" to ParameterDef("filePattern", String::class.java, false, "Alias for 'pattern'"),
            "path" to ParameterDef("path", String::class.java, false, "Directory path to list (for ls/list_directory)")
        )
    )

    /**
     * 列出目录工具（ls/list_directory 的别名）
     * 实际执行会映射到 find_file，但参数使用 path 而不是 pattern
     */
    private fun listDirectoryTool(project: Project) = LocalToolAdapter(
        project,
        "list_directory",
        "List directory contents. Lists files and subdirectories in the specified path.",
        mapOf(
            "path" to ParameterDef("path", String::class.java, true, "Directory path to list")
        )
    )

    private fun callChainTool(project: Project) = LocalToolAdapter(
        project,
        "call_chain",
        "Analyze method call chains.",
        mapOf(
            "method" to ParameterDef("method", String::class.java, true, "Method signature: ClassName.methodName"),
            "direction" to ParameterDef("direction", String::class.java, false, "Direction: callers/callees/both (default: both)"),
            "depth" to ParameterDef("depth", Int::class.java, false, "Analysis depth", 1),
            "includeSource" to ParameterDef("includeSource", Boolean::class.java, false, "Include source code", false)
        )
    )

    private fun extractXmlTool(project: Project) = LocalToolAdapter(
        project,
        "extract_xml",
        "Extract XML tag content.",
        mapOf(
            "relativePath" to ParameterDef("relativePath", String::class.java, true, "XML file path"),
            "tagPattern" to ParameterDef("tagPattern", String::class.java, true, "Tag name/pattern"),
            "tagName" to ParameterDef("tagName", String::class.java, false, "Alias for 'tagPattern'")
        )
    )

    private fun applyChangeTool(project: Project) = LocalToolAdapter(
        project,
        "apply_change",
        "Apply code changes to files.",
        mapOf(
            "relativePath" to ParameterDef("relativePath", String::class.java, true, "File path"),
            "searchContent" to ParameterDef("searchContent", String::class.java, false, "Content to search (replace mode)"),
            "newContent" to ParameterDef("newContent", String::class.java, true, "New content"),
            "mode" to ParameterDef("mode", String::class.java, false, "Mode: replace/create"),
            "description" to ParameterDef("description", String::class.java, false, "Change description")
        )
    )

    /**
     * Shell 命令执行工具（支持流式输出）
     */
    private fun runShellCommandTool(project: Project) = LocalToolAdapter(
        project,
        "run_shell_command",
        "Execute shell command in project directory. Supports streaming output for long-running commands.",
        mapOf(
            "command" to ParameterDef("command", String::class.java, true, "Shell command to execute")
        ),
        supportsStreaming = true  // 启用流式输出
    )

    /**
     * 批量工具执行工具
     *
     * 用于批量执行多个工具调用，主要用于批量修改代码。
     *
     * 使用场景：
     * - 批量读取多个文件
     * - 批量修改同一文件的多个位置（推荐）
     * - 批量执行多个独立的工具调用
     *
     * 注意：
     * - 最多支持 10 个工具调用
     * - 顺序执行（用于确保 apply_change 按顺序执行）
     * - 不支持嵌套 batch
     */
    private fun batchTool(project: Project) = LocalToolAdapter(
        project,
        "batch",
        "Execute multiple tool calls in batch. Use for batch edits or multiple independent operations.",
        mapOf(
            "tool_calls" to ParameterDef(
                "tool_calls",
                List::class.java,
                true,
                "Array of tool calls to execute. Each item: {tool: string, parameters: object}"
            )
        )
    )
}
