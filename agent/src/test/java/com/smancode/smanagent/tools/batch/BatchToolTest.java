package com.smancode.smanagent.tools.batch;

import com.smancode.smanagent.tools.ToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BatchTool 单元测试
 */
class BatchToolTest {

    private BatchTool batchTool;

    @BeforeEach
    void setUp() {
        batchTool = new BatchTool();
    }

    @Test
    void testGetName() {
        assertEquals("batch", batchTool.getName());
    }

    @Test
    void testGetDescription() {
        String description = batchTool.getDescription();
        assertNotNull(description);
        assertFalse(description.isEmpty());
        assertTrue(description.contains("sequence"));
    }

    @Test
    void testGetParameters() {
        Map<String, com.smancode.smanagent.tools.ParameterDef> params = batchTool.getParameters();
        assertTrue(params.containsKey("tool_calls"));
        assertTrue(params.get("tool_calls").isRequired());
    }

    @Test
    void testExecute_WithValidToolCalls() {
        // 构造测试参数
        Map<String, Object> params = new HashMap<>();
        List<Map<String, Object>> toolCalls = new ArrayList<>();

        // 添加一个模拟的工具调用（实际会失败因为没有完整环境）
        Map<String, Object> call1 = new HashMap<>();
        call1.put("tool", "extract_xml");
        Map<String, Object> call1Params = new HashMap<>();
        call1Params.put("tagPattern", "test");
        call1Params.put("relativePath", "test.xml");
        call1Params.put("mode", "local");
        call1.put("parameters", call1Params);

        toolCalls.add(call1);
        params.put("tool_calls", toolCalls);

        // 执行
        ToolResult result = batchTool.execute("test-project", params);

        // 验证
        assertTrue(result.isSuccess(), "Batch execution should succeed");
        assertNotNull(result.getBatchSubResults());
        assertEquals(1, result.getBatchSubResults().size());
    }

    @Test
    void testExecute_MissingToolCalls() {
        Map<String, Object> params = new HashMap<>();
        // 不提供 tool_calls

        ToolResult result = batchTool.execute("test-project", params);

        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("缺少 tool_calls 参数"));
    }

    @Test
    void testExecute_EmptyToolCalls() {
        Map<String, Object> params = new HashMap<>();
        params.put("tool_calls", new ArrayList<>());

        ToolResult result = batchTool.execute("test-project", params);

        assertFalse(result.isSuccess());
        // 应该返回失败，因为 tool_calls 数组不能为空
    }

    @Test
    void testExecute_ToolCallsLimit() {
        // 创建 11 个工具调用（超过限制）
        Map<String, Object> params = new HashMap<>();
        List<Map<String, Object>> toolCalls = new ArrayList<>();

        for (int i = 0; i < 11; i++) {
            Map<String, Object> call = new HashMap<>();
            call.put("tool", "extract_xml");
            Map<String, Object> callParams = new HashMap<>();
            callParams.put("tagPattern", "test");
            callParams.put("relativePath", "test.xml");
            callParams.put("mode", "local");
            call.put("parameters", callParams);
            toolCalls.add(call);
        }

        params.put("tool_calls", toolCalls);

        ToolResult result = batchTool.execute("test-project", params);

        assertTrue(result.isSuccess());
        // 应该只执行 10 个（丢弃 1 个）
        assertNotNull(result.getBatchSubResults());
        assertEquals(10, result.getBatchSubResults().size());
    }

    @Test
    void testGetExecutionMode() {
        Map<String, Object> params = new HashMap<>();
        assertEquals(com.smancode.smanagent.tools.Tool.ExecutionMode.LOCAL,
                     batchTool.getExecutionMode(params));
    }
}
