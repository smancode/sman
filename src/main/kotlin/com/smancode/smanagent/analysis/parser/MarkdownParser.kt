package com.smancode.smanagent.analysis.parser

import com.smancode.smanagent.analysis.model.VectorFragment
import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.text.Regex

/**
 * Markdown 解析器
 *
 * 职责：
 * - 解析 .md 文件内容为向量片段
 * - 从 MD 标题或文件名提取类名
 * - 解析 class 向量和 method 向量
 *
 * 不涉及：向量化、存储、LLM 调用
 */
class MarkdownParser {

    private val logger = LoggerFactory.getLogger(MarkdownParser::class.java)

    companion object {
        // 正则表达式模式（提取为常量避免重复创建）
        private val TITLE_PATTERN = Regex("""^#\s+(\S+)""", RegexOption.MULTILINE)
        private val BUSINESS_DESC_PATTERN = Regex("""## 业务描述\s*\n\s*(.+?)(?=\n\s*##|\n\s*---|\Z)""", RegexOption.DOT_MATCHES_ALL)
        private val METHOD_BLOCK_PATTERN = Regex("""## 方法：(\S+)\s*\n(.*?)(?=##\s*(?:方法：|类信息)|---|\Z)""", RegexOption.DOT_MATCHES_ALL)
        private val METHOD_DESC_PATTERN = Regex("""### 业务描述\s*\n\s*(.+?)(?=\n\s*###|\n\s*##|\Z)""", RegexOption.DOT_MATCHES_ALL)
        private val SIGNATURE_PATTERN = Regex("""- \*\*完整签名\*\*:\s*`([^`]+)`""")
        private val METHOD_SIGNATURE_PATTERN = Regex("""### 签名\s*\n\s*`([^`]+)`""")
        private val SOURCE_CODE_PATTERN = Regex("""### 源码\s*\n```java\s*(.+?)\n```""", RegexOption.DOT_MATCHES_ALL)
        private val PACKAGE_NAME_PATTERN = Regex("""- \*\*包名\*\*:\s*`([^`]+)`""")

        // 文件扩展名
        private val FILE_EXTENSIONS = listOf(".md", ".java")
    }

    /**
     * 从文件路径或 MD 内容中提取类名
     *
     * 优先级：
     * 1. 从 MD 内容的 `# ClassName` 标题提取
     * 2. 从文件名提取（去除 .md 后缀）
     */
    fun extractClassName(sourceFile: Path, mdContent: String): String {
        // 优先从 MD 内容的标题中提取
        TITLE_PATTERN.find(mdContent)?.let { match ->
            val className = match.groupValues[1]
            logger.debug("从 MD 标题提取类名: className={}, sourceFile={}", className, sourceFile.fileName)
            return className
        }

        // 从文件名提取
        val className = extractClassNameFromFileName(sourceFile.fileName.toString())
        logger.debug("从文件名提取类名: className={}, sourceFile={}", className, sourceFile.fileName)
        return className
    }

    /**
     * 从文件名提取类名
     */
    private fun extractClassNameFromFileName(fileName: String): String {
        for (ext in FILE_EXTENSIONS) {
            if (fileName.endsWith(ext)) {
                return fileName.removeSuffix(ext)
            }
        }
        return fileName
    }

    /**
     * 解析 class 向量
     *
     * 从 MD 内容中提取类级别的描述信息
     */
    fun parseClassVector(sourceFile: Path, mdContent: String, className: String): VectorFragment {
        val businessDesc = extractFirstLine(BUSINESS_DESC_PATTERN.find(mdContent))
        val signature = extractGroupValue(SIGNATURE_PATTERN.find(mdContent), 1) ?: ""
        val packageName = extractGroupValue(PACKAGE_NAME_PATTERN.find(mdContent), 1) ?: ""

        val content = buildVectorContent(businessDesc, listOfNotNull(
            "签名".takeIf { signature.isNotEmpty() }?.let { it to signature },
            "包名".takeIf { packageName.isNotEmpty() }?.let { it to packageName }
        ))

        return VectorFragment(
            id = "class:$className",
            title = className,
            content = content.ifEmpty { "类" },
            fullContent = mdContent,
            tags = listOf("class", "java"),
            metadata = buildMetadata("class", className, methodName = null, packageName, sourceFile),
            vector = floatArrayOf()
        )
    }

