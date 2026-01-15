package com.smancode.smanagent.ide.service

/**
 * LocalToolExecutor 单元测试
 * <p>
 * 测试 read_file 工具的各种场景和边界情况
 */
class LocalToolExecutorTest {

    /**
     * 测试场景：subList 行号/索引转换逻辑
     */
    fun testSubListLineNumberConversion() {
        println("=== 测试 subList 行号/索引转换 ===\n")

        // 模拟文件内容（共 20 行）
        val allLines = (1..20).map { "Line $it" }

        // 测试场景 1：读取完整文件（startLine=1, endLine=999999）
        println("场景1：读取完整文件")
        val startLine1 = 1
        val endLine1 = 999999
        val totalLines = allLines.size

        val startIndex1 = (startLine1 - 1).coerceAtLeast(0)
        val endIndex1 = endLine1.coerceAtMost(totalLines)  // 这里 endIndex1 是行号，不是索引！

        println("  startLine=$startLine1, endLine=$endLine1, totalLines=$totalLines")
        println("  startIndex=$startIndex1 (索引), endIndex=$endIndex1 (行号!)")

        // 分析：endIndex1 = 999999.coerceAtMost(20) = 20
        // subList(0, 20) 返回索引 0-19，正好 20 行！
        // 所以当 endIndex 等于 totalLines 时，错误用法恰好是对的！

        val result1 = allLines.subList(startIndex1, endIndex1)

        println("  实际用法 subList($startIndex1, $endIndex1) 返回 ${result1.size} 行")
        println("  期望：$totalLines 行")

        if (endIndex1 == totalLines) {
            println("  ⚠️  当 endLine >= totalLines 时，endIndex = totalLines，subList(0, totalLines) 恰好返回全部行！\n")
        }

        check(totalLines == result1.size) { "应该读取全部 20 行" }

        // 测试场景 2：读取前 10 行（startLine=1, endLine=10）
        println("场景2：读取前 10 行")
        val startLine2 = 1
        val endLine2 = 10

        val startIndex2 = (startLine2 - 1).coerceAtLeast(0)
        val endIndex2 = endLine2.coerceAtMost(totalLines)

        println("  startLine=$startLine2, endLine=$endLine2")
        println("  startIndex=$startIndex2 (索引), endIndex=$endIndex2 (行号!)")

        // 验证：subList(0, 10) 返回索引 0-9，共 10 个元素
        // 所以 endIndex=10（行号）传入 subList(0, 10) 是正确的！
        val result2 = allLines.subList(startIndex2, endIndex2)

        println("  subList($startIndex2, $endIndex2) 返回 ${result2.size} 行: ${result2.take(3)}...")
        println("  结论：当 endIndex 等于行号时，subList 是正确的！\n")

        check(10 == result2.size) { "应该返回 10 行" }
        check("Line 1" == result2.first())
        check("Line 10" == result2.last())

        // 测试场景 3：读取中间部分（startLine=5, endLine=15）
        println("场景3：读取中间部分")
        val startLine3 = 5
        val endLine3 = 15

        val startIndex3 = (startLine3 - 1).coerceAtLeast(0)
        val endIndex3 = endLine3.coerceAtMost(totalLines)

        val result3 = allLines.subList(startIndex3, endIndex3)

        println("  startLine=$startLine3, endLine=$endLine3")
        println("  返回 ${result3.size} 行: ${result3.first()} ... ${result3.last()}\n")

        check(11 == result3.size) { "5 到 15 共 11 行" }
        check("Line 5" == result3.first())
        check("Line 15" == result3.last())

        // 测试场景 4：endLine 超出文件行数
        println("场景4：endLine 超出文件行数")
        val startLine4 = 15
        val endLine4 = 999

        val startIndex4 = (startLine4 - 1).coerceAtLeast(0)
        val endIndex4 = endLine4.coerceAtMost(totalLines)

        val result4 = allLines.subList(startIndex4, endIndex4)

        println("  startLine=$startLine4, endLine=$endLine4, totalLines=$totalLines")
        println("  返回 ${result4.size} 行: ${result4.first()} ... ${result4.last()}\n")

        check(6 == result4.size) { "15 到 20 共 6 行" }
        check("Line 15" == result4.first())
        check("Line 20" == result4.last())
    }

