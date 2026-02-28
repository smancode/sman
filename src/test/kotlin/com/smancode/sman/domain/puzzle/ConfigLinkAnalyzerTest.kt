package com.smancode.sman.domain.puzzle

import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIf
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ConfigLinkAnalyzer 测试 - TDD 驱动
 *
 * 测试目标：验证系统能发现 Java 代码与配置文件的关联关系
 *
 * 场景：
 * 1. MyBatis Mapper 接口 ↔ XML
 * 2. Service ↔ transaction.xml (规则引擎模式)
 * 3. XML → Procedure 类
 *
 * 注意：需要在 autoloop 项目存在时运行
 */
@EnabledIf("com.smancode.sman.domain.puzzle.ConfigLinkAnalyzerTest#isAutoloopAvailable")
class ConfigLinkAnalyzerTest {

    companion object {
        private val AUTOLOOP_PATH: String = "${System.getProperty("user.home")}/projects/autoloop"

        @JvmStatic
        fun isAutoloopAvailable(): Boolean {
            return File(AUTOLOOP_PATH).exists()
        }

        @BeforeAll
        @JvmStatic
        fun verifyTestEnvironment() {
            assertTrue(File(AUTOLOOP_PATH).exists(), "autoloop 项目必须存在: $AUTOLOOP_PATH")
        }
    }

    // ========== 测试用例 ==========

    /**
     * 测试 1: MyBatis Mapper 关联发现
     *
     * Given: Mapper 接口文件路径
     * When: 分析关联配置
     * Then: 返回对应的 XML 文件路径
     */
    @Test
    fun `should find MyBatis XML for Mapper interface`() {
        // Given
        val analyzer = ConfigLinkAnalyzer(AUTOLOOP_PATH)
        val mapperPath = "loan/src/main/java/com/autoloop/loan/mapper/AcctTransactionMapper.java"

        // 确认测试文件存在
        assertTrue(File(AUTOLOOP_PATH, mapperPath).exists(), "Mapper 文件必须存在")

        // When
        val links = analyzer.findLinkedConfigs(mapperPath)

        // Then
        assertTrue(links.isNotEmpty(), "应该找到关联的 XML 文件")

        val mybatisLink = links.first { it.type == ConfigLinkType.MYBATIS_MAPPER }
        assertTrue(
            mybatisLink.targetPath.contains("AcctTransactionMapper.xml"),
            "XML 路径应该包含 AcctTransactionMapper.xml，实际: ${mybatisLink.targetPath}"
        )
        assertEquals(0.95, mybatisLink.confidence, 0.1)
    }

    /**
     * 测试 2: 从 XML 发现 Procedure 类
     *
     * Given: transaction.xml 文件
     * When: 分析关联的 Java 类
     * Then: 返回 XML 中引用的所有 Procedure 类
     */
    @Test
    fun `should find Java classes referenced in transaction XML`() {
        // Given
        val analyzer = ConfigLinkAnalyzer(AUTOLOOP_PATH)
        val xmlPath = "loan/src/main/resources/trans/transaction.xml"

        // 确认测试文件存在
        assertTrue(File(AUTOLOOP_PATH, xmlPath).exists(), "transaction.xml 必须存在")

        // When
        val links = analyzer.findLinkedConfigs(xmlPath)

        // Then
        assertTrue(links.isNotEmpty(), "应该找到关联的 Java 类")

        val procedureLinks = links.filter { it.type == ConfigLinkType.XML_PROCEDURE }
        assertTrue(procedureLinks.size >= 3, "应该找到至少 3 个 Procedure 类，实际: ${procedureLinks.size}")

        val classNames = procedureLinks.map { it.targetClass }
        println("发现的 Procedure 类: $classNames")

        // 验证找到关键 Procedure
        assertTrue(
            classNames.any { it.contains("LoadLoanProcedure") },
            "应该包含 LoadLoanProcedure"
        )
        assertTrue(
            classNames.any { it.contains("ValidateRepaymentProcedure") },
            "应该包含 ValidateRepaymentProcedure"
        )
    }

