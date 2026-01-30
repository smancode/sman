package com.smancode.smanagent.analysis.entrance.model

import kotlinx.serialization.Serializable

/**
 * HTTP 入口
 */
@Serializable
data class HttpEntrance(
    val className: String,
    val methodName: String,
    val methodId: String,
    val lineNumber: Int,
    val urlPath: String,
    val httpMethod: String,
    val reqDto: String? = null,
    val rspDto: String? = null,
    val description: String? = null,
    val domain: String? = null,
    val keywords: List<String> = emptyList(),
    val threadPoolConfig: ThreadPoolConfig? = null,
    val threadPoolIssues: List<ThreadPoolIssue> = emptyList()
)

/**
 * MQ 入口
 */
@Serializable
data class MqEntrance(
    val className: String,
    val methodName: String,
    val methodId: String,
    val lineNumber: Int,
    val mqType: MqType,
    val topics: List<String>,
    val groupId: String? = null,
    val reqDto: String? = null,
    val rspDto: String? = null,
    val description: String? = null,
    val domain: String? = null,
    val threadPoolConfig: ThreadPoolConfig? = null,
    val threadPoolIssues: List<ThreadPoolIssue> = emptyList()
)

/**
 * RPC 入口
 */
@Serializable
data class RpcEntrance(
    val className: String,
    val methodName: String,
    val methodId: String,
    val lineNumber: Int,
    val rpcType: RpcType,
    val serviceInterface: String,
    val version: String? = null,
    val reqDto: String? = null,
    val rspDto: String? = null,
    val description: String? = null,
    val domain: String? = null,
    val threadPoolConfig: ThreadPoolConfig? = null,
    val threadPoolIssues: List<ThreadPoolIssue> = emptyList()
)

/**
 * 定时任务入口
 */
@Serializable
data class ScheduledEntrance(
    val className: String,
    val methodName: String,
    val methodId: String,
    val lineNumber: Int,
    val jobType: JobType,
    val jobName: String? = null,
    val cron: String? = null,
    val reqDto: String? = null,
    val rspDto: String? = null,
    val description: String? = null,
    val domain: String? = null,
    val threadPoolConfig: ThreadPoolConfig? = null,
    val threadPoolIssues: List<ThreadPoolIssue> = emptyList()
)

/**
 * 事件监听器入口
 */
@Serializable
data class EventListenerEntrance(
    val className: String,
    val methodName: String,
    val methodId: String,
    val lineNumber: Int,
    val reqDto: String? = null,
    val rspDto: String? = null,
    val condition: String? = null,
    val description: String? = null,
    val domain: String? = null,
    val threadPoolConfig: ThreadPoolConfig? = null,
    val threadPoolIssues: List<ThreadPoolIssue> = emptyList()
)

/**
 * 入口类型枚举
 */
@Serializable
enum class EntranceType {
    HTTP,           // HTTP 接口
    MIDDLEWARE,     // 中间件入口（MQ、RPC）
    CLASS_BATCH,    // 类级别批量任务
    FILE_BATCH,     // 文件级别批量任务
    EVENT_LISTENER, // 事件监听器
    UNKNOWN         // 未知类型
}

/**
 * MQ 类型枚举
 */
@Serializable
enum class MqType {
    KAFKA, ROCKETMQ, RABBITMQ, JMS, UNKNOWN
}

/**
 * RPC 类型枚举
 */
@Serializable
enum class RpcType {
    DUBBO, GRPC, UNKNOWN
}

/**
 * 任务类型枚举
 */
@Serializable
enum class JobType {
    XXL_JOB, SPRING_SCHEDULED, QUARTZ, UNKNOWN
}

/**
 * 线程池配置信息
 */
@Serializable
data class ThreadPoolConfig(
    val type: ThreadPoolType,
    val beanName: String? = null,
    val coreSize: Int? = null,
    val maxSize: Int? = null,
    val queueCapacity: Int? = null,
    val queueType: String? = null,
    val rejectionPolicy: String? = null,
    val threadNamePrefix: String? = null,
    val keepAliveSeconds: Int? = null,
    val allowCoreThreadTimeOut: Boolean? = null
)

/**
 * 线程池类型枚举
 */
@Serializable
enum class ThreadPoolType {
    THREAD_POOL_TASK_EXECUTOR,  // Spring ThreadPoolTaskExecutor
    EXECUTOR_SERVICE,           // Java ExecutorService
    FORK_JOIN_POOL,            // ForkJoinPool
    SCHEDULED_EXECUTOR_SERVICE, // ScheduledExecutorService
    UNKNOWN
}

/**
 * 线程池配置问题
 */
@Serializable
data class ThreadPoolIssue(
    val severity: IssueSeverity,
    val message: String,
    val suggestion: String? = null
)

/**
 * 问题严重程度枚举
 */
@Serializable
enum class IssueSeverity {
    WARNING, ERROR, INFO
}
