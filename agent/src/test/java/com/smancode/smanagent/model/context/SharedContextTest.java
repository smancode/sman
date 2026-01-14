package com.smancode.smanagent.model.context;

import com.smancode.smanagent.model.subtask.SubTaskConclusion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SharedContext 单元测试
 */
class SharedContextTest {

    private SharedContext context;

    @BeforeEach
    void setUp() {
        context = new SharedContext();
    }

    @Test
    void testAddAndGetConclusion() {
        SubTaskConclusion conclusion = new SubTaskConclusion();
        conclusion.setSubTaskId("subtask-1");
        conclusion.setTarget("TestClass");
        conclusion.setQuestion("这个类是做什么的？");
        conclusion.setConclusion("这是一个测试类");

        context.addConclusion("subtask-1", conclusion);

        SubTaskConclusion retrieved = context.getConclusion("subtask-1");
        assertNotNull(retrieved);
        assertEquals("subtask-1", retrieved.getSubTaskId());
        assertEquals("TestClass", retrieved.getTarget());
        assertEquals("这个类是做什么的？", retrieved.getQuestion());
        assertEquals("这是一个测试类", retrieved.getConclusion());
    }

    @Test
    void testAddConclusionWithNullId() {
        SubTaskConclusion conclusion = new SubTaskConclusion();

        assertThrows(IllegalArgumentException.class, () -> {
            context.addConclusion(null, conclusion);
        });
    }

    @Test
    void testAddConclusionWithNullConclusion() {
        assertThrows(IllegalArgumentException.class, () -> {
            context.addConclusion("subtask-1", null);
        });
    }

    @Test
    void testAddAndGetGlobalContext() {
        context.addGlobalContext("key1", "value1");
        context.addGlobalContext("key2", 123);

        assertEquals("value1", context.getGlobalContext("key1"));
        assertEquals(123, context.getGlobalContext("key2"));
    }

    @Test
    void testAddGlobalContextWithNullKey() {
        assertThrows(IllegalArgumentException.class, () -> {
            context.addGlobalContext(null, "value");
        });
    }

    @Test
    void testAddGlobalContextWithNullValue() {
        assertThrows(IllegalArgumentException.class, () -> {
            context.addGlobalContext("key", null);
        });
    }

    @Test
    void testGetAllConclusions() {
        SubTaskConclusion conclusion1 = new SubTaskConclusion();
        conclusion1.setSubTaskId("subtask-1");

        SubTaskConclusion conclusion2 = new SubTaskConclusion();
        conclusion2.setSubTaskId("subtask-2");

        context.addConclusion("subtask-1", conclusion1);
        context.addConclusion("subtask-2", conclusion2);

        assertEquals(2, context.getAllConclusions().size());
        assertTrue(context.getAllConclusions().containsKey("subtask-1"));
        assertTrue(context.getAllConclusions().containsKey("subtask-2"));
    }

    @Test
    void testGetAllGlobalContext() {
        context.addGlobalContext("key1", "value1");
        context.addGlobalContext("key2", "value2");

        assertEquals(2, context.getAllGlobalContext().size());
        assertEquals("value1", context.getAllGlobalContext().get("key1"));
        assertEquals("value2", context.getAllGlobalContext().get("key2"));
    }

    @Test
    void testClear() {
        SubTaskConclusion conclusion = new SubTaskConclusion();
        conclusion.setSubTaskId("subtask-1");

        context.addConclusion("subtask-1", conclusion);
        context.addGlobalContext("key1", "value1");

        assertEquals(1, context.getConclusionCount());
        assertEquals(1, context.getGlobalContextCount());

        context.clear();

        assertEquals(0, context.getConclusionCount());
        assertEquals(0, context.getGlobalContextCount());
    }

    @Test
    void testGetConclusionCount() {
        SubTaskConclusion conclusion1 = new SubTaskConclusion();
        conclusion1.setSubTaskId("subtask-1");

        SubTaskConclusion conclusion2 = new SubTaskConclusion();
        conclusion2.setSubTaskId("subtask-2");

        context.addConclusion("subtask-1", conclusion1);
        context.addConclusion("subtask-2", conclusion2);

        assertEquals(2, context.getConclusionCount());
    }

    @Test
    void testGetGlobalContextCount() {
        context.addGlobalContext("key1", "value1");
        context.addGlobalContext("key2", "value2");
        context.addGlobalContext("key3", "value3");

        assertEquals(3, context.getGlobalContextCount());
    }
}
