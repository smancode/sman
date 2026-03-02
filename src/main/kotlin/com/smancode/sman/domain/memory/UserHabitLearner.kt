package com.smancode.sman.domain.memory

import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.UUID

/**
 * 用户习惯学习器
 * 
 * 负责从用户交互中学习习惯和偏好
 */
class UserHabitLearner(
    private val memoryStore: MemoryStore
) {
    private val logger = LoggerFactory.getLogger(UserHabitLearner::class.java)

    /**
     * 收集用户修正
     * 
     * 当用户修正 AI 的输出时调用
     */
    fun collectCorrection(
        projectId: String,
        key: String,
        originalValue: String,
        correctedValue: String
    ) {
        try {
            val collector = FeedbackCollector(memoryStore)
            collector.collectCorrection(projectId, key, originalValue, correctedValue)
            logger.info("已收集用户修正: project={}, key={}", projectId, key)
        } catch (e: Exception) {
            logger.warn("收集用户修正失败: project={}, key={}", projectId, key, e)
        }
    }

    /**
     * 收集显式偏好
     * 
     * 当用户明确告知偏好时调用
     */
    fun collectPreference(
        projectId: String,
        key: String,
        value: String
    ) {
        try {
            val collector = FeedbackCollector(memoryStore)
            collector.collectExplicitPreference(projectId, key, value)
            logger.info("已收集显式偏好: project={}, key={}", projectId, key)
        } catch (e: Exception) {
            logger.warn("收集显式偏好失败: project={}, key={}", projectId, key, e)
        }
    }

    /**
     * 记录用户行为反馈
     * 
     * @param action ACCEPTED_SUGGESTION, REJECTED_SUGGESTION, MODIFIED_SUGGESTION, REPEATED_ACTION
     */
    fun recordFeedback(
        projectId: String,
        key: String,
        action: UserAction
    ) {
        try {
            val collector = FeedbackCollector(memoryStore)
            collector.collectImplicitFeedback(projectId, key, action)
            logger.info("已记录反馈: project={}, key={}, action={}", projectId, key, action)
        } catch (e: Exception) {
            logger.warn("记录反馈失败: project={}, key={}", projectId, key, e)
        }
    }

    /**
     * 批量记录反馈
     */
    fun recordBatchFeedback(
        projectId: String,
        feedbacks: List<Pair<String, UserAction>>
    ) {
        feedbacks.forEach { (key, action) ->
            recordFeedback(projectId, key, action)
        }
    }

    /**
     * 从代码修改中学习
     * 
     * 分析用户修改的代码，提取偏好
     */
    fun learnFromCodeChange(
        projectId: String,
        originalCode: String,
        modifiedCode: String
    ) {
        // 简单的规则提取
        // 1. 检查是否将 var 改为 val
        if (originalCode.contains("var ") && modifiedCode.contains("val ")) {
            collectPreference(projectId, "prefer_val_over_var", "优先使用 val")
        }
        
        // 2. 检查命名风格
        if (originalCode.contains("_") && !modifiedCode.contains("_")) {
            collectPreference(projectId, "naming_convention", "使用 camelCase")
        } else if (!originalCode.contains("_") && modifiedCode.contains("_")) {
            collectPreference(projectId, "naming_convention", "使用 snake_case")
        }

        // 3. 检查空安全
        if (modifiedCode.contains("?.") && !originalCode.contains("?.")) {
            collectPreference(projectId, "null_safety", "使用空安全操作符")
        }

        logger.info("已从代码修改中学习: project={}", projectId)
    }
}
