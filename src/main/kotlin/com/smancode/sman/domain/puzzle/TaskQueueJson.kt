package com.smancode.sman.domain.puzzle

import org.slf4j.LoggerFactory
import java.time.Instant

/**
 * 任务队列 JSON 序列化/反序列化工具
 */
object TaskQueueJson {
    private val logger = LoggerFactory.getLogger(TaskQueueJson::class.java)

    fun serialize(queue: TaskQueue): String {
        val tasksJson = queue.tasks.joinToString(",\n") { serializeTask(it) }
        return """
            {
              "version": ${queue.version},
              "lastUpdated": "${queue.lastUpdated}",
              "tasks": [$tasksJson]
            }
        """.trimIndent()
    }

    fun deserialize(content: String): TaskQueue {
        val tasks = mutableListOf<AnalysisTask>()

        val version = Regex("\"version\"\\s*:\\s*(\\d+)")
            .find(content)?.groupValues?.get(1)?.toInt() ?: 1

        val lastUpdated = Regex("\"lastUpdated\"\\s*:\\s*\"([^\"]+)\"")
            .find(content)?.groupValues?.get(1)?.let { Instant.parse(it) }
            ?: Instant.now()

        val tasksMatch = Regex("\"tasks\"\\s*:\\s*\\[([\\s\\S]*)\\]\\s*}").find(content)
        if (tasksMatch != null) {
            val tasksContent = tasksMatch.groupValues[1]
            val taskPattern = Regex("\\{[^{}]*(?:\\{[^{}]*\\}[^{}]*)*\\}")
            taskPattern.findAll(tasksContent).forEach { match ->
                parseTask(match.value)?.let { tasks.add(it) }
            }
        }

        return TaskQueue(version = version, lastUpdated = lastUpdated, tasks = tasks)
    }

    private fun serializeTask(task: AnalysisTask): String {
        val relatedFilesJson = task.relatedFiles.joinToString(",") { "\"$it\"" }
        return """
            {
              "id": "${escapeJson(task.id)}",
              "type": "${task.type.name}",
              "target": "${escapeJson(task.target)}",
              "puzzleId": "${escapeJson(task.puzzleId)}",
              "status": "${task.status.name}",
              "priority": ${task.priority},
              "checksum": "${escapeJson(task.checksum)}",
              "relatedFiles": [$relatedFilesJson],
              "createdAt": "${task.createdAt}",
              "startedAt": ${task.startedAt?.let { "\"$it\"" } ?: "null"},
              "completedAt": ${task.completedAt?.let { "\"$it\"" } ?: "null"},
              "retryCount": ${task.retryCount},
              "errorMessage": ${task.errorMessage?.let { "\"${escapeJson(it)}\"" } ?: "null"}
            }
        """.trimIndent()
    }

    private fun parseTask(json: String): AnalysisTask? {
        return try {
            AnalysisTask(
                id = extractString(json, "id") ?: return null,
                type = TaskType.valueOf(extractString(json, "type") ?: return null),
                target = extractString(json, "target") ?: return null,
                puzzleId = extractString(json, "puzzleId") ?: return null,
                status = TaskStatus.valueOf(extractString(json, "status") ?: return null),
                priority = extractDouble(json, "priority") ?: 0.5,
                checksum = extractString(json, "checksum") ?: "",
                relatedFiles = extractArray(json, "relatedFiles"),
                createdAt = Instant.parse(extractString(json, "createdAt") ?: return null),
                startedAt = extractString(json, "startedAt")?.let { Instant.parse(it) },
                completedAt = extractString(json, "completedAt")?.let { Instant.parse(it) },
                retryCount = extractInt(json, "retryCount") ?: 0,
                errorMessage = extractString(json, "errorMessage")
            )
        } catch (e: Exception) {
            logger.warn("解析任务失败: {}", e.message)
            null
        }
    }

    private fun extractString(json: String, key: String): String? =
        Regex("\"$key\"\\s*:\\s*\"([^\"]*)\"").find(json)?.groupValues?.get(1)

    private fun extractDouble(json: String, key: String): Double? =
        Regex("\"$key\"\\s*:\\s*([\\d.]+)").find(json)?.groupValues?.get(1)?.toDoubleOrNull()

    private fun extractInt(json: String, key: String): Int? =
        Regex("\"$key\"\\s*:\\s*(\\d+)").find(json)?.groupValues?.get(1)?.toIntOrNull()

    private fun extractArray(json: String, key: String): List<String> {
        val content = Regex("\"$key\"\\s*:\\s*\\[([^\\]]*)\\]")
            .find(json)?.groupValues?.get(1)?.trim() ?: return emptyList()
        if (content.isEmpty()) return emptyList()
        return content.split(",")
            .map { it.trim().removeSurrounding("\"") }
            .filter { it.isNotEmpty() }
    }

    private fun escapeJson(str: String): String = str
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t")
}
