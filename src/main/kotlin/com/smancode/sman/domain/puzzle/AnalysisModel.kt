package com.smancode.sman.domain.puzzle

import com.smancode.sman.shared.model.Puzzle

/**
 * 分析上下文
 *
 * 包含 LLM 分析所需的所有上下文信息
 *
 * @property relatedFiles 相关文件映射（文件路径 → 文件内容）
 * @property existingPuzzles 已有的相关知识拼图
 * @property userQuery 用户查询（可选，当分析由用户问题触发时）
 */
data class AnalysisContext(
    val relatedFiles: Map<String, String>,
    val existingPuzzles: List<Puzzle>,
    val userQuery: String? = null
) {
    companion object {
        /**
         * 创建空上下文
         */
        fun empty() = AnalysisContext(
            relatedFiles = emptyMap(),
            existingPuzzles = emptyList(),
            userQuery = null
        )
    }

    /**
     * 是否包含文件
     */
    fun hasFiles(): Boolean = relatedFiles.isNotEmpty()

    /**
     * 是否有用户查询
     */
    fun hasUserQuery(): Boolean = !userQuery.isNullOrBlank()

    /**
     * 获取文件总数
     */
    val fileCount: Int get() = relatedFiles.size

    /**
     * 估算上下文 Token 数（简单估算：字符数 * 0.4）
     */
    fun estimateTokens(): Int {
        val filesTokens = relatedFiles.values.sumOf { (it.length * 0.4).toInt() }
        val puzzlesTokens = existingPuzzles.sumOf { (it.content.length * 0.4).toInt() }
        val queryTokens = (userQuery?.length ?: 0) / 2
        return filesTokens + puzzlesTokens + queryTokens
    }
}

/**
 * 分析结果
 *
 * LLM 分析后的结构化输出
 *
 * @property title 分析结果标题
 * @property content Markdown 格式的分析内容
 * @property tags 提取的标签（用于分类和检索）
 * @property confidence 置信度（0.0-1.0）
 * @property sourceFiles 分析的源文件路径列表
 */
data class AnalysisResult(
    val title: String,
    val content: String,
    val tags: List<String>,
    val confidence: Double,
    val sourceFiles: List<String>
) {
    init {
        require(confidence in 0.0..1.0) { "置信度必须在 0.0-1.0 之间，当前值: $confidence" }
    }

    /**
     * 是否有内容
     */
    fun hasContent(): Boolean = content.isNotBlank()

    /**
     * 是否有标签
     */
    fun hasTags(): Boolean = tags.isNotEmpty()

    companion object {
        /**
         * 创建空结果（用于失败场景）
         */
        fun empty(sourceFiles: List<String> = emptyList()) = AnalysisResult(
            title = "分析失败",
            content = "",
            tags = emptyList(),
            confidence = 0.0,
            sourceFiles = sourceFiles
        )
    }
}
