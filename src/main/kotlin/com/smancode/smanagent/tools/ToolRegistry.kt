package com.smancode.smanagent.tools

import org.slf4j.LoggerFactory

/**
 * 工具注册中心
 *
 * 管理所有可用工具，提供工具查询和获取功能。
 * （移除 Spring 依赖，改用手动注册）
 */
class ToolRegistry {

    private val logger = LoggerFactory.getLogger(ToolRegistry::class.java)

    /**
     * 工具名称 -> 工具实例 映射
     */
    private val toolMap: MutableMap<String, Tool> = mutableMapOf()

    /**
     * 注册工具
     *
     * @param tool 工具实例
     */
    fun registerTool(tool: Tool) {
        val name = tool.getName()
        if (toolMap.containsKey(name)) {
            logger.warn("工具名称冲突: {}，已存在，跳过注册", name)
            return
        }
        toolMap[name] = tool
        logger.info("注册工具: {} - {}", name, tool.getDescription())
    }

    /**
     * 批量注册工具
     *
     * @param tools 工具列表
     */
    fun registerTools(tools: List<Tool>) {
        for (tool in tools) {
            registerTool(tool)
        }
        logger.info("工具注册完成，共注册 {} 个工具", toolMap.size)
    }

    /**
     * 根据名称获取工具
     *
     * @param name 工具名称
     * @return 工具实例，如果不存在则返回 null
     */
    fun getTool(name: String): Tool? {
        logger.debug("ToolRegistry.getTool 获取工具: name={}", name)
        val tool = toolMap[name]
        if (tool == null) {
            logger.warn("ToolRegistry.getTool 未找到工具: name={}", name)
        } else {
            logger.debug("ToolRegistry.getTool 找到工具: name={}, class={}", name, tool.javaClass.simpleName)
        }
        return tool
    }

    /**
     * 获取所有工具
     *
     * @return 所有工具列表
     */
    fun getAllTools(): List<Tool> = toolMap.values.toList()

    /**
     * 获取所有工具名称
     *
     * @return 所有工具名称列表
     */
    fun getToolNames(): List<String> = toolMap.keys.toList()

    /**
     * 检查工具是否存在
     *
     * @param name 工具名称
     * @return 是否存在
     */
    fun hasTool(name: String): Boolean = toolMap.containsKey(name)

    /**
     * 获取工具描述（用于生成工具介绍）
     *
     * @return 工具描述列表
     */
    fun getToolDescriptions(): List<ToolDescription> {
        return toolMap.values.map { tool ->
            ToolDescription(
                tool.getName(),
                tool.getDescription(),
                buildParameterSummary(tool)
            )
        }
    }

    /**
     * 构建参数摘要
     *
     * @param tool 工具实例
     * @return 参数摘要字符串
     */
    private fun buildParameterSummary(tool: Tool): String {
        val params = tool.getParameters()
        if (params.isEmpty()) {
            return "无参数"
        }

        val sb = StringBuilder()
        for ((key, def) in params) {
            if (sb.isNotEmpty()) {
                sb.append(", ")
            }

            sb.append(key).append(": ")

            if (def.isRequired) {
                sb.append("[必需] ")
            } else {
                sb.append("[可选] ")
            }

            sb.append(def.type?.simpleName ?: "Any")
        }

        return sb.toString()
    }

    /**
     * 工具描述
     */
    data class ToolDescription(
        val name: String,
        val description: String,
        val parameters: String
    ) {
        override fun toString(): String {
            return "$name: $description (参数: $parameters)"
        }
    }
}
