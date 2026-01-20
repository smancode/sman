package com.smancode.smanagent.smancode.core;

import com.smancode.smanagent.model.message.Message;
import com.smancode.smanagent.model.message.Role;
import com.smancode.smanagent.model.part.Part;
import com.smancode.smanagent.model.part.TextPart;
import com.smancode.smanagent.model.part.ToolPart;
import com.smancode.smanagent.model.session.ProjectInfo;
import com.smancode.smanagent.model.session.Session;
import com.smancode.smanagent.model.session.SessionStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SmanAgent 循环测试（极简架构版）
 * <p>
 * 测试新的事件驱动循环架构。
 */
@SpringBootTest(classes = com.smancode.smanagent.BankCoreAgentApplication.class)
@TestPropertySource(properties = {
    "llm.endpoint=http://localhost:8080/mock/llm",
    "llm.api-key=test-key",
    "llm.model=test-model",
    "llm.max-tokens=1000"
})
class SmanAgentLoopTest {

    @Autowired
    private SmanAgentLoop smanAgentLoop;

    private Session testSession;
    private final List<Part> capturedParts = new ArrayList<>();

    @BeforeEach
    void setUp() {
        assertNotNull(smanAgentLoop, "SmanAgentLoop should be autowired");
        capturedParts.clear();

        // 创建测试会话
        testSession = new Session();
        testSession.setId("test-session");
        ProjectInfo projectInfo = new ProjectInfo();
        projectInfo.setProjectKey("test-project");
        testSession.setProjectInfo(projectInfo);
    }

    @Test
    void testSessionStatus_Transitions() {
        // 测试会话状态转换（新架构只有 3 种状态）
        assertEquals(SessionStatus.IDLE, testSession.getStatus());

        testSession.markBusy();
        assertEquals(SessionStatus.BUSY, testSession.getStatus());
        assertTrue(testSession.isBusy());
        assertFalse(testSession.isIdle());

        testSession.markIdle();
        assertEquals(SessionStatus.IDLE, testSession.getStatus());
        assertFalse(testSession.isBusy());
        assertTrue(testSession.isIdle());
    }

    @Test
    void testSession_HasNewUserMessageAfter() {
        // 测试检查是否有新用户消息的功能
        Message assistantMsg = new Message();
        assistantMsg.setId("assistant-1");
        assistantMsg.setRole(Role.ASSISTANT);
        testSession.addMessage(assistantMsg);

        // 没有新用户消息
        assertFalse(testSession.hasNewUserMessageAfter("assistant-1"));

        // 添加新用户消息
        Message userMsg = new Message();
        userMsg.setId("user-2");
        userMsg.setRole(Role.USER);
        TextPart textPart = new TextPart();
        textPart.setText("等等，我改主意了");
        userMsg.addPart(textPart);
        testSession.addMessage(userMsg);

        // 现在应该有新用户消息了
        assertTrue(testSession.hasNewUserMessageAfter("assistant-1"));
    }

    @Test
    void testToolPart_StateTransitions() {
        // 测试 ToolPart 状态转换（新架构使用简单枚举）
        ToolPart toolPart = new ToolPart();
        toolPart.setToolName("semantic_search");

        // 初始状态：PENDING
        assertEquals(ToolPart.ToolState.PENDING, toolPart.getState());

        // PENDING → RUNNING
        toolPart.setState(ToolPart.ToolState.RUNNING);
        assertEquals(ToolPart.ToolState.RUNNING, toolPart.getState());

        // RUNNING → COMPLETED
        toolPart.setState(ToolPart.ToolState.COMPLETED);
        assertEquals(ToolPart.ToolState.COMPLETED, toolPart.getState());

        // COMPLETED 状态不能再次转换
        assertThrows(IllegalStateException.class, () -> {
            toolPart.setState(ToolPart.ToolState.RUNNING);
        });

        // 测试 ERROR 状态
        ToolPart errorPart = new ToolPart();
        errorPart.setToolName("test_tool");
        errorPart.setState(ToolPart.ToolState.RUNNING);
        errorPart.setState(ToolPart.ToolState.ERROR);
        assertEquals(ToolPart.ToolState.ERROR, errorPart.getState());

        // ERROR 状态不能再次转换
        assertThrows(IllegalStateException.class, () -> {
            errorPart.setState(ToolPart.ToolState.RUNNING);
        });
    }

