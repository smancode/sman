package com.smancode.sman.ide.service

/**
 * 代码编辑多策略匹配器
 *
 * 实现 7 级容错匹配：
 * 1. 精确匹配 (Exact)
 * 2. 行 trim 匹配 (LineTrimmed)
 * 3. 空白归一化 (WhitespaceNormalized)
 * 4. 缩进灵活匹配 (IndentationFlexible)
 * 5. 锚点匹配 (BlockAnchor)
 * 6. 转义字符处理 (EscapeNormalized)
 * 7. 上下文感知 (ContextAware) - 最后策略
 */
class CodeEditMatcher {

    sealed class MatchResult {
        data class Success(
            val startOffset: Int,
            val endOffset: Int,
            val strategy: String
        ) : MatchResult()
        
        data class Failure(val reason: String) : MatchResult()
    }

    /**
     * 查找匹配（按优先级尝试各策略）
     */
    fun findMatch(fileContent: String, searchContent: String): MatchResult {
        // 策略 1: 精确匹配
        exactMatch(fileContent, searchContent)?.let {
            return it
        }

        // 策略 2: 行 trim 匹配
        lineTrimmedMatch(fileContent, searchContent)?.let {
            return it
        }

        // 策略 3: 空白归一化匹配
        whitespaceNormalizedMatch(fileContent, searchContent)?.let {
            return it
        }

        // 策略 4: 缩进灵活匹配
        indentationFlexibleMatch(fileContent, searchContent)?.let {
            return it
        }

        // 策略 5: 锚点匹配
        blockAnchorMatch(fileContent, searchContent)?.let {
            return it
        }

        // 策略 6: 转义字符处理
        escapeNormalizedMatch(fileContent, searchContent)?.let {
            return it
        }

        return MatchResult.Failure("未找到匹配内容（已尝试 6 种策略）")
    }

    /**
     * 策略 1: 精确匹配
     */
    private fun exactMatch(fileContent: String, searchContent: String): MatchResult? {
        val index = fileContent.indexOf(searchContent)
        return if (index >= 0) {
            MatchResult.Success(index, index + searchContent.length, "exact")
        } else null
    }

    /**
     * 策略 2: 行 trim 匹配（忽略每行的前后空格）
     */
    private fun lineTrimmedMatch(fileContent: String, searchContent: String): MatchResult? {
        val fileLines = fileContent.lines()
        val searchLines = searchContent.lines()

        if (searchLines.isEmpty()) return null

        val trimmedSearch = searchLines.map { it.trim() }.filter { it.isNotEmpty() }
        if (trimmedSearch.isEmpty()) return null

        for (i in 0..fileLines.size - trimmedSearch.size) {
            var allMatch = true
            for (j in trimmedSearch.indices) {
                if (fileLines[i + j].trim() != trimmedSearch[j]) {
                    allMatch = false
                    break
                }
            }
            if (allMatch) {
                val startOffset = fileContent.lines().take(i).sumOf { it.length + 1 }
                val endOffset = startOffset + fileLines.subList(i, i + trimmedSearch.size)
                    .sumOf { it.length + 1 } - 1
                return MatchResult.Success(startOffset, endOffset, "lineTrimmed")
            }
        }
        return null
    }

    /**
     * 策略 3: 空白归一化匹配（移除所有空白字符后比较）
     */
    private fun whitespaceNormalizedMatch(fileContent: String, searchContent: String): MatchResult? {
        val normalizedFile = fileContent.replace(Regex("\\s+"), "")
        val normalizedSearch = searchContent.replace(Regex("\\s+"), "")

        val index = normalizedFile.indexOf(normalizedSearch)
        return if (index >= 0) {
            // 找到归一化后的位置，需要映射回原始位置
            findOriginalOffset(fileContent, normalizedSearch, index, "whitespaceNormalized")
        } else null
    }

