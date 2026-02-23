package com.smancode.sman.domain.puzzle

import com.smancode.sman.shared.model.Puzzle
import com.smancode.sman.shared.model.PuzzleType
import org.slf4j.LoggerFactory
import java.time.Instant

/**
 * 空白检测器
 *
 * 负责发现 Puzzle 知识库中的空白（需要补充的知识）
 *
 * 支持四种发现方式：
 * 1. 文件变更触发 - 基于文件修改检测
 * 2. 交叉验证发现 - 基于多源数据一致性检查
 * 3. 引用追踪发现 - 基于代码引用关系检测
 * 4. 用户查询触发 - 基于用户查询分析检测
 */
class GapDetector {

    private val logger = LoggerFactory.getLogger(GapDetector::class.java)

    companion object {
        /** 低完成度阈值 */
        private const val LOW_COMPLETENESS_THRESHOLD = 0.5

        /** 低置信度阈值 */
        private const val LOW_CONFIDENCE_THRESHOLD = 0.6

        /** 低完成度优先级权重 */
        private const val COMPLETENESS_WEIGHT = 0.6

        /** 低置信度优先级权重 */
        private const val CONFIDENCE_WEIGHT = 0.4
    }

    /**
     * 基于 Puzzle 列表检测空白
     *
     * @param puzzles 要分析的 Puzzle 列表
     * @return 发现的空白列表
     */
    fun detect(puzzles: List<Puzzle>): List<Gap> {
        if (puzzles.isEmpty()) {
            return emptyList()
        }

        logger.info("开始检测空白: puzzleCount={}", puzzles.size)

        val gaps = mutableListOf<Gap>()

        puzzles.forEach { puzzle ->
            // 检测低完成度空白
            if (puzzle.completeness < LOW_COMPLETENESS_THRESHOLD) {
                val gap = createGap(
                    type = GapType.LOW_COMPLETENESS,
                    puzzle = puzzle,
                    description = "Puzzle 完成度不足: ${puzzle.completeness}"
                )
                gaps.add(gap)
            }

            // 检测低置信度空白
            if (puzzle.confidence < LOW_CONFIDENCE_THRESHOLD) {
                val gap = createGap(
                    type = GapType.LOW_CONFIDENCE,
                    puzzle = puzzle,
                    description = "Puzzle 置信度不足: ${puzzle.confidence}"
                )
                gaps.add(gap)
            }
        }

        logger.info("空白检测完成: gapCount={}", gaps.size)
        return gaps.sortedByDescending { it.priority }
    }

    /**
     * 方式一：文件变更触发
     *
     * 当文件发生变更时，检测相关的知识空白
     *
     * @param puzzles Puzzle 列表
     * @param changedFiles 变更的文件路径列表
     * @return 发现的空白列表
     */
    fun detectByFileChange(puzzles: List<Puzzle>, changedFiles: List<String>): List<Gap> {
        if (changedFiles.isEmpty() || puzzles.isEmpty()) {
            return emptyList()
        }

        logger.info("基于文件变更检测空白: fileCount={}, puzzleCount={}", changedFiles.size, puzzles.size)

        val gaps = mutableListOf<Gap>()

        // 查找与变更文件相关的 Puzzle
        puzzles.forEach { puzzle ->
            val relatedFiles = extractRelatedFiles(puzzle)
            val hasRelatedChange = changedFiles.any { changedFile ->
                relatedFiles.any { relatedFile ->
                    changedFile.contains(relatedFile, ignoreCase = true)
                }
            }

            if (hasRelatedChange) {
                val gap = createGap(
                    type = GapType.FILE_CHANGE_TRIGGERED,
                    puzzle = puzzle,
                    description = "相关文件变更，需要更新: ${puzzle.id}",
                    relatedFiles = changedFiles
                )
                gaps.add(gap)
            }
        }

        return gaps.sortedByDescending { it.priority }
    }

    /**
     * 方式二：用户查询触发
     *
     * 当用户查询无法得到满意答案时，记录为潜在空白
     *
     * @param puzzles Puzzle 列表
     * @param query 用户查询内容
     * @return 发现的空白列表
     */
    fun detectByUserQuery(puzzles: List<Puzzle>, query: String): List<Gap> {
        if (query.isBlank() || puzzles.isEmpty()) {
            return emptyList()
        }

        logger.info("基于用户查询检测空白: queryLength={}", query.length)

        val gaps = mutableListOf<Gap>()

        // 分析查询关键词与 Puzzle 的相关性
        val queryKeywords = extractKeywords(query)

        puzzles.forEach { puzzle ->
            val puzzleKeywords = extractKeywords(puzzle.content)
            val overlap = queryKeywords.intersect(puzzleKeywords)

            // 如果有部分匹配但完成度低，标记为空白
            if (overlap.isNotEmpty() && puzzle.completeness < LOW_COMPLETENESS_THRESHOLD) {
                val gap = createGap(
                    type = GapType.USER_QUERY_TRIGGERED,
                    puzzle = puzzle,
                    description = "用户查询相关但完成度不足: ${puzzle.id}, 查询: $query"
                )
                gaps.add(gap)
            }
        }

        return gaps.sortedByDescending { it.priority }
    }

    // 私有方法

    private fun createGap(
        type: GapType,
        puzzle: Puzzle,
        description: String,
        relatedFiles: List<String> = emptyList()
    ): Gap {
        val priority = calculatePriority(type, puzzle)

        return Gap(
            type = type,
            puzzleType = puzzle.type,
            description = description,
            priority = priority,
            relatedFiles = relatedFiles,
            detectedAt = Instant.now()
        )
    }

    private fun calculatePriority(type: GapType, puzzle: Puzzle): Double {
        // 基础优先级基于类型
        val basePriority = when (type) {
            GapType.LOW_COMPLETENESS -> (1.0 - puzzle.completeness) * COMPLETENESS_WEIGHT
            GapType.LOW_CONFIDENCE -> (1.0 - puzzle.confidence) * CONFIDENCE_WEIGHT
            GapType.FILE_CHANGE_TRIGGERED -> 0.8
            GapType.USER_QUERY_TRIGGERED -> 0.9
            GapType.CROSS_VALIDATION -> 0.6
            GapType.REFERENCE_TRACING -> 0.5
            // 默认优先级
            else -> 0.5
        }

        // 加权 Puzzle 类型的重要性
        val typeWeight = when (puzzle.type) {
            PuzzleType.API -> 1.2
            PuzzleType.FLOW -> 1.1
            PuzzleType.RULE -> 1.0
            PuzzleType.DATA -> 0.9
            PuzzleType.STRUCTURE -> 0.8
            PuzzleType.TECH_STACK -> 0.7
        }

        return (basePriority * typeWeight).coerceIn(0.0, 1.0)
    }

    private fun extractRelatedFiles(puzzle: Puzzle): List<String> {
        // 从 Puzzle 内容中提取可能的文件名
        val content = puzzle.content
        val filePatterns = listOf(".kt", ".java", ".xml", ".yml", ".yaml", ".properties")

        return filePatterns.flatMap { ext ->
            Regex("""[\w/]+${Regex.escape(ext)}""")
                .findAll(content)
                .map { it.value }
                .toList()
        }
    }

    private fun extractKeywords(text: String): Set<String> {
        // 简单的关键词提取：按空格和标点分割，过滤短词
        val delimiters = "[\\s,，。.!！?？:：\"\"'']+"
        return text.lowercase()
            .split(Regex(delimiters))
            .filter { it.length >= 2 }
            .toSet()
    }
}
