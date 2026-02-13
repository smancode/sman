package com.smancode.sman.evolution.model

import kotlinx.serialization.Serializable

/**
 * 失败记录 - 用于死循环防护和学习
 *
 * 记录后台自进化过程中失败的 操作，用于：
 * 1. 避免重复执行已知会失败的操作
 * 2. 记录失败上下文供后续分析
 * 3. 支持 LLM 生成避免策略
 *
 * @param id 唯一标识
 * @param projectKey 所属项目
 * @param operationType 操作类型
 * @param operation 操作描述
 * @param error 错误信息
 * @param context 上下文信息
 * @param timestamp 时间戳
 * @param retryCount 重试次数
 * @param status 失败状态
 * @param avoidStrategy 避免策略（LLM 生成）
 */
@Serializable
data class FailureRecord(
    val id: String,
    val projectKey: String,
    val operationType: OperationType,
    val operation: String,
    val error: String,
    val context: Map<String, String>,
    val timestamp: Long,
    val retryCount: Int = 0,
    val status: FailureStatus = FailureStatus.PENDING,
    val avoidStrategy: String? = null
)

/**
 * 操作类型枚举
 *
 * 定义所有可能产生失败记录的操作类型
 */
enum class OperationType {
    // 现有类型
    PROJECT_ANALYSIS,
    CODE_VECTORIZATION,

    // 自进化类型
    SELF_EVOLUTION_QUESTION,     // 问题生成失败
    SELF_EVOLUTION_EXPLORATION,  // 工具探索失败
    SELF_EVOLUTION_SUMMARY       // 学习总结失败
}

/**
 * 失败状态枚举
 *
 * 定义失败记录的生命周期状态
 */
enum class FailureStatus {
    PENDING,    // 待处理
    RETRYING,   // 重试中
    RECOVERED,  // 已恢复
    FAILED      // 彻底失败
}
