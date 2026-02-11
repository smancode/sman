package com.smancode.sman.smancode.core

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.smancode.sman.model.session.Session
import com.smancode.sman.smancode.llm.LlmService
import com.smancode.sman.tools.ToolResult
import org.slf4j.LoggerFactory

/**
 * 结果摘要生成器
 *
 * 将工具执行结果压缩为简洁的摘要，防止 Token 爆炸
 */
class ResultSummarizer(
    private val llmService: LlmService
) {
    private val logger = LoggerFactory.getLogger(ResultSummarizer::class.java)
    private val objectMapper = ObjectMapper()

    companion object {
        private const val SMALL_DATA_THRESHOLD = 500
        private const val LARGE_DATA_THRESHOLD = 5000
        private const val COMPRESSION_THRESHOLD = 500
    }

    /**
     * 生成工具结果摘要
     *
     * @param toolName      工具名称
     * @param result        完整工具结果
     * @param parentSession 父会话（用于获取用户问题上下文）
     * @return 摘要文本
     */
    fun summarize(toolName: String, result: ToolResult, parentSession: Session): String {
        logger.info("【ResultSummarizer开始】toolName={}, success={}", toolName, result.isSuccess)

        if (!result.isSuccess) {
            val errorSummary = "执行失败: " + (result.error ?: "未知错误")
            logger.info("【ResultSummarizer失败】toolName={}, summary={}", toolName, errorSummary)
            return errorSummary
        }

        if (toolName == "expert_consult" && result.displayContent != null) {
            val displayContent = result.displayContent!!
            logger.info("【ResultSummarizer expert_consult】使用 displayContent, 长度={}", displayContent.length)
            return displayContent
        }

        val data = result.data
        if (data == null) {
            logger.warn("【ResultSummarizer空数据】toolName={}, data为null，返回默认消息", toolName)
            return "执行完成，无返回数据"
        }

        val dataStr = data.toString()
        logger.info("【ResultSummarizer数据字符串】toolName={}, data长度={}", toolName, dataStr.length)

        return compressBySize(toolName, dataStr, result, parentSession)
    }

    private fun compressBySize(toolName: String, dataStr: String, result: ToolResult, parentSession: Session): String {
        val dataSize = dataStr.length

        if (dataSize < SMALL_DATA_THRESHOLD) {
            val summary = enrichWithPath(result, dataStr)
            logger.info("【ResultSummarizer小数据】toolName={}, summary长度={}", toolName, summary.length)
            return summary
        }

        if (dataSize < LARGE_DATA_THRESHOLD) {
            val compressed = simpleCompress(toolName, dataStr, result)
            val summary = enrichWithPath(result, compressed)
            logger.info("【ResultSummarizer中等数据】toolName={}, 压缩后长度={}", toolName, summary.length)
            return summary
        }

        logger.info("【ResultSummarizer大数据】toolName={}, 使用LLM压缩", toolName)
        return llmCompress(toolName, dataStr, parentSession, result)
    }

    /**
     * 为摘要添加文件路径信息
     */
    private fun enrichWithPath(result: ToolResult, content: String): String {
        if (!result.relativePath.isNullOrEmpty()) {
            return "路径: ${result.relativePath}\n$content"
        }

        val relatedPaths = result.relatedFilePaths
        if (!relatedPaths.isNullOrEmpty()) {
            val sb = StringBuilder()
            sb.append("找到文件: ${relatedPaths.size} 个\n")
            relatedPaths.forEach { path ->
                sb.append("  - ").append(path).append("\n")
            }
            sb.append("\n").append(content)
            return sb.toString()
        }

        return content
    }

    /**
     * 简单压缩（针对中等数据）
     */
    private fun simpleCompress(toolName: String, data: String, result: ToolResult): String {
        return when (toolName) {
            "semantic_search" -> extractLinesContaining(data, 20, "filePath:", "score:")
            "grep_file" -> extractNonEmptyLines(data, 30)
            "read_file" -> prefixWithPath(result, data)
            "call_chain" -> extractCallChain(data)
            "apply_change" -> extractApplyChangeStatus(data)
            else -> truncateData(data, 1000)
        }
    }

    private fun extractLinesContaining(data: String, maxLines: Int, vararg markers: String): String {
        val sb = StringBuilder()
        val lines = data.split("\n")
        var count = 0

        for (line in lines) {
            if (containsAny(line, *markers)) {
                sb.append(line.trim()).append("\n")
                if (++count >= maxLines) break
            }
        }
        return sb.toString()
    }

    private fun containsAny(line: String, vararg markers: String): Boolean {
        for (marker in markers) {
            if (line.contains(marker)) {
                return true
            }
        }
        return false
    }

    private fun extractNonEmptyLines(data: String, maxLines: Int): String {
        val sb = StringBuilder()
        val lines = data.split("\n")
        var count = 0

        for (line in lines) {
            if (line.trim().isNotEmpty()) {
                sb.append(line.trim()).append("\n")
                if (++count >= maxLines) break
            }
        }
        return sb.toString()
    }

    private fun prefixWithPath(result: ToolResult, data: String): String {
        if (!result.relativePath.isNullOrEmpty()) {
            return "${result.relativePath}\n$data"
        }
        return data
    }

    private fun extractCallChain(data: String): String {
        val sb = StringBuilder()
        val lines = data.split("\n")
        var depth = 0
        val maxDepth = 10

        for (line in lines) {
            if (line.contains("->")) {
                sb.append(line.trim()).append("\n")
                if (++depth >= maxDepth) break
            }
        }
        return if (sb.isNotEmpty()) sb.toString() else data.take(500)
    }

    private fun extractApplyChangeStatus(data: String): String {
        return if (data.contains("✅") || data.contains("成功")) {
            "✅ 修改已应用"
        } else {
            "❌ 修改失败"
        }
    }

    private fun truncateData(data: String, maxLength: Int): String {
        return if (data.length > maxLength) {
            data.take(maxLength) + "\n... (已压缩)"
        } else {
            data
        }
    }

    /**
     * LLM 智能压缩（针对大数据）
     */
    private fun llmCompress(toolName: String, data: String, parentSession: Session, result: ToolResult): String {
        return try {
            // 构建压缩提示词
            val prompt = buildCompressionPrompt(toolName, data, parentSession)

            // 调用 LLM 生成摘要
            val response = llmService.simpleRequest(prompt)

            // 解析响应
            val json = objectMapper.readTree(response)
            val summary = json.path("summary").asText("")

            if (summary.isNotEmpty()) {
                return enrichWithPath(result, summary)
            }

            // 降级到简单压缩
            simpleCompress(toolName, data, result)

        } catch (e: Exception) {
            logger.warn("LLM 压缩失败，降级到简单压缩: toolName={}", toolName, e)
            simpleCompress(toolName, data, result)
        }
    }

    /**
     * 构建压缩提示词
     */
    private fun buildCompressionPrompt(toolName: String, data: String, parentSession: Session): String {
        val prompt = StringBuilder()

        prompt.append("你是代码分析助手。请将以下工具执行结果压缩为简洁的摘要。\n\n")

        prompt.append("## 工具类型\n")
        prompt.append(toolName).append("\n\n")

        prompt.append("## 用户原始问题\n")
        val latestUser = parentSession.latestUserMessage
        if (latestUser != null && latestUser.parts.isNotEmpty()) {
            val firstPart = latestUser.parts[0]
            if (firstPart is com.smancode.sman.model.part.TextPart) {
                prompt.append(firstPart.text).append("\n\n")
            }
        }

        prompt.append("## 工具输出\n")
        prompt.append(data).append("\n\n")

        prompt.append("## 要求\n")
        prompt.append("请生成一个简洁的摘要（1-5句话），保留关键信息，去除冗余内容。\n")
        prompt.append("重点关注：\n")
        prompt.append("- 核心发现\n")
        prompt.append("- 关键数据（文件路径、类名、方法名等）\n")
        prompt.append("- 与用户问题的相关性\n\n")

        prompt.append("请以 JSON 格式返回：\n")
        prompt.append("{\n")
        prompt.append("  \"summary\": \"你的摘要\"\n")
        prompt.append("}")

        return prompt.toString()
    }

    /**
     * 检查是否需要压缩
     */
    fun needsCompression(result: ToolResult): Boolean {
        if (result.data == null) {
            return false
        }

        val dataStr = result.data.toString()
        return dataStr.length > COMPRESSION_THRESHOLD
    }

    /**
     * 计算压缩率
     */
    fun getCompressionRatio(original: String?, compressed: String?): Double {
        if (original.isNullOrEmpty()) {
            return 0.0
        }
        return compressed?.length?.toDouble()?.div(original.length) ?: 0.0
    }
}
