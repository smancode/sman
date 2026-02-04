package com.smancode.smanagent.analysis.parser

import com.smancode.smanagent.analysis.model.VectorFragment
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createFile
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * MarkdownParser 单元测试
 *
 * 测试策略：
 * 1. 测试从 MD 内容或文件名提取类名
 * 2. 测试解析 class 向量
 * 3. 测试解析 method 向量列表
 * 4. 测试完整解析流程
 */
@DisplayName("MarkdownParser 单元测试")
class MarkdownParserTest {

    @TempDir
    lateinit var tempDir: Path

    private val parser = MarkdownParser()

    @Test
    @DisplayName("测试从 MD 标题提取类名")
    fun testExtractClassNameFromTitle() = runTest {
        // Given: MD 内容包含标题
        val mdContent = """
            # RepayHandler

            ## 类信息
            - **包名**: com.autoloop.loan.handler
        """.trimIndent()

        val sourceFile = tempDir.resolve("RepayHandler.md").createFile()

        // When: 提取类名
        val className = parser.extractClassName(sourceFile, mdContent)

        // Then: 应该从标题中提取类名
        assertEquals("RepayHandler", className)
    }

    @Test
    @DisplayName("测试从文件名提取类名（无标题时）")
    fun testExtractClassNameFromFileName() = runTest {
        // Given: MD 内容无标题
        val mdContent = """
            ## 类信息
            - **包名**: com.autoloop.loan.handler
        """.trimIndent()

        val sourceFile = tempDir.resolve("RepayHandler.md").createFile()

        // When: 提取类名
        val className = parser.extractClassName(sourceFile, mdContent)

        // Then: 应该从文件名提取类名
        assertEquals("RepayHandler", className)
    }

    @Test
    @DisplayName("测试解析 class 向量")
    fun testParseClassVector() = runTest {
        // Given: 完整的 class 内容
        val mdContent = """
            # RepayHandler

            ## 类信息
            - **完整签名**: `public class RepayHandler extends BaseHandler`
            - **包名**: `com.autoloop.loan.handler`

            ## 业务描述
            处理贷款还款HTTP请求，是还款业务的REST入口。

            ## 核心数据模型
            - `repayService` (RepayService): 处理还款业务逻辑的服务层组件

            ## 包含功能
            - `repay(request)`: 处理贷款还款请求
            - `queryRepayment(repaymentId)`: 根据ID查询单条还款记录

            ---

            ## 方法：repay
            ...
        """.trimIndent()

        val sourceFile = tempDir.resolve("RepayHandler.md").createFile()

        // When: 解析 class 向量
        val classVector = parser.parseClassVector(sourceFile, mdContent, "RepayHandler")

        // Then: 验证向量内容
        assertNotNull(classVector)
        assertEquals("class:RepayHandler", classVector.id)
        assertEquals("RepayHandler", classVector.title)
        assertTrue(classVector.content.contains("还款"))
        assertEquals(listOf("class", "java"), classVector.tags)
        assertEquals("class", classVector.getMetadata("type"))
    }

    @Test
    @DisplayName("测试解析 method 向量列表")
    fun testParseMethodVectors() = runTest {
        // Given: 包含多个方法的 MD 内容
        val mdContent = """
            # RepayHandler

            ---

            ## 方法：repay
            ### 签名
            `public RepayRspDTO repay(@Valid @RequestBody RepayReqDTO request)`
            ### 业务描述
            处理还款请求，包含正常、提前和逾期还款场景。
            ### 源码
            ```java
            @PostMapping
            public RepayRspDTO repay(@Valid @RequestBody RepayReqDTO request) {
                return repayService.repay(request);
            }
            ```

            ---

            ## 方法：queryRepayment
            ### 签名
            `public RepaymentDetailDTO queryRepayment(String repaymentId)`
            ### 业务描述
            根据ID查询单条还款记录详情。
        """.trimIndent()

        val sourceFile = tempDir.resolve("RepayHandler.md").createFile()

        // When: 解析 method 向量
        val methodVectors = parser.parseMethodVectors(sourceFile, mdContent, "RepayHandler")

        // Then: 验证向量数量和内容
        assertEquals(2, methodVectors.size)

        val repayMethod = methodVectors[0]
        assertEquals("method:RepayHandler.repay", repayMethod.id)
        assertEquals("repay", repayMethod.title)
        assertTrue(repayMethod.content.contains("还款"))
        assertEquals("method", repayMethod.getMetadata("type"))
        assertEquals("repay", repayMethod.getMetadata("methodName"))

        val queryMethod = methodVectors[1]
        assertEquals("method:RepayHandler.queryRepayment", queryMethod.id)
        assertEquals("queryRepayment", queryMethod.title)
    }

