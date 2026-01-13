package com.smancode.smanagent.model;

import com.smancode.smanagent.model.message.Message;
import com.smancode.smanagent.model.message.Role;
import com.smancode.smanagent.model.part.TextPart;
import com.smancode.smanagent.model.part.ToolPart;
import com.smancode.smanagent.model.session.ProjectInfo;
import com.smancode.smanagent.model.session.Session;
import com.smancode.smanagent.model.session.SessionStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 模型单元测试（纯数据结构测试，不需要 Spring 上下文）
 * <p>
 * 测试核心数据结构和状态转换逻辑。
 */
class ModelTest {

    private Session testSession;

    @BeforeEach
    void setUp() {
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
        // 先添加一个用户消息作为起点
        Message firstUserMsg = new Message();
        firstUserMsg.setId("user-0");
        firstUserMsg.setRole(Role.USER);
        testSession.addMessage(firstUserMsg);

        // 添加助手消息
        Message assistantMsg = new Message();
        assistantMsg.setId("assistant-1");
        assistantMsg.setRole(Role.ASSISTANT);
        testSession.addMessage(assistantMsg);

        // 此时在 assistant-1 之后没有新用户消息
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

    @Test
    void testSession_EmptyMessages() {
        // 测试空会话
        Session emptySession = new Session();
        assertNull(emptySession.getLatestMessage());
        assertNull(emptySession.getLatestAssistantMessage());
        assertNull(emptySession.getLatestUserMessage());
        assertFalse(emptySession.hasNewUserMessageAfter(null));
    }

    @Test
    void testTextPart_Timestamps() {
        // 测试 TextPart 的时间戳
        TextPart textPart = new TextPart();
        textPart.setText("测试内容");
        textPart.touch();

        assertNotNull(textPart.getCreatedTime());
        assertNotNull(textPart.getUpdatedTime());
        // createdTime 和 updatedTime 应该接近或相等（允许微小差异）
        assertTrue(Math.abs(textPart.getCreatedTime().toEpochMilli() -
                   textPart.getUpdatedTime().toEpochMilli()) <= 10);
    }

    @Test
    void testToolPart_Parameters() {
        // 测试 ToolPart 的参数
        ToolPart toolPart = new ToolPart();
        toolPart.setToolName("semantic_search");

        java.util.Map<String, Object> params = new java.util.HashMap<>();
        params.put("query", "测试查询");
        params.put("topK", 10);

        toolPart.setParameters(params);

        assertEquals("semantic_search", toolPart.getToolName());
        assertEquals("测试查询", toolPart.getParameters().get("query"));
        assertEquals(10, toolPart.getParameters().get("topK"));
    }

    @Test
    void testMessage_Parts() {
        // 测试 Message 的 Parts 管理
        Message message = new Message();
        message.setId("msg-1");
        message.setRole(Role.USER);

        TextPart textPart = new TextPart();
        textPart.setText("测试文本");
        textPart.setMessageId("msg-1");

        message.addPart(textPart);

        assertEquals(1, message.getParts().size());
        assertEquals(textPart, message.getParts().get(0));

        // 测试消息角色判断
        Message userMsg = new Message();
        userMsg.setRole(Role.USER);
        assertTrue(userMsg.isUserMessage());
        assertFalse(userMsg.isAssistantMessage());

        Message assistantMsg = new Message();
        assistantMsg.setRole(Role.ASSISTANT);
        assertFalse(assistantMsg.isUserMessage());
        assertTrue(assistantMsg.isAssistantMessage());
    }
}
