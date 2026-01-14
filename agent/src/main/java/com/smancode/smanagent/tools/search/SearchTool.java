package com.smancode.smanagent.tools.search;

import com.smancode.smanagent.model.search.SearchResult;
import com.smancode.smanagent.repository.DomainKnowledgeRepository;
import com.smancode.smanagent.service.SearchService;
import com.smancode.smanagent.subagent.SearchSubAgent;
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
 * 统一搜索工具（Search SubAgent 入口）
 * <p>
 * 这是 SmanAgent 的核心工具，具备智能搜索能力：
 * - 理解业务需求和代码查询
 * - 调用知识图谱获取业务背景
 * - 双向查找（业务 ↔ 代码）
 * - 综合推理并返回结构化答案
 * <p>
 * 参数：
 * - query: 搜索查询（必填）- 可以是业务需求或代码查询
 * <p>
 * 执行模式：local（后端执行，内部使用 SubAgent）
 */
@Component
public class SearchTool extends AbstractTool implements Tool {

    private static final Logger logger = LoggerFactory.getLogger(SearchTool.class);

    @Autowired
    private SearchService searchService;

    @Autowired(required = false)
    private SearchSubAgent searchSubAgent;

    @Override
    public String getName() {
        return "search";
    }

    @Override
    public String getDescription() {
        return "智能搜索工具（SubAgent），理解业务需求并返回业务背景+代码入口";
    }

    @Override
    public Map<String, ParameterDef> getParameters() {
        Map<String, ParameterDef> params = new HashMap<>();
        params.put("query", new ParameterDef("query", String.class, true, "搜索查询（业务需求或代码查询）"));
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

            logger.info("执行智能搜索: projectKey={}, query={}", projectKey, query);

            // 如果 SearchSubAgent 可用，使用智能搜索
            if (searchSubAgent != null) {
                SearchSubAgent.SearchResult subAgentResult = searchSubAgent.search(
                        projectKey, query, null
                );

                long duration = System.currentTimeMillis() - startTime;

                if (subAgentResult.isError()) {
                    ToolResult toolResult = ToolResult.failure(subAgentResult.getErrorMessage());
                    toolResult.setExecutionTimeMs(duration);
                    return toolResult;
                }

                // 构建 ToolResult
                StringBuilder resultContent = new StringBuilder();
                if (subAgentResult.getBusinessContext() != null) {
                    resultContent.append("## 业务背景\n");
                    resultContent.append(subAgentResult.getBusinessContext()).append("\n\n");
                }

                if (subAgentResult.getBusinessKnowledge() != null && !subAgentResult.getBusinessKnowledge().isEmpty()) {
                    resultContent.append("## 业务知识\n");
                    for (String knowledge : subAgentResult.getBusinessKnowledge()) {
                        resultContent.append("- ").append(knowledge).append("\n");
                    }
                    resultContent.append("\n");
                }

                if (subAgentResult.getCodeEntries() != null && !subAgentResult.getCodeEntries().isEmpty()) {
                    resultContent.append("## 代码入口\n");
                    for (SearchSubAgent.CodeEntry entry : subAgentResult.getCodeEntries()) {
                        resultContent.append(String.format("- %s", entry.getClassName()));
                        if (entry.getMethod() != null) {
                            resultContent.append(String.format(".%s()", entry.getMethod()));
                        }
                        if (entry.getReason() != null) {
                            resultContent.append(String.format(" (%s)", entry.getReason()));
                        }
                        resultContent.append("\n");
                    }
                    resultContent.append("\n");
                }

                if (subAgentResult.getCodeRelations() != null) {
                    resultContent.append("## 代码关系\n");
                    resultContent.append(subAgentResult.getCodeRelations()).append("\n\n");
                }

                if (subAgentResult.getSummary() != null) {
                    resultContent.append("## 总结\n");
                    resultContent.append(subAgentResult.getSummary()).append("\n");
                }

                ToolResult toolResult = ToolResult.success(
                        resultContent.toString(),
                        "智能搜索结果",
                        subAgentResult.getRawResponse()
                );
                toolResult.setExecutionTimeMs(duration);
                return toolResult;
            }

            // 降级：使用传统搜索服务
            logger.info("SearchSubAgent 未启用，使用传统搜索服务");
            return fallbackToTraditionalSearch(projectKey, query, startTime);

        } catch (Exception e) {
            logger.error("搜索失败", e);
            long duration = System.currentTimeMillis() - startTime;
            ToolResult toolResult = ToolResult.failure("搜索失败: " + e.getMessage());
            toolResult.setExecutionTimeMs(duration);
            return toolResult;
        }
    }

    /**
     * 降级到传统搜索服务
     */
    private ToolResult fallbackToTraditionalSearch(String projectKey, String query, long startTime) {
        try {
            List<SearchResult> results = searchService.search(
                    query, projectKey, 10, SearchService.SearchType.BOTH
            );

            StringBuilder resultContent = new StringBuilder();
            resultContent.append("[传统搜索模式]\n\n");

            if (results.isEmpty()) {
                resultContent.append("未找到匹配结果\n");
                resultContent.append("查询: ").append(query);
            } else {
                resultContent.append("找到 ").append(results.size()).append(" 个结果:\n\n");

                for (int i = 0; i < results.size(); i++) {
                    SearchResult r = results.get(i);
                    resultContent.append(String.format("%d. [%s] %s (score=%.2f)\n",
                            i + 1, r.getType(), r.getTitle(), r.getScore()));

                    if (r.getContent() != null && !r.getContent().isEmpty()) {
                        String content = r.getContent();
                        if (content.length() > 200) {
                            content = content.substring(0, 200) + "...";
                        }
                        resultContent.append("   ").append(content).append("\n");
                    }
                    resultContent.append("\n");
                }
            }

            long duration = System.currentTimeMillis() - startTime;
            ToolResult toolResult = ToolResult.success(resultContent.toString(), "搜索结果", null);
            toolResult.setExecutionTimeMs(duration);
            return toolResult;

        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            ToolResult toolResult = ToolResult.failure("搜索失败: " + e.getMessage());
            toolResult.setExecutionTimeMs(duration);
            return toolResult;
        }
    }

    @Override
    public ExecutionMode getExecutionMode(Map<String, Object> params) {
        return ExecutionMode.LOCAL;
    }
}
