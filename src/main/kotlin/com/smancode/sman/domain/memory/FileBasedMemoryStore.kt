package com.smancode.sman.domain.memory

import java.io.File
import java.time.Instant

/**
 * 基于文件系统的记忆存储实现
 *
 * 存储格式：
 * - 每个项目的记忆存储在 {storagePath}/.sman/memories/{projectId}/{key}.md
 * - 元数据存储在 YAML frontmatter 中
 * - value 存储为 Markdown 正文
 */
class FileBasedMemoryStore(
    private val storagePath: String
) : MemoryStore {

    companion object {
        private const val MEMORIES_DIR = ".sman/memories"
        private const val FRONTMATTER_SEPARATOR = "---"
        private const val FILE_EXTENSION = ".md"
    }

    override fun save(memory: ProjectMemory): Result<Unit> = runCatching {
        validate(memory)
        val projectDir = getProjectDir(memory.projectId)
        projectDir.mkdirs()

        val file = File(projectDir, "${memory.key}$FILE_EXTENSION")
        val content = buildMarkdownContent(memory)
        file.writeText(content)
    }

    override fun load(projectId: String, key: String): Result<ProjectMemory?> = runCatching {
        val file = File(getProjectDir(projectId), "$key$FILE_EXTENSION")
        if (!file.exists()) {
            null
        } else {
            parseMemoryFromFile(file, projectId)
        }
    }

    override fun loadAll(projectId: String): Result<List<ProjectMemory>> = runCatching {
        val projectDir = getProjectDir(projectId)
        if (!projectDir.exists()) {
            emptyList()
        } else {
            projectDir.listFiles()
                ?.filter { it.extension == "md" }
                ?.mapNotNull { file ->
                    runCatching { parseMemoryFromFile(file, projectId) }.getOrNull()
                }
                ?: emptyList()
        }
    }

    override fun findByType(projectId: String, memoryType: MemoryType): Result<List<ProjectMemory>> = runCatching {
        loadAll(projectId).getOrThrow().filter { it.memoryType == memoryType }
    }

    override fun delete(projectId: String, key: String): Result<Unit> = runCatching {
        val file = File(getProjectDir(projectId), "$key$FILE_EXTENSION")
        if (file.exists()) {
            file.delete()
        }
    }

    override fun touch(projectId: String, key: String): Result<Unit> = runCatching {
        val memory = load(projectId, key).getOrThrow()
            ?: throw IllegalArgumentException("记忆不存在: $key")

        val updated = memory.copy(
            lastAccessedAt = Instant.now(),
            accessCount = memory.accessCount + 1
        )
        save(updated).getOrThrow()
    }

    // 私有方法

    private fun validate(memory: ProjectMemory) {
        require(memory.projectId.isNotBlank()) { "projectId 不能为空" }
        require(memory.key.isNotBlank()) { "key 不能为空" }
        require(memory.confidence in 0.0..1.0) {
            "confidence 必须在 0-1 之间，当前值: ${memory.confidence}"
        }
    }

    private fun getProjectDir(projectId: String): File {
        return File(storagePath, "$MEMORIES_DIR/$projectId")
    }

    private fun buildMarkdownContent(memory: ProjectMemory): String {
        return buildString {
            appendLine(FRONTMATTER_SEPARATOR)
            appendLine("id: ${memory.id}")
            appendLine("projectId: ${memory.projectId}")
            appendLine("memoryType: ${memory.memoryType.name}")
            appendLine("key: ${memory.key}")
            appendLine("confidence: ${memory.confidence}")
            appendLine("source: ${memory.source.name}")
            appendLine("createdAt: ${memory.createdAt}")
            appendLine("lastAccessedAt: ${memory.lastAccessedAt}")
            appendLine("accessCount: ${memory.accessCount}")
            appendLine(FRONTMATTER_SEPARATOR)
            appendLine()
            append(memory.value)
        }
    }

    private fun parseMemoryFromFile(file: File, projectId: String): ProjectMemory {
        val content = file.readText()
        val parts = content.split(FRONTMATTER_SEPARATOR, limit = 3)

        require(parts.size >= 3) { "无效的 Markdown 格式：缺少 frontmatter" }

        val frontmatter = parts[1].trim()
        val body = parts[2].trim()

        val metadata = parseFrontmatter(frontmatter)

        return ProjectMemory(
            id = metadata["id"] ?: throw IllegalArgumentException("缺少 id 字段"),
            projectId = metadata["projectId"] ?: projectId,
            memoryType = MemoryType.valueOf(
                metadata["memoryType"] ?: throw IllegalArgumentException("缺少 memoryType 字段")
            ),
            key = metadata["key"] ?: throw IllegalArgumentException("缺少 key 字段"),
            value = body,
            confidence = metadata["confidence"]?.toDoubleOrNull()
                ?: throw IllegalArgumentException("缺少 confidence 字段"),
            source = MemorySource.valueOf(
                metadata["source"] ?: throw IllegalArgumentException("缺少 source 字段")
            ),
            createdAt = metadata["createdAt"]?.let { Instant.parse(it) }
                ?: throw IllegalArgumentException("缺少 createdAt 字段"),
            lastAccessedAt = metadata["lastAccessedAt"]?.let { Instant.parse(it) }
                ?: throw IllegalArgumentException("缺少 lastAccessedAt 字段"),
            accessCount = metadata["accessCount"]?.toIntOrNull() ?: 0
        )
    }

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
