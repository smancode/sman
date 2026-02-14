package com.smancode.sman.evolution.guard

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertNull

/**
 * ExponentialBackoff 和 BackoffStateManager 测试
 */
@DisplayName("ExponentialBackoff 测试")
class ExponentialBackoffTest {

    @Nested
    @DisplayName("退避时间计算测试")
    inner class BackoffCalculationTest {

        @Test
        @DisplayName("零次或负数错误应返回 0 退避时间")
        fun `zero or negative errors should return zero backoff`() {
            // Given: 默认退避策略
            val backoff = ExponentialBackoff()

            // When: 计算零次或负数错误的退避时间
            val zeroResult = backoff.calculateBackoff(0)
            val negativeResult = backoff.calculateBackoff(-1)

            // Then: 应该返回 0
            assertEquals(0L, zeroResult)
            assertEquals(0L, negativeResult)
        }

        @Test
        @DisplayName("退避时间应按指数增长")
        fun `backoff should grow exponentially`() {
            // Given: 基础退避时间 1000ms
            val backoff = ExponentialBackoff(baseMs = 1000, multiplier = 2.0)

            // When: 计算连续错误的退避时间
            val backoff1 = backoff.calculateBackoff(1) // 1000 * 2^0 = 1000
            val backoff2 = backoff.calculateBackoff(2) // 1000 * 2^1 = 2000
            val backoff3 = backoff.calculateBackoff(3) // 1000 * 2^2 = 4000

            // Then: 应该按指数增长
            assertEquals(1000L, backoff1)
            assertEquals(2000L, backoff2)
            assertEquals(4000L, backoff3)
        }

        @Test
        @DisplayName("退避时间不应超过最大值")
        fun `backoff should not exceed maximum`() {
            // Given: 设置较小的最大退避时间
            val backoffStrategy = ExponentialBackoff(baseMs = 1000, maxMs = 5000, multiplier = 2.0)

            // When: 计算大量连续错误的退避时间
            val backoffResult = backoffStrategy.calculateBackoff(10) // 理论值 = 1000 * 2^9 = 512000

            // Then: 应该被限制在最大值
            assertEquals(5000L, backoffResult)
        }
    }

    @Nested
    @DisplayName("抖动退避测试")
    inner class JitterBackoffTest {

        @Test
        @DisplayName("带抖动的退避时间应大于等于基础值")
        fun `backoff with jitter should be at least base value`() {
            // Given: 默认退避策略
            val backoff = ExponentialBackoff(baseMs = 1000)

            // When: 多次计算带抖动的退避时间
            repeat(100) {
                val backoffWithJitter = backoff.calculateBackoffWithJitter(1)

                // Then: 应该大于等于基础值（1000）
                assertTrue(backoffWithJitter >= 1000L)
                // 抖动最多增加 20%，所以应该小于 1200
                assertTrue(backoffWithJitter < 1200L)
            }
        }

        @Test
        @DisplayName("带抖动的退避时间应有一定随机性")
        fun `backoff with jitter should have randomness`() {
            // Given: 默认退避策略
            val backoffStrategy = ExponentialBackoff(baseMs = 10000)

            // When: 多次计算带抖动的退避时间
            val results = (1..100).map { backoffStrategy.calculateBackoffWithJitter(1) }.toSet()

            // Then: 结果应该有一定多样性（不是全部相同）
            assertTrue(results.size > 1, "抖动应该产生不同的结果")
        }
    }
}

/**
 * BackoffStateManager 测试
 */
@DisplayName("BackoffStateManager 测试")
class BackoffStateManagerTest {

    @Nested
    @DisplayName("状态记录测试")
    inner class StateRecordingTest {

        @Test
        @DisplayName("记录错误应增加连续错误计数")
        fun `recording error should increment consecutive error count`() {
            // Given: 新的管理器
            val manager = BackoffStateManager()
            val projectKey = "test-project"

            // When: 记录多次错误
            manager.recordError(projectKey)
            manager.recordError(projectKey)
            manager.recordError(projectKey)

            // Then: 连续错误计数应为 3
            val state = manager.getState(projectKey)
            assertEquals(3, state.consecutiveErrors)
        }

        @Test
        @DisplayName("记录成功应重置连续错误计数")
        fun `recording success should reset consecutive error count`() {
            // Given: 有错误记录的管理器
            val manager = BackoffStateManager()
            val projectKey = "test-project"
            manager.recordError(projectKey)
            manager.recordError(projectKey)

            // When: 记录成功
            manager.recordSuccess(projectKey)

            // Then: 连续错误计数应重置为 0
            val state = manager.getState(projectKey)
            assertEquals(0, state.consecutiveErrors)
            assertNull(state.backoffUntil)
        }

        @Test
        @DisplayName("不同项目应有独立的退避状态")
        fun `different projects should have independent backoff states`() {
            // Given: 新的管理器
            val manager = BackoffStateManager()

            // When: 为不同项目记录不同次数的错误
            manager.recordError("project-a")
            manager.recordError("project-a")
            manager.recordError("project-b")

            // Then: 各项目应有独立的状态
            assertEquals(2, manager.getState("project-a").consecutiveErrors)
            assertEquals(1, manager.getState("project-b").consecutiveErrors)
        }
    }

    @Nested
    @DisplayName("退避检查测试")
    inner class BackoffCheckTest {

        @Test
        @DisplayName("无错误的项目不应在退避期")
        fun `project with no errors should not be in backoff`() {
            // Given: 新的管理器
            val manager = BackoffStateManager()

            // When: 检查未记录错误的项目
            val inBackoff = manager.isInBackoff("unknown-project")

            // Then: 不应在退避期
            assertFalse(inBackoff)
        }

        @Test
        @DisplayName("记录错误后应在退避期")
        fun `should be in backoff after recording error`() {
            // Given: 新的管理器
            val manager = BackoffStateManager()
            val projectKey = "test-project"

            // When: 记录错误
            manager.recordError(projectKey)

            // Then: 应在退避期
            assertTrue(manager.isInBackoff(projectKey))
            assertTrue(manager.getRemainingBackoff(projectKey) > 0)
        }

        @Test
        @DisplayName("清除状态后不应在退避期")
        fun `should not be in backoff after clearing state`() {
            // Given: 有错误记录的管理器
            val manager = BackoffStateManager()
            val projectKey = "test-project"
            manager.recordError(projectKey)

            // When: 清除状态
            manager.clearState(projectKey)

            // Then: 不应在退避期
            assertFalse(manager.isInBackoff(projectKey))
            assertEquals(0L, manager.getRemainingBackoff(projectKey))
        }
    }

    @Nested
    @DisplayName("状态清除测试")
    inner class ClearStateTest {

        @Test
        @DisplayName("清除所有状态应有效")
        fun `clearAllStates should clear all states`() {
            // Given: 有多个项目状态的管理器
            val manager = BackoffStateManager()
            manager.recordError("project-a")
            manager.recordError("project-b")
            manager.recordError("project-c")

            // When: 清除所有状态
            manager.clearAllStates()

            // Then: 所有项目都不应在退避期
            assertFalse(manager.isInBackoff("project-a"))
            assertFalse(manager.isInBackoff("project-b"))
            assertFalse(manager.isInBackoff("project-c"))
        }
    }
}
