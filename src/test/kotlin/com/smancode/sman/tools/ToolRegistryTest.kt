package com.smancode.sman.tools

import com.smancode.sman.base.mocks.DummyTool
import com.smancode.sman.base.mocks.FailingTool
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@DisplayName("ToolRegistry 单元测试")
class ToolRegistryTest {

    private lateinit var toolRegistry: ToolRegistry

    @BeforeEach
    fun setUp() {
        toolRegistry = ToolRegistry()
    }

    @Nested
    @DisplayName("注册工具")
    inner class RegisterTool {

        @Test
        @DisplayName("注册单个工具成功")
        fun testRegisterTool_success() {
            // Given
            val tool = DummyTool()

            // When
            toolRegistry.registerTool(tool)

            // Then
            assertTrue(toolRegistry.hasTool("dummy"))
            assertEquals(tool, toolRegistry.getTool("dummy"))
        }

        @Test
        @DisplayName("重复注册同名工具被忽略")
        fun testRegisterTool_duplicateName_ignored() {
            // Given
            val tool1 = DummyTool()
            val tool2 = DummyTool()

            // When
            toolRegistry.registerTool(tool1)
            toolRegistry.registerTool(tool2)

            // Then
            assertEquals(tool1, toolRegistry.getTool("dummy"))
        }

        @Test
        @DisplayName("注册多个不同工具")
        fun testRegisterTool_multipleDifferent_allRegistered() {
            // Given
            val dummy = DummyTool()
            val failing = FailingTool()

            // When
            toolRegistry.registerTool(dummy)
            toolRegistry.registerTool(failing)

            // Then
            assertEquals(2, toolRegistry.getAllTools().size)
            assertTrue(toolRegistry.hasTool("dummy"))
            assertTrue(toolRegistry.hasTool("failing"))
        }
    }

    @Nested
    @DisplayName("批量注册工具")
    inner class RegisterTools {

        @Test
        @DisplayName("批量注册多个工具")
        fun testRegisterTools_success() {
            // Given
            val tools = listOf(
                DummyTool(),
                FailingTool(),
                DummyTool().apply { fixedResult = "another" }
            )

            // When
            toolRegistry.registerTools(tools)

            // Then
            // 第三个 DummyTool 应该被忽略（同名）
            assertEquals(2, toolRegistry.getAllTools().size)
        }

        @Test
        @DisplayName("批量注册空列表")
        fun testRegisterTools_emptyList_noEffect() {
            // When
            toolRegistry.registerTools(emptyList())

            // Then
            assertEquals(0, toolRegistry.getAllTools().size)
        }
    }

    @Nested
    @DisplayName("获取工具")
    inner class GetTool {

        @Test
        @DisplayName("获取已注册的工具")
        fun testGetTool_existing_returnsTool() {
            // Given
            val tool = DummyTool()
            toolRegistry.registerTool(tool)

            // When
            val retrieved = toolRegistry.getTool("dummy")

            // Then
            assertTrue(retrieved != null)
            assertEquals(tool, retrieved)
        }

        @Test
        @DisplayName("获取不存在的工具返回 null")
        fun testGetTool_nonExisting_returnsNull() {
            // When
            val retrieved = toolRegistry.getTool("non-existing")

            // Then
            assertTrue(retrieved == null)
        }
    }

    @Nested
    @DisplayName("获取所有工具")
    inner class GetAllTools {

        @Test
        @DisplayName("获取所有已注册工具")
        fun testGetAllTools_success() {
            // Given
            val dummy = DummyTool()
            val failing = FailingTool()
            toolRegistry.registerTool(dummy)
            toolRegistry.registerTool(failing)

            // When
            val allTools = toolRegistry.getAllTools()

            // Then
            assertEquals(2, allTools.size)
            assertTrue(allTools.contains(dummy))
            assertTrue(allTools.contains(failing))
        }

        @Test
        @DisplayName("空注册表返回空列表")
        fun testGetAllTools_empty_returnsEmpty() {
            // When
            val allTools = toolRegistry.getAllTools()

            // Then
            assertTrue(allTools.isEmpty())
        }
    }

    @Nested
    @DisplayName("获取工具名称")
    inner class GetToolNames {

        @Test
        @DisplayName("获取所有工具名称")
        fun testGetToolNames_success() {
            // Given
            toolRegistry.registerTool(DummyTool())
            toolRegistry.registerTool(FailingTool())

            // When
            val names = toolRegistry.getToolNames()

            // Then
            assertEquals(2, names.size)
            assertTrue(names.contains("dummy"))
            assertTrue(names.contains("failing"))
        }

        @Test
        @DisplayName("空注册表返回空列表")
        fun testGetToolNames_empty_returnsEmpty() {
            // When
            val names = toolRegistry.getToolNames()

            // Then
            assertTrue(names.isEmpty())
        }
    }

    @Nested
    @DisplayName("检查工具是否存在")
    inner class HasTool {

        @Test
        @DisplayName("存在的工具返回 true")
        fun testHasTool_existing_returnsTrue() {
            // Given
            toolRegistry.registerTool(DummyTool())

            // When
            val hasDummy = toolRegistry.hasTool("dummy")

            // Then
            assertTrue(hasDummy)
        }

        @Test
        @DisplayName("不存在的工具返回 false")
        fun testHasTool_nonExisting_returnsFalse() {
            // When
            val hasNonExisting = toolRegistry.hasTool("non-existing")

            // Then
            assertFalse(hasNonExisting)
        }
    }

    @Nested
    @DisplayName("获取工具描述")
    inner class GetToolDescriptions {

        @Test
        @DisplayName("获取所有工具描述")
        fun testGetToolDescriptions_success() {
            // Given
            toolRegistry.registerTool(DummyTool())
            toolRegistry.registerTool(FailingTool())

            // When
            val descriptions = toolRegistry.getToolDescriptions()

            // Then
            assertEquals(2, descriptions.size)
            assertTrue(descriptions.any { it.name == "dummy" })
            assertTrue(descriptions.any { it.name == "failing" })
        }

        @Test
        @DisplayName("工具描述包含参数信息")
        fun testGetToolDescriptions_includesParameters() {
            // Given
            toolRegistry.registerTool(DummyTool())

            // When
            val descriptions = toolRegistry.getToolDescriptions()
            val dummyDesc = descriptions.find { it.name == "dummy" }

            // Then
            assertTrue(dummyDesc != null)
            assertTrue(dummyDesc.parameters.contains("message"))
        }

        @Test
        @DisplayName("空参数工具显示无参数")
        fun testGetToolDescriptions_noParameters_showsNoParams() {
            // Given
            toolRegistry.registerTool(FailingTool())

            // When
            val descriptions = toolRegistry.getToolDescriptions()
            val failingDesc = descriptions.find { it.name == "failing" }

            // Then
            assertTrue(failingDesc != null)
            assertTrue(failingDesc.parameters.contains("无参数"))
        }

        @Test
        @DisplayName("空注册表返回空列表")
        fun testGetToolDescriptions_empty_returnsEmpty() {
            // When
            val descriptions = toolRegistry.getToolDescriptions()

            // Then
            assertTrue(descriptions.isEmpty())
        }
    }
}
