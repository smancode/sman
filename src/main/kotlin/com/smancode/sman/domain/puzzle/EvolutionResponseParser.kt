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

        // 从 evaluation 对象中提取（支持嵌套对象）
        val evaluationMatch = Regex("""\"evaluation\"\s*:\s*\{([^}]+)\}""").find(jsonStr)
        val evalContent = evaluationMatch?.groupValues?.get(1) ?: ""

        // 调试日志
        logger.debug("解析 JSON, evalContent: {}", evalContent.take(200))

        // 提取质量评分 - 多种方式尝试
        var qualityScore: Double? = null

        // 方式1: 从 evalContent 中提取
        qualityScore = extractJsonValue(evalContent, "qualityScore")?.toDoubleOrNull()

        // 方式2: 从整个 JSON 中直接搜索 qualityScore
        if (qualityScore == null) {
            val directMatch = Regex("""\"qualityScore\"\s*:\s*(\d+\.?\d*)""").find(jsonStr)
            qualityScore = directMatch?.groupValues?.get(1)?.toDoubleOrNull()
        }

        // 方式3: 计算基于 hypothesis 质量的评分
        if (qualityScore == null || qualityScore == 0.5) {
            qualityScore = calculateQualityScore(hypothesis, evalContent)
        }

        // 最终兜底
        val finalQualityScore = qualityScore ?: 0.5

        // 提取上下文利用度
        val contextUtilization = extractJsonValue(evalContent, "contextUtilization")?.toDoubleOrNull()
            ?: calculateContextUtilization(hypothesis)

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
                val confidence = extractJsonValue(objStr, "confidence")?.toDoubleOrNull() ?: finalQualityScore

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

        logger.info("解析结果: qualityScore={}, contextUtilization={}, results={}",
            finalQualityScore, contextUtilization, results.size)

        return ParsedEvolution(
            hypothesis = hypothesis,
            results = results,
            evaluation = ParsedEvaluation(
                hypothesisConfirmed = finalQualityScore > 0.5,
                newKnowledgeGained = newKnowledgeGained,
                conflictsFound = conflictsFound,
                qualityScore = finalQualityScore,
                contextUtilization = contextUtilization,
                lessonsLearned = lessonsLearned
            )
        )
    }

    /**
     * 基于 hypothesis 质量计算评分
     */
    private fun calculateQualityScore(hypothesis: String, evalContent: String): Double {
        var score = 0.5

        // 1. hypothesis 长度（>=200 字符 +0.1）
        if (hypothesis.length >= 200) score += 0.1

        // 2. 引用了现有知识（+0.1）
        if (hypothesis.contains("已分析") || hypothesis.contains("基于") ||
            hypothesis.contains("结合") || hypothesis.contains("根据")) {
            score += 0.1
        }

        // 3. 包含推理关键词（+0.1）
        if (hypothesis.contains("推断") || hypothesis.contains("推测") ||
            hypothesis.contains("假设") || hypothesis.contains("分析")) {
            score += 0.1
        }

        // 4. 包含具体业务概念（+0.1）
        if (hypothesis.contains("状态机") || hypothesis.contains("规则") ||
            hypothesis.contains("流程") || hypothesis.contains("实体")) {
            score += 0.1
        }

        // 5. hypothesisConfirmed 为 true（从 evalContent 检查）
        if (evalContent.contains("hypothesisConfirmed") && evalContent.contains("true")) {
            score += 0.1
        }

        return score.coerceIn(0.0, 1.0)
    }

    /**
     * 计算上下文利用度（基于 hypothesis 是否引用了关键知识）
     */
    private fun calculateContextUtilization(hypothesis: String): Double {
        val contextKeywords = listOf("已有", "知识", "API", "规则", "数据", "模型", "已分析", "基于", "结合", "根据")
        val matchCount = contextKeywords.count { keyword -> hypothesis.contains(keyword) }
        return (matchCount.toDouble() / contextKeywords.size).coerceIn(0.0, 1.0)
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
