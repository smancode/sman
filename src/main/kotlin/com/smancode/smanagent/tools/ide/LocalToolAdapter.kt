package com.smancode.smanagent.tools.ide

import com.intellij.openapi.project.Project
import com.smancode.smanagent.ide.service.LocalToolExecutor
import com.smancode.smanagent.tools.AbstractTool
import com.smancode.smanagent.tools.ParameterDef
import com.smancode.smanagent.tools.Tool
import com.smancode.smanagent.tools.ToolResult

/**
 * 本地工具适配器
 */
class LocalToolAdapter(
    private val project: Project,
    private val toolName: String,
    private val toolDescription: String,
    private val parameterDefs: Map<String, ParameterDef>
) : AbstractTool(), Tool {

    private val localToolExecutor = LocalToolExecutor(project)

    override fun getName() = toolName

    override fun getDescription() = toolDescription

    override fun getParameters() = parameterDefs

    override fun execute(projectKey: String, params: Map<String, Any>): ToolResult {
        val startTime = System.currentTimeMillis()
        val result = localToolExecutor.execute(toolName, params, project.basePath)
        val duration = System.currentTimeMillis() - startTime

        return if (result.success) {
            val resultText = result.result.toString()
            ToolResult.success(resultText, toolName, resultText).also {
                it.executionTimeMs = duration
                it.relatedFilePaths = result.relatedFilePaths
                it.relativePath = result.relativePath
                it.metadata = result.metadata
            }
        } else {
            ToolResult.failure(result.result.toString()).also {
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
        readFileTool(project),
        grepFileTool(project),
        findFileTool(project),
        callChainTool(project),
        extractXmlTool(project),
        applyChangeTool(project)
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
}
