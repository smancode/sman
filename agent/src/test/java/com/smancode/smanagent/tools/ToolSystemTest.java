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
 * 测试所有 6 个核心工具的功能。
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
        assertTrue(toolRegistry.hasTool("semantic_search"), "semantic_search should be registered");
        assertTrue(toolRegistry.hasTool("grep_file"), "grep_file should be registered");
        assertTrue(toolRegistry.hasTool("find_file"), "find_file should be registered");
        assertTrue(toolRegistry.hasTool("read_file"), "read_file should be registered");
        assertTrue(toolRegistry.hasTool("call_chain"), "call_chain should be registered");
        assertTrue(toolRegistry.hasTool("extract_xml"), "extract_xml should be registered");

        // 应该有 6 个工具
        assertEquals(6, toolRegistry.getToolNames().size(), "Should have 6 tools registered");
    }

    @Test
    void testSemanticSearchTool() {
        Tool tool = toolRegistry.getTool("semantic_search");
        assertNotNull(tool, "semantic_search tool should exist");
        assertEquals("semantic_search", tool.getName());
        assertEquals("根据语义相似性搜索代码片段", tool.getDescription());

        // 测试参数
        Map<String, ParameterDef> params = tool.getParameters();
        assertTrue(params.containsKey("query"));
        assertTrue(params.containsKey("topK"));
        assertTrue(params.get("query").isRequired());
        assertFalse(params.get("topK").isRequired());

        // 测试执行（local 模式）
        Map<String, Object> input = new HashMap<>();
        input.put("query", "搜索测试");
        input.put("topK", 10);
        input.put("mode", "local");

        ToolResult result = toolExecutor.execute("semantic_search", "test-project", input);
        assertTrue(result.isSuccess());
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

        // 测试 XML 提取
        Map<String, Object> input = new HashMap<>();
        input.put("text", "<test>content</test>");
        input.put("tagName", "test");
        input.put("mode", "local");

        ToolResult result = toolExecutor.execute("extract_xml", "test-project", input);
        assertTrue(result.isSuccess());
        assertEquals("content", result.getData());
    }

    @Test
    void testToolParameterValidation() {
        // 测试参数验证
        Map<String, Object> input = new HashMap<>();
        // 缺少必需参数 query

        ToolExecutor.ValidationResult result =
            toolExecutor.validateParameters("semantic_search", input);

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
