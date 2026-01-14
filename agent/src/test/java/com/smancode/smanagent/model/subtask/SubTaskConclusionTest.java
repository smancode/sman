package com.smancode.smanagent.model.subtask;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SubTaskConclusion 单元测试
 */
class SubTaskConclusionTest {

    private SubTaskConclusion conclusion;

    @BeforeEach
    void setUp() {
        conclusion = new SubTaskConclusion();
    }

    @Test
    void testConstructor() {
        SubTaskConclusion c = new SubTaskConclusion();
        assertNotNull(c);
        assertEquals(0, c.getInternalIterations());
        assertFalse(c.hasEvidence());
    }

    @Test
    void testConstructorWithParameters() {
        SubTaskConclusion c = new SubTaskConclusion("subtask-1", "TestClass", "这个类是做什么的？");

        assertEquals("subtask-1", c.getSubTaskId());
        assertEquals("TestClass", c.getTarget());
        assertEquals("这个类是做什么的？", c.getQuestion());
        assertNotNull(c.getCompletedAt());
    }

    @Test
    void testGettersAndSetters() {
        conclusion.setSubTaskId("subtask-1");
        conclusion.setTarget("TestClass");
        conclusion.setQuestion("这个类是做什么的？");
        conclusion.setConclusion("这是一个测试类");
        conclusion.setInternalIterations(2);

        assertEquals("subtask-1", conclusion.getSubTaskId());
        assertEquals("TestClass", conclusion.getTarget());
        assertEquals("这个类是做什么的？", conclusion.getQuestion());
        assertEquals("这是一个测试类", conclusion.getConclusion());
        assertEquals(2, conclusion.getInternalIterations());
    }

    @Test
    void testHasConclusion() {
        assertFalse(conclusion.hasConclusion());

        conclusion.setConclusion("测试结论");
        assertTrue(conclusion.hasConclusion());
    }

    @Test
    void testHasConclusionWithEmptyString() {
        conclusion.setConclusion("");
        assertFalse(conclusion.hasConclusion());
    }

    @Test
    void testHasConclusionWithBlankString() {
        conclusion.setConclusion("   ");
        // 空格不是空字符串，所以 hasConclusion 返回 true
        // 这是符合预期的，因为用户可能输入了空格
        assertTrue(conclusion.hasConclusion());
    }

    @Test
    void testHasEvidence() {
        assertFalse(conclusion.hasEvidence());

        conclusion.setEvidence(List.of("evidence1", "evidence2"));
        assertTrue(conclusion.hasEvidence());
    }

    @Test
    void testHasEvidenceWithEmptyList() {
        conclusion.setEvidence(List.of());
        assertFalse(conclusion.hasEvidence());

        conclusion.setEvidence(null);
        assertFalse(conclusion.hasEvidence());
    }

    @Test
    void testGetEvidenceCount() {
        assertEquals(0, conclusion.getEvidenceCount());

        conclusion.setEvidence(List.of("e1", "e2", "e3"));
        assertEquals(3, conclusion.getEvidenceCount());
    }

    @Test
    void testAddEvidence() {
        conclusion.addEvidence("evidence1");
        assertTrue(conclusion.hasEvidence());
        assertEquals(1, conclusion.getEvidenceCount());

        conclusion.addEvidence("evidence2");
        assertEquals(2, conclusion.getEvidenceCount());
    }

    @Test
    void testAddEvidenceWithNull() {
        conclusion.addEvidence(null);
        assertFalse(conclusion.hasEvidence());

        conclusion.addEvidence("");
        assertFalse(conclusion.hasEvidence());
    }

    @Test
    void testSetInternalIterationsWithNull() {
        conclusion.setInternalIterations(null);
        assertEquals(0, conclusion.getInternalIterations());
    }

    @Test
    void testSetEvidenceWithNull() {
        conclusion.setEvidence(null);
        assertFalse(conclusion.hasEvidence());
        assertEquals(0, conclusion.getEvidenceCount());
    }

    @Test
    void testSetCompletedAt() {
        Instant now = Instant.now();
        conclusion.setCompletedAt(now);

        assertEquals(now, conclusion.getCompletedAt());
    }
}
