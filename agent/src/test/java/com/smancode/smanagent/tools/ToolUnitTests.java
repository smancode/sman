package com.smancode.smanagent.tools;

import com.smancode.smanagent.tools.analysis.CallChainTool;
import com.smancode.smanagent.tools.analysis.ExtractXmlTool;
import com.smancode.smanagent.tools.read.ReadFileTool;
import com.smancode.smanagent.tools.search.FindFileTool;
import com.smancode.smanagent.tools.search.GrepFileTool;
import com.smancode.smanagent.tools.search.SemanticSearchTool;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 工具单元测试（无 Spring 上下文）
 * <p>
 * 测试所有 6 个核心工具的基本功能。
 */
class ToolUnitTests {

    @Test
    void testExtractXmlTool_Success() {
        ExtractXmlTool tool = new ExtractXmlTool();

        Map<String, Object> params = new HashMap<>();
        params.put("text", "<root><test>content</test></root>");
        params.put("tagName", "test");
        params.put("mode", "local");

        ToolResult result = tool.execute("test-project", params);

        assertTrue(result.isSuccess(), "工具执行应该成功");
        assertEquals("content", result.getData(), "应该提取到正确的内容");
    }

    @Test
    void testExtractXmlTool_NotFound() {
        ExtractXmlTool tool = new ExtractXmlTool();

        Map<String, Object> params = new HashMap<>();
        params.put("text", "<root><other>data</other></root>");
        params.put("tagName", "test");
        params.put("mode", "local");

        ToolResult result = tool.execute("test-project", params);

        assertTrue(result.isSuccess());
        assertNull(result.getData(), "标签不存在时应返回 null");
    }

    @Test
    void testExtractXmlTool_MissingParams() {
        ExtractXmlTool tool = new ExtractXmlTool();

        // 缺少 text 参数
        Map<String, Object> params = new HashMap<>();
        params.put("tagName", "test");

        ToolResult result = tool.execute("test-project", params);

        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("缺少"));
    }

    @Test
    void testAllTools_HaveMetadata() {
        // 测试所有工具都有正确的元数据
        ExtractXmlTool xmlTool = new ExtractXmlTool();
        CallChainTool chainTool = new CallChainTool();
        ReadFileTool readTool = new ReadFileTool();
        FindFileTool findTool = new FindFileTool();
        GrepFileTool grepTool = new GrepFileTool();
        SemanticSearchTool semanticTool = new SemanticSearchTool();

        // ExtractXmlTool
        assertEquals("extract_xml", xmlTool.getName());
        assertEquals("从文本中提取 XML 标签内容", xmlTool.getDescription());
        assertTrue(xmlTool.getParameters().containsKey("text"));
        assertTrue(xmlTool.getParameters().containsKey("tagName"));

        // CallChainTool
        assertEquals("call_chain", chainTool.getName());
        assertEquals("分析方法调用链（向上和向下）", chainTool.getDescription());
        assertTrue(chainTool.getParameters().containsKey("className"));
        assertTrue(chainTool.getParameters().containsKey("methodName"));

        // ReadFileTool
        assertEquals("read_file", readTool.getName());
        assertEquals("读取文件内容（支持行范围）", readTool.getDescription());
        assertTrue(readTool.getParameters().containsKey("relativePath"));

        // FindFileTool
        assertEquals("find_file", findTool.getName());
        assertEquals("按文件名正则搜索文件", findTool.getDescription());
        assertTrue(findTool.getParameters().containsKey("filePattern"));

        // GrepFileTool
        assertEquals("grep_file", grepTool.getName());
        assertEquals("使用正则表达式搜索文件内容", grepTool.getDescription());
        assertTrue(grepTool.getParameters().containsKey("pattern"));

        // SemanticSearchTool
        assertEquals("semantic_search", semanticTool.getName());
        assertEquals("根据语义相似性搜索代码片段", semanticTool.getDescription());
        assertTrue(semanticTool.getParameters().containsKey("query"));
    }

    @Test
    void testToolExecutionModes() {
        // 测试执行模式
        ExtractXmlTool xmlTool = new ExtractXmlTool();
        SemanticSearchTool semanticTool = new SemanticSearchTool();

        // extract_xml 固定为 local
        Map<String, Object> params = new HashMap<>();
        assertEquals(Tool.ExecutionMode.LOCAL, xmlTool.getExecutionMode(params));

        // semantic_search 固定为 local
        assertEquals(Tool.ExecutionMode.LOCAL, semanticTool.getExecutionMode(params));

        // 其他工具默认为 intellij
        ReadFileTool readTool = new ReadFileTool();
        assertEquals(Tool.ExecutionMode.INTELLIJ, readTool.getExecutionMode(params));

        // 可以通过 mode 参数覆盖
        params.put("mode", "local");
        assertEquals(Tool.ExecutionMode.LOCAL, readTool.getExecutionMode(params));
    }

    @Test
    void testToolResult_Success() {
        ToolResult result = ToolResult.success(
            "test data",
            "测试标题",
            "测试内容"
        );

        assertTrue(result.isSuccess());
        assertEquals("test data", result.getData());
        assertEquals("测试标题", result.getDisplayTitle());
        assertEquals("测试内容", result.getDisplayContent());
    }

    @Test
    void testToolResult_Failure() {
        ToolResult result = ToolResult.failure("测试错误");

        assertFalse(result.isSuccess());
        assertEquals("测试错误", result.getError());
    }

    @Test
    void testToolResult_ExecutionTime() {
        ToolResult result = ToolResult.success(null, "title", "content");
        result.setExecutionTimeMs(123);

        assertEquals(123, result.getExecutionTimeMs());
    }
}
