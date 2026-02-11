package com.smancode.sman.tools

import com.smancode.sman.base.mocks.DummyTool
import com.smancode.sman.base.mocks.FailingTool
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@DisplayName("ToolExecutor 单元测试")
class ToolExecutorTest {

    private lateinit var toolRegistry: ToolRegistry
    private lateinit var toolExecutor: ToolExecutor

    @BeforeEach
    fun setUp() {
        toolRegistry = ToolRegistry()
        toolExecutor = ToolExecutor(toolRegistry)
    }

    @Nested
    @DisplayName("执行工具")
    inner class Execute {

        @Test
        @DisplayName("执行成功工具返回成功结果")
        fun testExecute_successTool_returnsSuccess() {
            // Given
            val dummy = DummyTool()
            toolRegistry.registerTool(dummy)

            // When
            val result = toolExecutor.execute("dummy", "test-project", mapOf("message" to "Hello"))

            // Then
            assertTrue(result.isSuccess)
            assertTrue(result.displayContent?.contains("Hello") == true)
            assertTrue(result.executionTimeMs >= 0)
            assertEquals(1, dummy.executeCount)
        }

        @Test
        @DisplayName("执行不存在的工具返回失败结果")
        fun testExecute_nonExistingTool_returnsFailure() {
            // When
            val result = toolExecutor.execute("non-existing", "test-project", emptyMap())

            // Then
            assertFalse(result.isSuccess)
            assertTrue(result.error?.contains("工具不存在") == true)
        }

        @Test
        @DisplayName("工具执行异常返回失败结果")
        fun testExecute_toolThrowsException_returnsFailure() {
            // Given
            val failing = FailingTool()
            failing.exceptionToThrow = RuntimeException("Test exception")
            toolRegistry.registerTool(failing)

            // When
            val result = toolExecutor.execute("failing", "test-project", emptyMap())

            // Then
            assertFalse(result.isSuccess)
            assertTrue(result.error?.contains("执行异常") == true)
        }

        @Test
        @DisplayName("执行失败工具返回失败结果")
        fun testExecute_failingTool_returnsFailure() {
            // Given
            val failing = FailingTool()
            failing.errorMessage = "Custom error"
            toolRegistry.registerTool(failing)

            // When
            val result = toolExecutor.execute("failing", "test-project", emptyMap())

            // Then
            assertFalse(result.isSuccess)
            assertEquals("Custom error", result.error)
        }

        @Test
        @DisplayName("记录执行时间")
        fun testExecute_recordsExecutionTime() {
            // Given
            val dummy = DummyTool()
            toolRegistry.registerTool(dummy)

            // When
            val result = toolExecutor.execute("dummy", "test-project", emptyMap())

            // Then
            assertTrue(result.executionTimeMs >= 0)
        }
    }

    @Nested
    @DisplayName("带会话执行")
    inner class ExecuteWithSession {

        @Test
        @DisplayName("带会话ID执行成功")
        fun testExecuteWithSession_success() {
            // Given
            val dummy = DummyTool()
            toolRegistry.registerTool(dummy)

            // When
            val result = toolExecutor.execute(
                "dummy",
                "test-project",
                emptyMap(),
                null
            )

            // Then
            assertTrue(result.isSuccess)
            assertEquals(1, dummy.executeCount)
        }

        @Test
        @DisplayName("会话ID为null也能执行")
        fun testExecuteWithSession_nullSessionId_success() {
            // Given
            val dummy = DummyTool()
            toolRegistry.registerTool(dummy)

            // When
            val result = toolExecutor.execute(
                "dummy",
                "test-project",
                emptyMap(),
                null
            )

            // Then
            assertTrue(result.isSuccess)
        }
    }

    @Nested
    @DisplayName("验证参数")
    inner class ValidateParameters {

        @Test
        @DisplayName("验证通过的工具")
        fun testValidateParameters_valid_passes() {
            // Given
            toolRegistry.registerTool(DummyTool())

            // When
            val result = toolExecutor.validateParameters("dummy", mapOf("message" to "Hello"))

            // Then
            assertTrue(result.isValid)
        }

        @Test
        @DisplayName("验证缺少必需参数的工具")
        fun testValidateParameters_missingRequired_fails() {
            // Given
            val tool = object : AbstractTool() {
                override fun getName() = "test"
                override fun getDescription() = "Test tool"
                override fun getParameters() = mapOf(
                    "required" to ParameterDef("required", String::class.java, true, "Required param")
                )
                override fun execute(projectKey: String, params: Map<String, Any>): ToolResult {
                    return ToolResult.success("OK", null, "OK")
                }
            }
            toolRegistry.registerTool(tool)

            // When
            val result = toolExecutor.validateParameters("test", emptyMap())

            // Then
            assertFalse(result.isValid)
            assertTrue(result.message.contains("缺少必需参数"))
        }

        @Test
        @DisplayName("验证无参数要求的工具")
        fun testValidateParameters_noParameters_passes() {
            // Given
            toolRegistry.registerTool(FailingTool())

            // When
            val result = toolExecutor.validateParameters("failing", emptyMap())

            // Then
            assertTrue(result.isValid)
        }

        @Test
        @DisplayName("验证不存在的工具")
        fun testValidateParameters_nonExisting_fails() {
            // When
            val result = toolExecutor.validateParameters("non-existing", emptyMap())

            // Then
            assertFalse(result.isValid)
            assertTrue(result.message.contains("工具不存在"))
        }
    }

    @Nested
    @DisplayName("工具执行模式")
    inner class ExecutionMode {

        @Test
        @DisplayName("本地模式工具执行成功")
        fun testExecutionMode_local_success() {
            // Given
            val tool = object : AbstractTool() {
                override fun getName() = "local-tool"
                override fun getDescription() = "Local tool"
                override fun getParameters() = emptyMap<String, ParameterDef>()
                override fun execute(projectKey: String, params: Map<String, Any>): ToolResult {
                    return ToolResult.success("Local execution", null, "Local execution")
                }
            }
            toolRegistry.registerTool(tool)

            // When
            val result = toolExecutor.execute("local-tool", "test-project", mapOf("mode" to "local"))

            // Then
            assertTrue(result.isSuccess)
            assertEquals("Local execution", result.displayContent)
        }

        @Test
        @DisplayName("IntelliJ模式降级为本地执行")
        fun testExecutionMode_intellij_fallsBackToLocal() {
            // Given
            val tool = object : AbstractTool() {
                override fun getName() = "intellij-tool"
                override fun getDescription() = "IntelliJ tool"
                override fun getParameters() = emptyMap<String, ParameterDef>()
                override fun execute(projectKey: String, params: Map<String, Any>): ToolResult {
                    return ToolResult.success("Executed", null, "Executed")
                }
            }
            toolRegistry.registerTool(tool)

            // When
            val result = toolExecutor.execute("intellij-tool", "test-project", mapOf("mode" to "intellij"))

            // Then
            assertTrue(result.isSuccess)
            // 简化版本会降级为本地执行
        }
    }
}
