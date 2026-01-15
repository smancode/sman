package com.smancode.smanagent.ide.service

import com.smancode.smanagent.ide.model.ToolResult
import java.io.File

/**
 * 直接测试 read_file 工具
 */
fun main() {
    println("\n=== 测试 read_file 工具 ===\n")

    val testFile = File("/Users/liuchao/projects/smanagent/agent/src/main/java/com/smancode/smanagent/tools/analysis/CallChainTool.java")

    println("1. 文件信息:")
    println("   路径: ${testFile.absolutePath}")
    println("   存在: ${testFile.exists()}")
    println("   总行数: ${testFile.readLines().size}")
    println("   总字符数: ${testFile.readText().length}")
    println()

    println("2. 模拟 IDE 的 read_file 读取:")
    val content = testFile.readText()
    val allLines = content.lines()
    val totalLines = allLines.size

    // 默认参数
    val startLine = 1
    val endLine = 100

    val actualStartLine = 1
    val actualEndLine = 100

    val startIndex = (actualStartLine - 1).coerceAtLeast(0)
    val endIndex = actualEndLine.coerceAtMost(totalLines)

    println("   startLine=$startLine, endLine=$endLine")
    println("   actualStartLine=$actualStartLine, actualEndLine=$actualEndLine")
    println("   startIndex=$startIndex, endIndex=$endIndex")
    println("   totalLines=$totalLines")
    println()

    val selectedLines = if (startIndex >= totalLines) {
        listOf("// 文件只有 $totalLines 行，请求的起始行 $actualStartLine 超出范围")
    } else {
        allLines.toList().subList(startIndex, endIndex)
    }

    println("3. 读取结果:")
    println("   返回行数: ${selectedLines.size}")
    println("   返回字符数: ${selectedLines.joinToString("\n").length}")
    println()

    println("4. 前 5 行内容:")
    selectedLines.take(5).forEachIndexed { index, line ->
        println("   ${index + 1}: $line")
    }
    println()

    println("5. 后 5 行内容:")
    selectedLines.takeLast(5).forEachIndexed { index, line ->
        println("   ${selectedLines.size - 5 + index + 1}: $line")
    }
    println()

    println("6. 结论:")
    if (selectedLines.size == 100) {
        println("   ✅ 读取了 100 行 (正确)")
    } else if (selectedLines.size == totalLines) {
        println("   ✅ 读取了全部 ${totalLines} 行 (正确)")
    } else {
        println("   ❌ 读取了 ${selectedLines.size} 行 (错误，应该是 ${minOf(100, totalLines)} 行)")
    }
}
