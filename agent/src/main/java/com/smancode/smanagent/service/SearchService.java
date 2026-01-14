package com.smancode.smanagent.service;

import com.smancode.smanagent.model.search.SearchResult;

import java.util.List;

/**
 * 统一搜索服务
 * <p>
 * 统一代码搜索和知识搜索的入口，返回统一格式的结果列表。
 * <p>
 * 内部调用 BGE-M3 向量搜索 + BGE-Reranker 重排。
 */
public interface SearchService {

    /**
     * 搜索类型
     */
    enum SearchType {
        /**
         * 仅搜索代码
         */
        CODE,

        /**
         * 仅搜索领域知识
         */
        KNOWLEDGE,

        /**
         * 同时搜索代码和领域知识
         */
        BOTH
    }

    /**
     * 统一搜索接口
     *
     * @param query      搜索查询
     * @param projectKey 项目标识
     * @param topK       返回数量
     * @param searchType 搜索类型
     * @return 统一格式的搜索结果列表
     */
    List<SearchResult> search(String query, String projectKey, int topK, SearchType searchType);
}
