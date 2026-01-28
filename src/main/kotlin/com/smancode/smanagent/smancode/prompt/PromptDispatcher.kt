package com.smancode.smanagent.smancode.prompt

import org.slf4j.LoggerFactory

/**
 * 提示词分发器（极简架构版）
 *
 * 新架构特点：
 * - 无意图识别：完全由 LLM 决定行为
 * - 无阶段划分：一个主循环处理所有
 * - system-reminder 支持：允许用户随时打断
 */
class PromptDispatcher(
    private val promptLoader: PromptLoaderService
) {
    private val logger = LoggerFactory.getLogger(PromptDispatcher::class.java)

    /**
     * 构建系统提示词（包含工具介绍）
     *
     * 新架构下只需要这一个基础系统提示词
     *
     * @return 完整系统提示词
     */
    fun buildSystemPrompt(): String {
        val systemPrompt = promptLoader.loadPrompt("common/system-header.md")
        val toolIntroduction = promptLoader.loadPrompt("tools/tool-introduction.md")
        val toolUsageGuidelines = promptLoader.loadPrompt("tools/tool-usage-guidelines.md")

        return """
            $systemPrompt

            $toolIntroduction

            $toolUsageGuidelines
        """.trimIndent()
    }

    /**
     * 获取工具摘要
     *
     * @return 工具摘要（精简版）
     */
    fun getToolSummary(): String {
        return """
            ## 可用工具摘要

            | 工具 | 功能 | 使用场景 |
            |------|------|----------|
            | **search** | **智能搜索（SubAgent）** | **万能入口，90%情况用这个** |
            | read_file | 读取文件 | 已知类名时直接读取 |
            | grep_file | 正则搜索 | 找方法使用位置 |
            | find_file | 文件查找 | 按文件名模式查找 |
            | call_chain | 调用链分析 | 理解调用关系 |
            | extract_xml | XML 提取 | 提取配置 |
            | apply_change | 代码修改 | 应用代码修改 |

            详细信息请参考工具文档。
        """.trimIndent()
    }

    /**
     * 构建带变量的提示词（保留用于特殊场景）
     *
     * @param promptPath 提示词路径
     * @param variables  变量映射
     * @return 替换后的提示词
     */
    fun buildPromptWithVariables(promptPath: String, variables: Map<String, String>): String {
        return promptLoader.loadPromptWithVariables(promptPath, variables)
    }

    // ========== 属性访问方式（兼容 Java 风格调用） ==========

    /**
     * 工具摘要（属性访问方式）
     */
    val toolSummary: String
        @JvmName("getToolSummaryProp")
        get() = getToolSummary()
}
