package com.smancode.sman.usage.store

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.smancode.sman.usage.model.SkillUsageRecord
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * Skill 使用记录存储
 *
 * 负责持久化 LLM 使用记录到 JSON 文件，支持追加写入和批量读取。
 *
 * 存储路径：{projectPath}/.sman/usage/records.json
 */
class SkillUsageStore(
    private val storagePath: Path
) {
    private val objectMapper = ObjectMapper()
        .registerModule(KotlinModule.Builder().build())  // 支持 Kotlin data class
        .registerModule(JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        .enable(SerializationFeature.INDENT_OUTPUT)

    private val lock = ReentrantReadWriteLock()

    init {
        ensureDirectoryExists()
    }

    /**
     * 追加单条记录
     *
     * @param record 使用记录
     * @return 记录 ID
     */
    fun append(record: SkillUsageRecord): String {
        lock.write {
            try {
                val records = loadAllInternal().toMutableList()
                records.add(record)
                saveAllInternal(records)
                return record.id
            } catch (e: IOException) {
                throw StorageException("追加记录失败: ${e.message}", e)
            }
        }
    }

    /**
     * 批量追加记录
     *
     * @param newRecords 新记录列表
     * @return 成功追加的数量
     */
    fun appendAll(newRecords: List<SkillUsageRecord>): Int {
        lock.write {
            try {
                val records = loadAllInternal().toMutableList()
                records.addAll(newRecords)
                saveAllInternal(records)
                return newRecords.size
            } catch (e: IOException) {
                throw StorageException("批量追加记录失败: ${e.message}", e)
            }
        }
    }

    /**
     * 更新记录的编辑次数
     *
     * @param recordId 记录 ID
     * @param editCount 新的编辑次数
     * @return 是否更新成功
     */
    fun updateEditCount(recordId: String, editCount: Int): Boolean {
        return updateRecord(recordId, "更新编辑次数") { it.copy(editCount = editCount) }
    }

    /**
     * 更新记录的接受状态
     *
     * @param recordId 记录 ID
     * @param accepted 是否接受
     * @return 是否更新成功
     */
    fun updateAcceptance(recordId: String, accepted: Boolean): Boolean {
        return updateRecord(recordId, "更新接受状态") { it.copy(accepted = accepted) }
    }

    /**
     * 通用记录更新方法
     */
    private inline fun updateRecord(
        recordId: String,
        operationName: String,
        updater: (SkillUsageRecord) -> SkillUsageRecord
    ): Boolean {
        lock.write {
            try {
                val records = loadAllInternal().toMutableList()
                val index = records.indexOfFirst { it.id == recordId }
                if (index == -1) return false

                records[index] = updater(records[index])
                saveAllInternal(records)
                return true
            } catch (e: IOException) {
                throw StorageException("${operationName}失败: ${e.message}", e)
            }
        }
    }

    /**
     * 加载所有记录
     */
    fun loadAll(): List<SkillUsageRecord> {
        lock.read {
            return loadAllInternal()
        }
    }

    /**
     * 加载指定时间之后的记录
     *
     * @param since 起始时间
     * @return 过滤后的记录列表
     */
    fun loadRecent(since: Instant): List<SkillUsageRecord> {
        lock.read {
            return loadAllInternal().filter { it.timestamp >= since }
        }
    }

    /**
     * 加载指定范围的记录（用于分批处理）
     *
     * @param startIndex 起始索引（包含）
     * @param endIndex 结束索引（不包含）
     * @return 记录列表
     */
    fun loadRange(startIndex: Int, endIndex: Int): List<SkillUsageRecord> {
        lock.read {
            val records = loadAllInternal()
            if (startIndex >= records.size) return emptyList()
            val end = minOf(endIndex, records.size)
            return records.subList(startIndex, end)
        }
    }

    /**
     * 获取记录总数
     */
    fun count(): Int {
        lock.read {
            return loadAllInternal().size
        }
    }

    /**
     * 清理指定时间之前的记录（用于归档）
     *
     * @param before 截止时间
     * @return 删除的记录数
     */
    fun cleanup(before: Instant): Int {
        lock.write {
            try {
                val records = loadAllInternal()
                val (toKeep, toRemove) = records.partition { it.timestamp >= before }
                if (toRemove.isNotEmpty()) {
                    saveAllInternal(toKeep)
                }
                return toRemove.size
            } catch (e: IOException) {
                throw StorageException("清理记录失败: ${e.message}", e)
            }
        }
    }

    private fun loadAllInternal(): List<SkillUsageRecord> {
        if (!Files.exists(storagePath)) {
            return emptyList()
        }

        return try {
            val content = Files.readString(storagePath)
            if (content.isBlank()) {
                emptyList()
            } else {
                objectMapper.readValue(content, object : TypeReference<List<SkillUsageRecord>>() {})
            }
        } catch (e: Exception) {
            // 记录错误但不抛出，返回空列表
            println("Error loading records: ${e.message}")
            emptyList()
        }
    }

    private fun saveAllInternal(records: List<SkillUsageRecord>) {
        val content = objectMapper.writeValueAsString(records)
        Files.writeString(
            storagePath,
            content,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING
        )
    }

    private fun ensureDirectoryExists() {
        val parent = storagePath.parent
        if (!Files.exists(parent)) {
            Files.createDirectories(parent)
        }
    }

    /**
     * 存储异常
     */
    class StorageException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
}
