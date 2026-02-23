package com.smancode.sman.domain.scheduler

import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import java.time.Duration
import java.time.Instant

@DisplayName("ActivityMonitor 测试套件")
class ActivityMonitorTest {

    private lateinit var activityMonitor: ActivityMonitor

    @BeforeEach
    fun setUp() {
        activityMonitor = ActivityMonitor(thresholdMs = 1000L) // 1 秒阈值便于测试
    }

    // ========== 基础功能测试 ==========

    @Nested
    @DisplayName("基础功能测试")
    inner class BasicFunctionTests {

        @Test
        @DisplayName("初始状态用户应视为不活跃")
        fun `initial state should be inactive`() {
            assertFalse(activityMonitor.isUserActive())
        }

        @Test
        @DisplayName("初始空闲时间应大于阈值")
        fun `initial idle time should exceed threshold`() {
            val idleTime = activityMonitor.getIdleTime()
            assertEquals(Long.MAX_VALUE, idleTime.toMillis())
        }
    }

    // ========== 活动记录测试 ==========

    @Nested
    @DisplayName("活动记录测试")
    inner class ActivityRecordTests {

        @Test
        @DisplayName("recordActivity 后应标记为活跃")
        fun `should be active after recordActivity`() {
            activityMonitor.recordActivity()
            assertTrue(activityMonitor.isUserActive())
        }

        @Test
        @DisplayName("recordActivity 后空闲时间应接近 0")
        fun `idle time should be near zero after recordActivity`() {
            activityMonitor.recordActivity()
            val idleTime = activityMonitor.getIdleTime()
            assertTrue(idleTime.toMillis() < 100L)
        }

        @Test
        @DisplayName("多次 recordActivity 应持续活跃")
        fun `multiple recordActivity should stay active`() {
            repeat(5) {
                activityMonitor.recordActivity()
                Thread.sleep(50)
            }
            assertTrue(activityMonitor.isUserActive())
        }
    }

    // ========== 阈值检测测试 ==========

    @Nested
    @DisplayName("阈值检测测试")
    inner class ThresholdTests {

        @Test
        @DisplayName("超过阈值后应变为不活跃")
        fun `should become inactive after threshold`() {
            activityMonitor.recordActivity()
            assertTrue(activityMonitor.isUserActive())

            // 等待超过阈值
            Thread.sleep(1100)

            assertFalse(activityMonitor.isUserActive())
        }

        @Test
        @DisplayName("自定义阈值应生效")
        fun `custom threshold should work`() {
            val monitor = ActivityMonitor(thresholdMs = 100L)
            monitor.recordActivity()
            assertTrue(monitor.isUserActive())

            Thread.sleep(150)

            assertFalse(monitor.isUserActive())
        }
    }

    // ========== 最后活动时间测试 ==========

    @Nested
    @DisplayName("最后活动时间测试")
    inner class LastActivityTimeTests {

        @Test
        @DisplayName("应能获取最后活动时间")
        fun `should get last activity time`() {
            assertNull(activityMonitor.getLastActivityTime())

            activityMonitor.recordActivity()
            assertNotNull(activityMonitor.getLastActivityTime())
        }

        @Test
        @DisplayName("最后活动时间应更新")
        fun `last activity time should update`() {
            activityMonitor.recordActivity()
            val firstTime = activityMonitor.getLastActivityTime()

            Thread.sleep(100)
            activityMonitor.recordActivity()
            val secondTime = activityMonitor.getLastActivityTime()

            assertTrue(secondTime!!.isAfter(firstTime))
        }
    }

    // ========== 配置测试 ==========

    @Nested
    @DisplayName("配置测试")
    inner class ConfigTests {

        @Test
        @DisplayName("应能获取阈值配置")
        fun `should get threshold config`() {
            assertEquals(1000L, activityMonitor.getThresholdMs())
        }

        @Test
        @DisplayName("应能动态更新阈值")
        fun `should update threshold dynamically`() {
            activityMonitor.recordActivity()
            assertTrue(activityMonitor.isUserActive())

            activityMonitor.setThreshold(100L)
            assertEquals(100L, activityMonitor.getThresholdMs())

            Thread.sleep(150)
            assertFalse(activityMonitor.isUserActive())
        }
    }
}
