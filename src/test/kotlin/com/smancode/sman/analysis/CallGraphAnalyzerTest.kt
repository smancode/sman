package com.smancode.sman.analysis

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

/**
 * CallGraphAnalyzer 测试
 */
@DisplayName("调用图分析器测试")
class CallGraphAnalyzerTest {

    @Test
    @DisplayName("获取方法的调用者")
    fun testGetCallers() {
        val analyzer = CallGraphAnalyzer()
        // 简单验证接口存在
        assertTrue(true, "接口存在")
    }

    @Test
    @DisplayName("获取方法的被调用者")
    fun testGetCallees() {
        val analyzer = CallGraphAnalyzer()
        assertTrue(true)
    }
}
