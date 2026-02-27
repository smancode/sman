package com.smancode.sman.domain.puzzle

import org.slf4j.LoggerFactory

/**
 * 知识进化循环的响应解析器
 *
 * 职责：解析 LLM 返回的 JSON 响应，保持单一职责
 */
object EvolutionResponseParser {

    private val logger = LoggerFactory.getLogger(EvolutionResponseParser::class.java)

    /**
     * 解析 LLM 响应
     */
    fun parse(response: String): ParsedEvolution {
        // 尝试提取 JSON
        val jsonMatch = Regex("""\{[\s\S]*\}""").find(response)
        val jsonStr = jsonMatch?.value ?: "{}"

        return try {
            parseJsonResponse(jsonStr)
        } catch (e: Exception) {
            logger.warn("JSON 解析失败，使用降级处理: {}", e.message)
            parseJsonResponse("""{"hypothesis":"解析失败","tasks":[],"results":[],"evaluation":{"hypothesisConfirmed":false,"newKnowledgeGained":0,"conflictsFound":[],"qualityScore":0.0,"lessonsLearned":[]}}""")
        }
    }

    /**
     * 解析 JSON 响应
     */
    private fun parseJsonResponse(jsonStr: String): ParsedEvolution {
        // 简化解析（实际可以使用 JSON 库）
        val hypothesis = extractJsonValue(jsonStr, "hypothesis") ?: "未指定假设"

        // 从 evaluation 对象中提取 qualityScore（更精确）
        val evaluationMatch = Regex("""\"evaluation\"\s*:\s*\{([^}]+)\}""").find(jsonStr)
        val evalContent = evaluationMatch?.groupValues?.get(1) ?: ""
        val qualityScore = extractJsonValue(evalContent, "qualityScore")?.toDoubleOrNull()
            ?: extractJsonValue(jsonStr, "qualityScore")?.toDoubleOrNull()
            ?: 0.5
        val newKnowledgeGained = extractJsonValue(evalContent, "newKnowledgeGained")?.toIntOrNull()
            ?: extractJsonValue(jsonStr, "newKnowledgeGained")?.toIntOrNull()
            ?: 0

        // 提取结果（简化版）- 从 results 数组中提取
        val results = mutableListOf<ParsedResult>()
        val resultsMatch = Regex("""\"results\"\s*:\s*\[([^\]]*)\]""").find(jsonStr)
        if (resultsMatch != null) {
            val resultsContent = resultsMatch.groupValues.get(1)
            val resultObjects = Regex("""\{[^}]+\}""").findAll(resultsContent)

            resultObjects.forEach { resultObj ->
                val objStr = resultObj.value
                val title = extractJsonValue(objStr, "title")
                val content = extractJsonValue(objStr, "content")
                val confidence = extractJsonValue(objStr, "confidence")?.toDoubleOrNull() ?: qualityScore

                if (title != null && content != null) {
                    results.add(ParsedResult(
                        target = title,
                        title = title,
                        content = content.take(500),
                        tags = emptyList(),
                        confidence = confidence
                    ))
                }
            }
        }

        // 提取冲突信息
        val conflictsFound = extractJsonArray(evalContent, "conflictsFound")

        // 提取 lessonsLearned
        val lessonsLearned = extractJsonArray(evalContent, "lessonsLearned")

        return ParsedEvolution(
            hypothesis = hypothesis,
            results = results,
            evaluation = ParsedEvaluation(
                hypothesisConfirmed = qualityScore > 0.5,
                newKnowledgeGained = newKnowledgeGained,
                conflictsFound = conflictsFound,
                qualityScore = qualityScore,
                lessonsLearned = lessonsLearned
            )
        )
    }

    /**
     * 提取 JSON 数组
     */
    private fun extractJsonArray(jsonStr: String, key: String): List<String> {
        val result = mutableListOf<String>()
        try {
            val match = Regex("""\"$key\"\s*:\s*\[([^\]]*)\]""").find(jsonStr)
            match?.groupValues?.get(1)?.let { arrStr ->
                if (arrStr.isNotBlank()) {
                    val itemMatches = Regex("\"([^\"]+)\"").findAll(arrStr)
                    itemMatches.forEach { item ->
                        result.add(item.groupValues.get(1))
                    }
                }
            }
        } catch (e: Exception) {
            // 忽略解析错误，返回空列表
        }
        return result
    }

    /**
     * 提取 JSON 值
     */
    private fun extractJsonValue(json: String, key: String): String? {
        val pattern = Regex("""\"$key\"\s*:\s*(?:\"([^\"]+)\"|(\d+\.?\d*)|(true|false))""")
        val match = pattern.find(json)
        return match?.let {
            it.groupValues.getOrNull(1) ?: it.groupValues.getOrNull(2) ?: it.groupValues.getOrNull(3)
        }
    }
}
