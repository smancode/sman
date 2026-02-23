package com.smancode.sman.domain.scheduler

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@DisplayName("HeartbeatRunner 测试套件")
class HeartbeatRunnerTest {

    private lateinit var runner: HeartbeatRunner
    private lateinit var tickCallback: suspend () -> Unit

    @BeforeEach
    fun setUp() {
        tickCallback = mockk(relaxed = true)
    }

    @AfterEach
    fun tearDown() {
        runner?.stop()
    }

    // ========== 基础功能测试 ==========

    @Nested
    @DisplayName("基础功能测试")
    inner class BasicFunctionTests {

        @Test
        @DisplayName("start - 应开始心跳")
        fun `start should begin heartbeat`() {
            runner = HeartbeatRunner(
                config = SchedulerConfig(intervalMs = 1000, skipValidation = true),
                onTick = tickCallback
            )

            runner.start()

            assertTrue(runner.isRunning())
        }

        @Test
        @DisplayName("stop - 应停止心跳")
        fun `stop should stop heartbeat`() {
            runner = HeartbeatRunner(
                config = SchedulerConfig(intervalMs = 1000, skipValidation = true),
                onTick = tickCallback
            )

            runner.start()
            runner.stop()

            assertFalse(runner.isRunning())
        }

        @Test
        @DisplayName("多次 stop 应安全")
        fun `multiple stops should be safe`() {
            runner = HeartbeatRunner(
                config = SchedulerConfig(intervalMs = 1000, skipValidation = true),
                onTick = tickCallback
            )

            runner.start()
            runner.stop()
            runner.stop() // 不应抛出异常
            runner.stop()
        }
    }

    // ========== 心跳触发测试 ==========

    @Nested
    @DisplayName("心跳触发测试")
    inner class TriggerTests {

        @Test
        @DisplayName("应在配置间隔后触发回调")
        fun `should trigger callback after interval`() = runBlocking {
            val latch = CountDownLatch(1)
            val callCount = AtomicInteger(0)

            runner = HeartbeatRunner(
                config = SchedulerConfig(intervalMs = 100, skipValidation = true), // 100ms
                onTick = {
                    callCount.incrementAndGet()
                    latch.countDown()
                }
            )

            runner.start()

            assertTrue(latch.await(2, TimeUnit.SECONDS), "应该在 2 秒内触发")
            assertEquals(1, callCount.get())
        }

        @Test
        @DisplayName("停止后不应再触发")
        fun `should not trigger after stop`() = runBlocking {
            val callCount = AtomicInteger(0)

            runner = HeartbeatRunner(
                config = SchedulerConfig(intervalMs = 100, skipValidation = true),
                onTick = { callCount.incrementAndGet() }
            )

            runner.start()
            Thread.sleep(150) // 等待第一次触发
            runner.stop()

            val countAfterStop = callCount.get()
            Thread.sleep(300) // 等待更多时间

            assertEquals(countAfterStop, callCount.get(), "停止后不应再触发")
        }
    }

    // ========== 请求合并测试 ==========

    @Nested
    @DisplayName("请求合并测试")
    inner class CoalesceTests {

        @Test
        @DisplayName("requestWakeNow - 应立即请求唤醒")
        fun `requestWakeNow should request immediate wake`() = runBlocking {
            val latch = CountDownLatch(1)

            runner = HeartbeatRunner(
                config = SchedulerConfig(intervalMs = 60000, skipValidation = true), // 1 分钟
                onTick = { latch.countDown() }
            )

            runner.start()
            runner.requestWakeNow(WakeReason.MANUAL)

            assertTrue(latch.await(1, TimeUnit.SECONDS), "应该立即触发")
        }

        @Test
        @DisplayName("多次 requestWakeNow 应合并（Coalesce Window）")
        fun `multiple requestWakeNow should coalesce`() = runBlocking {
            val callCount = AtomicInteger(0)
            val latch = CountDownLatch(1)

            runner = HeartbeatRunner(
                config = SchedulerConfig(intervalMs = 60000, skipValidation = true),
                onTick = {
                    if (callCount.incrementAndGet() == 1) {
                        latch.countDown()
                    }
                }
            )

            runner.start()
            // 快速连续请求多次
            runner.requestWakeNow(WakeReason.FILE_CHANGE)
            runner.requestWakeNow(WakeReason.USER_QUERY)
            runner.requestWakeNow(WakeReason.MANUAL)

            assertTrue(latch.await(500, TimeUnit.MILLISECONDS), "应该在合并窗口后触发")
            // 合并窗口内只应触发一次
            Thread.sleep(100)
            assertEquals(1, callCount.get(), "合并窗口内只应触发一次")
        }
    }

