package com.smancode.smanagent.tools

import com.smancode.smanagent.model.part.Part
import org.slf4j.LoggerFactory
import java.lang.Exception
import java.util.function.Consumer

/**
 * 工具执行器
 *
 * 负责执行工具调用，处理 local 执行模式。
 * （移除 WebSocket 转发功能，简化为只支持本地执行）
 */
class ToolExecutor(
    private val toolRegistry: ToolRegistry
) {

    private val logger = LoggerFactory.getLogger(ToolExecutor::class.java)

    /**
     * 执行工具
     *
     * @param toolName   工具名称
     * @param projectKey 项目标识
     * @param params     参数
     * @return 执行结果
     */
    fun execute(toolName: String, projectKey: String, params: Map<String, Any>): ToolResult {
        return execute(toolName, projectKey, params, null)
    }

    /**
     * 执行工具（带流式输出）
     *
     * @param toolName   工具名称
     * @param projectKey 项目标识
     * @param params     参数
     * @param partPusher Part 推送器（用于实时推送输出）
     * @return 执行结果
     */
    fun execute(toolName: String, projectKey: String, params: Map<String, Any>, partPusher: Consumer<Part>?): ToolResult {
        val startTime = System.currentTimeMillis()

        try {
            // 1. 查找工具
            val tool = toolRegistry.getTool(toolName)
            if (tool == null) {
                logger.error("工具不存在: {}", toolName)
                return ToolResult.failure("工具不存在: $toolName")
            }

            // 2. 确定执行模式并执行
            val mode = tool.getExecutionMode(params)
            logger.info("执行工具: toolName={}, projectKey={}, mode={}", toolName, projectKey, mode)

            // 3. 根据是否支持流式输出选择执行方式
            val result = if (partPusher != null && tool.supportsStreaming()) {
                // 使用流式输出
                executeLocalStreaming(tool, projectKey, params, partPusher)
            } else {
                // 普通执行
                if (mode == Tool.ExecutionMode.LOCAL) {
                    executeLocal(tool, projectKey, params)
                } else {
                    // 简化版本不支持 IntelliJ 模式，降级为本地执行
                    logger.warn("IntelliJ 模式不可用，降级为本地执行: {}", toolName)
                    executeLocal(tool, projectKey, params)
                }
            }

            // 4. 记录执行时长
            result.executionTimeMs = System.currentTimeMillis() - startTime
            logger.info("工具执行完成: toolName={}, success={}, duration={}ms",
                toolName, result.isSuccess, result.executionTimeMs)

            return result

        } catch (e: Exception) {
            logger.error("工具执行异常: toolName={}, {}", toolName, e.message, e)
            val result = ToolResult.failure("执行异常: ${e.message}")
            result.executionTimeMs = System.currentTimeMillis() - startTime
            return result
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
    private fun executeLocal(tool: Tool, projectKey: String, params: Map<String, Any>): ToolResult {
        logger.debug("本地执行工具: {}", tool.getName())
        return tool.execute(projectKey, params)
    }

    /**
     * 本地执行工具（带流式输出）
     *
     * @param tool       工具实例
     * @param projectKey 项目标识
     * @param params     参数
     * @param partPusher Part 推送器
     * @return 执行结果
     */
    private fun executeLocalStreaming(tool: Tool, projectKey: String, params: Map<String, Any>, partPusher: Consumer<Part>): ToolResult {
        logger.debug("本地执行工具（流式输出）: {}", tool.getName())
        return tool.executeStreaming(projectKey, params, partPusher)
    }

    /**
     * 验证工具参数
     *
     * @param toolName 工具名称
     * @param params   参数
     * @return 验证结果
     */
    fun validateParameters(toolName: String, params: Map<String, Any>): ValidationResult {
        val tool = toolRegistry.getTool(toolName)
        if (tool == null) {
            return ValidationResult(false, "工具不存在: $toolName")
        }

        val requiredParams = tool.getParameters()
        if (requiredParams.isEmpty()) {
            return ValidationResult(true, "无参数要求")
        }

        // 检查必需参数
        for ((key, def) in requiredParams) {
            if (def.isRequired) {
                val value = params[key]
                if (value == null) {
                    return ValidationResult(false, "缺少必需参数: $key")
                }
            }
        }

        return ValidationResult(true, "参数验证通过")
    }

    /**
     * 参数验证结果
     */
    data class ValidationResult(
        val isValid: Boolean,
        val message: String
    )
}
