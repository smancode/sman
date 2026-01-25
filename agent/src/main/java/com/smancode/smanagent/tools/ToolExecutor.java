package com.smancode.smanagent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.smancode.smanagent.util.StackTraceUtils;
import com.smancode.smanagent.websocket.ToolForwardingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 工具执行器
 * <p>
 * 负责执行工具调用，处理 local 和 intellij 两种模式。
 */
@Service
public class ToolExecutor {

    private static final Logger logger = LoggerFactory.getLogger(ToolExecutor.class);
    private static final int SESSION_ID_MASK_LENGTH = 8;

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

    @Lazy
    @Autowired
    private ToolRegistry toolRegistry;

    @Autowired(required = false)
    private ToolForwardingService toolForwardingService;

    /**
     * 执行工具
     *
     * @param toolName   工具名称
     * @param projectKey 项目标识
     * @param params     参数
     * @return 执行结果
     */
    public ToolResult execute(String toolName, String projectKey, Map<String, Object> params) {
        long startTime = System.currentTimeMillis();
        ToolResult result;

        try {
            // 1. 查找工具
            Tool tool = toolRegistry.getTool(toolName);
            if (tool == null) {
                logger.error("工具不存在: {}", toolName);
                return ToolResult.failure("工具不存在: " + toolName);
            }

            // 2. 确定执行模式并执行
            Tool.ExecutionMode mode = tool.getExecutionMode(params);
            logger.info("执行工具: toolName={}, projectKey={}, mode={}", toolName, projectKey, mode);

            result = mode == Tool.ExecutionMode.LOCAL
                ? executeLocal(tool, projectKey, params)
                : executeIntellij(tool, projectKey, params);

        } catch (Exception e) {
            logger.error("工具执行异常: toolName={}, {}", toolName, StackTraceUtils.formatStackTrace(e));
            result = ToolResult.failure("执行异常: " + e.getMessage());
        }

        // 3. 记录执行时长（统一处理，避免重复代码）
        result.setExecutionTimeMs(System.currentTimeMillis() - startTime);
        logger.info("工具执行完成: toolName={}, success={}, duration={}ms",
            toolName, result.isSuccess(), result.getExecutionTimeMs());

        return result;
    }

    /**
     * 本地执行工具
     *
     * @param tool       工具实例
     * @param projectKey 项目标识
     * @param params     参数
     * @return 执行结果
     */
    private ToolResult executeLocal(Tool tool, String projectKey, Map<String, Object> params) {
        logger.debug("本地执行工具: {}", tool.getName());
        return tool.execute(projectKey, params);
    }

    /**
     * IntelliJ 执行工具（通过 WebSocket 转发）
     *
     * @param tool       工具实例
     * @param projectKey 项目标识
     * @param params     参数
     * @return 执行结果
     */
    private ToolResult executeIntellij(Tool tool, String projectKey, Map<String, Object> params) {
        logger.debug("IntelliJ 执行工具: {}", tool.getName());

        // 检查转发服务是否可用
        if (toolForwardingService == null) {
            logger.warn("工具转发服务未启用，返回占位结果: {}", tool.getName());
            return tool.execute(projectKey, params);
        }

        // 检查是否应该转发
        if (!toolForwardingService.shouldForwardToIde(tool.getName())) {
            logger.info("工具 {} 不需要转发，本地执行", tool.getName());
            return tool.execute(projectKey, params);
        }

        // 没有 Session ID，无法转发
        logger.warn("WebSocket 转发需要 Session ID，暂时返回占位结果: {}", tool.getName());
        return tool.execute(projectKey, params);
    }

    /**
     * 带会话的执行工具（用于 IntelliJ 模式）
     *
     * @param toolName       工具名称
     * @param projectKey     项目标识
     * @param params         参数
     * @param webSocketSessionId WebSocket Session ID
     * @return 执行结果
     */
    public ToolResult executeWithSession(String toolName, String projectKey,
                                         Map<String, Object> params, String webSocketSessionId) {
        long startTime = System.currentTimeMillis();

        try {
            Tool tool = toolRegistry.getTool(toolName);
            if (tool == null) {
                return ToolResult.failure("工具不存在: " + toolName);
            }

            Tool.ExecutionMode mode = tool.getExecutionMode(params);

            logger.info("执行工具（带会话）: toolName={}, projectKey={}, mode={}, sessionId={}",
                toolName, projectKey, mode, maskSessionId(webSocketSessionId));

            ToolResult result;
            if (mode == Tool.ExecutionMode.LOCAL) {
                // 如果工具需要 Session ID（实现了 SessionAwareTool 接口），则传递给它
                if (tool instanceof SessionAwareTool sessionAwareTool) {
                    sessionAwareTool.setWebSocketSessionId(webSocketSessionId);
                }
                result = executeLocal(tool, projectKey, params);
            } else {
                result = executeIntellijWithSession(tool, projectKey, params, webSocketSessionId);
            }

            long duration = System.currentTimeMillis() - startTime;
            result.setExecutionTimeMs(duration);

            logger.info("工具执行完成: toolName={}, success={}, duration={}ms",
                toolName, result.isSuccess(), duration);

            return result;

        } catch (Exception e) {
            logger.error("工具执行异常: toolName={}, {}", toolName, StackTraceUtils.formatStackTrace(e));
            long duration = System.currentTimeMillis() - startTime;
            ToolResult result = ToolResult.failure("执行异常: " + e.getMessage());
            result.setExecutionTimeMs(duration);
            return result;
        }
    }

