package com.smancode.sman.usage.tracker

import com.smancode.sman.usage.model.SkillUsageRecord
import com.smancode.sman.usage.store.SkillUsageStore
import java.time.Instant
import java.util.UUID

/**
 * Skill 使用追踪器
 *
 * 负责 LLM 调用过程中的记录追踪，包括：
 * - 记录调用开始
 * - 更新编辑次数
 * - 记录接受状态
 *
 * 调用示例：
 * ```kotlin
 * val tracker = SkillUsageTracker(store)
 *
 * // 1. 开始调用，获取记录 ID
 * val recordId = tracker.startTracking(
 *     userQuery = "如何实现用户登录",
 *     skillsUsed = listOf("api-entry", "business-flow")
 * )
 *
 * // 2. 调用完成，记录响应
 * tracker.completeTracking(
 *     recordId = recordId,
 *     llmResponse = "...",
 *     responseTimeMs = 1500,
 *     tokenCount = 500
 * )
 *
 * // 3. 用户修改（可选）
 * tracker.recordEdit(recordId, editCount = 2)
 *
 * // 4. 用户接受/拒绝
 * tracker.recordAcceptance(recordId, accepted = true)
 * ```
 */
class SkillUsageTracker(
    private val store: SkillUsageStore
) {
    // 临时存储正在进行的调用（用于关联开始和完成）
    private val pendingCalls = mutableMapOf<String, PendingCall>()

    /**
     * 开始追踪一次 LLM 调用
     *
     * @param userQuery 用户问题
     * @param skillsUsed 使用的 Skill 列表
     * @return 记录 ID（用于后续更新）
     */
    fun startTracking(
        userQuery: String,
        skillsUsed: List<String> = emptyList()
    ): String {
        val recordId = generateRecordId()
        val pendingCall = PendingCall(
            recordId = recordId,
            userQuery = userQuery,
            skillsUsed = skillsUsed,
            startTime = Instant.now()
        )
        pendingCalls[recordId] = pendingCall
        return recordId
    }

    /**
     * 完成追踪（记录响应）
     *
     * @param recordId 记录 ID
     * @param llmResponse LLM 响应
     * @param responseTimeMs 响应时间（毫秒）
     * @param tokenCount Token 消耗
     * @return 是否成功
     */
    fun completeTracking(
        recordId: String,
        llmResponse: String,
        responseTimeMs: Long,
        tokenCount: Int
    ): Boolean {
        val pending = pendingCalls.remove(recordId) ?: return false

        val truncatedResponse = if (llmResponse.length > SkillUsageRecord.MAX_RESPONSE_LENGTH) {
            llmResponse.substring(0, SkillUsageRecord.MAX_RESPONSE_LENGTH) + "..."
        } else {
            llmResponse
        }

        val record = SkillUsageRecord(
            id = recordId,
            timestamp = pending.startTime,
            userQuery = pending.userQuery,
            llmResponse = truncatedResponse,
            skillsUsed = pending.skillsUsed,
            editCount = 0,
            accepted = false,
            responseTimeMs = responseTimeMs,
            tokenCount = tokenCount
        )

        store.append(record)
        return true
    }

    /**
     * 记录用户编辑次数
     *
     * @param recordId 记录 ID
     * @param editCount 编辑次数
     * @return 是否成功
     */
    fun recordEdit(recordId: String, editCount: Int): Boolean {
        return store.updateEditCount(recordId, editCount)
    }

    /**
     * 记录用户接受/拒绝
     *
     * @param recordId 记录 ID
     * @param accepted 是否接受
     * @return 是否成功
     */
    fun recordAcceptance(recordId: String, accepted: Boolean): Boolean {
        return store.updateAcceptance(recordId, accepted)
    }

    /**
     * 取消追踪（调用失败时使用）
     *
     * @param recordId 记录 ID
     */
    fun cancelTracking(recordId: String) {
        pendingCalls.remove(recordId)
    }

    /**
     * 清理过期的待处理调用
     *
     * @param maxAgeMs 最大存活时间（毫秒）
     * @return 清理的数量
     */
    fun cleanupPendingCalls(maxAgeMs: Long = 3600000): Int {
        val now = Instant.now()
        val toRemove = pendingCalls.entries
            .filter { now.toEpochMilli() - it.value.startTime.toEpochMilli() > maxAgeMs }
            .map { it.key }

        toRemove.forEach { pendingCalls.remove(it) }
        return toRemove.size
    }

    private fun generateRecordId(): String {
        return "rec-${UUID.randomUUID().toString().take(8)}"
    }

    /**
     * 待处理调用
     */
    private data class PendingCall(
        val recordId: String,
        val userQuery: String,
        val skillsUsed: List<String>,
        val startTime: Instant
    )
}
