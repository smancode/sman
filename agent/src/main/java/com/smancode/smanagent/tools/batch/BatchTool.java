package com.smancode.smanagent.tools.batch;

import com.smancode.smanagent.tools.AbstractTool;
import com.smancode.smanagent.tools.BatchSubResult;
import com.smancode.smanagent.tools.ParameterDef;
import com.smancode.smanagent.tools.SessionAwareTool;
import com.smancode.smanagent.tools.Tool;
import com.smancode.smanagent.tools.ToolExecutor;
import com.smancode.smanagent.tools.ToolRegistry;
import com.smancode.smanagent.tools.ToolResult;
import com.smancode.smanagent.util.StackTraceUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 批量执行工具
 * <p>
 * 串行执行多个工具调用，确保对同一文件的修改不会冲突。
 * 执行模式：local（后端执行）
 * <p>
 * 使用场景：
 * - 读取多个文件
 * - 多个对同一文件的 apply_change
 * - grep + find + read 组合
 * <p>
 * 限制：
 * - 最多 10 个工具调用
 * - 不能嵌套使用（batch 内不能有 batch）
 * - 工具串行执行，避免并发冲突
 */
@Component
public class BatchTool extends AbstractTool implements Tool, SessionAwareTool {

    private static final Logger logger = LoggerFactory.getLogger(BatchTool.class);
    private static final int SESSION_ID_MASK_LENGTH = 8;

    /**
     * 禁止在 batch 中使用的工具
     */
    private static final Set<String> DISALLOWED_TOOLS = Set.of("batch");

    /**
     * 每次批量执行的最大工具数量
     */
    private static final int MAX_BATCH_SIZE = 10;

    @Lazy
    @Autowired
    private ToolRegistry toolRegistry;

    @Autowired
    private ToolExecutor toolExecutor;

    /**
     * WebSocket Session ID（用于转发子工具调用到 IDE）
     */
    private volatile String webSocketSessionId;

    /**
     * 遮蔽 Session ID（用于日志输出）
     *
     * @param sessionId Session ID，可能为 null
     * @return 掩盖后的字符串，例如 "01234567..." 或 "null"
     */
    private static String maskSessionId(String sessionId) {
        if (sessionId == null) {
            return "null";
        }
        int length = Math.min(sessionId.length(), SESSION_ID_MASK_LENGTH);
        return sessionId.substring(0, length) + "...";
    }

    /**
     * 获取工具名称
     *
     * @return 工具名称
     */
    @Override
    public String getName() {
        return "batch";
    }

    /**
     * 获取工具描述
     *
     * @return 工具描述信息
     */
    @Override
    public String getDescription() {
        return """
                Executes multiple tool calls in sequence to avoid file modification conflicts.

                **IMPORTANT**: batch does NOT return file contents to LLM. Only use for multiple edits on the SAME file.

                **Payload Format (JSON array)**:
                [{"tool": "apply_change", "parameters": {"relativePath": "A.java", ...}}, {"tool": "apply_change", "parameters": {"relativePath": "A.java", ...}}]

                **Notes**:
                - 1–10 tool calls per batch
                - Calls are executed sequentially to avoid conflicts
                - Partial failures do NOT stop other tool calls
                - Do NOT use batch within another batch
                - Does NOT return detailed results to LLM (only summary status)

                **Good Use Cases**:
                - Multiple edits on the same file (PRIMARY USE CASE - ensures sequential execution)

                **When NOT to Use**:
                - Reading multiple files → Call read_file separately for each file
                - Operations that depend on prior tool output (use separate calls instead)
                - Independent operations on different files (just call them separately)

                **Why NOT use batch for reading files?**
                - batch does NOT return file contents to LLM (you won't see the data)
                - read_file operations have no dependencies, so no need for batch
                - Call read_file separately to get actual file contents
                """;
    }

    /**
     * 获取工具参数定义
     *
     * @return 参数定义 Map
     */
    @Override
    public Map<String, ParameterDef> getParameters() {
        Map<String, ParameterDef> params = new HashMap<>();
        params.put("tool_calls",
            new ParameterDef("tool_calls", List.class, true,
                "Array of tool calls to execute sequentially. Each item: {\"tool\": \"tool_name\", \"parameters\": {...}}"));
        return params;
    }

    /**
     * 获取执行模式（强制本地执行）
     *
     * @param params 参数
     * @return 始终返回 LOCAL（batch 工具必须由后端执行）
     */
    @Override
    public Tool.ExecutionMode getExecutionMode(Map<String, Object> params) {
        return Tool.ExecutionMode.LOCAL;
    }