    /**
     * IntelliJ 执行工具（带 Session ID）
     */
    private ToolResult executeIntellijWithSession(Tool tool, String projectKey,
                                                   Map<String, Object> params, String webSocketSessionId) {
        logger.debug("IntelliJ 执行工具（带会话）: {}, sessionId={}",
            tool.getName(), webSocketSessionId != null ? webSocketSessionId.substring(0, 8) : "null");

        if (toolForwardingService == null || webSocketSessionId == null) {
            logger.warn("工具转发服务未启用或 Session ID 为空，返回占位结果: {}", tool.getName());
            return tool.execute(projectKey, params);
        }

        try {
            // 通过 WebSocket 转发到 IDE
            JsonNode result = toolForwardingService.forwardToolCall(
                webSocketSessionId,
                tool.getName(),
                params
            );

            // 转换结果
            boolean success = result.has("success") && result.get("success").asBoolean();
            String content = result.has("result") ? result.get("result").asText() : null;
            String error = result.has("error") ? result.get("error").asText() : null;

            logger.info("【IDE返回原始JSON】success={}, has relativePath={}, has relatedFilePaths={}, has metadata={}",
                success, result.has("relativePath"), result.has("relatedFilePaths"), result.has("metadata"));
            if (result.has("relativePath")) {
                logger.info("【IDE返回relativePath】{}", result.get("relativePath").asText());
            }
            if (result.has("metadata") && result.get("metadata").isObject()) {
                JsonNode metadata = result.get("metadata");
                logger.info("【IDE返回metadata】has changeSummary={}, has description={}, has searchContent={}",
                        metadata.has("changeSummary"), metadata.has("description"), metadata.has("searchContent"));
                if (metadata.has("changeSummary")) {
                    logger.info("【IDE返回changeSummary】{}", metadata.get("changeSummary").asText());
                }
                if (metadata.has("description")) {
                    logger.info("【IDE返回description】{}", metadata.get("description").asText());
                }
            }

            if (success) {
                // 使用 IDE 返回的 result 作为 displayContent
                ToolResult toolResult = ToolResult.success(content, tool.getName() + " 结果", content);

                // 新增：解析 IDE 返回的额外字段
                if (result.has("relativePath")) {
                    toolResult.setRelativePath(result.get("relativePath").asText());
                }

                // 新增：解析 relatedFilePaths
                if (result.has("relatedFilePaths") && result.get("relatedFilePaths").isArray()) {
                    JsonNode filesArray = result.get("relatedFilePaths");
                    java.util.List<String> filePaths = new java.util.ArrayList<>();
                    for (JsonNode fileNode : filesArray) {
                        filePaths.add(fileNode.asText());
                    }
                    toolResult.setRelatedFilePaths(filePaths);
                }

                // 新增：解析 metadata
                if (result.has("metadata") && result.get("metadata").isObject()) {
                    JsonNode metadataNode = result.get("metadata");
                    java.util.Map<String, Object> metadata = new com.fasterxml.jackson.databind.ObjectMapper().convertValue(
                        metadataNode,
                        java.util.Map.class
                    );
                    toolResult.setMetadata(metadata);
                }

                // 如果有 relativePath，更新 displayTitle
                if (toolResult.getRelativePath() != null) {
                    toolResult.setDisplayTitle(toolResult.getRelativePath());
                }

                return toolResult;
            } else {
                // 失败时，优先使用 error 字段，否则使用 result 字段（前端可能把错误信息放在 result 中）
                String errorMessage = error != null ? error : content;
                if (errorMessage == null || errorMessage.trim().isEmpty()) {
                    errorMessage = "执行失败";
                }
                logger.info("【工具执行失败】toolName={}, errorMessage={}", tool.getName(), errorMessage);
                return ToolResult.failure(errorMessage);
            }

        } catch (Exception e) {
            logger.error("转发工具执行失败: {}", tool.getName(), e);
            return ToolResult.failure("转发失败: " + e.getMessage());
        }
    }

    /**
     * 验证工具参数
     *
     * @param toolName 工具名称
     * @param params   参数
     * @return 验证结果
     */
    public ValidationResult validateParameters(String toolName, Map<String, Object> params) {
        Tool tool = toolRegistry.getTool(toolName);
        if (tool == null) {
            return new ValidationResult(false, "工具不存在: " + toolName);
        }

        Map<String, ParameterDef> requiredParams = tool.getParameters();
        if (requiredParams == null || requiredParams.isEmpty()) {
            return new ValidationResult(true, "无参数要求");
        }

        // 检查必需参数
        for (Map.Entry<String, ParameterDef> entry : requiredParams.entrySet()) {
            ParameterDef def = entry.getValue();
            if (def.isRequired()) {
                Object value = params.get(entry.getKey());
                if (value == null) {
                    return new ValidationResult(
                        false,
                        "缺少必需参数: " + entry.getKey()
                    );
                }
            }
        }

        return new ValidationResult(true, "参数验证通过");
    }

    /**
     * 参数验证结果
     */
    public static class ValidationResult {
        private final boolean valid;
        private final String message;

        public ValidationResult(boolean valid, String message) {
            this.valid = valid;
            this.message = message;
        }

        public boolean isValid() {
            return valid;
        }

        public String getMessage() {
            return message;
        }
    }
}
