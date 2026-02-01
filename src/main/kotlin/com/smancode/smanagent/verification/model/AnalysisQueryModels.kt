package com.smancode.smanagent.verification.model

/**
 * 分析结果查询请求
 */
data class AnalysisQueryRequest(
    val module: String, // project_structure, tech_stack, api_entries, etc.
    val projectKey: String,
    val filters: Map<String, Any>? = null,
    val page: Int = 0,
    val size: Int = 20
)

/**
 * 分析结果查询响应
 */
data class AnalysisQueryResponse<T>(
    val module: String,
    val projectKey: String,
    val data: List<T>,
    val total: Int,
    val page: Int,
    val size: Int
)
