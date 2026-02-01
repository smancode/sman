package com.smancode.smanagent.verification.model

/**
 * 向量搜索请求
 */
data class VectorSearchRequest(
    val query: String,
    val projectKey: String? = null,
    val topK: Int = 10,
    val enableRerank: Boolean = true,
    val rerankTopN: Int = 5
)

/**
 * 向量搜索响应
 */
data class VectorSearchResponse(
    val query: String,
    val recallResults: List<SearchResult>,
    val rerankResults: List<SearchResult>? = null,
    val processingTimeMs: Long
)

/**
 * 搜索结果
 */
data class SearchResult(
    val fragmentId: String,
    val fileName: String,
    val content: String,
    val score: Double,
    val rank: Int
)
