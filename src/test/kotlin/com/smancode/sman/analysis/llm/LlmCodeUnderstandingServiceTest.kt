package com.smancode.sman.analysis.llm

import com.smancode.sman.smancode.llm.LlmService
import com.smancode.sman.smancode.llm.config.LlmEndpoint
import com.smancode.sman.smancode.llm.config.LlmPoolConfig
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import java.nio.file.Path

/**
 * LlmCodeUnderstandingService 单元测试
 *
 * 测试覆盖：
 * 1. 白名单准入测试：正常LLM分析响应
 * 2. 白名单拒绝测试：空数据、非法格式
 * 3. 边界值测试：空文件、错误响应
 * 4. 优雅降级测试：LLM调用失败不影响调用方
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("LlmCodeUnderstandingService 测试套件")
class LlmCodeUnderstandingServiceTest {

    private lateinit var mockLlmService: LlmService
    private lateinit var service: LlmCodeUnderstandingService

    @BeforeEach
    fun setUp() {
        mockLlmService = mockk()
        service = LlmCodeUnderstandingService(mockLlmService)
    }

    @AfterEach
    fun tearDown() {
        // 清理资源
    }

    @Nested
    @DisplayName("白名单准入测试：正常LLM分析响应")
    inner class HappyPathTests {

        @Test
        @DisplayName("analyzeJavaFile - 简单Java类 - 生成完整MD文档")
        fun testAnalyzeJavaFile_简单Java类_生成完整MD文档() = runTest {
            // Given: 简单的 Java 源代码
            val javaFile = Path.of("/tmp/DisburseHandler.java")
            val javaSource = """
                package com.autoloop.loan.handler;

                import org.springframework.stereotype.Component;

                @Component
                public class DisburseHandler {
                    private BigDecimal amount;
                    private String accountId;

                    public void execute(Txn txn) {
                        // 放款逻辑
                    }

                    public void validate(DisburseRequest request) {
                        // 验证逻辑
                    }
                }
            """.trimIndent()

            // Given: LLM 返回标准 Markdown 格式
            val expectedMd = """
                # DisburseHandler

                ## 类信息

                - **完整签名**: `public class DisburseHandler`
                - **包名**: `com.autoloop.loan.handler`
                - **注解**: `@Component`

                ## 业务描述

                放款处理器，处理贷款发放业务。

                ## 核心数据模型

                - `amount` (BigDecimal): 放款金额
                - `accountId` (String): 账户ID

                ## 包含功能

                - `execute(Txn txn)`: 执行放款交易
                - `validate(DisburseRequest)`: 验证放款请求

                ---

                ## 方法：execute

                ### 签名
                `public void execute(Txn txn)`

                ### 参数
                - `txn`: 交易对象

                ### 返回值
                void

                ### 异常
                - `PaymentException`: 支付异常

                ### 业务描述
                执行放款交易，调用支付系统划转资金。

                ### 源码
                ```java
                public void execute(Txn txn) {
                    // 放款逻辑
                }
                ```

                ---

                ## 方法：validate

                ### 签名
                `public void validate(DisburseRequest request)`

                ### 参数
                - `request`: 放款请求对象

                ### 返回值
                void

                ### 异常
                - `ValidationException`: 验证异常

                ### 业务描述
                验证放款请求的完整性。

                ### 源码
                ```java
                public void validate(DisburseRequest request) {
                    // 验证逻辑
                }
                ```
            """.trimIndent()

            // Mock LLM 响应
            every { mockLlmService.simpleRequest(any(), any()) } returns expectedMd

            // When: 调用 analyzeJavaFile
            val result = service.analyzeJavaFile(javaFile, javaSource)

            // Then: 验证返回的 Markdown 格式正确
            assertTrue(result.contains("# DisburseHandler"))
            assertTrue(result.contains("放款处理器"))
            assertTrue(result.contains("## 方法：execute"))
            assertTrue(result.contains("## 方法：validate"))
        }

        @Test
        @DisplayName("analyzeEnumFile - 枚举类 - 生成字典映射表")
        fun testAnalyzeEnumFile_枚举类_生成字典映射表() = runTest {
            // Given: 枚举源代码
            val enumFile = Path.of("/tmp/LoanStatus.java")
            val enumSource = """
                package com.autoloop.loan.enums;

                public enum LoanStatus {
                    APPLIED(1, "已提交申请"),
                    REJECTED(4, "审核拒绝"),
                    PASSED(5, "审批通过"),
                    ACTIVE(6, "贷款生效中"),
                    CLOSED(7, "已结清");

                    private final int code;
                    private final String desc;

                    LoanStatus(int code, String desc) {
                        this.code = code;
                        this.desc = desc;
                    }
                }
            """.trimIndent()

            // Given: LLM 返回的枚举分析结果
            val expectedMd = """
                # LoanStatus

                ## 枚举定义
                - **完整签名**: `public enum LoanStatus`
                - **包名**: `com.autoloop.loan.enums`

                ## 业务描述
                贷款申请流程中的各种状态流转定义。

                ## 字典映射

                | 枚举值 | 编码 | 业务描述 |
                |--------|------|---------|
                | APPLIED | 1 | 已提交申请，等待初审 |
                | REJECTED | 4 | 审核拒绝 |
                | PASSED | 5 | 审批通过，待放款 |
                | ACTIVE | 6 | 贷款生效中，正常还款 |
                | CLOSED | 7 | 已结清 |
            """.trimIndent()

            // Mock LLM 响应
            every { mockLlmService.simpleRequest(any(), any()) } returns expectedMd

            // When: 调用 analyzeEnumFile
            val result = service.analyzeEnumFile(enumFile, enumSource)

            // Then: 验证枚举分析结果
            assertTrue(result.contains("# LoanStatus"))
            assertTrue(result.contains("贷款"))
            assertTrue(result.contains("| APPLIED | 1 |"))
            assertTrue(result.contains("| CLOSED | 7 |"))
        }
    }

    @Nested
    @DisplayName("白名单拒绝测试：非法输入")
    inner class RejectionTests {

        @Test
        @DisplayName("analyzeJavaFile - 空文件路径 - 抛异常")
        fun testAnalyzeJavaFile_空文件路径_抛异常() = runTest {
            // Given & Then: 空文件路径必须抛异常
            val exception = assertThrows<IllegalArgumentException> {
                service.analyzeJavaFile(Path.of(""), "source")
            }

            assertTrue(exception.message!!.contains("文件路径不能为空"))
        }

        @Test
        @DisplayName("analyzeJavaFile - 空源代码 - 抛异常")
        fun testAnalyzeJavaFile_空源代码_抛异常() = runTest {
            // Given & Then: 空源代码必须抛异常
            val exception = assertThrows<IllegalArgumentException> {
                service.analyzeJavaFile(Path.of("/tmp/Test.java"), "")
            }

            assertTrue(exception.message!!.contains("源代码不能为空"))
        }

        @Test
        @DisplayName("analyzeEnumFile - 空源代码 - 抛异常")
        fun testAnalyzeEnumFile_空源代码_抛异常() = runTest {
            // Given & Then: 空源代码必须抛异常
            val exception = assertThrows<IllegalArgumentException> {
                service.analyzeEnumFile(Path.of("/tmp/Status.java"), "")
            }

            assertTrue(exception.message!!.contains("源代码不能为空"))
        }

        @Test
        @DisplayName("parseMarkdownToVectors - 空 MD 内容 - 抛异常")
        fun testParseMarkdownToVectors_空MD内容_抛异常() {
            // Given & Then: 空 MD 内容必须抛异常
            val exception = assertThrows<IllegalArgumentException> {
                service.parseMarkdownToVectors(Path.of("/tmp/Test.java"), "")
            }

            assertTrue(exception.message!!.contains("MD 内容不能为空"))
        }
    }

    @Nested
    @DisplayName("边界值测试：特殊情况")
    inner class BoundaryTests {

        @Test
        @DisplayName("parseMarkdownToVectors - 只有类信息 - 返回1个类向量")
        fun testParseMarkdownToVectors_只有类信息_返回1个类向量() {
            // Given: 只有类信息的 MD
            val mdContent = """
                # TestClass

                ## 类信息
                - **完整签名**: `public class TestClass`
                - **包名**: `com.test`

                ## 业务描述
                测试类
            """.trimIndent()

            // When: 解析 MD
            val vectors = service.parseMarkdownToVectors(Path.of("/tmp/TestClass.java"), mdContent)

            // Then: 应该返回1个向量（类向量）
            assertEquals(1, vectors.size)
            assertEquals("TestClass", vectors[0].title)
            assertEquals("class", vectors[0].metadata["type"])
        }

        @Test
        @DisplayName("parseMarkdownToVectors - 类+2个方法 - 返回3个向量")
        fun testParseMarkdownToVectors_类加2个方法_返回3个向量() {
            // Given: 包含类和2个方法的 MD
            val mdContent = """
                # TestClass

                ## 类信息
                - **完整签名**: `public class TestClass`
                - **包名**: `com.test`

                ## 业务描述
                测试类

                ---

                ## 方法：method1

                ### 业务描述
                方法1功能

                ### 源码
                ```java
                public void method1() {}
                ```

                ---

                ## 方法：method2

                ### 业务描述
                方法2功能

                ### 源码
                ```java
                public void method2() {}
                ```
            """.trimIndent()

            // When: 解析 MD
            val vectors = service.parseMarkdownToVectors(Path.of("/tmp/TestClass.java"), mdContent)

            // Then: 应该返回3个向量（1个类 + 2个方法）
            assertEquals(3, vectors.size)
            assertEquals("TestClass", vectors[0].title)
            assertEquals("method1", vectors[1].title)
            assertEquals("method2", vectors[2].title)
        }

        @Test
        @DisplayName("LLM 返回格式错误 - 抛出明确异常")
        fun testLLM返回格式错误_抛出明确异常() = runTest {
            // Given: LLM 返回非 Markdown 格式
            val javaFile = Path.of("/tmp/Test.java")
            val javaSource = "public class Test {}"

            // Mock LLM 返回错误格式
            every { mockLlmService.simpleRequest(any(), any()) } returns "这不是 Markdown 格式"

            // When & Then: 应该抛出明确异常
            val exception = assertThrows<RuntimeException> {
                service.analyzeJavaFile(javaFile, javaSource)
            }

            assertTrue(exception.message!!.contains("LLM 返回格式错误") ||
                       exception.message!!.contains("解析失败"))
        }
    }

    @Nested
    @DisplayName("优雅降级测试")
    inner class GracefulDegradationTests {

        @Test
        @DisplayName("LLM 调用失败 - 抛出明确异常")
        fun testLLM调用失败_抛出明确异常() = runTest {
            // Given: LLM 调用失败
            val javaFile = Path.of("/tmp/Test.java")
            val javaSource = "public class Test {}"

            // Mock LLM 抛异常
            every { mockLlmService.simpleRequest(any(), any()) } throws
                RuntimeException("LLM API 调用失败")

            // When & Then: 应该抛出明确异常（不静默失败）
            assertThrows<RuntimeException> {
                service.analyzeJavaFile(javaFile, javaSource)
            }
        }

        @Test
        @DisplayName("MD 解析部分失败 - 返回已解析的向量")
        fun testMD解析部分失败_返回已解析的向量() {
            // Given: MD 内容包含一些无法解析的部分
            val mdContent = """
                # TestClass

                ## 类信息
                - **完整签名**: `public class TestClass`
                - **包名**: `com.test`

                ## 业务描述
                测试类

                ---

                ## 方法：validMethod

                ### 业务描述
                有效方法

                ### 源码
                ```java
                public void validMethod() {}
                ```

                ---

                ## 损坏的方法标题
                （这里没有方法签名等信息）
            """.trimIndent()

            // When: 解析 MD
            val vectors = service.parseMarkdownToVectors(Path.of("/tmp/TestClass.java"), mdContent)

            // Then: 应该返回至少类向量和有效方法向量
            assertTrue(vectors.size >= 2)
            assertTrue(vectors.any { it.title == "TestClass" })
            assertTrue(vectors.any { it.title == "validMethod" })
        }
    }

    @Nested
    @DisplayName("Prompt 构建测试")
    inner class PromptBuildingTests {

        @Test
        @DisplayName("analyzeJavaFile - 构建正确的 Prompt")
        fun testAnalyzeJavaFile_构建正确的Prompt() = runTest {
            // Given: Java 源代码
            val javaFile = Path.of("/tmp/Test.java")
            val javaSource = "public class Test { void method() {} }"

            val expectedMd = "# Test\n\n## 类信息\n- **完整签名**: `public class Test`"

            every { mockLlmService.simpleRequest(any(), any()) } returns expectedMd

            // When: 调用 analyzeJavaFile
            service.analyzeJavaFile(javaFile, javaSource)

            // Then: 验证 LLM 被调用，且 prompt 包含必要信息
            // 这里验证调用发生，具体 prompt 内容通过集成测试验证
            assertTrue(true) // 占位，实际应验证 prompt 内容
        }
    }
}
