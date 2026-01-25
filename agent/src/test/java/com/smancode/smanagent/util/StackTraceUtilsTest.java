package com.smancode.smanagent.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * StackTraceUtils 测试
 */
class StackTraceUtilsTest {

    @Test
    void testFormatStackTrace_WithSimpleException() {
        Exception e = new RuntimeException("Test exception");
        String result = StackTraceUtils.formatStackTrace(e);

        assertNotNull(result);
        assertTrue(result.contains("RuntimeException"));
        assertTrue(result.contains("Test exception"));
        // 验证是单行
        assertFalse(result.contains("\n"));
    }

    @Test
    void testFormatStackTrace_WithCause() {
        Exception cause = new IllegalStateException("Cause exception");
        Exception e = new RuntimeException("Wrapper exception", cause);
        String result = StackTraceUtils.formatStackTrace(e);

        assertNotNull(result);
        assertTrue(result.contains("RuntimeException"));
        assertTrue(result.contains("Wrapper exception"));
        assertTrue(result.contains("Caused by"));
        assertTrue(result.contains("IllegalStateException"));
        assertTrue(result.contains("Cause exception"));
        // 验证是单行
        assertFalse(result.contains("\n"));
    }

    @Test
    void testFormatStackTrace_WithNull() {
        String result = StackTraceUtils.formatStackTrace(null);
        assertEquals("", result);
    }

    @Test
    void testFormatStackTrace_LimitStackTraceElements() {
        Exception e = new RuntimeException("Test");
        String result = StackTraceUtils.formatStackTrace(e);

        // 验证堆栈被限制在合理范围内
        // 如果堆栈超过10个元素，应该有 "..." 标记
        if (e.getStackTrace().length > 10) {
            assertTrue(result.contains("..."));
        }
    }

    @Test
    void testGetFullStackTrace() {
        Exception e = new RuntimeException("Test exception");
        String result = StackTraceUtils.getFullStackTrace(e);

        assertNotNull(result);
        assertTrue(result.contains("RuntimeException"));
        assertTrue(result.contains("Test exception"));
        // 验证是多行
        assertTrue(result.contains("\n"));
        assertTrue(result.contains("at "));
    }
}
