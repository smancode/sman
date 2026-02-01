package com.smancode.smanagent.verification.model

/**
 * 专家咨询请求
 */
data class ExpertConsultRequest(
    val question: String,
    val projectKey: String,
    val topK: Int = 10,
    val enableRerank: Boolean = true,
    val rerankTopN: Int = 5
)

/**
 * 专家咨询响应
 */
data class ExpertConsultResponse(
    val answer: String,
    val sources: List<SourceInfo>,
    val confidence: Double,
    val processingTimeMs: Long
)

/**
 * 来源信息
 */
data class SourceInfo(
    val filePath: String,
    val className: String,
    val methodName: String,
    val score: Double
)
