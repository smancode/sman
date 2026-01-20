package com.smancode.smanagent.smancode.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smancode.smanagent.model.part.ToolPart;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证 batch 工具参数解析 bug
 * <p>
 * Bug 描述：LLM 返回的 tool_calls 是数组，但解析后变成了 null 或空字符串
 */
class BatchToolBugTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void testBatchToolCallsParsingBug() throws Exception {
        // 真实场景：LLM 返回的 batch 工具调用
        String json = """
                {
                  "type": "tool",
                  "toolName": "batch",
                  "parameters": {
                    "tool_calls": [
                      {
                        "tool": "apply_change",
                        "parameters": {
                          "relativePath": "Test.java",
                          "mode": "replace"
                        }
                      },
                      {
                        "tool": "read_file",
                        "parameters": {
                          "simpleName": "Test"
                        }
                      }
                    ]
                  }
                }
                """;

        JsonNode partJson = objectMapper.readTree(json);

        // 使用反射调用实际的解析方法
        // 注意：这个 JSON 是标准格式 {"type": "tool", "toolName": "batch", "parameters": {...}}
        // 应该使用 createToolPart 方法
        SmanAgentLoop loop = new SmanAgentLoop();
        Method method = SmanAgentLoop.class.getDeclaredMethod(
            "createToolPart", JsonNode.class, String.class, String.class
        );
        method.setAccessible(true);

        ToolPart toolPart = (ToolPart) method.invoke(loop, partJson, "msg-id", "session-id");

        // 获取解析后的参数
        Map<String, Object> params = toolPart.getParameters();

        // 打印结果
        System.out.println("=== 测试结果 ===");
        System.out.println("toolName: " + toolPart.getToolName());
        System.out.println("params: " + params);
        System.out.println("tool_calls: " + params.get("tool_calls"));
        System.out.println("tool_calls type: " + (params.get("tool_calls") != null ? params.get("tool_calls").getClass() : "null"));

        // 验证：这个测试会失败，证明 bug 存在
        Object toolCalls = params.get("tool_calls");

        if (toolCalls == null) {
            fail("❌ Bug 确认：tool_calls 是 null");
        } else if (toolCalls instanceof String) {
            fail("❌ Bug 确认：tool_calls 是字符串: '" + toolCalls + "'");
        } else if (!(toolCalls instanceof List)) {
            fail("❌ Bug 确认：tool_calls 类型错误: " + toolCalls.getClass());
        } else {
            List<?> list = (List<?>) toolCalls;
            if (list.isEmpty()) {
                fail("❌ Bug 确认：tool_calls 是空列表");
            } else {
                System.out.println("✅ tool_calls 解析正确，包含 " + list.size() + " 个元素");

                // 验证第一个元素
                Map<String, Object> firstCall = (Map<String, Object>) list.get(0);
                assertEquals("apply_change", firstCall.get("tool"));
                assertNotNull(firstCall.get("parameters"));
            }
        }
    }
}
