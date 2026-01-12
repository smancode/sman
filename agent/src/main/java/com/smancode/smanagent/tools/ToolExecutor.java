package com.smancode.smanagent.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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

    @Autowired
    private ToolRegistry toolRegistry;

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

        try {
            // 1. 查找工具
            Tool tool = toolRegistry.getTool(toolName);
            if (tool == null) {
                logger.error("工具不存在: {}", toolName);
                return ToolResult.failure("工具不存在: " + toolName);
            }

            // 2. 确定执行模式
            Tool.ExecutionMode mode = tool.getExecutionMode(params);

            logger.info("执行工具: toolName={}, projectKey={}, mode={}",
                toolName, projectKey, mode);

            // 3. 根据 mode 执行
            ToolResult result;
            if (mode == Tool.ExecutionMode.LOCAL) {
                // 本地执行
                result = executeLocal(tool, projectKey, params);
            } else {
                // IntelliJ 执行（需要通过 WebSocket 转发到 IDE）
                result = executeIntellij(tool, projectKey, params);
            }

            // 4. 记录执行时长
            long duration = System.currentTimeMillis() - startTime;
            result.setExecutionTimeMs(duration);

            logger.info("工具执行完成: toolName={}, success={}, duration={}ms",
                toolName, result.isSuccess(), duration);

            return result;

        } catch (Exception e) {
            logger.error("工具执行异常: toolName={}", toolName, e);
            long duration = System.currentTimeMillis() - startTime;
            ToolResult result = ToolResult.failure("执行异常: " + e.getMessage());
            result.setExecutionTimeMs(duration);
            return result;
        }
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

        // TODO: 通过 WebSocket 转发到 IDE 执行
        // 目前先返回工具自己的占位结果
        logger.warn("WebSocket 推送功能尚未实现，返回工具的占位结果");
        return tool.execute(projectKey, params);
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
