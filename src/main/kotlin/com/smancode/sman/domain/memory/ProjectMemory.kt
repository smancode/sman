package com.smancode.sman.domain.memory

import java.time.Instant

/**
 * 项目记忆数据结构
 *
 * 存储从用户交互中学习到的项目特定知识
 *
 * @property id 记忆唯一标识符
 * @property projectId 项目标识符
 * @property memoryType 记忆类型
 * @property key 记忆键（如业务规则名称、领域术语）
 * @property value 记忆值
 * @property confidence 置信度（0.0-1.0）
 * @property source 记忆来源
 * @property createdAt 创建时间
 * @property lastAccessedAt 最后访问时间
 * @property accessCount 访问次数
 */
data class ProjectMemory(
    val id: String,
    val projectId: String,
    val memoryType: MemoryType,
    val key: String,
    val value: String,
    val confidence: Double,
    val source: MemorySource,
    val createdAt: Instant,
    val lastAccessedAt: Instant,
    val accessCount: Int
)

/**
 * 记忆类型枚举
 */
enum class MemoryType {
    /** 业务规则 - 如校验规则、计算规则 */
    BUSINESS_RULE,
    /** 领域术语 - 如业务名词定义 */
    DOMAIN_TERM,
    /** 用户偏好 - 如代码风格偏好 */
    USER_PREFERENCE,
    /** 项目约束 - 如技术限制 */
    PROJECT_CONSTRAINT
}

/**
 * 记忆来源枚举
 */
enum class MemorySource {
    /** 显式输入 - 用户明确告知 */
    EXPLICIT_INPUT,
    /** 隐式学习 - 从行为推断 */
    IMPLICIT_LEARNING,
    /** 代码分析 - 从代码提取 */
    CODE_ANALYSIS,
    /** 反馈修正 - 用户修正后学习 */
    FEEDBACK_CORRECTION
}
