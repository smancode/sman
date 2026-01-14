package com.smancode.smanagent.service.impl;

import com.smancode.smanagent.model.DomainKnowledge;
import com.smancode.smanagent.model.search.SearchResult;
import com.smancode.smanagent.repository.DomainKnowledgeRepository;
import com.smancode.smanagent.service.SearchService;
import com.smancode.smanagent.vector.VectorSearchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.smancode.smanagent.models.VectorModels;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 统一搜索服务实现
 * <p>
 * 整合代码搜索和领域知识搜索。
 */
@Service
public class SearchServiceImpl implements SearchService {

    private static final Logger logger = LoggerFactory.getLogger(SearchServiceImpl.class);

    @Autowired
    private VectorSearchService vectorSearchService;

    @Autowired
    private DomainKnowledgeRepository domainKnowledgeRepository;

    @Override
    public List<SearchResult> search(String query, String projectKey, int topK, SearchType searchType) {
        logger.info("🔍 统一搜索: query={}, projectKey={}, topK={}, type={}",
                query, projectKey, topK, searchType);

        List<SearchResult> allResults = new ArrayList<>();

        // 代码搜索
        if (searchType == SearchType.CODE || searchType == SearchType.BOTH) {
            List<SearchResult> codeResults = searchCode(query, projectKey, topK);
            allResults.addAll(codeResults);
            logger.debug("代码搜索结果: {} 个", codeResults.size());
        }

        // 领域知识搜索
        if (searchType == SearchType.KNOWLEDGE || searchType == SearchType.BOTH) {
            List<SearchResult> knowledgeResults = searchKnowledge(query, projectKey, topK);
            allResults.addAll(knowledgeResults);
            logger.debug("领域知识搜索结果: {} 个", knowledgeResults.size());
        }

        // 按分数排序，取 topK
        List<SearchResult> finalResults = allResults.stream()
                .sorted(Comparator.comparingDouble(SearchResult::getScore).reversed())
                .limit(topK)
                .collect(Collectors.toList());

        logger.info("✅ 搜索完成: 返回 {} 个结果", finalResults.size());
        return finalResults;
    }

    /**
     * 代码搜索
     */
    private List<SearchResult> searchCode(String query, String projectKey, int topK) {
        try {
            // 使用现有的 VectorSearchService
            VectorModels.SemanticSearchRequest request =
                    new VectorModels.SemanticSearchRequest();
            request.setProjectKey(projectKey);
            request.setRecallQuery(query);
            request.setRecallTopK(topK);

            List<VectorModels.SearchResult> codeResults =
                    vectorSearchService.semanticSearch(request);

            // 转换为统一的 SearchResult
            return codeResults.stream()
                    .map(this::toSearchResult)
                    .collect(Collectors.toList());

        } catch (Exception e) {
            logger.error("代码搜索失败: query={}, projectKey={}", query, projectKey, e);
            return List.of();
        }
    }

    /**
     * 领域知识搜索
     */
    private List<SearchResult> searchKnowledge(String query, String projectKey, int topK) {
        try {
            // 获取所有有向量的领域知识
            List<DomainKnowledge> allKnowledge =
                    domainKnowledgeRepository.findAllWithEmbedding(projectKey);

            if (allKnowledge.isEmpty()) {
                logger.debug("没有领域知识: projectKey={}", projectKey);
                return List.of();
            }

            // TODO: 使用 BGE-M3 进行向量相似度搜索
            // 这里暂时使用简单的关键词匹配
            List<SearchResult> results = allKnowledge.stream()
                    .filter(dk -> containsKeyword(dk, query))
                    .limit(topK)
                    .map(this::toSearchResult)
                    .collect(Collectors.toList());

            return results;

        } catch (Exception e) {
            logger.error("领域知识搜索失败: query={}, projectKey={}", query, projectKey, e);
            return List.of();
        }
    }

    /**
     * 简单的关键词匹配（临时方案）
     */
    private boolean containsKeyword(DomainKnowledge dk, String query) {
        String content = (dk.getTitle() + " " + dk.getContent()).toLowerCase();
        String[] keywords = query.toLowerCase().split("\\s+");
        for (String keyword : keywords) {
            if (content.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 转换代码搜索结果
     */
    private SearchResult toSearchResult(VectorModels.SearchResult codeResult) {
        SearchResult result = new SearchResult();
        result.setType("code");
        result.setId(codeResult.getId());
        result.setTitle(codeResult.getClassName());
        result.setContent(codeResult.getSummary());
        result.setScore(codeResult.getScore());
        result.setMetadata(String.format("{\"path\":\"%s\",\"type\":\"%s\"}",
                codeResult.getRelativePath(), codeResult.getDocType()));
        return result;
    }

    /**
     * 转换领域知识搜索结果
     */
    private SearchResult toSearchResult(DomainKnowledge dk) {
        SearchResult result = new SearchResult();
        result.setType("knowledge");
        result.setId(dk.getId());
        result.setTitle(dk.getTitle());
        result.setContent(dk.getContent());
        result.setScore(0.8); // 临时固定分数
        result.setMetadata(String.format("{\"projectKey\":\"%s\"}", dk.getProjectKey()));
        return result;
    }
}