    /**
     * 解析 method 向量列表
     *
     * 从 MD 内容中提取所有方法块
     */
    fun parseMethodVectors(sourceFile: Path, mdContent: String, className: String): List<VectorFragment> {
        return METHOD_BLOCK_PATTERN.findAll(mdContent).map { match ->
            val methodName = match.groupValues[1]
            val methodBlock = match.groupValues[2]

            val businessDesc = extractFirstLine(METHOD_DESC_PATTERN.find(methodBlock))
            val signature = extractGroupValue(METHOD_SIGNATURE_PATTERN.find(methodBlock), 1) ?: ""
            val sourceCode = extractGroupValue(SOURCE_CODE_PATTERN.find(methodBlock), 1)?.trim() ?: ""

            val content = buildVectorContent(businessDesc, listOfNotNull(
                "签名".takeIf { signature.isNotEmpty() }?.let { it to signature }
            ))

            VectorFragment(
                id = "method:$className.$methodName",
                title = methodName,
                content = content.ifEmpty { "方法" },
                fullContent = sourceCode,
                tags = listOf("method", "java"),
                metadata = buildMetadata("method", className, methodName, null, sourceFile),
                vector = floatArrayOf()
            )
        }.toList()
    }

    /**
     * 解析所有向量（class + methods）
     *
     * 这是最常用的入口方法
     */
    fun parseAll(sourceFile: Path, mdContent: String): List<VectorFragment> {
        require(mdContent.isNotBlank()) { "MD 内容不能为空" }

        val className = extractClassName(sourceFile, mdContent)
        val vectors = mutableListOf<VectorFragment>()

        // 解析 class 向量
        parseSafely("class 向量", sourceFile) {
            vectors.add(parseClassVector(sourceFile, mdContent, className))
        }

        // 解析 method 向量
        parseSafely("method 向量", sourceFile) {
            vectors.addAll(parseMethodVectors(sourceFile, mdContent, className))
        }

        logger.debug("解析 MD 完成: file={}, class={}, totalVectors={}", sourceFile.fileName, className, vectors.size)
        return vectors
    }

    // ========== 辅助方法 ==========

    /**
     * 提取正则匹配的第一行
     */
    private fun extractFirstLine(match: MatchResult?): String {
        return match?.groupValues?.get(1)?.trim()?.lines()?.firstOrNull() ?: ""
    }

    /**
     * 提取正则匹配的指定组
     */
    private fun extractGroupValue(match: MatchResult?, groupIndex: Int): String? {
        return match?.groupValues?.get(groupIndex)
    }

    /**
     * 构建向量内容
     */
    private fun buildVectorContent(businessDesc: String, pairs: List<Pair<String, String>>): String {
        return buildString {
            if (businessDesc.isNotEmpty()) append(businessDesc)

            pairs.forEach { (label, value) ->
                if (isNotEmpty()) append("\n")
                append("$label: $value")
            }
        }
    }

    /**
     * 构建元数据
     */
    private fun buildMetadata(
        type: String,
        className: String,
        methodName: String?,
        packageName: String?,
        sourceFile: Path
    ): Map<String, String> {
        return buildMap {
            put("type", type)
            put("className", className)
            methodName?.let { put("methodName", it) }
            packageName?.let { put("packageName", it) }
            put("sourceFile", sourceFile.toString())
        }
    }

    /**
     * 安全解析（捕获异常并记录日志）
     */
    private inline fun parseSafely(
        vectorType: String,
        sourceFile: Path,
        block: () -> Unit
    ) {
        try {
            block()
        } catch (e: Exception) {
            logger.warn("解析 {} 失败: file={}, error={}", vectorType, sourceFile.fileName, e.message)
        }
    }
}
