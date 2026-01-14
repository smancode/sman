package com.smancode.smanagent.tools.search;

import com.smancode.smanagent.models.VectorModels.SemanticSearchRequest;
import com.smancode.smanagent.models.VectorModels.SearchResult;
import com.smancode.smanagent.tools.AbstractTool;
import com.smancode.smanagent.tools.ParameterDef;
import com.smancode.smanagent.tools.Tool;
import com.smancode.smanagent.tools.ToolResult;
import com.smancode.smanagent.vector.VectorSearchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 语义搜索工具
 * <p>
 * 根据语义相似性搜索代码片段。
 * 执行模式：local（后端执行）
 */
@Component
public class SemanticSearchTool extends AbstractTool implements Tool {

    private static final Logger logger = LoggerFactory.getLogger(SemanticSearchTool.class);

    @Autowired(required = false)
    private VectorSearchService vectorSearchService;

    @Override
    public String getName() {
        return "semantic_search";
    }

    @Override
    public String getDescription() {
        return "根据语义相似性搜索代码片段";
    }

    @Override
    public Map<String, ParameterDef> getParameters() {
        Map<String, ParameterDef> params = new HashMap<>();
        params.put("query", new ParameterDef("query", String.class, true, "搜索查询"));
        params.put("topK", new ParameterDef("topK", Integer.class, false, "返回结果数量（默认 10）", 10));
        params.put("mode", new ParameterDef("mode", String.class, false, "执行模式：local/intellij", "local"));
        return params;
    }

    @Override
    public ToolResult execute(String projectKey, Map<String, Object> params) {
        long startTime = System.currentTimeMillis();

        try {
            // 获取参数
            String query = (String) params.get("query");
            if (query == null || query.trim().isEmpty()) {
                return ToolResult.failure("缺少 query 参数");
            }

            int topK = getOptInt(params, "topK", 10);
            if (topK <= 0) {
                return ToolResult.failure("topK 必须大于 0");
            }

            logger.info("执行语义搜索: projectKey={}, query={}, topK={}", projectKey, query, topK);

            // 检查向量搜索服务是否可用
            if (vectorSearchService == null) {
                String displayContent = String.format(
                    "向量搜索服务未启用\n" +
                    "参数：projectKey=%s, query=%s, topK=%d",
                    projectKey, query, topK
                );
                ToolResult toolResult = ToolResult.success(null, "语义搜索（服务未启用）", displayContent);
                toolResult.setExecutionTimeMs(System.currentTimeMillis() - startTime);
                return toolResult;
            }

            // 构建搜索请求
            SemanticSearchRequest request = new SemanticSearchRequest();
            request.setProjectKey(projectKey);
            request.setRecallQuery(query);
            request.setRerankQuery(query);
            request.setRecallTopK(topK);
            request.setRerankTopN(topK);
            request.setEnableReranker(false);

            // 执行搜索
            List<SearchResult> results = vectorSearchService.semanticSearch(request);

            // 构建结果
            StringBuilder resultContent = new StringBuilder();
            if (results.isEmpty()) {
                resultContent.append("未找到匹配结果\n");
                resultContent.append("查询: ").append(query);
            } else {
                resultContent.append("找到 ").append(results.size()).append(" 个结果:\n\n");
                for (int i = 0; i < results.size(); i++) {
                    SearchResult r = results.get(i);
                    resultContent.append(String.format("%d. %s (score=%.3f)\n", i + 1, r.getClassName(), r.getScore()));
                    if (r.getSummary() != null) {
                        resultContent.append("   ").append(r.getSummary()).append("\n");
                    }
                    resultContent.append("\n");
                }
            }

            long duration = System.currentTimeMillis() - startTime;
            logger.info("语义搜索完成: projectKey={}, 结果数={}, 耗时={}ms", projectKey, results.size(), duration);

            ToolResult toolResult = ToolResult.success(resultContent.toString(), "语义搜索结果", null);
            toolResult.setExecutionTimeMs(duration);
            return toolResult;

        } catch (Exception e) {
            logger.error("语义搜索失败", e);
            long duration = System.currentTimeMillis() - startTime;
            ToolResult toolResult = ToolResult.failure("搜索失败: " + e.getMessage());
            toolResult.setExecutionTimeMs(duration);
            return toolResult;
        }
    }

    @Override
    public ExecutionMode getExecutionMode(Map<String, Object> params) {
        // semantic_search 固定为 local 模式
        return ExecutionMode.LOCAL;
    }
}