    /**
     * 设置 WebSocket Session ID
     * <p>
     * 由 {@link ToolExecutor#executeWithSession} 在工具执行前调用。
     * BatchTool 使用此 Session ID 将其内部的子工具调用转发到 IDE 执行。
     * <p>
     * 注意：此字段在执行完成后会被清理，防止 Session ID 泄漏到其他请求。
     *
     * @param sessionId WebSocket Session ID，可能为 null
     */
    @Override
    public void setWebSocketSessionId(String sessionId) {
        this.webSocketSessionId = sessionId;
        logger.info("BatchTool 收到 WebSocket Session ID: {}", maskSessionId(sessionId));
    }

    /**
     * 执行批量工具调用
     *
     * @param projectKey 项目标识
     * @param params     参数 Map，包含 tool_calls 数组
     * @return 工具执行结果
     */
    @Override
    @SuppressWarnings("unchecked")
    public ToolResult execute(String projectKey, Map<String, Object> params) {
        long startTime = System.currentTimeMillis();
        logger.info("BatchTool.execute called: projectKey={}, params={}", projectKey, params);

        // 详细日志：检查 tool_calls 的实际类型和值
        Object toolCallsObj = params.get("tool_calls");
        String typeName = toolCallsObj != null ? toolCallsObj.getClass().getSimpleName() : "null";
        logger.info("【Batch参数解析】tool_calls类型={}, 值={}, class={}",
                toolCallsObj != null ? toolCallsObj.getClass().getName() : "null",
                toolCallsObj,
                typeName);

        try {
            // 1. 解析并验证 tool_calls 参数
            List<Map<String, Object>> toolCalls = parseToolCalls(params);
            if (toolCalls == null) {
                return ToolResult.failure("缺少 tool_calls 参数");
            }

            // 2. 限制最多执行 MAX_BATCH_SIZE 个并记录丢弃数量
            List<Map<String, Object>> validCalls = toolCalls.stream()
                .limit(MAX_BATCH_SIZE)
                .collect(Collectors.toList());

            int discardedCount = toolCalls.size() - validCalls.size();
            if (discardedCount > 0) {
                logger.info("丢弃了 {} 个工具调用（超过限制）", discardedCount);
            }

            // 3. 串行执行所有工具调用
            // 注意：使用串行执行是为了避免对同一文件的并发修改冲突
            // 特别是 apply_change 工具，如果并行执行多个对同一文件的修改，会导致文件结构混乱
            List<BatchSubResult> subResults = new ArrayList<>();
            int index = 0;
            for (Map<String, Object> call : validCalls) {
                try {
                    BatchSubResult result = executeSingleTool(projectKey, call, index);
                    subResults.add(result);
                } catch (Exception e) {
                    logger.error("批量子工具执行异常: index={}, {}", index, StackTraceUtils.formatStackTrace(e));
                    subResults.add(new BatchSubResult("unknown", false, null,
                        "执行异常: " + e.getMessage()));
                }
                index++;
            }

            // 4. 统计并构建汇总结果
            long successCount = subResults.stream().filter(BatchSubResult::isSuccess).count();
            long failedCount = subResults.size() - successCount;

            // 简化显示：只显示文件名和工具数量（作为结果，不是括号内容）
            String displayContent = buildSimpleDisplayContent(subResults);

            ToolResult result = new ToolResult();
            result.setSuccess(true);
            result.setDisplayTitle(String.format("批量执行 (%d/%d 成功)", successCount, subResults.size()));
            result.setDisplayContent(displayContent);
            result.setBatchSubResults(subResults);
            result.setExecutionTimeMs(System.currentTimeMillis() - startTime);

            // 5. 设置元数据
            Map<String, Object> metadata = new ConcurrentHashMap<>();
            metadata.put("totalCalls", subResults.size());
            metadata.put("successful", successCount);
            metadata.put("failed", failedCount);
            metadata.put("tools", subResults.stream()
                .map(BatchSubResult::getToolName)
                .collect(Collectors.toList()));
            result.setMetadata(metadata);

            logger.info("批量工具执行完成: 总数={}, 成功={}, 失败={}, 耗时={}ms",
                subResults.size(), successCount, failedCount, result.getExecutionTimeMs());

            return result;

        } catch (Exception e) {
            logger.error("批量工具执行失败: {}", StackTraceUtils.formatStackTrace(e));
            ToolResult result = ToolResult.failure("批量执行失败: " + e.getMessage());
            result.setExecutionTimeMs(System.currentTimeMillis() - startTime);
            return result;
        } finally {
            // 清理 Session ID，防止泄漏到下一个请求
            this.webSocketSessionId = null;
        }
    }

