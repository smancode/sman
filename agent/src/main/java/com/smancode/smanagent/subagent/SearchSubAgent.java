package com.smancode.smanagent.subagent;

import com.fasterxml.jackson.databind.JsonNode;
import com.smancode.smanagent.smancode.llm.LlmService;
import com.smancode.smanagent.tools.ToolResult;
import com.smancode.smanagent.tools.knowledge.KnowledgeGraphClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Search SubAgent
 * <p>
 * 智能搜索助手，具备独立 LLM 循环，能够：
 * 1. 理解业务需求和代码查询
 * 2. 调用知识图谱获取业务背景
 * 3. 双向查找（业务 ↔ 代码）
 * 4. 综合推理并返回结构化答案
 * <p>
 * 这是系统的核心入口，大多数情况下用户只需要调用 search 即可。
 */
@Service
public class SearchSubAgent {

    private static final Logger logger = LoggerFactory.getLogger(SearchSubAgent.class);

    @Autowired
    private LlmService llmService;

    @Autowired(required = false)
    private KnowledgeGraphClient knowledgeGraphClient;

    /**
     * 执行智能搜索
     *
     * @param projectKey 项目标识
     * @param query      用户查询（业务需求或代码查询）
     * @return 搜索结果
     */
    public SearchResult search(String projectKey, String query) {
        return search(projectKey, query, null);
    }

    /**
     * 执行智能搜索（带回调）
     *
     * @param projectKey 项目标识
     * @param query      用户查询（业务需求或代码查询）
     * @param partPusher Part 推送器（用于流式输出，可选）
     * @return 搜索结果
     */
    public SearchResult search(String projectKey, String query, java.util.function.Consumer<String> partPusher) {
        logger.info("SearchSubAgent 开始: projectKey={}, query={}", projectKey, query);

        long startTime = System.currentTimeMillis();

        try {
            // ==================== Step 1: 分析查询类型 ====================
            QueryType queryType = analyzeQuery(query);
            logger.info("查询类型: {}", queryType);

            // ==================== Step 2: 收集信息 ====================
            StringBuilder context = new StringBuilder();

            // 从知识图谱获取业务背景
            if (queryType == QueryType.BUSINESS_REQUIREMENT && knowledgeGraphClient != null) {
                KnowledgeGraphClient.BusinessContext businessContext =
                        knowledgeGraphClient.searchBusinessContext(projectKey, query);
                if (businessContext != null) {
                    context.append("## 业务背景\n");
                    context.append("项目: ").append(businessContext.getProjectName()).append("\n");
                    context.append("描述: ").append(businessContext.getDescription()).append("\n");
                    if (businessContext.getDomains() != null) {
                        context.append("领域: ").append(businessContext.getDomains()).append("\n");
                    }
                    context.append("\n");
                }

                // TODO: 根据查询内容匹配相关业务知识
                // 暂时跳过
            }

            // TODO: 根据查询类型搜索代码
            // 这里应该调用向量搜索或 find_file 来查找相关代码
            // 暂时使用占位符
            context.append("## 代码信息\n");
            context.append("(代码搜索功能待实现，需要集成向量搜索)\n\n");

            // ==================== Step 3: 综合推理 ====================
            String synthesisPrompt = buildSynthesisPrompt(query, context.toString());
            String systemPrompt = buildSynthesisSystemPrompt();

            String response = llmService.jsonRequest(systemPrompt, synthesisPrompt).asText();

            long duration = System.currentTimeMillis() - startTime;
            logger.info("SearchSubAgent 完成: 耗时={}ms", duration);

            return parseResult(response, duration);

        } catch (Exception e) {
            logger.error("SearchSubAgent 失败", e);
            return SearchResult.error("搜索失败: " + e.getMessage());
        }
    }

    /**
     * 分析查询类型
     */
    private QueryType analyzeQuery(String query) {
        // 简单启发式判断
        if (query.contains("Service") || query.contains("Controller") ||
            query.matches(".*[A-Z][a-z]+[A-Z].*")) {
            return QueryType.CODE_QUERY;
        }
        if (query.contains("是干啥的") || query.contains("是做什么的") ||
            query.contains("怎么用") || query.contains("实现")) {
            return QueryType.CODE_QUERY;
        }
        return QueryType.BUSINESS_REQUIREMENT;
    }

    /**
     * 构建综合推理 Prompt
     */
    private String buildSynthesisPrompt(String query, String context) {
        return String.format("""
                ## 用户查询
                %s

                ## 上下文信息
                %s

                ## 任务
                请综合以上信息，回答用户的查询。如果查询的是业务需求，请给出：
                1. businessContext: 业务背景
                2. businessKnowledge: 业务知识列表
                3. codeEntries: 相关代码入口
                4. codeRelations: 代码关系说明

                如果查询的是代码，请给出：
                1. businessContext: 类的功能描述
                2. codeEntries: 类名
                3. codeRelations: 调用关系和系统角色
                """,
                query, context);
    }

