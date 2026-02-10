package com.smancode.smanagent.tools.ide

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.intellij.openapi.project.Project
import com.smancode.smanagent.ide.service.LocalToolExecutor
import com.smancode.smanagent.model.part.Part
import com.smancode.smanagent.tools.AbstractTool
import com.smancode.smanagent.tools.ParameterDef
import com.smancode.smanagent.tools.Tool
import com.smancode.smanagent.tools.ToolResult
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.slf4j.LoggerFactory
import java.util.function.Consumer
import java.util.concurrent.TimeUnit

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
 * 专家咨询工具
 *
 * 通过 HTTP 调用验证服务的 expert_consult API
 * 提供业务↔代码双向查询能力
 */
class ExpertConsultTool(
    private val project: Project
) : AbstractTool(), Tool {

    private val logger = LoggerFactory.getLogger(ExpertConsultTool::class.java)
    private val objectMapper = ObjectMapper()

    // 从环境变量或使用默认端口
    private val verificationPort = System.getProperty("verification.port", "8080")
    private val baseUrl = "http://localhost:$verificationPort"
    private val apiUrl = "$baseUrl/api/verify/expert_consult"

    // OkHttp 客户端
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    override fun getName() = "expert_consult"

    override fun getDescription() = "Expert consultation tool with bidirectional Business ↔ Code understanding. Use for business/rule/code analysis."

    override fun getParameters() = mapOf(
        "query" to ParameterDef("query", String::class.java, true, "Query/question to ask"),
        "projectKey" to ParameterDef("projectKey", String::class.java, true, "Project key"),
        "topK" to ParameterDef("topK", Int::class.java, false, "Number of results to retrieve", 10),
        "enableRerank" to ParameterDef("enableRerank", Boolean::class.java, false, "Enable reranking", true),
        "rerankTopN" to ParameterDef("rerankTopN", Int::class.java, false, "Number of results after reranking", 5)
    )

    override fun execute(projectKey: String, params: Map<String, Any>): ToolResult {
        val startTime = System.currentTimeMillis()

        // 白名单参数校验
        val query = params["query"]?.toString()
            ?: return ToolResult.failure("缺少 query 参数")

        val actualProjectKey = params["projectKey"]?.toString() ?: projectKey
        if (actualProjectKey.isEmpty()) {
            return ToolResult.failure("缺少 projectKey 参数")
        }

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

        return try {
            // 构建请求体
            val requestBody = mapOf(
                "question" to query,
                "projectKey" to actualProjectKey,
                "topK" to topK,
                "enableRerank" to enableRerank,
                "rerankTopN" to rerankTopN
            )

            val jsonBody = objectMapper.writeValueAsString(requestBody)
            logger.info("调用专家咨询 API: url={}, request={}", apiUrl, jsonBody)

            val request = Request.Builder()
                .url(apiUrl)
                .post(jsonBody.toRequestBody(JSON_MEDIA_TYPE))
                .build()

            httpClient.newCall(request).execute().use { response ->
                val responseBody = response.body?.string()
                val duration = System.currentTimeMillis() - startTime

                if (!response.isSuccessful) {
                    logger.error("专家咨询 API 调用失败: code={}, body={}", response.code, responseBody)
                    return ToolResult.failure(
                        "专家咨询 API 调用失败: HTTP ${response.code}. " +
                        "请确保验证服务已启动（端口: $verificationPort）"
                    ).also { it.executionTimeMs = duration }
                }

                if (responseBody.isNullOrEmpty()) {
                    return ToolResult.failure("API 返回空响应").also {
                        it.executionTimeMs = duration
                    }
                }

                // 解析响应
                val jsonNode = objectMapper.readTree(responseBody)
                val answer = jsonNode.get("answer")?.asText() ?: "未获取到答案"
                val sources = jsonNode.get("sources")
                val confidence = jsonNode.get("confidence")?.asDouble() ?: 0.0
                val processingTime = jsonNode.get("processingTimeMs")?.asLong() ?: 0

                // 构建结果文本
                val resultText = buildString {
                    appendLine("## 专家咨询结果")
                    appendLine()
                    appendLine("**置信度**: ${(confidence * 100).toInt()}%")
                    appendLine("**处理时间**: ${processingTime}ms")
                    appendLine()
                    appendLine("### 答案")
                    appendLine(answer)
                    appendLine()

                    // 添加来源信息
                    if (sources != null && sources.isArray && sources.size() > 0) {
                        appendLine("### 来源代码")
                        sources.forEach { source ->
                            val filePath = source.get("filePath")?.asText() ?: ""
                            val score = source.get("score")?.asDouble() ?: 0.0
                            if (filePath.isNotEmpty()) {
                                appendLine("- `$filePath` (相似度: ${"%.2f".format(score)})")
                            }
                        }
                    }
                }

                logger.info("专家咨询完成: sources={}, confidence={}, time={}ms",
                    sources?.size() ?: 0, confidence, duration)

                ToolResult.success(resultText, "expert_consult", resultText).also {
                    it.executionTimeMs = duration
                    it.metadata = mapOf(
                        "confidence" to confidence,
                        "processingTimeMs" to processingTime,
                        "sourcesCount" to (sources?.size() ?: 0)
                    )
                }
            }

        } catch (e: java.net.ConnectException) {
            val duration = System.currentTimeMillis() - startTime
            logger.error("无法连接到验证服务: {}", e.message)
            ToolResult.failure(
                "无法连接到验证服务 (http://localhost:$verificationPort). " +
                "请确保验证服务已启动。\n" +
                "启动命令: ./scripts/verification-web.sh"
            ).also { it.executionTimeMs = duration }
        } catch (e: Exception) {
            val duration = System.currentTimeMillis() - startTime
            logger.error("专家咨询执行失败", e)
            ToolResult.failure("专家咨询执行失败: ${e.message}").also {
                it.executionTimeMs = duration
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
        callChainTool(project),
        extractXmlTool(project),
        applyChangeTool(project),
        runShellCommandTool(project),  // Shell 命令执行工具
        batchTool(project)  // 批量工具执行
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
            "pattern" to ParameterDef("pattern", String::class.java, true, "File name pattern"),
            "filePattern" to ParameterDef("filePattern", String::class.java, false, "Alias for 'pattern'")
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