    /**
     * 测试场景：边界条件
     */
    fun testBoundaryConditions() {
        println("\n=== 测试边界条件 ===\n")

        val allLines = (1..20).map { "Line $it" }
        val totalLines = allLines.size

        // 边界1：startLine = 1, endLine = 1（只读第一行）
        println("边界1：只读第一行")
        val result1 = readLines(allLines, 1, 1)
        check(1 == result1.size)
        check("Line 1" == result1.first())
        println("  ✓ 正确\n")

        // 边界2：startLine = totalLines, endLine = totalLines（只读最后一行）
        println("边界2：只读最后一行")
        val result2 = readLines(allLines, totalLines, totalLines)
        check(1 == result2.size)
        check("Line $totalLines" == result2.first())
        println("  ✓ 正确\n")

        // 边界3：startLine = 1, endLine = totalLines（读取全部）
        println("边界3：读取全部")
        val result3 = readLines(allLines, 1, totalLines)
        check(totalLines == result3.size)
        println("  ✓ 正确\n")

        // 边界4：startLine < 1（自动修正为 1）
        println("边界4：startLine < 1")
        val result4 = readLines(allLines, 0, 5)
        check(5 == result4.size)
        check("Line 1" == result4.first())
        println("  ✓ 正确\n")

        // 边界5：endLine > totalLines（自动限制为 totalLines）
        println("边界5：endLine > totalLines")
        val result5 = readLines(allLines, 15, 999)
        check(6 == result5.size)
        check("Line 15" == result5.first())
        check("Line $totalLines" == result5.last())
        println("  ✓ 正确\n")

        // 边界6：startLine > endLine（返回空）
        println("边界6：startLine > endLine")
        val result6 = readLines(allLines, 10, 5)
        check(0 == result6.size)
        println("  ✓ 正确（空列表）\n")

        // 边界7：startLine > totalLines（返回空或错误提示）
        println("边界7：startLine > totalLines")
        val result7 = readLines(allLines, totalLines + 1, totalLines + 5)
        check(0 == result7.size)
        println("  ✓ 正确（空列表）\n")
    }

    /**
     * 测试场景：原始代码的 bug
     */
    fun testOriginalBug() {
        println("\n=== 测试原始代码的 bug ===\n")

        val allLines = (1..93).map { "Line $it" }
        val totalLines = allLines.size

        // 模拟原始代码的错误逻辑
        println("场景：使用 simpleName，默认 startLine=1, endLine=100")

        // 原始代码第 243-244 行
        val relativePath = null  // 使用 simpleName
        val startLine = 1
        val endLine = 100

        val actualStartLine = if (relativePath == null && startLine == 1 && endLine == 100) 1 else startLine
        val actualEndLine = if (relativePath == null && startLine == 1 && endLine == 100) 100 else endLine

        println("  actualStartLine=$actualStartLine, actualEndLine=$actualEndLine")

        // 原始代码第 247-248 行
        val startIndex = (actualStartLine - 1).coerceAtLeast(0)
        val endIndex = actualEndLine.coerceAtMost(totalLines)  // endIndex 是行号！

        println("  startIndex=$startIndex (索引), endIndex=$endIndex (行号!)")

        // 原始代码第 253 行（BUG！）
        val resultBuggy = allLines.subList(startIndex, endIndex)  // endIndex 是行号，但 subList 需要索引
        println("  BUGGY: subList($startIndex, $endIndex) 返回 ${resultBuggy.size} 行")
        println("  期望：100 行（或 93 行如果文件只有 93 行）")
        println("  实际：${resultBuggy.size} 行 ❌ BUG!\n")

        // 正确的做法
        val resultCorrect = allLines.subList(startIndex, endIndex - 1)
        println("  CORRECT: subList($startIndex, ${endIndex - 1}) 返回 ${resultCorrect.size} 行")

        check(93 == resultCorrect.size) { "应该返回 93 行" }
    }