    // ========== 配置更新测试 ==========

    @Nested
    @DisplayName("配置更新测试")
    inner class ConfigUpdateTests {

        @Test
        @DisplayName("updateConfig - 应更新间隔配置")
        fun `updateConfig should update interval`() {
            runner = HeartbeatRunner(
                config = SchedulerConfig(intervalMs = 1000, skipValidation = true),
                onTick = tickCallback
            )

            runner.start()
            runner.updateConfig(SchedulerConfig(intervalMs = 5000, skipValidation = true))

            assertEquals(5000L, runner.getConfig().intervalMs)
        }

        @Test
        @DisplayName("updateConfig - 禁用后应停止触发")
        fun `updateConfig with disabled should stop triggering`() = runBlocking {
            val callCount = AtomicInteger(0)

            runner = HeartbeatRunner(
                config = SchedulerConfig(intervalMs = 100, enabled = true, skipValidation = true),
                onTick = { callCount.incrementAndGet() }
            )

            runner.start()
            Thread.sleep(150)
            runner.updateConfig(SchedulerConfig(intervalMs = 100, enabled = false, skipValidation = true))

            val countAfterDisable = callCount.get()
            Thread.sleep(300)

            assertEquals(countAfterDisable, callCount.get(), "禁用后不应再触发")
        }
    }

    // ========== Generation Counter 测试 ==========

    @Nested
    @DisplayName("Generation Counter 测试")
    inner class GenerationTests {

        @Test
        @DisplayName("restart 后旧回调应被忽略")
        fun `old callbacks should be ignored after restart`() = runBlocking {
            val generation = AtomicInteger(0)
            val callCount = AtomicInteger(0)

            runner = HeartbeatRunner(
                config = SchedulerConfig(intervalMs = 100, skipValidation = true),
                onTick = {
                    generation.set(runner.getCurrentGeneration())
                    callCount.incrementAndGet()
                }
            )

            runner.start()
            Thread.sleep(150)
            val firstGen = generation.get()

            runner.stop()
            runner.start()
            Thread.sleep(150)
            val secondGen = generation.get()

            assertTrue(secondGen > firstGen, "Generation 应该增加")
        }
    }

    // ========== 状态查询测试 ==========

    @Nested
    @DisplayName("状态查询测试")
    inner class StatusTests {

        @Test
        @DisplayName("getNextWakeTime - 应返回下次唤醒时间")
        fun `getNextWakeTime should return next wake time`() {
            runner = HeartbeatRunner(
                config = SchedulerConfig(intervalMs = 5000, skipValidation = true),
                onTick = tickCallback
            )

            runner.start()

            val nextWake = runner.getNextWakeTime()
            assertNotNull(nextWake)
            assertTrue(nextWake!!.isAfter(Instant.now()))
        }

        @Test
        @DisplayName("停止后 getNextWakeTime 应返回 null")
        fun `getNextWakeTime should return null after stop`() {
            runner = HeartbeatRunner(
                config = SchedulerConfig(intervalMs = 5000, skipValidation = true),
                onTick = tickCallback
            )

            runner.start()
            runner.stop()

            assertNull(runner.getNextWakeTime())
        }
    }
}