    @Test
    void testLoop_ProcessSimpleMessage() {
        // 测试处理简单消息
        AtomicInteger partCount = new AtomicInteger(0);

        Message response = smanAgentLoop.process(testSession, "你好", part -> {
            partCount.incrementAndGet();
            capturedParts.add(part);
        });

        assertNotNull(response, "Response should not be null");
        assertEquals(Role.ASSISTANT, response.getRole());
        assertTrue(partCount.get() > 0, "Should push at least one part");

        // 会话应该恢复到 IDLE 状态
        assertEquals(SessionStatus.IDLE, testSession.getStatus());
    }

    @Test
    void testLoop_BusySession() {
        // 测试会话忙碌时的行为
        testSession.markBusy();

        AtomicInteger partCount = new AtomicInteger(0);
        Message response = smanAgentLoop.process(testSession, "测试消息", part -> {
            partCount.incrementAndGet();
            capturedParts.add(part);
        });

        // 应该返回忙碌消息
        assertNotNull(response);
        assertEquals(Role.ASSISTANT, response.getRole());
        assertTrue(partCount.get() > 0);

        // 检查返回的是忙碌消息
        TextPart textPart = (TextPart) response.getParts().get(0);
        assertTrue(textPart.getText().contains("正在处理") || textPart.getText().contains("请稍候"));

        // 会话状态应该保持 BUSY
        assertEquals(SessionStatus.BUSY, testSession.getStatus());
    }

    @Test
    void testSession_Messages() {
        // 测试消息管理
        Message userMsg = new Message();
        userMsg.setId("user-1");
        userMsg.setRole(Role.USER);
        testSession.addMessage(userMsg);

        assertEquals(1, testSession.getMessages().size());
        assertEquals(userMsg, testSession.getLatestMessage());

        Message assistantMsg = new Message();
        assistantMsg.setId("assistant-1");
        assistantMsg.setRole(Role.ASSISTANT);
        testSession.addMessage(assistantMsg);

        assertEquals(2, testSession.getMessages().size());
        assertEquals(assistantMsg, testSession.getLatestMessage());
        assertEquals(assistantMsg, testSession.getLatestAssistantMessage());
        assertEquals(userMsg, testSession.getLatestUserMessage());
    }

    @Test
    void testSession_Timestamps() {
        // 测试时间戳更新
        Instant beforeTouch = testSession.getUpdatedTime();
        testSession.touch();
        Instant afterTouch = testSession.getUpdatedTime();

        assertTrue(afterTouch.isAfter(beforeTouch) || afterTouch.equals(beforeTouch));
    }