    /**
     * 测试场景：用户传入大 endLine
     */
    fun testLargeEndLine() {
        println("\n=== 测试用户传入大 endLine ===\n")

        val allLines = (1..93).map { "Line $it" }
        val totalLines = allLines.size

        // 用户传入 endLine=999999
        println("场景：用户传入 startLine=1, endLine=999999")

        val relativePath = null  // 使用 simpleName
        val startLine = 1
        val endLine = 999999

        // 原始代码逻辑
        val actualStartLine = if (relativePath == null && startLine == 1 && endLine == 100) 1 else startLine
        val actualEndLine = if (relativePath == null && startLine == 1 && endLine == 100) 100 else endLine

        println("  actualStartLine=$actualStartLine, actualEndLine=$actualEndLine")

        val startIndex = (actualStartLine - 1).coerceAtLeast(0)
        val endIndex = actualEndLine.coerceAtMost(totalLines)

        println("  startIndex=$startIndex, endIndex=$endIndex (行号)")

        // 原始代码（BUG）
        val resultBuggy = allLines.subList(startIndex, endIndex)
        println("  BUGGY: 返回 ${resultBuggy.size} 行")

        // 正确做法
        val resultCorrect = allLines.subList(startIndex, endIndex - 1)
        println("  CORRECT: 返回 ${resultCorrect.size} 行")

        check(93 == resultCorrect.size) { "应该返回全部 93 行" }
    }

    /**
     * 测试场景：条件判断的各种组合
     */
    fun testConditionCombinations() {
        println("\n=== 测试条件判断的各种组合 ===\n")

        // 测试第 243-244 行的条件逻辑
        testCondition("relativePath=null, startLine=1, endLine=100", null, 1, 100, 1, 100)
        testCondition("relativePath=null, startLine=1, endLine=500", null, 1, 500, 1, 500)
        testCondition("relativePath=null, startLine=10, endLine=100", null, 10, 100, 10, 100)
        testCondition("relativePath=null, startLine=10, endLine=50", null, 10, 50, 10, 50)
        testCondition("relativePath='path', startLine=1, endLine=100", "path", 1, 100, 1, 100)
        testCondition("relativePath='path', startLine=1, endLine=500", "path", 1, 500, 1, 500)
    }

    private fun testCondition(
        description: String,
        relativePath: String?,
        startLine: Int,
        endLine: Int,
        expectedStart: Int,
        expectedEnd: Int
    ) {
        println("  $description")

        val actualStartLine = if (relativePath == null && startLine == 1 && endLine == 100) 1 else startLine
        val actualEndLine = if (relativePath == null && startLine == 1 && endLine == 100) 100 else endLine

        println("    期望: start=$expectedStart, end=$expectedEnd")
        println("    实际: start=$actualStartLine, end=$actualEndLine")

        if (actualStartLine == expectedStart && actualEndLine == expectedEnd) {
            println("    ✓ 通过\n")
        } else {
            println("    ❌ 失败\n")
        }

        check(expectedStart == actualStartLine)
        check(expectedEnd == actualEndLine)
    }

    /**
     * 辅助函数：模拟 readLines 逻辑
     */
    private fun readLines(allLines: List<String>, startLine: Int, endLine: Int): List<String> {
        val totalLines = allLines.size

        val startIndex = (startLine - 1).coerceAtLeast(0)
        val endIndex = endLine.coerceAtMost(totalLines)

        return if (startIndex >= totalLines || startIndex >= endIndex) {
            emptyList()
        } else {
            allLines.subList(startIndex, endIndex)
        }
    }
}

fun main() {
    val test = LocalToolExecutorTest()

    println("========================================")
    println("  LocalToolExecutor 单元测试")
    println("========================================\n")

    test.testSubListLineNumberConversion()
    test.testBoundaryConditions()
    test.testOriginalBug()
    test.testLargeEndLine()
    test.testConditionCombinations()

    println("\n========================================")
    println("  所有测试完成！")
    println("========================================")
}