    @Test
    @DisplayName("测试完整解析流程：class + methods")
    fun testParseAll() = runTest {
        // Given: 完整的 MD 文件
        val mdContent = """
            # RepayHandler

            ## 类信息
            - **完整签名**: `public class RepayHandler extends BaseHandler`
            - **包名**: `com.autoloop.loan.handler`

            ## 业务描述
            处理贷款还款HTTP请求，是还款业务的REST入口。

            ## 包含功能
            - `repay(request)`: 处理贷款还款请求
            - `queryRepayment(repaymentId)`: 查询还款记录

            ---

            ## 方法：repay
            ### 业务描述
            处理还款请求，包含正常、提前和逾期还款场景。

            ---

            ## 方法：queryRepayment
            ### 业务描述
            根据ID查询单条还款记录详情。
        """.trimIndent()

        val sourceFile = tempDir.resolve("RepayHandler.md").createFile()

        // When: 解析所有向量
        val allVectors = parser.parseAll(sourceFile, mdContent)

        // Then: 验证向量数量和 ID 格式
        assertEquals(3, allVectors.size) // 1 class + 2 methods

        val classVector = allVectors[0]
        assertEquals("class:RepayHandler", classVector.id)

        val method1 = allVectors[1]
        assertEquals("method:RepayHandler.repay", method1.id)

        val method2 = allVectors[2]
        assertEquals("method:RepayHandler.queryRepayment", method2.id)

        // 验证所有向量 ID 都不包含 .md 后缀
        allVectors.forEach { vector ->
            assertFalse(
                vector.id.contains(".md"),
                "向量 ID 不应包含 .md 后缀: ${vector.id}"
            )
        }
    }

    @Test
    @DisplayName("测试边界情况：空内容")
    fun testEmptyContent() = runTest {
        // Given: 空 MD 内容
        val mdContent = ""
        val sourceFile = tempDir.resolve("Empty.md").createFile()

        // When & Then: 应该抛出异常
        var exceptionThrown = false
        try {
            parser.parseAll(sourceFile, mdContent)
        } catch (e: IllegalArgumentException) {
            exceptionThrown = true
            assertTrue(e.message?.contains("不能为空") == true)
        }
        assertTrue(exceptionThrown, "空内容应该抛出异常")
    }

    @Test
    @DisplayName("测试解析 enum 向量")
    fun testParseEnumVector() = runTest {
        // Given: Enum 格式的 MD 内容
        val mdContent = """
            # LoanStatus

            ## 业务描述
            贷款状态枚举，表示贷款在业务流程中的不同状态

            ## 枚举值表格

            | 枚举值 | 代码 | 业务含义 |
            | --- | --- | --- |
            | ACTIVE | 1 | 活跃状态，用户正在还款中 |
            | PAID | 2 | 已还清，贷款已全部还清 |
            | OVERDUE | 3 | 逾期状态，用户未按时还款 |
            | CANCELLED | 4 | 已取消，贷款申请被取消 |
        """.trimIndent()

        val sourceFile = tempDir.resolve("LoanStatus.java").createFile()

        // When: 解析 enum 向量
        val enumVector = parser.parseEnumVector(sourceFile, mdContent, "LoanStatus")

        // Then: 验证向量内容
        assertNotNull(enumVector)
        assertEquals("enum:LoanStatus", enumVector.id)
        assertEquals("LoanStatus", enumVector.title)
        assertTrue(enumVector.content.contains("贷款状态"))
        assertTrue(enumVector.content.contains("ACTIVE") || enumVector.content.contains("枚举值"))
        assertEquals(listOf("enum", "java"), enumVector.tags)
        assertEquals("enum", enumVector.getMetadata("type"))
    }