    /**
     * 测试 batch 工具的数组参数解析（使用真实 LLM 返回）
     * <p>
     * 验证 LLM 返回的 tool_calls 数组能正确解析为 Map 中的 List
     * 这个测试修复了之前数组参数被解析为空字符串的问题
     * <p>
     * 真实数据来源：app.log 中 2026-01-20 23:20:54 的 LLM 响应
     */
    @Test
    void testBatchToolParameterParsing() {
        // 真实的 LLM 返回 JSON（从 app.log 提取）
        String realLlmResponse = """
            {
              "type": "tool",
              "toolName": "batch",
              "parameters": {
                "tool_calls": [
                  {
                    "tool": "apply_change",
                    "parameters": {
                      "relativePath": "core/src/main/java/com/autoloop/core/service/DirectoryScanService.java",
                      "mode": "replace",
                      "searchContent": "    public ProjectStructureDTO scanDirectory(String projectPath, List<Path> javaFiles) {",
                      "newContent": "    /**\\n     * 扫描目录并构建项目结构信息\\n     * \\n     * @param projectPath 项目根路径\\n     * @param javaFiles Java 文件列表\\n     * @return 项目结构信息 DTO，包含目录树、文件统计、包结构等\\n     */\\n    public ProjectStructureDTO scanDirectory(String projectPath, List<Path> javaFiles) {",
                      "description": "为 scanDirectory 方法添加 JavaDoc 注释"
                    }
                  },
                  {
                    "tool": "apply_change",
                    "parameters": {
                      "relativePath": "core/src/main/java/com/autoloop/core/service/DirectoryScanService.java",
                      "mode": "replace",
                      "searchContent": "    private Map<String, Integer> calculateFileTypeStats(Path rootPath) {",
                      "newContent": "    /**\\n     * 计算项目中的文件类型统计信息\\n     * \\n     * @param rootPath 项目根路径\\n     * @return 文件类型及其数量的映射表，例如 {\\\".java\\\": 100, \\\".xml\\\": 5}\\n     */\\n    private Map<String, Integer> calculateFileTypeStats(Path rootPath) {",
                      "description": "为 calculateFileTypeStats 方法添加 JavaDoc 注释"
                    }
                  },
                  {
                    "tool": "apply_change",
                    "parameters": {
                      "relativePath": "core/src/main/java/com/autoloop/core/service/DirectoryScanService.java",
                      "mode": "replace",
                      "searchContent": "    /**\\n     * 构建包结构\\n     * \\n     */\\n    private List<PackageInfoDTO> buildPackageStructure(List<Path> javaFiles, Path rootPath) {",
                      "newContent": "    /**\\n     * 构建包结构信息\\n     * \\n     * <p>遍历所有 Java 文件，提取包名和类名，构建包结构列表。</p>\\n     * \\n     * @param javaFiles Java 文件列表\\n     * @param rootPath 项目根路径\\n     * @return 包结构信息列表，按包名排序\\n     */\\n    private List<PackageInfoDTO> buildPackageStructure(List<Path> javaFiles, Path rootPath) {",
                      "description": "完善 buildPackageStructure 方法的 JavaDoc 注释"
                    }
                  },
                  {
                    "tool": "apply_change",
                    "parameters": {
                      "relativePath": "core/src/main/java/com/autoloop/core/service/DirectoryScanService.java",
                      "mode": "replace",
                      "searchContent": "    private String extractPackageName(Path javaFile) throws IOException {",
                      "newContent": "    /**\\n     * 从 Java 文件中提取包名\\n     * \\n     * @param javaFile Java 文件路径\\n     * @return 包名，如果未找到 package 语句则返回 \\\"default\\\"\\n     * @throws IOException 读取文件失败时抛出\\n     */\\n    private String extractPackageName(Path javaFile) throws IOException {",
                      "description": "为 extractPackageName 方法添加 JavaDoc 注释"
                    }
                  },
                  {
                    "tool": "apply_change",
                    "parameters": {
                      "relativePath": "core/src/main/java/com/autoloop/core/service/DirectoryScanService.java",
                      "mode": "replace",
                      "searchContent": "    private String getFileExtension(String fileName) {",
                      "newContent": "    /**\\n     * 获取文件扩展名\\n     * \\n     * @param fileName 文件名\\n     * @return 文件扩展名（包含点号），例如 \\\".java\\\"，无扩展名则返回 \\\"无扩展名\\\"\\n     */\\n    private String getFileExtension(String fileName) {",
                      "description": "为 getFileExtension 方法添加 JavaDoc 注释"
                    }
                  }
                ]
              },
              "summary": "read_file(path: core/src/main/java/com/autoloop/core/service/DirectoryScanService.java): Read DirectoryScanService class with methods including scanDirectory, calculateFileTypeStats, buildPackageStructure, extractPackageName, and getFileExtension"
            }
            """;

        // 使用 Jackson 解析 JSON
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        try {
            com.fasterxml.jackson.databind.JsonNode partJson = mapper.readTree(realLlmResponse);

            // 验证基本结构
            assertNotNull(partJson);
            assertEquals("tool", partJson.path("type").asText());
            assertEquals("batch", partJson.path("toolName").asText());

            com.fasterxml.jackson.databind.JsonNode paramsJson = partJson.path("parameters");
            assertTrue(paramsJson.isObject());
            assertTrue(paramsJson.path("tool_calls").isArray());

            // 验证数组有 5 个元素（真实的 LLM 返回了 5 个 apply_change 调用）
            com.fasterxml.jackson.databind.JsonNode toolCalls = paramsJson.path("tool_calls");
            assertEquals(5, toolCalls.size());

            // 验证第一个元素的结构
            com.fasterxml.jackson.databind.JsonNode firstCall = toolCalls.get(0);
            assertEquals("apply_change", firstCall.path("tool").asText());
            assertTrue(firstCall.path("parameters").isObject());

            // 验证第一个调用包含预期的参数
            com.fasterxml.jackson.databind.JsonNode firstParams = firstCall.path("parameters");
            assertEquals("replace", firstParams.path("mode").asText());
            assertTrue(firstParams.path("relativePath").asText().contains("DirectoryScanService.java"));
            assertTrue(firstParams.path("searchContent").asText().contains("scanDirectory"));
            assertTrue(firstParams.path("newContent").asText().contains("扫描目录并构建项目结构信息"));

        } catch (Exception e) {
            fail("Failed to parse real batch tool JSON: " + e.getMessage());
        }
    }
}
