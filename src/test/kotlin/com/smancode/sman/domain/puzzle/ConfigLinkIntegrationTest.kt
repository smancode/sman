package com.smancode.sman.domain.puzzle

import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIf
import java.io.File
import kotlin.test.assertTrue

/**
 * 配置关联集成测试 - TDD 驱动
 *
 * 验证系统能够发现"代码 → XML → 代码"的完整调用链
 *
 * 核心场景：
 * RepayHandler.executeXmlTransaction()
 *   → RepayService.executeXmlTransaction()
 *     → transaction.xml (TransactionCode=2001)
 *       → LoadLoanProcedure
 *       → ValidateRepaymentProcedure
 *       → ProcessRepaymentProcedure
 */
@EnabledIf("com.smancode.sman.domain.puzzle.ConfigLinkIntegrationTest#isAutoloopAvailable")
class ConfigLinkIntegrationTest {

    companion object {
        private val AUTOLOOP_PATH = "${System.getProperty("user.home")}/projects/autoloop"

        @JvmStatic
        fun isAutoloopAvailable(): Boolean = File(AUTOLOOP_PATH).exists()

        @BeforeAll
        @JvmStatic
        fun verifyEnvironment() {
            assertTrue(File(AUTOLOOP_PATH).exists(), "autoloop 项目必须存在")
        }
    }

    /**
     * 测试：从 Handler 发现完整调用链（包含 XML 定义的 Procedure）
     *
     * 这是核心测试！验证系统能否发现隐藏在 XML 中的执行流程
     */
    @Test
    fun `should discover complete call chain from Handler to XML procedures`() {
        // Given
        val analyzer = ConfigLinkAnalyzer(AUTOLOOP_PATH)
        val handlerPath = "loan/src/main/java/com/autoloop/loan/handler/RepayHandler.java"

        // When: 发现直接关联
        val directLinks = analyzer.findLinkedConfigs(handlerPath)

        // Then
        println("=== Handler 直接关联 ===")
        directLinks.forEach { link ->
            println("  ${link.type}: ${link.targetPath}")
        }

        // 验证：应该发现 transaction.xml
        val transactionLink = directLinks.firstOrNull { it.type == ConfigLinkType.TRANSACTION_CONFIG }
        assertTrue(transactionLink != null, "应该发现 transaction.xml")
    }

    /**
     * 测试：从 transaction.xml 发现所有 Procedure 类
     */
    @Test
    fun `should discover all Procedure classes from transaction XML`() {
        // Given
        val analyzer = ConfigLinkAnalyzer(AUTOLOOP_PATH)
        val xmlPath = "loan/src/main/resources/trans/transaction.xml"

        // When
        val links = analyzer.findLinkedConfigs(xmlPath)

        // Then
        println("=== XML 关联的 Procedure 类 ===")
        links.filter { it.type == ConfigLinkType.XML_PROCEDURE }.forEach { link ->
            println("  ${link.targetClass}")
            println("    -> ${link.targetPath}")
        }

        // 验证：应该发现至少 5 个 Procedure
        val procedureLinks = links.filter { it.type == ConfigLinkType.XML_PROCEDURE }
        assertTrue(procedureLinks.size >= 5, "应该发现至少 5 个 Procedure，实际: ${procedureLinks.size}")
    }

    /**
     * 测试：完整调用链发现
     *
     * Handler → Service → transaction.xml → Procedures
     */
    @Test
    fun `should discover complete transaction flow including XML`() {
        // Given
        val analyzer = ConfigLinkAnalyzer(AUTOLOOP_PATH)
        val xmlPath = "loan/src/main/resources/trans/transaction.xml"

        // When: 深度分析
        val chain = analyzer.discoverCallChain(xmlPath, maxDepth = 3)

        // Then
        println("=== 完整调用链（深度 3）===")
        chain.sortedBy { it.depth }.forEach { link ->
            val indent = "  ".repeat(link.depth)
            println("${indent}[${link.type}] ${link.targetPath}")
        }

        // 验证：应该发现多层关联
        assertTrue(chain.isNotEmpty(), "应该发现调用链")

        // 验证：应该包含 XML_PROCEDURE 类型
        val procedureLinks = chain.filter { it.type == ConfigLinkType.XML_PROCEDURE }
        assertTrue(procedureLinks.isNotEmpty(), "应该发现 XML 中定义的 Procedure")
    }

    /**
     * 测试：MyBatis Mapper 双向关联
     */
    @Test
    fun `should discover MyBatis Mapper bidirectional links`() {
        // Given
        val analyzer = ConfigLinkAnalyzer(AUTOLOOP_PATH)

        // When: Java → XML
        val mapperPath = "loan/src/main/java/com/autoloop/loan/mapper/AcctRepaymentMapper.java"
        val javaToXml = analyzer.findLinkedConfigs(mapperPath)

        // And: XML → Java
        val xmlPath = "loan/src/main/resources/mapper/AcctRepaymentMapper.xml"
        val xmlToJava = analyzer.findLinkedConfigs(xmlPath)

        // Then
        println("=== MyBatis 双向关联 ===")
        println("Java → XML: ${javaToXml.map { it.targetPath }}")
        println("XML → Java: ${xmlToJava.map { it.targetClass }}")

        // 验证双向关联
        assertTrue(javaToXml.isNotEmpty(), "Java 应该关联到 XML")
        assertTrue(xmlToJava.isNotEmpty(), "XML 应该关联到 Java")
    }

    /**
     * 测试：真实业务场景 - 还款流程完整分析
     *
     * 模拟用户问题："还款流程是怎样的？"
     */
    @Test
    fun `should analyze repayment flow including XML hidden logic`() {
        // Given
        val analyzer = ConfigLinkAnalyzer(AUTOLOOP_PATH)

        // 还款入口文件
        val entryPoints = listOf(
            "loan/src/main/java/com/autoloop/loan/handler/RepayHandler.java",
            "loan/src/main/java/com/autoloop/loan/service/RepayService.java"
        )

        // When: 分析所有入口
        val allLinks = entryPoints.flatMap { path ->
            println("分析入口: $path")
            val links = analyzer.findLinkedConfigs(path)
            links.forEach { println("  -> ${it.type}: ${it.targetPath}") }
            links
        }

        // Then
        println("\n=== 还款流程涉及的所有文件 ===")
        val uniquePaths = allLinks.map { it.targetPath }.distinct()
        uniquePaths.forEach { println("  $it") }

        // 验证：应该发现 transaction.xml（核心！）
        assertTrue(
            uniquePaths.any { it.contains("transaction.xml") },
            "应该发现 transaction.xml，这是还款流程的核心配置"
        )
    }
}
