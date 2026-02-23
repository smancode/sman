package com.smancode.sman.domain.scheduler

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*

@DisplayName("SchedulerCore 测试套件")
class SchedulerCoreTest {

    private lateinit var schedulerCore: SchedulerCore
    private lateinit var onTick: suspend () -> Unit
    private lateinit var activityMonitor: ActivityMonitor
    private lateinit var backoffPolicy: BackoffPolicy

    @BeforeEach
    fun setUp() {
        onTick = mockk(relaxed = true)
        activityMonitor = ActivityMonitor(thresholdMs = 1000L)
        backoffPolicy = BackoffPolicy()
        schedulerCore = SchedulerCore(
            onTick = onTick,
            activityMonitor = activityMonitor,
            backoffPolicy = backoffPolicy,
            config = SchedulerConfig(intervalMs = 100, skipValidation = true)
        )
    }

    @AfterEach
    fun tearDown() {
        schedulerCore.stop()
    }

    // ========== 基础功能测试 ==========

    @Nested
    @DisplayName("基础功能测试")
    inner class BasicFunctionTests {

        @Test
        @DisplayName("初始状态应为 IDLE")
        fun `initial state should be IDLE`() {
            assertEquals(SchedulerState.IDLE, schedulerCore.getState())
        }

        @Test
        @DisplayName("start 后状态应为 RUNNING")
        fun `state should be RUNNING after start`() {
            schedulerCore.start()
            assertEquals(SchedulerState.RUNNING, schedulerCore.getState())
        }

        @Test
        @DisplayName("stop 后状态应为 IDLE")
        fun `state should be IDLE after stop`() {
            schedulerCore.start()
            schedulerCore.stop()
            assertEquals(SchedulerState.IDLE, schedulerCore.getState())
        }
    }

    // ========== Tick 执行测试 ==========

    @Nested
    @DisplayName("Tick 执行测试")
    inner class TickExecutionTests {

        @Test
        @DisplayName("tick 应调用 onTick 回调")
        fun `tick should call onTick callback`() = runBlocking {
            schedulerCore.start()

            val result = schedulerCore.tick()

            assertTrue(result is TickResult.Success)
            coVerify { onTick() }
        }

        @Test
        @DisplayName("用户活跃时应暂停 tick")
        fun `should pause when user active`() = runBlocking {
            schedulerCore.start()
            activityMonitor.recordActivity() // 模拟用户活跃

            val result = schedulerCore.tick()

            assertEquals(SchedulerState.PAUSED, schedulerCore.getState())
            assertTrue(result is TickResult.Skipped)
            coVerify(exactly = 0) { onTick() }
        }

        @Test
        @DisplayName("onTick 异常时应记录错误")
        fun `should record error when onTick throws`() = runBlocking {
            coEvery { onTick() } throws RuntimeException("测试异常")

            schedulerCore.start()
            val result = schedulerCore.tick()

            assertTrue(result is TickResult.Error)
            assertEquals(1, backoffPolicy.getConsecutiveErrors())
        }

        @Test
        @DisplayName("成功执行后应重置退避")
        fun `should reset backoff after success`() = runBlocking {
            backoffPolicy.recordError() // 预设一个错误

            schedulerCore.start()
            schedulerCore.tick()

            assertEquals(0, backoffPolicy.getConsecutiveErrors())
        }
    }

    // ========== 退避状态测试 ==========

    @Nested
    @DisplayName("退避状态测试")
    inner class BackoffStateTests {

        @Test
        @DisplayName("连续错误后应进入退避状态")
        fun `should enter backoff after consecutive errors`() = runBlocking {
            coEvery { onTick() } throws RuntimeException("测试异常")

            schedulerCore.start()
            repeat(3) { schedulerCore.tick() }

            assertEquals(SchedulerState.BACKOFF, schedulerCore.getState())
        }

        @Test
        @DisplayName("退避状态下 tick 应跳过")
        fun `should skip tick in backoff state`() = runBlocking {
            coEvery { onTick() } throws RuntimeException("测试异常")

            schedulerCore.start()
            repeat(5) { schedulerCore.tick() } // 触发退避

            val result = schedulerCore.tick()
            assertTrue(result is TickResult.Skipped)
        }
    }

    // ========== 暂停恢复测试 ==========

    @Nested
    @DisplayName("暂停恢复测试")
    inner class PauseResumeTests {

        @Test
        @DisplayName("pause 应暂停调度")
        fun `pause should pause scheduler`() = runBlocking {
            schedulerCore.start()
            schedulerCore.pause()

            assertEquals(SchedulerState.PAUSED, schedulerCore.getState())

            val result = schedulerCore.tick()
            assertTrue(result is TickResult.Skipped)
        }

        @Test
        @DisplayName("resume 应恢复调度")
        fun `resume should resume scheduler`() = runBlocking {
            schedulerCore.start()
            schedulerCore.pause()
            schedulerCore.resume()

            assertEquals(SchedulerState.RUNNING, schedulerCore.getState())

            val result = schedulerCore.tick()
            assertTrue(result is TickResult.Success)
        }
    }

    // ========== 状态查询测试 ==========

    @Nested
    @DisplayName("状态查询测试")
    inner class StatusQueryTests {

        @Test
        @DisplayName("应能获取总执行次数")
        fun `should get total ticks`() = runBlocking {
            schedulerCore.start()
            schedulerCore.tick()
            schedulerCore.tick()

            assertEquals(2, schedulerCore.getTotalTicks())
        }

        @Test
        @DisplayName("应能获取总错误次数")
        fun `should get total errors`() = runBlocking {
            coEvery { onTick() } throws RuntimeException("测试异常")

            schedulerCore.start()
            schedulerCore.tick()
            schedulerCore.tick()

            assertEquals(2, schedulerCore.getTotalErrors())
        }
    }
}
