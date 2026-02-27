package com.smancode.sman.domain.puzzle

import com.smancode.sman.smancode.llm.LlmService
import org.slf4j.LoggerFactory

/**
 * 默认的 LLM 分析器实现
 *
 * 使用 LLM 分析代码，生成结构化的知识内容。
 *
 * 流程：
 * 1. 使用 AnalysisPromptTemplate 构建 Prompt
 * 2. 调用 LlmService 获取响应
 * 3. 解析响应为 AnalysisResult
 */
class DefaultLlmAnalyzer(
    private val llmService: LlmService
) : LlmAnalyzer {

    private val logger = LoggerFactory.getLogger(DefaultLlmAnalyzer::class.java)

    private val promptTemplate = AnalysisPromptTemplate()

    override suspend fun analyze(target: String, context: AnalysisContext): AnalysisResult {
        logger.info("开始分析: target={}", target)

        try {
            // 1. 构建 Prompt
            val prompt = promptTemplate.build(target, context)

            // 2. 调用 LLM
            val response = llmService.simpleRequest(prompt)

            // 3. 解析响应
            val result = parseResponse(response, target)

            logger.info("分析完成: target={}, title={}, tags={}", target, result.title, result.tags)
            return result

        } catch (e: Exception) {
            logger.error("分析失败: target={}, error={}", target, e.message)
            throw AnalysisException("分析失败: ${e.message}", e)
        }
    }

    /**
     * 解析 LLM 响应为 AnalysisResult
     */
    private fun parseResponse(response: String, target: String): AnalysisResult {
        val title = extractSection(response, "TITLE")
            .ifEmpty { "项目分析: $target" }

        val tags = extractSection(response, "TAGS")
            .split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        val confidence = extractSection(response, "CONFIDENCE")
            .trim()
            .toDoubleOrNull()
            ?: 0.5

        val content = extractSection(response, "CONTENT")

        return AnalysisResult(
            title = title,
            content = content,
            tags = tags,
            confidence = confidence.coerceIn(0.0, 1.0),
            sourceFiles = listOf(target)
        )
    }

    /**
     * 提取指定 section 的内容
     *
     * 格式：### SECTION_NAME\n内容
     */
    private fun extractSection(response: String, sectionName: String): String {
        val pattern = Regex(
            """###\s*$sectionName\s*\n([\s\S]*?)(?=###\s+\w+|$)""",
            RegexOption.IGNORE_CASE
        )

        val match = pattern.find(response)
        return match?.groupValues?.getOrNull(1)?.trim() ?: ""
    }
}
