package com.smancode.sman.analysis.executor

import com.smancode.sman.analysis.model.AnalysisTodo
import com.smancode.sman.analysis.model.AnalysisType
import com.smancode.sman.analysis.model.TodoStatus
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * 分析输出验证器
 *
 * 负责验证分析输出的完整性、计算完整度、检测缺失章节和生成 TODO
 */
class AnalysisOutputValidator {

    private val logger = LoggerFactory.getLogger(AnalysisOutputValidator::class.java)

    /**
     * 验证结果
     */
    data class ValidationResult(
        val isValid: Boolean,
        val completeness: Double,
        val missingSections: List<String>
    )

    /**
     * 各分析类型的必填章节定义
     *
     * Pair 结构：章节关键词（用于检测） -> 章节名称（用于报告）
     */
    private val requiredSections: Map<AnalysisType, List<Pair<String, String>>> = mapOf(
        AnalysisType.PROJECT_STRUCTURE to listOf(
            "概述" to "项目概述",
            "目录结构" to "目录结构",
            "模块划分" to "模块划分",
            "依赖" to "依赖管理"
        ),
        AnalysisType.TECH_STACK to listOf(
            "编程语言" to "编程语言",
            "构建" to "构建工具",
            "框架" to "框架",
            "数据" to "数据存储"
        ),
        AnalysisType.API_ENTRIES to listOf(
            "入口" to "入口列表",
            "认证" to "认证方式",
            "请求" to "请求格式",
            "响应" to "响应格式"
        ),
        AnalysisType.DB_ENTITIES to listOf(
            "实体" to "实体列表",
            "关系" to "表关系",
            "字段" to "字段详情"
        ),
        AnalysisType.ENUMS to listOf(
            "枚举" to "枚举列表",
            "用途" to "枚举用途"
        ),
        AnalysisType.CONFIG_FILES to listOf(
            "配置" to "配置文件列表",
            "环境" to "环境配置"
        )
    )

    /**
     * 对话式内容特征（用于识别需要清理的内容）
     */
    private val conversationalPatterns = listOf(
        "请问", "我可以帮您", "需要我", "您想要", "有什么可以",
        "您需要我做什么", "我可以为您", "请告诉我"
    )

    /**
     * 验证分析报告
     *
     * @param content 报告内容
     * @param type 分析类型
     * @return 验证结果
     */
    fun validate(content: String, type: AnalysisType): ValidationResult {
        val cleanedContent = cleanMarkdownContent(content)
        val completeness = calculateCompleteness(cleanedContent, type)
        val missingSections = extractMissingSections(cleanedContent, type)
        val isValid = completeness >= COMPLETENESS_THRESHOLD

        logger.debug(
            "验证结果: type={}, completeness={}, isValid={}, missing={}",
            type, completeness, isValid, missingSections
        )

        return ValidationResult(
            isValid = isValid,
            completeness = completeness,
            missingSections = missingSections
        )
    }

    /**
     * 计算完整度
     *
     * @param content 报告内容
     * @param type 分析类型
     * @return 完整度（0.0 - 1.0）
     */
    fun calculateCompleteness(content: String, type: AnalysisType): Double {
        if (content.isBlank()) return 0.0

        val required = requiredSections[type] ?: return 0.0
        if (required.isEmpty()) return 1.0

        val foundCount = required.count { (keyword, _) ->
            content.contains(keyword, ignoreCase = true)
        }

        return foundCount.toDouble() / required.size
    }

    /**
     * 提取缺失章节
     *
     * @param content 报告内容
     * @param type 分析类型
     * @return 缺失的章节名称列表
     */
    fun extractMissingSections(content: String, type: AnalysisType): List<String> {
        if (content.isBlank()) {
            return requiredSections[type]?.map { it.second } ?: emptyList()
        }

        val required = requiredSections[type] ?: return emptyList()

        return required
            .filter { (keyword, _) -> !content.contains(keyword, ignoreCase = true) }
            .map { it.second }
    }

    /**
     * 生成 TODO
     *
     * @param missingSections 缺失的章节列表
     * @param type 分析类型
     * @return TODO 列表
     */
    fun generateTodos(missingSections: List<String>, type: AnalysisType): List<AnalysisTodo> {
        return missingSections.mapIndexed { index, section ->
            AnalysisTodo(
                id = UUID.randomUUID().toString(),
                content = "补充分析：$section",
                status = TodoStatus.PENDING,
                priority = index + 1
            )
        }
    }

    /**
     * 清理 Markdown 内容
     *
     * 移除 thinking/thinkable 标签、对话式内容等
     *
     * @param content 原始内容
     * @return 清理后的内容
     */
    fun cleanMarkdownContent(content: String): String {
        var cleaned = content
            .replace(THINKING_TAG_REGEX, "")
            .replace(THINKABLE_TAG_REGEX, "")
            .replace(THINKING_CODE_BLOCK_REGEX, "")

        // 移除对话式内容（整行）
        val filteredLines = cleaned.lines().filter { line ->
            !conversationalPatterns.any { pattern -> line.contains(pattern, ignoreCase = true) }
        }

        cleaned = filteredLines.joinToString("\n")
            .replace(MULTIPLE_BLANK_LINES_REGEX, "\n\n")
            .trim()

        return cleaned
    }

    companion object {
        private const val COMPLETENESS_THRESHOLD = 0.8

        // 匹配 <thinking>...</thinking> 标签及其内容（支持多行）
        private val THINKING_TAG_REGEX = Regex("""<thinking>[\s\S]*?</thinking>""", RegexOption.MULTILINE)

        // 匹配 <thinkable>...</thinkable> 标签及其内容（支持多行）
        private val THINKABLE_TAG_REGEX = Regex("""<thinkable>[\s\S]*?</thinkable>""", RegexOption.MULTILINE)

        // 匹配 ```<think>...</think>...</think> 代码块及其内容（支持多行）
        private val THINKING_CODE_BLOCK_REGEX = Regex("""```[\s\S]*?</think>[\s\S]*?```""", RegexOption.MULTILINE)

        // 匹配多个连续空行
        private val MULTIPLE_BLANK_LINES_REGEX = Regex("""\n{3,}""")
    }
}
