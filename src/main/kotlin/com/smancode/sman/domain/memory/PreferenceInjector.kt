package com.smancode.sman.domain.memory

import org.slf4j.LoggerFactory

/**
 * 用户偏好注入器
 *
 * 负责将用户偏好和业务规则注入到 Prompt 中
 * 
 * 功能：
 * - 加载用户偏好（USER_PREFERENCE）
 * - 加载业务规则（BUSINESS_RULE）
 * - 按置信度排序
 * - 过滤低置信度记忆
 * - 限制返回数量
 */
class PreferenceInjector(
    private val memoryStore: MemoryStore
) {
    private val logger = LoggerFactory.getLogger(PreferenceInjector::class.java)

    companion object {
        /** 默认最低置信度阈值 */
        const val DEFAULT_MIN_CONFIDENCE = 0.5
        
        /** 默认最大返回数量 */
        const val DEFAULT_MAX_COUNT = 10
    }

    /**
     * 注入用户偏好
     *
     * @param projectId 项目 ID
     * @param minConfidence 最低置信度阈值（默认 0.5）
     * @param maxCount 最大返回数量（默认 10）
     * @return 格式化的偏好字符串
     */
    fun injectPreferences(
        projectId: String,
        minConfidence: Double = DEFAULT_MIN_CONFIDENCE,
        maxCount: Int = DEFAULT_MAX_COUNT
    ): String {
        return try {
            val memories = memoryStore.findByType(projectId, MemoryType.USER_PREFERENCE)
                .getOrElse { emptyList() }
                .filter { it.confidence >= minConfidence }
                .sortedByDescending { it.confidence }
                .take(maxCount)

            if (memories.isEmpty()) {
                return ""
            }

            buildString {
                appendLine("## 用户偏好 (User Preferences)")
                appendLine()
                memories.forEach { memory ->
                    appendLine("### ${memory.key}")
                    appendLine("- **偏好**: ${memory.value}")
                    appendLine("- **置信度**: ${(memory.confidence * 100).toInt()}%")
                    appendLine("- **来源**: ${memory.source.name}")
                    appendLine()
                }
                appendLine("请在生成代码时遵循以上偏好。")
            }
        } catch (e: Exception) {
            logger.warn("加载用户偏好失败: projectId={}", projectId, e)
            ""
        }
    }

    /**
     * 注入业务规则
     *
     * @param projectId 项目 ID
     * @param minConfidence 最低置信度阈值
     * @param maxCount 最大返回数量
     * @return 格式化的业务规则字符串
     */
    fun injectBusinessRules(
        projectId: String,
        minConfidence: Double = DEFAULT_MIN_CONFIDENCE,
        maxCount: Int = DEFAULT_MAX_COUNT
    ): String {
        return try {
            val memories = memoryStore.findByType(projectId, MemoryType.BUSINESS_RULE)
                .getOrElse { emptyList() }
                .filter { it.confidence >= minConfidence }
                .sortedByDescending { it.confidence }
                .take(maxCount)

            if (memories.isEmpty()) {
                return ""
            }

            buildString {
                appendLine("## 业务规则 (Business Rules)")
                appendLine()
                memories.forEach { memory ->
                    appendLine("### ${memory.key}")
                    appendLine("- **规则**: ${memory.value}")
                    appendLine("- **置信度**: ${(memory.confidence * 100).toInt()}%")
                    appendLine()
                }
                appendLine("请在生成代码时遵循以上业务规则。")
            }
        } catch (e: Exception) {
            logger.warn("加载业务规则失败: projectId={}", projectId, e)
            ""
        }
    }

    /**
     * 组合注入（偏好 + 业务规则）
     *
     * @param projectId 项目 ID
     * @return 组合后的字符串
     */
    fun injectAll(projectId: String): String {
        val preferences = injectPreferences(projectId)
        val businessRules = injectBusinessRules(projectId)

        return buildString {
            if (preferences.isNotEmpty()) {
                append(preferences)
                appendLine()
            }
            if (businessRules.isNotEmpty()) {
                append(businessRules)
            }
        }
    }

    /**
     * 获取相关偏好（基于关键词）
     *
     * @param projectId 项目 ID
     * @param query 查询关键词
     * @return 相关偏好
     */
    fun getRelevantPreferences(projectId: String, query: String): String {
        return try {
            val allMemories = memoryStore.loadAll(projectId).getOrElse { emptyList() }
                .filter { it.confidence >= DEFAULT_MIN_CONFIDENCE }

            // 简单的关键词匹配
            val queryLower = query.lowercase()
            val relevant = allMemories.filter { memory ->
                memory.key.lowercase().contains(queryLower) ||
                memory.value.lowercase().contains(queryLower)
            }.sortedByDescending { it.confidence }
            .take(DEFAULT_MAX_COUNT)

            if (relevant.isEmpty()) {
                return injectAll(projectId) // 返回所有
            }

            buildString {
                appendLine("## 相关记忆 (Relevant Memories)")
                relevant.forEach { memory ->
                    appendLine("### ${memory.key} (${memory.memoryType.name})")
                    appendLine("- **内容**: ${memory.value}")
                    appendLine("- **置信度**: ${(memory.confidence * 100).toInt()}%")
                    appendLine()
                }
            }
        } catch (e: Exception) {
            logger.warn("获取相关偏好失败: projectId={}, query={}", projectId, query, e)
            ""
        }
    }
}