    /**
     * 构建综合推理系统提示词
     */
    private String buildSynthesisSystemPrompt() {
        return """
                # Search 综合推理专家

                你是 SmanAgent 的核心搜索助手，负责综合业务知识和代码信息，为用户提供准确的答案。

                ## 输出格式

                请严格按照以下 JSON 格式输出：

                ```json
                {
                  "businessContext": "业务背景描述",
                  "businessKnowledge": ["业务规则1", "业务规则2"],
                  "codeEntries": [
                    {"className": "类名", "method": "方法名", "reason": "为什么相关"}
                  ],
                  "codeRelations": "代码关系说明",
                  "summary": "总结性回答"
                }
                ```

                ## 注意事项

                - 如果是业务需求查询，重点输出业务背景和代码入口
                - 如果是代码查询，重点输出类的作用和调用关系
                - 保持信息准确，不要编造
                """;
    }

    /**
     * 解析 LLM 返回的 JSON 结果
     */
    private SearchResult parseResult(String jsonResponse, long duration) {
        SearchResult result = new SearchResult();
        result.setExecutionTimeMs(duration);
        result.setRawResponse(jsonResponse);

        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            JsonNode json = mapper.readTree(jsonResponse);

            if (json.has("businessContext")) {
                result.setBusinessContext(json.get("businessContext").asText());
            }

            if (json.has("businessKnowledge") && json.get("businessKnowledge").isArray()) {
                java.util.List<String> knowledge = new java.util.ArrayList<>();
                for (JsonNode item : json.get("businessKnowledge")) {
                    knowledge.add(item.asText());
                }
                result.setBusinessKnowledge(knowledge);
            }

            if (json.has("codeEntries") && json.get("codeEntries").isArray()) {
                java.util.List<CodeEntry> entries = new java.util.ArrayList<>();
                for (JsonNode item : json.get("codeEntries")) {
                    CodeEntry entry = new CodeEntry();
                    if (item.has("className")) {
                        entry.setClassName(item.get("className").asText());
                    }
                    if (item.has("method")) {
                        entry.setMethod(item.get("method").asText());
                    }
                    if (item.has("reason")) {
                        entry.setReason(item.get("reason").asText());
                    }
                    entries.add(entry);
                }
                result.setCodeEntries(entries);
            }

            if (json.has("codeRelations")) {
                result.setCodeRelations(json.get("codeRelations").asText());
            }

            if (json.has("summary")) {
                result.setSummary(json.get("summary").asText());
            }

        } catch (Exception e) {
            logger.warn("解析搜索结果失败，使用原始响应", e);
            result.setSummary(jsonResponse);
        }

        return result;
    }

    // ==================== Inner Classes ====================

    /**
     * 查询类型
     */
    private enum QueryType {
        BUSINESS_REQUIREMENT,  // 业务需求
        CODE_QUERY             // 代码查询
    }

    /**
     * 搜索结果
     */
    public static class SearchResult {
        private String businessContext;
        private java.util.List<String> businessKnowledge;
        private java.util.List<CodeEntry> codeEntries;
        private String codeRelations;
        private String summary;
        private String rawResponse;
        private long executionTimeMs;
        private boolean error;
        private String errorMessage;

        public String getBusinessContext() {
            return businessContext;
        }

        public void setBusinessContext(String businessContext) {
            this.businessContext = businessContext;
        }

        public java.util.List<String> getBusinessKnowledge() {
            return businessKnowledge;
        }

        public void setBusinessKnowledge(java.util.List<String> businessKnowledge) {
            this.businessKnowledge = businessKnowledge;
        }

        public java.util.List<CodeEntry> getCodeEntries() {
            return codeEntries;
        }

        public void setCodeEntries(java.util.List<CodeEntry> codeEntries) {
            this.codeEntries = codeEntries;
        }

        public String getCodeRelations() {
            return codeRelations;
        }

        public void setCodeRelations(String codeRelations) {
            this.codeRelations = codeRelations;
        }

        public String getSummary() {
            return summary;
        }

        public void setSummary(String summary) {
            this.summary = summary;
        }

        public String getRawResponse() {
            return rawResponse;
        }

        public void setRawResponse(String rawResponse) {
            this.rawResponse = rawResponse;
        }

        public long getExecutionTimeMs() {
            return executionTimeMs;
        }

        public void setExecutionTimeMs(long executionTimeMs) {
            this.executionTimeMs = executionTimeMs;
        }

        public boolean isError() {
            return error;
        }

        public void setError(boolean error) {
            this.error = error;
        }

        public String getErrorMessage() {
            return errorMessage;
        }

        public void setErrorMessage(String errorMessage) {
            this.errorMessage = errorMessage;
        }

        public static SearchResult error(String message) {
            SearchResult result = new SearchResult();
            result.setError(true);
            result.setErrorMessage(message);
            return result;
        }
    }

    /**
     * 代码入口
     */
    public static class CodeEntry {
        private String className;
        private String method;
        private String reason;

        public String getClassName() {
            return className;
        }

        public void setClassName(String className) {
            this.className = className;
        }

        public String getMethod() {
            return method;
        }

        public void setMethod(String method) {
            this.method = method;
        }

        public String getReason() {
            return reason;
        }

        public void setReason(String reason) {
            this.reason = reason;
        }
    }
}
