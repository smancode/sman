package com.smancode.sman.tools

import com.smancode.sman.model.part.Part
import java.util.function.Consumer

/**
 * 工具接口
 *
 * 所有工具必须实现此接口。
 */
interface Tool {

    /**
     * 获取工具名称
     *
     * @return 工具名称
     */
    fun getName(): String

    /**
     * 获取工具描述
     *
     * @return 工具描述
     */
    fun getDescription(): String

    /**
     * 获取参数定义
     *
     * @return 参数定义映射（参数名 → 参数定义）
     */
    fun getParameters(): Map<String, ParameterDef>

    /**
     * 执行工具（不带流式输出）
     *
     * @param projectKey 项目 Key
     * @param params     参数映射
     * @return 工具执行结果
     */
    fun execute(projectKey: String, params: Map<String, Any>): ToolResult

    /**
     * 执行工具（带流式输出）
     *
     * @param projectKey 项目 Key
     * @param params     参数映射
     * @param partPusher Part 推送器（用于实时推送输出）
     * @return 工具执行结果
     */
    fun executeStreaming(
        projectKey: String,
        params: Map<String, Any>,
        partPusher: Consumer<Part>
    ): ToolResult {
        // 默认实现：调用不带流式输出的方法
        return execute(projectKey, params)
    }

    /**
     * 检查工具是否支持流式输出
     *
     * @return true 如果支持流式输出
     */
    fun supportsStreaming(): Boolean = false

    /**
     * 获取执行模式
     *
     * @param params 参数映射
     * @return 执行模式
     */
    fun getExecutionMode(params: Map<String, Any>): ExecutionMode {
        val mode = getOptionalString(params, "mode", "intellij")
        return if ("local".equals(mode, ignoreCase = true)) ExecutionMode.LOCAL else ExecutionMode.INTELLIJ
    }

    /**
     * 获取字符串参数（带默认值）
     *
     * @param params      参数映射
     * @param key         参数键
     * @param defaultValue 默认值
     * @return 参数值
     */
    fun getOptionalString(params: Map<String, Any>, key: String, defaultValue: String): String {
        val value = params[key] ?: return defaultValue
        return value.toString()
    }

    /**
     * 获取整型参数（带默认值）
     *
     * @param params      参数映射
     * @param key         参数键
     * @param defaultValue 默认值
     * @return 参数值
     */
    fun getOptionalInt(params: Map<String, Any>, key: String, defaultValue: Int): Int {
        val value = params[key] ?: return defaultValue
        if (value is Number) return value.toInt()
        return try {
            value.toString().toInt()
        } catch (e: NumberFormatException) {
            defaultValue
        }
    }

    /**
     * 执行模式
     */
    enum class ExecutionMode {
        /**
         * 后端执行（需要向量索引等后端资源）
         */
        LOCAL,

        /**
         * IDE 执行（需要本地文件访问）
         */
        INTELLIJ
    }
}