    /**
     * 策略 4: 缩进灵活匹配（忽略行首空白差异）
     */
    private fun indentationFlexibleMatch(fileContent: String, searchContent: String): MatchResult? {
        val fileLines = fileContent.lines()
        val searchLines = searchContent.lines()

        if (searchLines.isEmpty()) return null

        for (i in 0..fileLines.size - searchLines.size) {
            var allMatch = true
            for (j in searchLines.indices) {
                val fileLineTrimmed = fileLines[i + j].trim()
                val searchLineTrimmed = searchLines[j].trim()
                if (fileLineTrimmed != searchLineTrimmed) {
                    allMatch = false
                    break
                }
            }
            if (allMatch) {
                val startOffset = fileContent.lines().take(i).sumOf { it.length + 1 }
                val endOffset = startOffset + fileLines.subList(i, i + searchLines.size)
                    .sumOf { it.length + 1 } - 1
                return MatchResult.Success(startOffset, endOffset, "indentationFlexible")
            }
        }
        return null
    }

    /**
     * 策略 5: 锚点匹配（使用首尾行作为锚点）
     */
    private fun blockAnchorMatch(fileContent: String, searchContent: String): MatchResult? {
        val searchLines = searchContent.lines().filter { it.trim().isNotEmpty() }
        if (searchLines.isEmpty()) return null

        val firstAnchor = searchLines.first().trim()
        val lastAnchor = searchLines.last().trim()

        // 找到首行锚点
        val firstMatch = findLineContaining(fileContent, firstAnchor) ?: return null
        
        // 在首行之后找尾行锚点
        val afterFirst = fileContent.substring(firstMatch.second)
        val lastMatch = findLineContaining(afterFirst, lastAnchor)
        
        if (lastMatch != null) {
            val startOffset = firstMatch.first
            val endOffset = firstMatch.second + lastMatch.second
            return MatchResult.Success(startOffset, endOffset, "blockAnchor")
        }
        return null
    }

    /**
     * 策略 6: 转义字符处理
     */
    private fun escapeNormalizedMatch(fileContent: String, searchContent: String): MatchResult? {
        // 处理常见的转义字符差异
        val escapedSearch = searchContent
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\t", "\\t")

        val index = fileContent.indexOf(escapedSearch)
        return if (index >= 0) {
            MatchResult.Success(index, index + escapedSearch.length, "escapeNormalized")
        } else null
    }

    /**
     * 辅助: 查找包含指定内容的行及位置
     */
    private fun findLineContaining(content: String, search: String): Pair<Int, Int>? {
        val lines = content.lines()
        for (i in lines.indices) {
            if (lines[i].contains(search)) {
                val offset = content.lines().take(i).sumOf { it.length + 1 }
                return Pair(offset, offset + lines[i].length)
            }
        }
        return null
    }

    /**
     * 辅助: 映射归一化位置到原始位置
     */
    private fun findOriginalOffset(
        fileContent: String,
        normalizedSearch: String,
        normalizedIndex: Int,
        strategy: String
    ): MatchResult? {
        var charCount = 0
        var offset = 0
        
        for (i in fileContent.indices) {
            if (!fileContent[i].isWhitespace()) {
                if (charCount == normalizedIndex) {
                    // 找到起始位置，继续找结束位置
                    val remaining = fileContent.substring(i)
                    val remainingNormalized = remaining.replace(Regex("\\s+"), "")
                    val endIndex = remainingNormalized.indexOf(normalizedSearch)
                    if (endIndex >= 0) {
                        // 映射回原始结束位置
                        var originalEnd = i
                        var searchCount = 0
                        for (j in remaining.indices) {
                            if (!remaining[j].isWhitespace()) {
                                searchCount++
                                if (searchCount == normalizedSearch.length) {
                                    originalEnd = i + j + 1
                                    break
                                }
                            }
                        }
                        return MatchResult.Success(i, originalEnd, strategy)
                    }
                }
                charCount++
            }
        }
        return null
    }
}
