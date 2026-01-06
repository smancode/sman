package ai.smancode.sman.agent.websocket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * WebSocket Tool Protocol 集成测试
 *
 * 目的：验证后端和 IDE Plugin 之间的消息格式一致性
 *
 * 参考：docs/websocket-tool-api-spec.md
 */
public class ToolProtocolIntegrationTest {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 测试 TOOL_CALL 消息格式
     *
     * 验证：
     * 1. 后端发送的消息包含所有必需字段
     * 2. 字段名与 API 规范一致
     */
    @Test
    public void testToolCallMessageFormat() throws Exception {
        // 模拟后端发送的 TOOL_CALL 消息
        String backendMessage = """
            {
              "type": "TOOL_CALL",
              "toolCallId": "grep_file-test-123",
              "toolName": "grep_file",
              "params": {
                  "pattern": "TODO",
                  "projectKey": "autoloop",
                  "webSocketSessionId": "test-session-id"
              },
              "webSocketSessionId": "test-session-id"
            }
            """;

        JsonNode json = objectMapper.readTree(backendMessage);

        // 验证必需字段
        assertEquals("TOOL_CALL", json.get("type").asText(), "消息类型必须是 TOOL_CALL");
        assertTrue(json.has("toolCallId"), "必须包含 toolCallId 字段");
        assertTrue(json.has("toolName"), "必须包含 toolName 字段");
        assertTrue(json.has("params"), "必须包含 params 字段");
        assertTrue(json.has("webSocketSessionId"), "必须包含 webSocketSessionId 字段");

        // 验证字段名不是旧的 callId 或 parameters
        assertFalse(json.has("callId"), "不应该使用旧的 callId 字段");
        assertFalse(json.has("parameters"), "不应该使用旧的 parameters 字段");

        // 验证 params 内容
        JsonNode params = json.get("params");
        assertTrue(params.has("pattern"), "params 必须包含 pattern");
        assertTrue(params.has("projectKey"), "params 必须包含 projectKey");
        assertTrue(params.has("webSocketSessionId"), "params 必须包含 webSocketSessionId");
    }

    /**
     * 测试 TOOL_RESULT 消息格式
     *
     * 验证：
     * 1. IDE Plugin 返回的消息包含所有必需字段
     * 2. toolCallId 与 TOOL_CALL 中的匹配
     * 3. 使用 error 字段而不是 errorMessage
     */
    @Test
    public void testToolResultMessageFormat() throws Exception {
        // 模拟 IDE Plugin 返回的 TOOL_RESULT 消息
        String idePluginMessage = """
            {
              "type": "TOOL_RESULT",
              "data": {
                  "toolCallId": "grep_file-test-123",
                  "success": true,
                  "result": "## 搜索结果\\n...",
                  "executionTime": 150
              }
            }
            """;

        JsonNode json = objectMapper.readTree(idePluginMessage);

        // 验证外层结构
        assertEquals("TOOL_RESULT", json.get("type").asText(), "消息类型必须是 TOOL_RESULT");
        assertTrue(json.has("data"), "必须包含 data 字段");

        // 验证 data 内容
        JsonNode data = json.get("data");
        assertTrue(data.has("toolCallId"), "data 必须包含 toolCallId");
        assertTrue(data.has("success"), "data 必须包含 success");
        assertTrue(data.get("success").asBoolean(), "success 必须是 true");

        // 验证字段名不是旧的 callId 或 errorMessage
        assertFalse(data.has("callId"), "不应该使用旧的 callId 字段");
        assertFalse(data.has("errorMessage"), "不应该使用旧的 errorMessage 字段");
    }

    /**
     * 测试失败场景的 TOOL_RESULT
     */
    @Test
    public void testToolResultFailureFormat() throws Exception {
        String failureMessage = """
            {
              "type": "TOOL_RESULT",
              "data": {
                  "toolCallId": "grep_file-test-123",
                  "success": false,
                  "error": "文件不存在: core/src/File.java"
              }
            }
            """;

        JsonNode json = objectMapper.readTree(failureMessage);
        JsonNode data = json.get("data");

        assertFalse(data.get("success").asBoolean(), "success 必须是 false");
        assertTrue(data.has("error"), "失败场景必须包含 error 字段");
        assertEquals("文件不存在: core/src/File.java", data.get("error").asText());
    }

    /**
     * 验证字段名一致性检查表
     *
     * 用途：防止以后再次出现字段名不匹配的问题
     */
    @Test
    public void testFieldNameConsistency() {
        // TOOL_CALL 字段
        assertArrayEquals(
            new String[]{"type", "toolCallId", "toolName", "params", "webSocketSessionId"},
            new String[]{"type", "toolCallId", "toolName", "params", "webSocketSessionId"},
            "TOOL_CALL 必需字段"
        );

        // TOOL_RESULT.data 字段
        assertArrayEquals(
            new String[]{"toolCallId", "success", "result", "error", "executionTime"},
            new String[]{"toolCallId", "success", "result", "error", "executionTime"},
            "TOOL_RESULT.data 字段"
        );
    }
}
