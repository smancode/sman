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
}
