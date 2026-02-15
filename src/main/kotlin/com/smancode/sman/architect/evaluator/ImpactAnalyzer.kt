package com.smancode.sman.architect.evaluator

import com.fasterxml.jackson.databind.ObjectMapper
import com.smancode.sman.analysis.model.AnalysisType
import com.smancode.sman.architect.model.FileChangeImpact
import com.smancode.sman.config.SmanConfig
import com.smancode.sman.smancode.llm.LlmService
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.exists
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.isRegularFile

/**
 * 文件变更影响分析器
 *
 * 分析项目文件变更对 MD 分析结果的影响
 */
class ImpactAnalyzer(
    private val projectPath: Path,
    private val llmService: LlmService
) {
    private val logger = LoggerFactory.getLogger(ImpactAnalyzer::class.java)
    private val objectMapper = ObjectMapper()

    // 需要监控的文件扩展名
    private val monitoredExtensions = setOf(
        ".kt", ".java", ".scala", ".groovy",
        ".xml", ".properties", ".yaml", ".yml",
        ".gradle", ".gradle.kts", ".pom",
        ".json", ".md", ".sql"
    )

    // 需要忽略的目录
    private val ignoredDirectories = setOf(
        ".git", ".idea", ".sman", "build", "target", "out", "node_modules"
    )

    /**
     * 分析文件变更影响
     *
     * @param analysisType 分析类型
     * @param mdLastModified MD 文件的最后修改时间
     * @return 文件变更影响分析结果
     */
    fun analyze(
        analysisType: AnalysisType,
        mdLastModified: Instant
    ): FileChangeImpact {
        return try {
            // 1. 扫描变更的文件
            val changedFiles = scanChangedFiles(mdLastModified)

            if (changedFiles.isEmpty()) {
                logger.debug("没有发现变更的文件: analysisType={}", analysisType.key)
                return FileChangeImpact.noChange(analysisType)
            }

            logger.info("发现 {} 个变更的文件: analysisType={}", changedFiles.size, analysisType.key)

            // 2. 调用 LLM 分析影响
            analyzeImpactWithLlm(analysisType, changedFiles)

        } catch (e: Exception) {
            logger.error("分析文件变更影响失败", e)
            // 出错时保守处理，认为需要更新
            FileChangeImpact(
                analysisType = analysisType,
                changedFiles = emptyList(),
                needsUpdate = true,
                impactLevel = FileChangeImpact.ImpactLevel.MEDIUM,
                reason = "分析失败，保守触发更新: ${e.message}"
            )
        }
    }

    /**
     * 扫描比指定时间更新的文件
     */
    private fun scanChangedFiles(since: Instant): List<String> {
        val changedFiles = mutableListOf<String>()
        val sinceMillis = since.toEpochMilli()

        scanDirectory(projectPath, sinceMillis, changedFiles)

        return changedFiles
    }

    /**
     * 递归扫描目录
     */
    private fun scanDirectory(dir: Path, sinceMillis: Long, result: MutableList<String>) {
        if (!dir.exists()) return

        try {
            java.nio.file.Files.list(dir).use { stream ->
                stream.forEach { path ->
                    val fileName = path.fileName.toString()

                    if (java.nio.file.Files.isDirectory(path)) {
                        // 跳过忽略的目录
                        if (fileName !in ignoredDirectories && !fileName.startsWith(".")) {
                            scanDirectory(path, sinceMillis, result)
                        }
                    } else if (path.isRegularFile()) {
                        // 检查文件扩展名
                        val extension = fileName.substringAfterLast(".", "")
                        if (extension.isNotEmpty() && ".$extension" in monitoredExtensions) {
                            try {
                                val lastModified = path.getLastModifiedTime().toMillis()
                                if (lastModified > sinceMillis) {
                                    // 转换为相对路径
                                    val relativePath = projectPath.relativize(path).toString()
                                    result.add(relativePath)
                                }
                            } catch (e: Exception) {
                                logger.debug("无法获取文件修改时间: {}", path)
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            logger.warn("扫描目录失败: {}", dir, e)
        }
    }

    /**
     * 调用 LLM 分析文件变更影响
     */
    private fun analyzeImpactWithLlm(
        analysisType: AnalysisType,
        changedFiles: List<String>
    ): FileChangeImpact {
        return try {
            val systemPrompt = buildSystemPrompt()
            val userPrompt = buildUserPrompt(analysisType, changedFiles)

            val response = llmService.simpleRequest(systemPrompt, userPrompt)

            if (response.isNullOrBlank()) {
                logger.warn("LLM 影响分析响应为空")
                return FileChangeImpact.mediumImpact(
                    analysisType,
                    changedFiles,
                    reason = "LLM 响应为空，保守触发更新"
                )
            }

            parseImpactResult(analysisType, changedFiles, response)

        } catch (e: Exception) {
            logger.error("LLM 影响分析失败", e)
            FileChangeImpact.mediumImpact(
                analysisType,
                changedFiles,
                reason = "分析异常: ${e.message}"
            )
        }
    }

    /**
     * 构建系统提示词
     */
    private fun buildSystemPrompt(): String {
        return """
<system_config>
    <language_rule>
        <input_processing>English (For logic & reasoning)</input_processing>
        <final_output>Valid JSON only</final_output>
    </language_rule>
</system_config>

<context>
    <role>Architecture Impact Analyst</role>
    <task>Determine if file changes require re-analysis of project documentation</task>
</context>

<impact_levels>
- HIGH: Core files changed, major structural impact, MUST re-analyze
- MEDIUM: Some files changed, partial update needed, SHOULD re-analyze
- LOW: Minor changes, no impact on analysis, CAN skip
</impact_levels>

<output_format>
Return ONLY valid JSON:
```json
{
    "impactLevel": "HIGH",
    "needsUpdate": true,
    "affectedSections": ["section1", "section2"],
    "reason": "Brief explanation in English"
}
```
</output_format>
        """.trimIndent()
    }

    /**
     * 构建用户提示词
     */
    private fun buildUserPrompt(
        analysisType: AnalysisType,
        changedFiles: List<String>
    ): String {
        val filesList = changedFiles.take(50).joinToString("\n") { "- $it" }
        val moreCount = maxOf(0, changedFiles.size - 50)

        return """
<task>
Analyze if these file changes impact the "${analysisType.displayName}" documentation.
</task>

<analysis_type>
Key: ${analysisType.key}
Name: ${analysisType.displayName}
MD File: ${analysisType.mdFileName}
</analysis_type>

<changed_files count="${changedFiles.size}">
$filesList
${if (moreCount > 0) "... and $moreCount more files" else ""}
</changed_files>

<instructions>
1. Determine impact level based on which files changed
2. For PROJECT_STRUCTURE: changes to build files, main directories are HIGH
3. For TECH_STACK: changes to dependencies, config files are HIGH
4. For API_ENTRIES: changes to controller/handler files are HIGH
5. For DB_ENTITIES: changes to entity/model files are HIGH
6. Return ONLY the JSON result
</instructions>
        """.trimIndent()
    }

    /**
     * 解析影响分析结果
     */
    private fun parseImpactResult(
        analysisType: AnalysisType,
        changedFiles: List<String>,
        response: String
    ): FileChangeImpact {
        return try {
            val jsonString = extractJson(response)
            if (jsonString == null) {
                return FileChangeImpact.mediumImpact(
                    analysisType,
                    changedFiles,
                    reason = "无法解析 LLM 响应"
                )
            }

            val node = objectMapper.readTree(jsonString)

            val impactLevelStr = node.path("impactLevel").asText("LOW")
            val impactLevel = FileChangeImpact.ImpactLevel.fromString(impactLevelStr)
            val needsUpdate = node.path("needsUpdate").asBoolean(impactLevel != FileChangeImpact.ImpactLevel.LOW)
            val reason = node.path("reason").asText("LLM 分析结果")

            val affectedSections = mutableListOf<String>()
            val sectionsNode = node.path("affectedSections")
            if (sectionsNode.isArray) {
                for (sectionNode in sectionsNode) {
                    affectedSections.add(sectionNode.asText())
                }
            }

            logger.info("影响分析完成: impactLevel={}, needsUpdate={}, files={}",
                impactLevel, needsUpdate, changedFiles.size)

            FileChangeImpact(
                analysisType = analysisType,
                changedFiles = changedFiles,
                needsUpdate = needsUpdate,
                impactLevel = impactLevel,
                affectedSections = affectedSections,
                reason = reason
            )

        } catch (e: Exception) {
            logger.error("解析影响分析结果失败", e)
            FileChangeImpact.mediumImpact(
                analysisType,
                changedFiles,
                reason = "解析失败: ${e.message}"
            )
        }
    }

    /**
     * 从响应中提取 JSON
     */
    private fun extractJson(response: String): String? {
        val trimmed = response.trim()

        // 尝试直接解析
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            return trimmed
        }

        // 尝试从 markdown 代码块提取
        val jsonBlockPattern = Regex("""```(?:json)?\s*([\s\S]*?)```""")
        val match = jsonBlockPattern.find(trimmed)
        if (match != null) {
            return match.groupValues[1].trim()
        }

        // 尝试找第一个 { 和最后一个 }
        val firstBrace = trimmed.indexOf('{')
        val lastBrace = trimmed.lastIndexOf('}')
        if (firstBrace >= 0 && lastBrace > firstBrace) {
            return trimmed.substring(firstBrace, lastBrace + 1)
        }

        return null
    }
}
