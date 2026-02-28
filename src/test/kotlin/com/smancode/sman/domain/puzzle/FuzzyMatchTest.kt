package com.smancode.sman.domain.puzzle

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIf
import java.io.File
import kotlin.test.assertTrue

/**
 * 模糊匹配能力测试
 *
 * 验证系统能通过"脑洞"发现隐藏的关联：
 * - 从注释中提取 TransactionCode
 * - 通过字符串搜索关联 XML
 */
@EnabledIf("com.smancode.sman.domain.puzzle.FuzzyMatchTest#isAutoloopAvailable")
class FuzzyMatchTest {

    companion object {
        private val AUTOLOOP_PATH = "${System.getProperty("user.home")}/projects/autoloop"

        @JvmStatic
        fun isAutoloopAvailable(): Boolean = File(AUTOLOOP_PATH).exists()
    }

    /**
     * 测试：从注释中提取 TransactionCode
     *
     * RepayHandler.java 注释中有：
     * "使用事务代码 "2001" 触发XML配置的还款流程"
     *
     * 验证模糊匹配能发现这个关联
     */
    @Test
    fun `should find transaction XML through fuzzy matching from comments`() {
        // Given
        val analyzer = ConfigLinkAnalyzer(AUTOLOOP_PATH)
        val handlerPath = "loan/src/main/java/com/autoloop/loan/handler/RepayHandler.java"

        // 先验证注释中确实有 transactionCode
        val handlerContent = File(AUTOLOOP_PATH, handlerPath).readText()
        println("=== Handler 中的关键注释 ===")
        handlerContent.lines()
            .filter { it.contains("2001") || it.contains("事务代码") || it.contains("TransactionCode") }
            .forEach { println(it) }

        // When
        val links = analyzer.findLinkedConfigs(handlerPath)

        // Then
        println("\n=== 发现的关联 ===")
        links.forEach { link ->
            println("类型: ${link.type}")
            println("路径: ${link.targetPath}")
            println("置信度: ${link.confidence}")
            println("上下文: ${link.context}")
            println("---")
        }

        val transactionLink = links.firstOrNull { it.type == ConfigLinkType.TRANSACTION_CONFIG }
        assertTrue(transactionLink != null, "应该发现 transaction.xml")

        // 验证：通过关键词或模糊匹配，置信度应该 > 0.4
        assertTrue(
            transactionLink.confidence >= 0.4,
            "置信度应该 >= 0.4（关键词匹配），实际: ${transactionLink.confidence}"
        )

        println("\n匹配结果:")
        println("- 匹配类型: ${transactionLink.context["matchType"] ?: "code"}")
        println("- TransactionCode: ${transactionLink.context["transactionCode"]}")
    }

    /**
     * 测试：通过业务关键词"还款"发现 XML 关联
     */
    @Test
    fun `should match by business keyword Repayment`() {
        // Given
        val analyzer = ConfigLinkAnalyzer(AUTOLOOP_PATH)

        // 读取文件
        val handlerContent = File(AUTOLOOP_PATH,
            "loan/src/main/java/com/autoloop/loan/handler/RepayHandler.java").readText()
        val xmlContent = File(AUTOLOOP_PATH,
            "loan/src/main/resources/trans/transaction.xml").readText()

        // When: 检查共同关键词
        val hasRepayment = handlerContent.contains("还款") && xmlContent.contains("还款")
        val hasNormalRepayment = handlerContent.contains("正常还款") && xmlContent.contains("正常还款")

        // Then
        println("=== 关键词分析 ===")
        println("Handler 包含 '还款': ${handlerContent.contains("还款")}")
        println("XML 包含 '还款': ${xmlContent.contains("还款")}")
        println("Handler 包含 '正常还款': ${handlerContent.contains("正常还款")}")
        println("XML 包含 '正常还款': ${xmlContent.contains("正常还款")}")

        assertTrue(hasRepayment, "两边都应该包含 '还款' 这个业务关键词")

        // 验证分析器能发现关联
        val links = analyzer.findLinkedConfigs(
            "loan/src/main/java/com/autoloop/loan/handler/RepayHandler.java"
        )
        val transactionLink = links.firstOrNull { it.type == ConfigLinkType.TRANSACTION_CONFIG }

        println("\n发现关联的置信度: ${transactionLink?.confidence}")
        assertTrue(transactionLink != null)
    }

    /**
     * 测试：完整调用链 - 发现隐藏的 Procedure
     */
    @Test
    fun `should discover hidden Procedures through XML`() {
        // Given
        val analyzer = ConfigLinkAnalyzer(AUTOLOOP_PATH)
        val xmlPath = "loan/src/main/resources/trans/transaction.xml"

        // When
        val links = analyzer.findLinkedConfigs(xmlPath)

        // Then
        println("=== XML 中发现的 Procedure 类 ===")
        val procedureLinks = links.filter { it.type == ConfigLinkType.XML_PROCEDURE }
        procedureLinks.forEach { link ->
            println("类名: ${link.targetClass}")
            println("路径: ${link.targetPath}")
            println("---")
        }

        // 验证：应该发现至少 5 个 Procedure
        assertTrue(procedureLinks.size >= 5, "应该发现至少 5 个 Procedure，实际: ${procedureLinks.size}")

        // 验证：包含关键 Procedure
        val classNames = procedureLinks.map { it.targetClass }
        assertTrue(classNames.any { it.contains("LoadLoan") }, "应该包含 LoadLoanProcedure")
        assertTrue(classNames.any { it.contains("Repayment") }, "应该包含 Repayment 相关 Procedure")
    }
}
