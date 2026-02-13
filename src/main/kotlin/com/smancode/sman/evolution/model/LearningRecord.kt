package com.smancode.sman.evolution.model

import kotlinx.serialization.Serializable

// ==================== 学习记录 (核心) ====================

/**
 * 学习记录 - 后台自问自答产生的知识
 *
 * 这是最重要的数据结构，前台通过语义搜索找到它。
 * 记录了智能体在后台探索项目时发现的有价值知识。
 *
 * @property id 唯一标识
 * @property projectKey 所属项目
 * @property createdAt 创建时间戳
 * @property question 好问题 (LLM 生成)
 * @property questionType 问题类型
 * @property answer 答案 (LLM 总结)
 * @property explorationPath 探索路径 (工具调用步骤列表)
 * @property confidence 置信度 (0.0 - 1.0)
 * @property sourceFiles 涉及的源文件列表
 * @property relatedRecords 关联的其他学习记录 ID
 * @property tags 标签列表
 * @property domain 领域 (如: 还款、订单、支付)
 * @property metadata 扩展元数据
 */
@Serializable
data class LearningRecord(
    // 基础信息
    val id: String,
    val projectKey: String,
    val createdAt: Long,

    // 问题与答案
    val question: String,
    val questionType: QuestionType,
    val answer: String,

    // 探索过程
    val explorationPath: List<ToolCallStep>,

    // 元数据
    val confidence: Double,
    val sourceFiles: List<String>,
    val relatedRecords: List<String>,

    // 标签和分类
    val tags: List<String> = emptyList(),
    val domain: String? = null,
    val metadata: Map<String, String> = emptyMap()
) {
    /**
     * 向量字段不参与序列化，运行时通过 VectorStoreManager 单独存储和检索
     * 这样设计的原因:
     * 1. FloatArray 不支持 Kotlin 原生序列化
     * 2. 向量数据存储在独立的向量数据库表中
     * 3. 加载时通过 ID 关联查询
     */
    @kotlinx.serialization.Transient
    var questionVector: FloatArray? = null

    @kotlinx.serialization.Transient
    var answerVector: FloatArray? = null

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is LearningRecord) return false
        // 不比较向量字段
        return id == other.id &&
                projectKey == other.projectKey &&
                createdAt == other.createdAt &&
                question == other.question &&
                questionType == other.questionType &&
                answer == other.answer &&
                explorationPath == other.explorationPath &&
                confidence == other.confidence &&
                sourceFiles == other.sourceFiles &&
                relatedRecords == other.relatedRecords &&
                tags == other.tags &&
                domain == other.domain &&
                metadata == other.metadata
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + projectKey.hashCode()
        result = 31 * result + createdAt.hashCode()
        result = 31 * result + question.hashCode()
        result = 31 * result + questionType.hashCode()
        result = 31 * result + answer.hashCode()
        result = 31 * result + explorationPath.hashCode()
        result = 31 * result + confidence.hashCode()
        result = 31 * result + sourceFiles.hashCode()
        result = 31 * result + relatedRecords.hashCode()
        result = 31 * result + tags.hashCode()
        result = 31 * result + (domain?.hashCode() ?: 0)
        result = 31 * result + metadata.hashCode()
        return result
    }
}

/**
 * 问题类型枚举
 *
 * 定义了智能体可以探索的问题类别，用于分类和检索学习记录。
 */
enum class QuestionType {
    /** 代码结构问题: "项目的分层架构是什么？" */
    CODE_STRUCTURE,

    /** 业务逻辑问题: "还款的核心流程是什么？" */
    BUSINESS_LOGIC,

    /** 数据流问题: "订单状态如何流转？" */
    DATA_FLOW,

    /** 依赖关系问题: "支付依赖哪些外部服务？" */
    DEPENDENCY,

    /** 配置问题: "数据库连接池如何配置？" */
    CONFIGURATION,

    /** 错误分析: "常见的异常有哪些？" */
    ERROR_ANALYSIS,

    /** 最佳实践: "这个项目使用了什么设计模式？" */
    BEST_PRACTICE,

    /** 领域知识: "先息后本是什么意思？" */
    DOMAIN_KNOWLEDGE
}

/**
 * 工具调用步骤
 *
 * 记录智能体在探索过程中调用的每一个工具及其结果。
 * 用于追踪学习路径，支持知识溯源。
 *
 * @property toolName 工具名称
 * @property parameters 调用参数
 * @property resultSummary 结果摘要
 * @property timestamp 调用时间戳
 */
@Serializable
data class ToolCallStep(
    val toolName: String,
    val parameters: Map<String, String>,
    val resultSummary: String,
    val timestamp: Long
)
