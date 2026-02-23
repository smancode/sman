package com.smancode.sman.domain.scheduler

import io.mockk.mockk
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*

@DisplayName("BackgroundScheduler 测试套件")
class BackgroundSchedulerTest {

    companion object {
        /** 测试等待时间（毫秒） */
        private const val TEST_WAIT_MS = 500L
    }

    private lateinit var scheduler: BackgroundScheduler
    private lateinit var onTick: suspend () -> Unit

    @BeforeEach
    fun setUp() {
        onTick = mockk(relaxed = true)
        scheduler = BackgroundScheduler(
            onTick = onTick,
            initialConfig = SchedulerConfig(
                intervalMs = 100,
                skipValidation = true
            )
        )
    }

    @AfterEach
    fun tearDown() {
        scheduler.stop()
    }

    // ========== 生命周期测试 ==========

    @Nested
    @DisplayName("生命周期测试")
    inner class LifecycleTests {

        @Test
        @DisplayName("start 后应处于 RUNNING 状态")
        fun `should be RUNNING after start`() {
            scheduler.start()
            assertEquals(SchedulerState.RUNNING, scheduler.getState())
        }

        @Test
        @DisplayName("stop 后应处于 IDLE 状态")
        fun `should be IDLE after stop`() {
            scheduler.start()
            scheduler.stop()
            assertEquals(SchedulerState.IDLE, scheduler.getState())
        }

        @Test
        @DisplayName("多次 start 应安全")
        fun `multiple starts should be safe`() {
            scheduler.start()
            scheduler.start()
            scheduler.start()
            assertEquals(SchedulerState.RUNNING, scheduler.getState())
        }

        @Test
        @DisplayName("多次 stop 应安全")
        fun `multiple stops should be safe`() {
            scheduler.start()
            scheduler.stop()
            scheduler.stop()
            assertEquals(SchedulerState.IDLE, scheduler.getState())
        }
    }

    // ========== 手动触发测试 ==========

    @Nested
    @DisplayName("手动触发测试")
    inner class TriggerTests {

        @Test
        @DisplayName("triggerNow 应立即触发一次 tick")
        fun `triggerNow should trigger tick immediately`() {
            scheduler.start()
            scheduler.triggerNow()

            Thread.sleep(TEST_WAIT_MS)

            assertTrue(scheduler.getTotalTicks() >= 1)
        }
    }

    // ========== 配置更新测试 ==========

    @Nested
    @DisplayName("配置更新测试")
    inner class ConfigTests {

        @Test
        @DisplayName("应能动态更新配置")
        fun `should update config dynamically`() {
            scheduler.start()

            scheduler.updateConfig(SchedulerConfig(
                intervalMs = 5000,
                skipValidation = true
            ))

            assertEquals(5000L, scheduler.getConfig().intervalMs)
        }

        @Test
        @DisplayName("禁用后应停止调度")
        fun `should stop when disabled`() {
            scheduler.start()
            assertEquals(SchedulerState.RUNNING, scheduler.getState())

            scheduler.updateConfig(SchedulerConfig(
                enabled = false,
                intervalMs = 100,
                skipValidation = true
            ))

            assertEquals(SchedulerState.IDLE, scheduler.getState())
        }
    }

    // ========== 状态查询测试 ==========

    @Nested
    @DisplayName("状态查询测试")
    inner class StatusTests {

        @Test
        @DisplayName("应能获取状态")
        fun `should get state`() {
            assertEquals(SchedulerState.IDLE, scheduler.getState())
        }

        @Test
        @DisplayName("应能获取配置")
        fun `should get config`() {
            val config = scheduler.getConfig()
            assertEquals(100L, config.intervalMs)
        }

        @Test
        @DisplayName("应能获取总执行次数")
        fun `should get total ticks`() {
            assertEquals(0L, scheduler.getTotalTicks())
        }
    }

    // ========== 用户活动测试 ==========

    @Nested
    @DisplayName("用户活动测试")
    inner class UserActivityTests {

        @Test
        @DisplayName("记录用户活动后下次 tick 应暂停")
        fun `should pause on next tick after user activity`() {
            scheduler.start()
            scheduler.recordActivity()

            scheduler.triggerNow()
            Thread.sleep(TEST_WAIT_MS)

            assertEquals(SchedulerState.PAUSED, scheduler.getState())
        }
    }
}
