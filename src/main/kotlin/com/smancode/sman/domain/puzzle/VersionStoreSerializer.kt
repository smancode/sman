package com.smancode.sman.domain.puzzle

import com.smancode.sman.shared.model.Puzzle
import com.smancode.sman.shared.model.PuzzleStatus
import com.smancode.sman.shared.model.PuzzleType
import java.time.Instant

/**
 * 版本存储序列化器
 *
 * 负责版本索引和快照的序列化/反序列化
 */
object VersionStoreSerializer {

    // ========== 索引序列化 ==========

    fun serializeIndex(index: VersionIndex): String {
        val versionsJson = index.versions.joinToString(",\n") { entry ->
            """    {
      "id": "${entry.id}",
      "versionNumber": ${entry.versionNumber},
      "createdAt": "${entry.createdAt}",
      "puzzleCount": ${entry.puzzleCount},
      "checksum": "${entry.checksum}"
    }"""
        }

        return """{
  "currentVersion": ${index.currentVersion},
  "versions": [
$versionsJson
  ]
}"""
    }

    fun parseIndex(content: String): VersionIndex {
        val currentVersionMatch = Regex("\"currentVersion\"\\s*:\\s*(\\d+)").find(content)
        val currentVersion = currentVersionMatch?.groupValues?.get(1)?.toInt() ?: 0

        val entries = mutableListOf<VersionEntry>()
        val entryPattern = Regex(
            """\{\s*"id"\s*:\s*"([^"]+)"\s*,\s*"versionNumber"\s*:\s*(\d+)\s*,\s*"createdAt"\s*:\s*"([^"]+)"\s*,\s*"puzzleCount"\s*:\s*(\d+)\s*,\s*"checksum"\s*:\s*"([^"]+)"\s*\}"""
        )
        entryPattern.findAll(content).forEach { match ->
            entries.add(
                VersionEntry(
                    id = match.groupValues[1],
                    versionNumber = match.groupValues[2].toInt(),
                    createdAt = Instant.parse(match.groupValues[3]),
                    puzzleCount = match.groupValues[4].toInt(),
                    checksum = match.groupValues[5]
                )
            )
        }

        return VersionIndex(currentVersion, entries)
    }

    // ========== 快照序列化 ==========

    fun serializeSnapshot(version: KnowledgeBaseVersion, puzzles: List<Puzzle>): String {
        val puzzlesJson = puzzles.joinToString(",\n") { puzzle ->
            val contentEscaped = puzzle.content
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")

            """    {
      "id": "${puzzle.id}",
      "type": "${puzzle.type.name}",
      "status": "${puzzle.status.name}",
      "content": "$contentEscaped",
      "completeness": ${puzzle.completeness},
      "confidence": ${puzzle.confidence},
      "lastUpdated": "${puzzle.lastUpdated}",
      "filePath": "${puzzle.filePath}"
    }"""
        }

        val descriptionJson = if (version.description != null) "\"${version.description}\"" else "null"

        return """{
  "version": {
    "id": "${version.id}",
    "versionNumber": ${version.versionNumber},
    "createdAt": "${version.createdAt}",
    "puzzleCount": ${version.puzzleCount},
    "checksum": "${version.checksum}",
    "description": $descriptionJson,
    "trigger": "${version.trigger.name}"
  },
  "puzzles": [
$puzzlesJson
  ]
}"""
    }

    fun parseSnapshot(content: String): KnowledgeBaseSnapshot {
        val version = parseVersion(content)
        val puzzles = parsePuzzles(content)
        return KnowledgeBaseSnapshot(version, puzzles)
    }

    private fun parseVersion(content: String): KnowledgeBaseVersion {
        val versionIdMatch = Regex("\"id\"\\s*:\\s*\"([^\"]+)\"").find(content)
        val versionNumberMatch = Regex("\"versionNumber\"\\s*:\\s*(\\d+)").find(content)
        val createdAtMatch = Regex("\"createdAt\"\\s*:\\s*\"([^\"]+)\"").find(content)
        val puzzleCountMatch = Regex("\"puzzleCount\"\\s*:\\s*(\\d+)").find(content)
        val checksumMatch = Regex("\"checksum\"\\s*:\\s*\"([^\"]+)\"").find(content)
        val descriptionMatch = Regex("\"description\"\\s*:\\s*\"([^\"]+)\"").find(content)
        val triggerMatch = Regex("\"trigger\"\\s*:\\s*\"([^\"]+)\"").find(content)

        return KnowledgeBaseVersion(
            id = versionIdMatch?.groupValues?.get(1) ?: "",
            versionNumber = versionNumberMatch?.groupValues?.get(1)?.toInt() ?: 0,
            createdAt = Instant.parse(createdAtMatch?.groupValues?.get(1)),
            puzzleCount = puzzleCountMatch?.groupValues?.get(1)?.toInt() ?: 0,
            checksum = checksumMatch?.groupValues?.get(1) ?: "",
            description = descriptionMatch?.groupValues?.get(1),
            trigger = triggerMatch?.groupValues?.get(1)?.let { VersionTrigger.valueOf(it) }
                ?: VersionTrigger.AUTO
        )
    }

    private fun parsePuzzles(content: String): List<Puzzle> {
        val puzzles = mutableListOf<Puzzle>()
        val puzzlePattern = Regex(
            """\{\s*"id"\s*:\s*"([^"]+)"\s*,\s*"type"\s*:\s*"([^"]+)"\s*,\s*"status"\s*:\s*"([^"]+)"\s*,\s*"content"\s*:\s*"((?:[^"\\]|\\.)*)"\s*,\s*"completeness"\s*:\s*([\d.]+)\s*,\s*"confidence"\s*:\s*([\d.]+)\s*,\s*"lastUpdated"\s*:\s*"([^"]+)"\s*,\s*"filePath"\s*:\s*"([^"]+)"\s*\}""",
            RegexOption.DOT_MATCHES_ALL
        )
        puzzlePattern.findAll(content).forEach { match ->
            val rawContent = match.groupValues[4]
                .replace("\\n", "\n")
                .replace("\\\"", "\"")
                .replace("\\\\", "\\")

            puzzles.add(
                Puzzle(
                    id = match.groupValues[1],
                    type = PuzzleType.valueOf(match.groupValues[2]),
                    status = PuzzleStatus.valueOf(match.groupValues[3]),
                    content = rawContent,
                    completeness = match.groupValues[5].toDouble(),
                    confidence = match.groupValues[6].toDouble(),
                    lastUpdated = Instant.parse(match.groupValues[7]),
                    filePath = match.groupValues[8]
                )
            )
        }
        return puzzles
    }
}
