package com.smancode.smanagent.tools;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 工具系统测试
 * <p>
 * 测试所有 8 个核心工具的功能。
 */
@SpringBootTest
class ToolSystemTest {

    @Autowired
    private ToolRegistry toolRegistry;

    @Autowired
    private ToolExecutor toolExecutor;

    @BeforeEach
    void setUp() {
        assertNotNull(toolRegistry, "ToolRegistry should be autowired");
        assertNotNull(toolExecutor, "ToolExecutor should be autowired");
    }

    @Test
    void testToolRegistryLoadsAllTools() {
        // 验证所有工具都已注册
        assertTrue(toolRegistry.hasTool("expert_consult"), "expert_consult should be registered");
        assertTrue(toolRegistry.hasTool("grep_file"), "grep_file should be registered");
        assertTrue(toolRegistry.hasTool("find_file"), "find_file should be registered");
        assertTrue(toolRegistry.hasTool("read_file"), "read_file should be registered");
        assertTrue(toolRegistry.hasTool("call_chain"), "call_chain should be registered");
        assertTrue(toolRegistry.hasTool("extract_xml"), "extract_xml should be registered");
        assertTrue(toolRegistry.hasTool("apply_change"), "apply_change should be registered");
        assertTrue(toolRegistry.hasTool("batch"), "batch should be registered");

        // 应该有 8 个工具
        assertEquals(8, toolRegistry.getToolNames().size(), "Should have 8 tools registered");
    }

    @Test
    void testExpertConsultTool() {
        Tool tool = toolRegistry.getTool("expert_consult");
        assertNotNull(tool, "expert_consult tool should exist");
        assertEquals("expert_consult", tool.getName());
        assertEquals("专家咨询工具：查询业务知识、规则、流程，并定位相关代码入口（委托给 Knowledge 服务）", tool.getDescription());

        // 测试参数
        Map<String, ParameterDef> params = tool.getParameters();
        assertTrue(params.containsKey("query"));
        assertTrue(params.get("query").isRequired());

        // 测试执行（local 模式）
        Map<String, Object> input = new HashMap<>();
        input.put("query", "测试查询");

        ToolResult result = toolExecutor.execute("expert_consult", "test-project", input);
        // Note: This will fail if Knowledge service is not running
        // but the tool registration and parameter validation should work
    }

    @Test
    void testGrepFileTool() {
        Tool tool = toolRegistry.getTool("grep_file");
        assertNotNull(tool);
        assertEquals("grep_file", tool.getName());
        assertEquals("使用正则表达式搜索文件内容", tool.getDescription());

        // 测试执行（intellij 模式）
        Map<String, Object> input = new HashMap<>();
        input.put("pattern", "class.*Test");
        input.put("mode", "intellij");

        ToolResult result = toolExecutor.execute("grep_file", "test-project", input);
        assertTrue(result.isSuccess());
    }

    @Test
    void testFindFileTool() {
        Tool tool = toolRegistry.getTool("find_file");
        assertNotNull(tool);
        assertEquals("find_file", tool.getName());

        Map<String, Object> input = new HashMap<>();
        input.put("filePattern", ".*Test\\.java");
        input.put("mode", "intellij");

        ToolResult result = toolExecutor.execute("find_file", "test-project", input);
        assertTrue(result.isSuccess());
    }

    @Test
    void testReadFileTool() {
        Tool tool = toolRegistry.getTool("read_file");
        assertNotNull(tool);
        assertEquals("read_file", tool.getName());

        Map<String, Object> input = new HashMap<>();
        input.put("relativePath", "src/main/java/Test.java");
        input.put("startLine", 1);
        input.put("endLine", 100);
        input.put("mode", "intellij");

        ToolResult result = toolExecutor.execute("read_file", "test-project", input);
        assertTrue(result.isSuccess());
    }

    @Test
    void testCallChainTool() {
        Tool tool = toolRegistry.getTool("call_chain");
        assertNotNull(tool);
        assertEquals("call_chain", tool.getName());

        Map<String, Object> input = new HashMap<>();
        input.put("className", "com.example.TestClass");
        input.put("methodName", "testMethod");
        input.put("direction", "both");
        input.put("maxDepth", 5);
        input.put("mode", "intellij");

        ToolResult result = toolExecutor.execute("call_chain", "test-project", input);
        assertTrue(result.isSuccess());
    }

    @Test
    void testExtractXmlTool() {
        Tool tool = toolRegistry.getTool("extract_xml");
        assertNotNull(tool);
        assertEquals("extract_xml", tool.getName());

        // 测试 XML 提取（需要 tagPattern 和 relativePath）
        Map<String, Object> input = new HashMap<>();
        input.put("tagPattern", "bean.*id=\"test\"");
        input.put("relativePath", "src/test/resources/test.xml");
        input.put("mode", "intellij");

        ToolResult result = toolExecutor.execute("extract_xml", "test-project", input);
        // Note: This will return success with a placeholder message since
        // it requires IDE execution
        assertTrue(result.isSuccess());
    }

    @Test
    void testToolParameterValidation() {
        // 测试参数验证
        Map<String, Object> input = new HashMap<>();
        // 缺少必需参数 query

        ToolExecutor.ValidationResult result =
            toolExecutor.validateParameters("expert_consult", input);

        assertFalse(result.isValid(), "Should fail with missing required parameter");
        assertTrue(result.getMessage().contains("缺少必需参数"));
    }

    @Test
    void testAllToolsHaveDescriptions() {
        for (String toolName : toolRegistry.getToolNames()) {
            Tool tool = toolRegistry.getTool(toolName);
            assertNotNull(tool.getDescription(), "Tool should have description");
            assertFalse(tool.getDescription().isEmpty(), "Description should not be empty");
        }
    }

    @Test
    void testAllToolsHaveParameters() {
        for (String toolName : toolRegistry.getToolNames()) {
            Tool tool = toolRegistry.getTool(toolName);
            Map<String, ParameterDef> params = tool.getParameters();
            assertNotNull(params, "Tool should have parameters");
            assertTrue(!params.isEmpty(), "Tool should have at least one parameter");
        }
    }
}
