package com.smancode.smanagent.smancode;

import com.smancode.smanagent.model.session.ProjectInfo;
import com.smancode.smanagent.model.session.Session;
import com.smancode.smanagent.smancode.core.IntentRecognizer;
import com.smancode.smanagent.smancode.core.SmanCodeOrchestrator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SmanCode 编排器测试
 * <p>
 * 测试三阶段流程：意图识别 → 需求规划 → 执行。
 */
@SpringBootTest
class SmanCodeOrchestratorTest {

    @Autowired
    private SmanCodeOrchestrator orchestrator;

    @Autowired
    private IntentRecognizer intentRecognizer;

    private Session testSession;

    @BeforeEach
    void setUp() {
        assertNotNull(orchestrator, "SmanCodeOrchestrator should be autowired");
        assertNotNull(intentRecognizer, "IntentRecognizer should be autowired");

        // 创建测试会话
        testSession = new Session();
        testSession.setId("test-session");
        ProjectInfo projectInfo = new ProjectInfo();
        projectInfo.setProjectKey("test-project");
        testSession.setProjectInfo(projectInfo);
    }

    @Test
    void testIntentRecognition_Chat() {
        // 测试闲聊识别
        IntentRecognizer.IntentResult result =
            intentRecognizer.recognize(testSession, "你好");

        assertNotNull(result);
        assertEquals(IntentRecognizer.IntentType.CHAT, result.getIntentType());
        assertNotNull(result.getChatReply());
    }

    @Test
    void testIntentRecognition_Work() {
        // 测试工作请求识别
        IntentRecognizer.IntentResult result =
            intentRecognizer.recognize(testSession, "搜索所有包含 User 的类");

        assertNotNull(result);
        // 应该识别为 WORK 或 PLAN_ADJUST
        assertTrue(
            result.getIntentType() == IntentRecognizer.IntentType.WORK ||
            result.getIntentType() == IntentRecognizer.IntentType.PLAN_ADJUST
        );
    }

    @Test
    void testIntentRecognition_Confirm() {
        // 设置会话有进行中的目标
        testSession.startWorking();

        IntentRecognizer.IntentResult result =
            intentRecognizer.recognize(testSession, "确认");

        assertNotNull(result);
        // 应该识别为 PLAN_ADJUST（确认执行）
        assertEquals(IntentRecognizer.IntentType.PLAN_ADJUST, result.getIntentType());
    }

    @Test
    void testOrchestrator_Chat() {
        // 测试闲聊处理
        AtomicInteger partCount = new AtomicInteger(0);

        var message = orchestrator.process(testSession, "你好", part -> {
            partCount.incrementAndGet();
        });

        assertNotNull(message);
        assertTrue(partCount.get() > 0, "Should push at least one part");
        assertEquals(com.smancode.smanagent.model.message.Role.ASSISTANT, message.getRole());
    }

    @Test
    void testOrchestrator_Work() {
        // 测试工作请求处理
        AtomicInteger partCount = new AtomicInteger(0);

        var message = orchestrator.process(
            testSession,
            "搜索所有包含 UserController 的文件",
            part -> {
                partCount.incrementAndGet();
            }
        );

        assertNotNull(message);
        assertTrue(partCount.get() > 0, "Should push multiple parts");
        assertTrue(message.getParts().size() > 0);
    }

    @Test
    void testSessionStatus() {
        // 测试会话状态管理
        assertEquals(com.smancode.smanagent.model.session.SessionStatus.IDLE, testSession.getStatus());

        testSession.startWorking();
        assertEquals(com.smancode.smanagent.model.session.SessionStatus.WORKING, testSession.getStatus());

        testSession.complete();
        assertEquals(com.smancode.smanagent.model.session.SessionStatus.DONE, testSession.getStatus());
    }

    @Test
    void testSession_GoalManagement() {
        // 测试目标管理
        Session.Goal goal = new Session.Goal();
        goal.setId("goal-1");
        goal.setTitle("测试目标");
        goal.setDescription("这是一个测试目标");

        testSession.setGoal(goal);

        assertNotNull(testSession.getGoal());
        assertEquals("goal-1", testSession.getGoal().getId());
        assertEquals("测试目标", testSession.getGoal().getTitle());
        assertEquals(com.smancode.smanagent.model.session.Session.Goal.GoalStatus.PENDING, testSession.getGoal().getGoalStatus());

        goal.setGoalStatus(com.smancode.smanagent.model.session.Session.Goal.GoalStatus.IN_PROGRESS);
        assertEquals(com.smancode.smanagent.model.session.Session.Goal.GoalStatus.IN_PROGRESS, testSession.getGoal().getGoalStatus());
    }
}