    /**
     * 执行单个工具调用
     *
     * @param projectKey 项目标识
     * @param call       工具调用信息
     * @param index      索引（用于日志）
     * @return 子结果
     */
    @SuppressWarnings("unchecked")
    private BatchSubResult executeSingleTool(String projectKey, Map<String, Object> call, int index) {
        String toolName = (String) call.get("tool");
        Map<String, Object> parameters = (Map<String, Object>) call.get("parameters");

        logger.info("执行批量子工具 [{}/{}]: toolName={}, parameters={}, wsSessionId={}",
            index + 1, MAX_BATCH_SIZE, toolName, parameters, maskSessionId(webSocketSessionId));

        try {
            // 1. 检查是否是禁止的工具
            if (DISALLOWED_TOOLS.contains(toolName)) {
                return failBatchSubResult(toolName, String.format("工具 '%s' 不允许在 batch 中使用", toolName));
            }

            // 2. 获取工具
            Tool tool = toolRegistry.getTool(toolName);
            if (tool == null) {
                return failBatchSubResult(toolName, String.format("工具 '%s' 不存在", toolName));
            }

            // 3. 执行工具（使用 toolExecutor 以支持 INTELLIJ 模式的工具）
            ToolResult result;
            if (webSocketSessionId != null) {
                result = toolExecutor.executeWithSession(toolName, projectKey, parameters, webSocketSessionId);
            } else {
                // 降级：没有 wsSessionId 时直接调用（可能无法正确执行 INTELLIJ 模式工具）
                logger.warn("BatchTool 缺少 wsSessionId，子工具 {} 可能无法正确执行", toolName);
                result = tool.execute(projectKey, parameters);
            }

            // 4. 返回结果
            return new BatchSubResult(toolName, result.isSuccess(), result,
                result.isSuccess() ? null : result.getError());

        } catch (Exception e) {
            logger.error("执行批量子工具失败: toolName={}, {}", toolName, StackTraceUtils.formatStackTrace(e));
            return new BatchSubResult(toolName, false, null,
                "执行失败: " + e.getMessage());
        }
    }

    /**
     * 创建失败的工具调用结果
     *
     * @param toolName 工具名称
     * @param error    错误信息
     * @return 失败的 BatchSubResult
     */
    private BatchSubResult failBatchSubResult(String toolName, String error) {
        logger.warn(error);
        return new BatchSubResult(toolName, false, null, error);
    }

    /**
     * 解析 tool_calls 参数
     *
     * @param params 参数 Map
     * @return 工具调用列表，如果解析失败返回 null
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parseToolCalls(Map<String, Object> params) {
        Object toolCallsObj = params.get("tool_calls");
        if (toolCallsObj == null) {
            return null;
        }

        if (!(toolCallsObj instanceof List)) {
            logger.error("tool_calls 参数类型错误，应为数组，实际类型: {}",
                toolCallsObj.getClass().getSimpleName());
            return null;
        }

        List<Map<String, Object>> toolCalls = (List<Map<String, Object>>) toolCallsObj;
        if (toolCalls.isEmpty()) {
            logger.error("tool_calls 数组不能为空");
            return null;
        }

        return toolCalls;
    }

    /**
     * 格式化汇总头部信息
     *
     * @param successCount 成功数量
     * @param total       总数量
     * @return 格式化的头部字符串
     */
    private String formatSummaryHeader(long successCount, int total) {
        return String.format("批量执行完成：%d/%d 成功", successCount, total);
    }

    /**
     * 构建简化的显示内容
     * <p>
     * 格式：文件名, N个工具 或 N个工具
     * <p>
     * 前端会渲染为：⏺ batch(文件名, N个工具)
     *
     * @param results 执行结果列表
     * @return 简化的显示字符串
     */
    private String buildSimpleDisplayContent(List<BatchSubResult> results) {
        // 提取文件名（从第一个 apply_change 的参数中）
        String fileName = extractFileNameFromResults(results);

        if (fileName != null) {
            return String.format("%s, %d个工具", fileName, results.size());
        } else {
            return String.format("%d个工具", results.size());
        }
    }

    /**
     * 从结果列表中提取文件名
     *
     * @param results 执行结果列表
     * @return 文件名，如果没有找到则返回 null
     */
    private String extractFileNameFromResults(List<BatchSubResult> results) {
        for (BatchSubResult result : results) {
            if ("apply_change".equals(result.getToolName()) &&
                result.getResult() != null &&
                result.getResult().getRelativePath() != null) {

                String path = result.getResult().getRelativePath();
                // 提取文件名（不含路径）
                int lastSlash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
                if (lastSlash >= 0 && lastSlash < path.length() - 1) {
                    return path.substring(lastSlash + 1);
                }
                return path;
            }
        }
        return null;
    }
}