    @Test
    @DisplayName("测试从 Enum 表格提取枚举值")
    fun testExtractEnumValues() = runTest {
        // Given: 包含多个枚举值的 MD 内容
        val mdContent = """
            # PaymentMethod

            ## 业务描述
            支付方式枚举

            ## 枚举值表格

            | 枚举值 | 代码 | 业务含义 |
            | --- | --- | --- |
            | ALIPAY | 1 | 支付宝支付 |
            | WECHAT | 2 | 微信支付 |
            | BANK_CARD | 3 | 银行卡支付 |
        """.trimIndent()

        val sourceFile = tempDir.resolve("PaymentMethod.java").createFile()

        // When: 解析所有向量
        val allVectors = parser.parseAll(sourceFile, mdContent)

        // Then: 应该只有一个 enum 向量
        assertEquals(1, allVectors.size)
        assertEquals("enum:PaymentMethod", allVectors[0].id)
    }

    @Test
    @DisplayName("测试判断是否为 Enum MD 格式")
    fun testIsEnumMarkdown() = runTest {
        // Given: Enum 格式的 MD
        val enumMd = """
            # TestEnum
            ## 业务描述
            测试枚举
            ## 枚举值表格
            | 枚举值 | 代码 | 业务含义 |
        """.trimIndent()

        // When: 判断是否为 Enum
        val isEnum = parser.isEnumMarkdown(enumMd)

        // Then: 应该返回 true
        assertTrue(isEnum)
    }

    @Test
    @DisplayName("测试完整解析流程：enum（而非 class）")
    fun testParseAllForEnum() = runTest {
        // Given: Enum 格式的 MD 内容
        val mdContent = """
            # LoanStatus

            ## 业务描述
            贷款状态枚举，表示贷款在业务流程中的不同状态

            ## 枚举值表格

            | 枚举值 | 代码 | 业务含义 |
            | --- | --- | --- |
            | ACTIVE | 1 | 活跃状态 |
            | PAID | 2 | 已还清 |
        """.trimIndent()

        val sourceFile = tempDir.resolve("LoanStatus.java").createFile()

        // When: 解析所有向量
        val allVectors = parser.parseAll(sourceFile, mdContent)

        // Then: 应该只有 1 个 enum 向量（不是 class + methods）
        assertEquals(1, allVectors.size)
        assertEquals("enum:LoanStatus", allVectors[0].id)
        assertEquals("enum", allVectors[0].getMetadata("type"))
    }

    @Test
    @DisplayName("测试边界情况：无方法的 class")
    fun testClassWithoutMethods() = runTest {
        // Given: 只有 class 信息，没有 method
        val mdContent = """
            # SimpleClass

            ## 业务描述
            这是一个简单的类，没有任何方法。
        """.trimIndent()

        val sourceFile = tempDir.resolve("SimpleClass.md").createFile()

        // When: 解析所有向量
        val allVectors = parser.parseAll(sourceFile, mdContent)

        // Then: 只应该有 class 向量
        assertEquals(1, allVectors.size)
        assertEquals("class:SimpleClass", allVectors[0].id)
    }

    @Test
    @DisplayName("测试向量 ID 不包含 .md 后缀")
    fun testNoMdSuffixInVectorIds() = runTest {
        // Given: 带有 .md 后缀的文件
        val mdContent = "# TestClass\n\n## 业务描述\n测试类"
        val sourceFile = tempDir.resolve("TestClass.md").createFile()

        // When: 解析所有向量
        val allVectors = parser.parseAll(sourceFile, mdContent)

        // Then: 所有向量 ID 都不应包含 .md
        allVectors.forEach { vector ->
            assertFalse(
                vector.id.contains(".md"),
                "向量 ID 包含 .md 后缀: ${vector.id}"
            )
        }
    }
}

private fun assertFalse(condition: Boolean, message: String? = null) {
    if (condition) {
        throw AssertionError(message ?: "Expected false but was true")
    }
}
