package com.smancode.smanagent.smancode.core

import com.smancode.smanagent.tools.ToolResult

/**
 * 工具结果格式化器
 *
 * 提供工具结果的格式化和摘要生成功能
 */
object ToolResultFormatter {

    /**
     * 格式化工具结果
     *
     * 注意：不截取结果，完整输出！
     */
    fun formatToolResult(result: ToolResult): String {
        val data = result.data ?: return ""

        // 完整返回结果，不截取
        return data.toString()
    }

    /**
     * 生成结果摘要
     */
    fun generateResultSummary(toolName: String, data: Any?): String {
        val dataStr = data?.toString() ?: ""

        return when (toolName) {
            "semantic_search" -> {
                val count = countSearchResults(dataStr)
                if (count > 0) "找到 $count 个相关结果" else "未找到相关结果"
            }
            "grep_file" -> {
                val count = countMatches(dataStr)
                if (count > 0) "匹配到 $count 处" else "未找到匹配"
            }
            "find_file" -> {
                val count = countFiles(dataStr)
                if (count > 0) "找到 $count 个文件" else "未找到文件"
            }
            "read_file" -> {
                val lines = countLines(dataStr)
                "读取了 $lines 行"
            }
            "call_chain" -> {
                val depth = countCallDepth(dataStr)
                "调用链深度: $depth"
            }
            else -> "执行完成"
        }
    }

    private fun countSearchResults(data: String): Int {
        // 简单的启发式计数
        return maxOf(0, data.split("{").size - 1)
    }

    private fun countMatches(data: String): Int {
        return maxOf(0, data.split("\n").size - 1)
    }

    private fun countFiles(data: String): Int {
        return maxOf(0, data.split("\n").size)
    }

    private fun countLines(data: String): Int {
        return maxOf(0, data.split("\n").size)
    }

    private fun countCallDepth(data: String): Int {
        return maxOf(0, data.split(" -> ").size)
    }
}
