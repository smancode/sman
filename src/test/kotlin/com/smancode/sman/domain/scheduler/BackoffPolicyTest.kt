package com.smancode.sman.domain.scheduler

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("BackoffPolicy 测试套件")
class BackoffPolicyTest {

    private lateinit var backoffPolicy: BackoffPolicy

    @BeforeEach
    fun setUp() {
        backoffPolicy = BackoffPolicy()
    }

    @Nested
    @DisplayName("基础功能测试")
    inner class BasicFunctionTests {

        @Test
        @DisplayName("初始状态不应处于退避")
        fun `initial state should not be in backoff`() {
            assertFalse(backoffPolicy.isInBackoff())
        }

        @Test
        @DisplayName("初始延迟应为 0")
        fun `initial delay should be zero`() {
            assertEquals(0L, backoffPolicy.getNextDelayMs())
        }
    }

    @Nested
    @DisplayName("退避时间表测试")
    inner class ScheduleTests {

        @Test
        @DisplayName("第 1 次失败后应退避 30 秒")
        fun `first error should backoff 30 seconds`() {
            backoffPolicy.recordError()
            assertEquals(30_000L, backoffPolicy.getNextDelayMs())
        }

        @Test
        @DisplayName("第 2 次失败后应退避 1 分钟")
        fun `second error should backoff 1 minute`() {
            repeat(2) { backoffPolicy.recordError() }
            assertEquals(60_000L, backoffPolicy.getNextDelayMs())
        }

        @Test
        @DisplayName("第 3 次失败后应退避 5 分钟")
        fun `third error should backoff 5 minutes`() {
            repeat(3) { backoffPolicy.recordError() }
            assertEquals(5 * 60_000L, backoffPolicy.getNextDelayMs())
        }

        @Test
        @DisplayName("第 4 次失败后应退避 15 分钟")
        fun `fourth error should backoff 15 minutes`() {
            repeat(4) { backoffPolicy.recordError() }
            assertEquals(15 * 60_000L, backoffPolicy.getNextDelayMs())
        }

        @Test
        @DisplayName("第 5+ 次失败后应退避 1 小时")
        fun `fifth and beyond should backoff 1 hour`() {
            repeat(5) { backoffPolicy.recordError() }
            assertEquals(60 * 60_000L, backoffPolicy.getNextDelayMs())

            // 继续失败仍然是 1 小时
            backoffPolicy.recordError()
            assertEquals(60 * 60_000L, backoffPolicy.getNextDelayMs())
        }
    }

    @Nested
    @DisplayName("成功重置测试")
    inner class SuccessResetTests {

        @Test
        @DisplayName("成功后应重置退避状态")
        fun `success should reset backoff state`() {
            repeat(3) { backoffPolicy.recordError() }
            assertTrue(backoffPolicy.isInBackoff())

            backoffPolicy.recordSuccess()

            assertFalse(backoffPolicy.isInBackoff())
            assertEquals(0L, backoffPolicy.getNextDelayMs())
        }

        @Test
        @DisplayName("成功后连续错误计数应重置")
        fun `success should reset consecutive error count`() {
            repeat(3) { backoffPolicy.recordError() }
            backoffPolicy.recordSuccess()
            backoffPolicy.recordError()

            // 应该回到第 1 次失败的退避时间
            assertEquals(30_000L, backoffPolicy.getNextDelayMs())
        }
    }

    @Nested
    @DisplayName("退避状态测试")
    inner class BackoffStateTests {

        @Test
        @DisplayName("失败后应处于退避状态")
        fun `should be in backoff after error`() {
            backoffPolicy.recordError()
            assertTrue(backoffPolicy.isInBackoff())
        }

        @Test
        @DisplayName("应能获取连续错误次数")
        fun `should get consecutive error count`() {
            assertEquals(0, backoffPolicy.getConsecutiveErrors())

            backoffPolicy.recordError()
            assertEquals(1, backoffPolicy.getConsecutiveErrors())

            backoffPolicy.recordError()
            assertEquals(2, backoffPolicy.getConsecutiveErrors())
        }

        @Test
        @DisplayName("应能获取最后错误时间")
        fun `should get last error time`() {
            assertNull(backoffPolicy.getLastErrorTime())

            backoffPolicy.recordError()
            assertNotNull(backoffPolicy.getLastErrorTime())
        }
    }

    @Nested
    @DisplayName("边界情况测试")
    inner class EdgeCaseTests {

        @Test
        @DisplayName("多次成功调用不应影响状态")
        fun `multiple success calls should not affect state`() {
            backoffPolicy.recordSuccess()
            backoffPolicy.recordSuccess()
            backoffPolicy.recordSuccess()

            assertFalse(backoffPolicy.isInBackoff())
            assertEquals(0L, backoffPolicy.getNextDelayMs())
        }
    }
}
