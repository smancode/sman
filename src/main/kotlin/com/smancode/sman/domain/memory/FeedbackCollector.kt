package com.smancode.sman.domain.memory

import java.time.Instant
import java.util.UUID

/**
 * 反馈收集器
 *
 * 负责从用户交互中收集反馈，更新记忆置信度
 */
class FeedbackCollector(
    private val memoryStore: MemoryStore
) {

    companion object {
        /** 修正后初始置信度 */
        private const val INITIAL_CORRECTION_CONFIDENCE = 0.9

        /** 显式偏好置信度 */
        private const val EXPLICIT_PREFERENCE_CONFIDENCE = 1.0

        /** 接受建议置信度增量 */
        private const val ACCEPTED_INCREMENT = 0.1

        /** 拒绝建议置信度减量 */
        private const val REJECTED_DECREMENT = 0.2

        /** 修改建议置信度增量 */
        private const val MODIFIED_INCREMENT = 0.05

        /** 重复动作置信度增量 */
        private const val REPEATED_ACTION_INCREMENT = 0.3
    }

    /**
     * 收集修正反馈
     *
     * 当用户修正 AI 的输出时，创建或更新业务规则记忆
     *
     * @param projectId 项目 ID
     * @param key 记忆键
     * @param originalValue 原始值（AI 输出）
     * @param correctedValue 修正值（用户修正后）
     * @throws IllegalArgumentException 如果 projectId 或 key 为空
     */
    fun collectCorrection(
        projectId: String,
        key: String,
        originalValue: String,
        correctedValue: String
    ) {
        require(projectId.isNotBlank()) { "projectId 不能为空" }
        require(key.isNotBlank()) { "key 不能为空" }

        val existingMemory = memoryStore.load(projectId, key).getOrThrow()
        val now = Instant.now()

        val memory = if (existingMemory != null) {
            // 相同修正提高置信度
            val newConfidence = minOf(1.0, existingMemory.confidence + ACCEPTED_INCREMENT)
            existingMemory.copy(
                value = correctedValue,
                confidence = newConfidence,
                lastAccessedAt = now
            )
        } else {
            ProjectMemory(
                id = UUID.randomUUID().toString(),
                projectId = projectId,
                memoryType = MemoryType.BUSINESS_RULE,
                key = key,
                value = correctedValue,
                confidence = INITIAL_CORRECTION_CONFIDENCE,
                source = MemorySource.FEEDBACK_CORRECTION,
                createdAt = now,
                lastAccessedAt = now,
                accessCount = 0
            )
        }

        memoryStore.save(memory).getOrThrow()
    }

    /**
     * 收集显式偏好
     *
     * 当用户明确告知偏好时，创建用户偏好记忆
     *
     * @param projectId 项目 ID
     * @param key 记忆键
     * @param value 偏好值
     * @throws IllegalArgumentException 如果参数为空
     */
    fun collectExplicitPreference(
        projectId: String,
        key: String,
        value: String
    ) {
        require(projectId.isNotBlank()) { "projectId 不能为空" }
        require(key.isNotBlank()) { "key 不能为空" }
        require(value.isNotBlank()) { "value 不能为空" }

        val now = Instant.now()
        val memory = ProjectMemory(
            id = UUID.randomUUID().toString(),
            projectId = projectId,
            memoryType = MemoryType.USER_PREFERENCE,
            key = key,
            value = value,
            confidence = EXPLICIT_PREFERENCE_CONFIDENCE,
            source = MemorySource.EXPLICIT_INPUT,
            createdAt = now,
            lastAccessedAt = now,
            accessCount = 0
        )

        memoryStore.save(memory).getOrThrow()
    }

    /**
     * 收集隐式反馈
     *
     * 根据用户行为调整记忆置信度
     *
     * @param projectId 项目 ID
     * @param key 记忆键
     * @param action 用户行为
     */
    fun collectImplicitFeedback(
        projectId: String,
        key: String,
        action: UserAction
    ) {
        val existingMemory = memoryStore.load(projectId, key).getOrThrow()
            ?: return // 不存在的记忆忽略

        val confidenceDelta = when (action) {
            UserAction.ACCEPTED_SUGGESTION -> ACCEPTED_INCREMENT
            UserAction.REJECTED_SUGGESTION -> -REJECTED_DECREMENT
            UserAction.MODIFIED_SUGGESTION -> MODIFIED_INCREMENT
            UserAction.REPEATED_ACTION -> REPEATED_ACTION_INCREMENT
        }

        val newConfidence = (existingMemory.confidence + confidenceDelta)
            .coerceIn(0.0, 1.0)

        val updatedMemory = existingMemory.copy(
            confidence = newConfidence,
            lastAccessedAt = Instant.now(),
            accessCount = existingMemory.accessCount + 1
        )

        memoryStore.save(updatedMemory).getOrThrow()
    }
}
