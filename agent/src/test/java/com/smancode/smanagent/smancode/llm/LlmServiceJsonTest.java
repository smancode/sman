package com.smancode.smanagent.smancode.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 测试 LLM JSON 响应解析（带 Markdown 代码块）
 */
class LlmServiceJsonTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 从日志中提取的实际 LLM 响应内容
     */
    private static final String ACTUAL_LLM_RESPONSE = """
            ```json
            {
              "acknowledgment": "已理解问题，将进行分析。"
            }
            ```
            """;

    @Test
    void testCleanMarkdownCodeBlock() throws Exception {
        // 模拟 LlmService.cleanMarkdownCodeBlock 方法
        String content = ACTUAL_LLM_RESPONSE;
        String cleaned = content.trim();

        // 移除开头的 ```json 或 ```
        cleaned = cleaned.replaceAll("^```[\\w]*\\n?", "");

        // 移除结尾的 ```
        cleaned = cleaned.replaceAll("\\n?```$", "");

        cleaned = cleaned.trim();

        // 验证清理后的结果
        String expectedJson = """
                {
                  "acknowledgment": "已理解问题，将进行分析。"
                }
                """;
        assertEquals(expectedJson.trim(), cleaned);

        // 验证可以解析为 JSON
        JsonNode jsonNode = objectMapper.readTree(cleaned);
        assertTrue(jsonNode.has("acknowledgment"));
        assertEquals("已理解问题，将进行分析。", jsonNode.path("acknowledgment").asText());
    }

    @Test
    void testValidateJsonFlow() throws Exception {
        // 模拟完整的 validateJson 流程
        String jsonText = ACTUAL_LLM_RESPONSE;

        // 步骤 1: 清理 Markdown 代码块
        String cleaned = jsonText.trim();
        cleaned = cleaned.replaceAll("^```[\\w]*\\n?", "");
        cleaned = cleaned.replaceAll("\\n?```$", "");
        cleaned = cleaned.trim();

        // 步骤 2: 解析 JSON
        JsonNode result = objectMapper.readTree(cleaned);

        // 验证结果
        assertNotNull(result);
        assertTrue(result.isObject());
        assertTrue(result.has("acknowledgment"));
        assertEquals("已理解问题，将进行分析。", result.path("acknowledgment").asText());
    }

    @Test
    void testEdgeCases() throws Exception {
        // 测试没有 Markdown 代码块的纯 JSON
        String pureJson = "{\"key\": \"value\"}";
        String cleaned1 = pureJson.trim().replaceAll("^```[\\w]*\\n?", "").replaceAll("\\n?```$", "").trim();
        JsonNode result1 = objectMapper.readTree(cleaned1);
        assertEquals("value", result1.path("key").asText());

        // 测试带 ``` 但没有语言标记
        String withGenericCodeBlock = """
                ```
                {"test": "value"}
                ```
                """;
        String cleaned2 = withGenericCodeBlock.trim().replaceAll("^```[\\w]*\\n?", "").replaceAll("\\n?```$", "").trim();
        JsonNode result2 = objectMapper.readTree(cleaned2);
        assertEquals("value", result2.path("test").asText());
    }
}
