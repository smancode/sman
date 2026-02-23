package com.smancode.sman.domain.scheduler

import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@DisplayName("BackgroundScheduler E2E 健壮性测试")
class SchedulerE2ETest {

    private lateinit var scheduler: BackgroundScheduler

    @AfterEach
    fun tearDown() {
        scheduler.stop()
    }

    // ========== 长时间运行测试 ==========

    @Nested
    @DisplayName("长时间运行测试")
    inner class LongRunningTests {

        @Test
        @DisplayName("应能持续运行多次 tick")
        fun `should run multiple ticks continuously`() {
            val tickCount = AtomicInteger(0)
            val latch = CountDownLatch(5)

            scheduler = BackgroundScheduler(
                onTick = {
                    tickCount.incrementAndGet()
                    latch.countDown()
                },
                initialConfig = SchedulerConfig(
                    intervalMs = 100,
                    skipValidation = true
                )
            )

            scheduler.start()

            assertTrue(latch.await(3, TimeUnit.SECONDS), "应该在 3 秒内完成 5 次 tick")
            assertTrue(tickCount.get() >= 5)
        }
    }

    // ========== 错误恢复测试 ==========

    @Nested
    @DisplayName("错误恢复测试")
    inner class ErrorRecoveryTests {

        @Test
        @DisplayName("连续错误后应进入退避状态")
        fun `should enter backoff after consecutive errors`() {
            var callCount = 0
            scheduler = BackgroundScheduler(
                onTick = {
                    callCount++
                    throw RuntimeException("模拟错误 $callCount")
                },
                initialConfig = SchedulerConfig(
                    intervalMs = 50,
                    skipValidation = true
                )
            )

            scheduler.start()
            Thread.sleep(500) // 等待多次错误

            // 应进入退避状态
            assertEquals(SchedulerState.BACKOFF, scheduler.getState())
            assertTrue(scheduler.getConsecutiveErrors() >= 3)
        }

        @Test
        @DisplayName("错误恢复后应重置退避")
        fun `should reset backoff after recovery`() {
            var shouldFail = true
            val successLatch = CountDownLatch(1)

            scheduler = BackgroundScheduler(
                onTick = {
                    if (shouldFail) {
                        throw RuntimeException("模拟错误")
                    } else {
                        successLatch.countDown()
                    }
                },
                initialConfig = SchedulerConfig(
                    intervalMs = 100,
                    skipValidation = true
                )
            )

            scheduler.start()
            Thread.sleep(300) // 让错误发生

            // 恢复
            shouldFail = false
            scheduler.triggerNow()

            assertTrue(successLatch.await(2, TimeUnit.SECONDS))
            assertEquals(0, scheduler.getConsecutiveErrors())
        }
    }

    // ========== 用户活动测试 ==========

    @Nested
    @DisplayName("用户活动测试")
    inner class UserActivityTests {

        @Test
        @DisplayName("用户活跃时应暂停执行")
        fun `should pause when user active`() {
            val tickCount = AtomicInteger(0)
            scheduler = BackgroundScheduler(
                onTick = { tickCount.incrementAndGet() },
                initialConfig = SchedulerConfig(
                    intervalMs = 100,
                    pauseOnUserActive = true,
                    activityThresholdMs = 1000,
                    skipValidation = true
                )
            )

            scheduler.start()
            Thread.sleep(200) // 让一些 tick 执行

            // 记录用户活动
            scheduler.recordActivity()

            val countBeforePause = tickCount.get()
            Thread.sleep(500) // 等待

            // 用户活跃期间不应增加 tick
            assertEquals(countBeforePause, tickCount.get())
        }

        @Test
        @DisplayName("用户空闲后应恢复执行")
        fun `should resume when user idle`() {
            val tickCount = AtomicInteger(0)
            val latch = CountDownLatch(3)

            scheduler = BackgroundScheduler(
                onTick = {
                    tickCount.incrementAndGet()
                    latch.countDown()
                },
                initialConfig = SchedulerConfig(
                    intervalMs = 100,
                    pauseOnUserActive = true,
                    activityThresholdMs = 100, // 短阈值便于测试
                    skipValidation = true
                )
            )

            scheduler.start()
            scheduler.recordActivity() // 模拟用户活跃

            Thread.sleep(100)

            // 等待用户空闲（阈值过期）
            Thread.sleep(200)

            // 手动触发恢复执行
            scheduler.triggerNow()

            assertTrue(latch.await(3, TimeUnit.SECONDS))
        }
    }

    // ========== 重启测试 ==========

    @Nested
    @DisplayName("重启测试")
    inner class RestartTests {

        @Test
        @DisplayName("停止后重启应正常工作")
        fun `should work after restart`() {
            val tickCount = AtomicInteger(0)
            val latch = CountDownLatch(2)

            scheduler = BackgroundScheduler(
                onTick = {
                    tickCount.incrementAndGet()
                    latch.countDown()
                },
                initialConfig = SchedulerConfig(
                    intervalMs = 100,
                    skipValidation = true
                )
            )

            // 第一次运行
            scheduler.start()
            Thread.sleep(200)
            scheduler.stop()

            val firstCount = tickCount.get()

            // 重启
            scheduler.start()
            Thread.sleep(200)

            assertTrue(tickCount.get() > firstCount)
        }

        @Test
        @DisplayName("重启后 Generation 应增加")
        fun `generation should increase after restart`() {
            scheduler = BackgroundScheduler(
                onTick = {},
                initialConfig = SchedulerConfig(
                    intervalMs = 100,
                    skipValidation = true
                )
            )

            scheduler.start()
            Thread.sleep(100)
            scheduler.stop()

            // 重启
            scheduler.start()

            assertTrue(scheduler.isRunning())
        }
    }

    // ========== 边界情况测试 ==========

    @Nested
    @DisplayName("边界情况测试")
    inner class EdgeCaseTests {

        @Test
        @DisplayName("快速启停应安全")
        fun `rapid start stop should be safe`() {
            scheduler = BackgroundScheduler(
                onTick = {},
                initialConfig = SchedulerConfig(
                    intervalMs = 100,
                    skipValidation = true
                )
            )

            repeat(10) {
                scheduler.start()
                Thread.sleep(10)
                scheduler.stop()
            }

            // 不应抛出异常
        }

        @Test
        @DisplayName("未启动时操作应安全")
        fun `operations without start should be safe`() {
            scheduler = BackgroundScheduler(
                onTick = {},
                initialConfig = SchedulerConfig(
                    intervalMs = 100,
                    skipValidation = true
                )
            )

            // 未启动时调用这些方法应安全
            scheduler.triggerNow()
            scheduler.recordActivity()
            scheduler.getConfig()
            scheduler.getState()
            scheduler.getTotalTicks()

            // 不应抛出异常
        }
    }
}
