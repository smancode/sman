package com.smancode.sman.usage.store

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.smancode.sman.usage.model.AnalysisJobState
import com.smancode.sman.usage.model.JobStatus
import com.smancode.sman.usage.model.UsageAnalysisResult
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 分析任务状态存储
 *
 * 负责持久化分析任务状态，支持断点续传和异常恢复。
 *
 * 存储路径：
 * - 当前任务状态：{projectPath}/.sman/usage/analysis_job.json
 * - 历史任务归档：{projectPath}/.sman/usage/history/job-{timestamp}.json
 */
class AnalysisJobStateStore(
    private val storagePath: Path,
    private val historyDir: Path
) {
    private val objectMapper = ObjectMapper()
        .registerModule(KotlinModule.Builder().build())  // 支持 Kotlin data class
        .registerModule(JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        .enable(SerializationFeature.INDENT_OUTPUT)

    private val lock = ReentrantLock()

    init {
        ensureDirectoriesExist()
    }

    /**
     * 加载当前任务状态
     *
     * @return 当前任务状态，如果不存在则返回 null
     */
    fun loadCurrent(): AnalysisJobState? {
        lock.withLock {
            if (!Files.exists(storagePath)) {
                return null
            }

            return try {
                val content = Files.readString(storagePath)
                if (content.isBlank()) {
                    null
                } else {
                    objectMapper.readValue(content, AnalysisJobState::class.java)
                }
            } catch (e: IOException) {
                null
            }
        }
    }

    /**
     * 保存任务状态
     *
     * @param state 任务状态
     */
    fun save(state: AnalysisJobState) {
        lock.withLock {
            try {
                val content = objectMapper.writeValueAsString(state)
                Files.writeString(storagePath, content)
            } catch (e: IOException) {
                throw StateStorageException("保存任务状态失败: ${e.message}", e)
            }
        }
    }

    /**
     * 创建新任务
     *
     * @param totalRecords 总记录数
     * @return 新任务状态
     */
    fun createNew(totalRecords: Int): AnalysisJobState {
        lock.withLock {
            // 检查是否有正在运行的任务
            val current = loadCurrentInternal()
            if (current != null && current.status == JobStatus.RUNNING) {
                throw StateStorageException("已有任务正在运行: ${current.jobId}")
            }

            // 将旧任务归档（如果存在）
            current?.let { archiveJob(it) }

            // 创建新任务
            val newState = AnalysisJobState.createNew(totalRecords)
            saveInternal(newState)
            return newState
        }
    }

    /**
     * 更新任务进度
     *
     * @param jobId 任务 ID
     * @param processedIndex 已处理到的索引
     */
    fun updateProgress(jobId: String, processedIndex: Int) {
        lock.withLock {
            val current = loadCurrentInternal()
            if (current == null || current.jobId != jobId) {
                return
            }

            val updated = current.copy(
                status = JobStatus.RUNNING,
                lastProcessedIndex = processedIndex,
                processedRecords = processedIndex + 1
            )
            saveInternal(updated)
        }
    }

    /**
     * 标记任务完成
     *
     * @param jobId 任务 ID
     * @param result 分析结果
     */
    fun markCompleted(jobId: String, result: UsageAnalysisResult) {
        lock.withLock {
            val current = loadCurrentInternal()
            if (current == null || current.jobId != jobId) {
                return
            }

            val updated = current.copy(
                status = JobStatus.COMPLETED,
                completedAt = Instant.now(),
                result = result
            )
            saveInternal(updated)
            archiveJob(updated)
        }
    }

    /**
     * 标记任务失败
     *
     * @param jobId 任务 ID
     * @param error 错误信息
     */
    fun markFailed(jobId: String, error: String) {
        lock.withLock {
            val current = loadCurrentInternal()
            if (current == null || current.jobId != jobId) {
                return
            }

            val updated = current.copy(
                status = JobStatus.FAILED,
                completedAt = Instant.now(),
                errorMessage = error
            )
            saveInternal(updated)
        }
    }

    /**
     * 清理当前状态（用于强制重置）
     */
    fun clear() {
        lock.withLock {
            val current = loadCurrentInternal()
            current?.let { archiveJob(it) }
            Files.deleteIfExists(storagePath)
        }
    }

    /**
     * 获取最近的历史任务列表
     *
     * @param limit 最大数量
     * @return 历史任务列表（按时间倒序）
     */
    fun getRecentHistory(limit: Int = 10): List<AnalysisJobState> {
        lock.withLock {
            if (!Files.exists(historyDir)) {
                return emptyList()
            }

            return Files.list(historyDir)
                .filter { it.toString().endsWith(".json") }
                .sorted { a, b -> b.fileName.compareTo(a.fileName) }
                .limit(limit.toLong())
                .map { path -> loadFromPath(path) }
                .filter { it != null }
                .map { it!! }
                .toList()
        }
    }

    private fun loadCurrentInternal(): AnalysisJobState? {
        return loadFromPath(storagePath)
    }

    private fun loadFromPath(path: Path): AnalysisJobState? {
        if (!Files.exists(path)) {
            return null
        }

        return try {
            val content = Files.readString(path)
            if (content.isBlank()) {
                null
            } else {
                objectMapper.readValue(content, AnalysisJobState::class.java)
            }
        } catch (e: IOException) {
            null
        }
    }

    private fun saveInternal(state: AnalysisJobState) {
        val content = objectMapper.writeValueAsString(state)
        Files.writeString(storagePath, content)
    }

    private fun archiveJob(state: AnalysisJobState) {
        if (!Files.exists(historyDir)) {
            Files.createDirectories(historyDir)
        }

        val timestamp = DateTimeFormatter.ISO_INSTANT.format(state.startedAt)
            .replace(":", "-")
            .replace(".", "-")
        val archiveFile = historyDir.resolve("job-${timestamp}.json")

        // 复制到归档目录
        val tempFile = Files.createTempFile("job-archive", ".json")
        try {
            objectMapper.writeValue(tempFile.toFile(), state)
            Files.move(tempFile, archiveFile, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: Exception) {
            Files.deleteIfExists(tempFile)
        }
    }

    private fun ensureDirectoriesExist() {
        val parent = storagePath.parent
        if (!Files.exists(parent)) {
            Files.createDirectories(parent)
        }
        if (!Files.exists(historyDir)) {
            Files.createDirectories(historyDir)
        }
    }

    /**
     * 状态存储异常
     */
    class StateStorageException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
}
