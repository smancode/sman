package com.smancode.sman.smancode.prompt

import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * 提示词分发器（极简架构版）
 *
 * 新架构特点：
 * - 无意图识别：完全由 LLM 决定行为
 * - 无阶段划分：一个主循环处理所有
 * - system-reminder 支持：允许用户随时打断
 * - 支持从外部文件加载用户规则（类似 opencode 的 InstructionPrompt）
 */
class PromptDispatcher(
    private val promptLoader: PromptLoaderService
) {
    private val logger = LoggerFactory.getLogger(PromptDispatcher::class.java)

    companion object {
        // 用户规则文件列表（按优先级排序，后面的会覆盖前面的）
        private val USER_RULES_FILES = listOf(
            ".sman/RULES.md",           // 项目级规则
            ".smanrules",               // 项目级规则（简化名称）
            "SMAN_RULES.md"             // 项目根目录规则
        )

        // 全局用户规则文件
        private val GLOBAL_RULES_PATHS = listOf(
            System.getProperty("user.home") + "/.sman/RULES.md",
            System.getProperty("user.home") + "/.config/sman/RULES.md"
        )
    }

    /**
     * 构建系统提示词（包含工具介绍）
     *
     * 注意：用户规则现在由 SmanLoop 从 session.projectInfo?.rules 加载
     * 这样可以支持 IDE 配置界面动态修改规则
     *
     * @return 完整系统提示词
     */
    fun buildSystemPrompt(): String {
        val systemPrompt = promptLoader.loadPrompt("common/system-header.md")
        val toolIntroduction = promptLoader.loadPrompt("tools/tool-introduction.md")

        return """
            $systemPrompt

            $toolIntroduction
        """.trimIndent()
    }

    /**
     * 构建系统提示词（包含工具介绍 + 从文件系统加载用户规则）
     *
     * 此方法用于没有 session 上下文时，直接从文件系统加载规则
     *
     * @param projectPath 项目路径（用于加载项目级规则）
     * @return 完整系统提示词
     */
    fun buildSystemPromptWithFileRules(projectPath: String? = null): String {
        val systemPrompt = promptLoader.loadPrompt("common/system-header.md")
        val toolIntroduction = promptLoader.loadPrompt("tools/tool-introduction.md")
        val userRules = loadUserRules(projectPath)

        return buildString {
            append(systemPrompt)
            append("\n\n")
            append(toolIntroduction)
            if (userRules.isNotBlank()) {
                append("\n\n")
                append("## User Instructions\n\n")
                append("Instructions from: user rules files\n")
                append("<user_rules>\n")
                append(userRules)
                append("\n</user_rules>")
            }
        }.trimIndent()
    }

    /**
     * 加载用户自定义规则
     *
     * 加载顺序（后加载的会追加到前面）：
     * 1. 全局规则（~/.sman/RULES.md 或 ~/.config/sman/RULES.md）
     * 2. 项目规则（.sman/RULES.md, .smanrules, SMAN_RULES.md）
     *
     * @param projectPath 项目路径
     * @return 合并后的用户规则
     */
    private fun loadUserRules(projectPath: String?): String {
        val rules = mutableListOf<String>()

        // 1. 加载全局规则
        for (globalPath in GLOBAL_RULES_PATHS) {
            val content = loadFileContent(globalPath)
            if (content != null) {
                logger.debug("加载全局规则: {}", globalPath)
                rules.add(content)
                break  // 只加载第一个存在的全局规则
            }
        }

        // 2. 加载项目规则
        if (projectPath != null) {
            for (rulesFile in USER_RULES_FILES) {
                val filePath = Paths.get(projectPath, rulesFile).toString()
                val content = loadFileContent(filePath)
                if (content != null) {
                    logger.debug("加载项目规则: {}", filePath)
                    rules.add(content)
                    break  // 只加载第一个存在的项目规则
                }
            }
        }

        return rules.joinToString("\n\n---\n\n")
    }

    /**
     * 加载文件内容
     *
     * @param filePath 文件路径
     * @return 文件内容，如果文件不存在则返回 null
     */
    private fun loadFileContent(filePath: String): String? {
        return try {
            val path = Paths.get(filePath)
            if (Files.exists(path) && Files.isRegularFile(path)) {
                Files.readString(path).trim().takeIf { it.isNotBlank() }
            } else {
                null
            }
        } catch (e: Exception) {
            logger.debug("无法加载规则文件 {}: {}", filePath, e.message)
            null
        }
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
            | **expert_consult** | **智能搜索（SubAgent）** | **万能入口，90%情况用这个** |
            | read_file | 读取文件 | 已知类名时直接读取 |
            | grep_file | 正则搜索 | 找方法使用位置 |
            | find_file | 文件查找 | 按文件名模式查找 |
            | call_chain | 调用链分析 | 理解调用关系 |
            | extract_xml | XML 提取 | 提取配置 |
            | apply_change | 代码修改 | 应用代码修改 |
            | **run_shell_command** | **Shell 命令执行** | **构建、测试、运行等 CLI 操作** |

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
