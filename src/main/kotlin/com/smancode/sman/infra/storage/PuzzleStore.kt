package com.smancode.sman.infra.storage

import com.smancode.sman.shared.model.Puzzle
import com.smancode.sman.shared.model.PuzzleStatus
import com.smancode.sman.shared.model.PuzzleType
import java.io.File
import java.time.Instant

/**
 * 拼图存储服务
 * 负责 Puzzle 的持久化存储和读取
 *
 * 存储格式：
 * - 每个 Puzzle 存储为独立的 .md 文件
 * - 元数据存储在 YAML frontmatter 中
 * - content 存储为 Markdown 正文
 *
 * 文件路径：{projectPath}/.sman/puzzles/{puzzleId}.md
 */
class PuzzleStore(private val projectPath: String) {

    companion object {
        private const val PUZZLES_DIR = ".sman/puzzles"
        private const val FRONTMATTER_SEPARATOR = "---"
        private const val FILE_EXTENSION = ".md"
    }

    /**
     * 保存 Puzzle 到文件系统
     *
     * @param puzzle 要保存的拼图
     * @return Result.success 如果保存成功，Result.failure 如果校验失败或写入失败
     * @throws IllegalArgumentException 如果 puzzle id 为空或 completeness/confidence 超出范围
     */
    fun save(puzzle: Puzzle): Result<Unit> = runCatching {
        validate(puzzle)
        val puzzleDir = getPuzzleDir()
        puzzleDir.mkdirs()

        val file = File(puzzleDir, "${puzzle.id}$FILE_EXTENSION")
        val content = buildMarkdownContent(puzzle)
        file.writeText(content)
    }

    /**
     * 从文件系统加载 Puzzle
     *
     * @param puzzleId 拼图 ID
     * @return Result.success(Puzzle?) 如果加载成功，Result.failure 如果读取失败
     */
    fun load(puzzleId: String): Result<Puzzle?> = runCatching {
        val file = File(getPuzzleDir(), "$puzzleId$FILE_EXTENSION")
        if (!file.exists()) {
            null
        } else {
            parsePuzzleFromFile(file)
        }
    }

    /**
     * 加载所有 Puzzles
     *
     * @return Result.success(List<Puzzle>) 如果加载成功
     */
    fun loadAll(): Result<List<Puzzle>> = runCatching {
        val puzzleDir = getPuzzleDir()
        if (!puzzleDir.exists()) {
            emptyList()
        } else {
            puzzleDir.listFiles()
                ?.filter { it.extension == "md" }
                ?.mapNotNull { file ->
                    runCatching { parsePuzzleFromFile(file) }.getOrNull()
                }
                ?: emptyList()
        }
    }

    /**
     * 删除 Puzzle
     *
     * @param puzzleId 拼图 ID
     * @return Result.success 如果删除成功或文件不存在
     */
    fun delete(puzzleId: String): Result<Unit> = runCatching {
        val file = File(getPuzzleDir(), "$puzzleId$FILE_EXTENSION")
        if (file.exists()) {
            file.delete()
        }
    }

    /**
     * 按类型查找 Puzzles
     *
     * @param type 拼图类型
     * @return Result.success(List<Puzzle>) 匹配的拼图列表
     */
    fun findByType(type: PuzzleType): Result<List<Puzzle>> = runCatching {
        loadAll().getOrThrow().filter { it.type == type }
    }

    /**
     * 按状态查找 Puzzles
     *
     * @param status 拼图状态
     * @return Result.success(List<Puzzle>) 匹配的拼图列表
     */
    fun findByStatus(status: PuzzleStatus): Result<List<Puzzle>> = runCatching {
        loadAll().getOrThrow().filter { it.status == status }
    }

    // ========== 私有方法 ==========

    /**
     * 白名单校验
     */
    private fun validate(puzzle: Puzzle) {
        require(puzzle.id.isNotBlank()) { "puzzle id 不能为空" }
        require(puzzle.completeness in 0.0..1.0) { "completeness 必须在 0-1 之间，当前值: ${puzzle.completeness}" }
        require(puzzle.confidence in 0.0..1.0) { "confidence 必须在 0-1 之间，当前值: ${puzzle.confidence}" }
    }

    /**
     * 获取拼图存储目录
     */
    private fun getPuzzleDir(): File {
        return File(projectPath, PUZZLES_DIR)
    }

    /**
     * 构建 Markdown 内容（包含 YAML frontmatter）
     */
    private fun buildMarkdownContent(puzzle: Puzzle): String {
        return buildString {
            appendLine(FRONTMATTER_SEPARATOR)
            appendLine("id: ${puzzle.id}")
            appendLine("type: ${puzzle.type.name}")
            appendLine("status: ${puzzle.status.name}")
            appendLine("completeness: ${puzzle.completeness}")
            appendLine("confidence: ${puzzle.confidence}")
            appendLine("lastUpdated: ${puzzle.lastUpdated}")
            appendLine(FRONTMATTER_SEPARATOR)
            appendLine()
            append(puzzle.content)
        }
    }

    /**
     * 从文件解析 Puzzle
     */
    private fun parsePuzzleFromFile(file: File): Puzzle {
        val content = file.readText()
        val parts = content.split(FRONTMATTER_SEPARATOR, limit = 3)

        require(parts.size >= 3) { "无效的 Markdown 格式：缺少 frontmatter" }

        val frontmatter = parts[1].trim()
        val body = parts[2].trim()

        val metadata = parseFrontmatter(frontmatter)

        return Puzzle(
            id = metadata["id"] ?: throw IllegalArgumentException("缺少 id 字段"),
            type = PuzzleType.valueOf(metadata["type"] ?: throw IllegalArgumentException("缺少 type 字段")),
            status = PuzzleStatus.valueOf(metadata["status"] ?: throw IllegalArgumentException("缺少 status 字段")),
            content = body,
            completeness = metadata["completeness"]?.toDoubleOrNull()
                ?: throw IllegalArgumentException("缺少 completeness 字段"),
            confidence = metadata["confidence"]?.toDoubleOrNull()
                ?: throw IllegalArgumentException("缺少 confidence 字段"),
            lastUpdated = metadata["lastUpdated"]?.let { Instant.parse(it) }
                ?: throw IllegalArgumentException("缺少 lastUpdated 字段"),
            filePath = file.relativeTo(File(projectPath)).path
        )
    }

    /**
     * 解析 YAML frontmatter
     */
    private fun parseFrontmatter(frontmatter: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        frontmatter.lines().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isNotEmpty()) {
                val colonIndex = trimmed.indexOf(':')
                if (colonIndex > 0) {
                    val key = trimmed.substring(0, colonIndex).trim()
                    val value = trimmed.substring(colonIndex + 1).trim()
                    result[key] = value
                }
            }
        }
        return result
    }
}