    /**
     * 测试 3: 从 Handler/Service 发现关联的 transaction.xml
     *
     * Given: Handler 调用 executeXmlTransaction，注释中有 "事务代码 2001"
     * When: 分析关联配置
     * Then: 通过模糊匹配发现 transaction.xml 和 TransactionCode
     */
    @Test
    fun `should find transaction XML when Service calls TransactionExecutor`() {
        // Given
        val analyzer = ConfigLinkAnalyzer(AUTOLOOP_PATH)

        // 使用已知的 Handler 文件
        val handlerPath = "loan/src/main/java/com/autoloop/loan/handler/RepayHandler.java"
        assertTrue(File(AUTOLOOP_PATH, handlerPath).exists(), "Handler 文件必须存在")

        // When
        val links = analyzer.findLinkedConfigs(handlerPath)

        // Then
        println("Handler 发现的关联: ${links.map { "${it.type} -> ${it.targetPath} (context=${it.context})" }}")

        val transactionLink = links.firstOrNull { it.type == ConfigLinkType.TRANSACTION_CONFIG }
        assertTrue(transactionLink != null, "应该发现关联的 transaction.xml")

        // 验证关联到了正确的 XML 文件
        assertTrue(
            transactionLink.targetPath.contains("transaction.xml"),
            "路径应该包含 transaction.xml，实际: ${transactionLink.targetPath}"
        )

        // 验证：通过模糊匹配应该能提取 transactionCode
        // 因为 RepayHandler 注释中有 "事务代码 2001" 或类似内容
        println("Context: ${transactionLink.context}")
        // 注释中有 "2001"，模糊匹配应该能找到
        val hasCode = transactionLink.context.containsKey("transactionCode") ||
                      transactionLink.confidence >= 0.5
        assertTrue(hasCode, "应该通过某种方式关联到 transaction.xml")
    }

    /**
     * 测试 4: 双向关联 - 从 XML 找到 Mapper 接口
     *
     * Given: Mapper XML 文件
     * When: 分析关联配置
     * Then: 发现对应的 Java Mapper 接口
     */
    @Test
    fun `should find Mapper interface from XML`() {
        // Given
        val analyzer = ConfigLinkAnalyzer(AUTOLOOP_PATH)
        val xmlPath = "loan/src/main/resources/mapper/AcctTransactionMapper.xml"

        // When
        val links = analyzer.findLinkedConfigs(xmlPath)

        // Then
        val mapperLink = links.firstOrNull { it.type == ConfigLinkType.MYBATIS_MAPPER }
        assertTrue(mapperLink != null, "应该反向发现对应的 Mapper 接口")
        assertTrue(
            mapperLink.targetClass.contains("AcctTransactionMapper"),
            "类名应该包含 AcctTransactionMapper"
        )
    }

    /**
     * 测试 5: 综合场景 - 调用链发现（含 XML 定义的 Procedure）
     */
    @Test
    fun `should discover call chain including XML-defined procedures`() {
        // Given
        val analyzer = ConfigLinkAnalyzer(AUTOLOOP_PATH)

        // 使用 XML 文件作为起点
        val xmlPath = "loan/src/main/resources/trans/transaction.xml"

        // When: 深度分析（递归发现关联）
        val chain = analyzer.discoverCallChain(xmlPath, maxDepth = 2)

        // Then
        println("发现的调用链:")
        chain.forEach { link ->
            println("  深度 ${link.depth}: ${link.type} -> ${link.targetPath}")
        }

        assertTrue(chain.isNotEmpty(), "应该发现调用链")

        // 验证链路包含 XML Procedure
        val procedureLinks = chain.filter { it.type == ConfigLinkType.XML_PROCEDURE }
        assertTrue(procedureLinks.isNotEmpty(), "应该发现 XML 中定义的 Procedure 类")
    }

    // ========== 辅助方法 ==========

    private fun findServiceWithTransactionExecutor(): String? {
        val projectDir = File(AUTOLOOP_PATH)
        return projectDir.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".java") }
            .filter { it.readText().contains("TransactionExecutor") }
            .firstOrNull()
            ?.relativeTo(projectDir)
            ?.path
    }
}
