package com.smancode.sman.domain.scheduler

import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@DisplayName("TaskLane 测试套件")
class TaskLaneTest {

    private lateinit var laneExecutor: LaneExecutor

    @BeforeEach
    fun setUp() {
        laneExecutor = LaneExecutor()
    }

    @AfterEach
    fun tearDown() {
        laneExecutor.shutdown()
    }

    // ========== Lane 配置测试 ==========

    @Nested
    @DisplayName("Lane 配置测试")
    inner class LaneConfigTests {

        @Test
        @DisplayName("MAIN 通道应有最大并发 3")
        fun `MAIN lane should have max concurrent 3`() {
            val config = LaneConfig.MAIN
            assertEquals(3, config.maxConcurrent)
            assertEquals(100, config.priority)
        }

        @Test
        @DisplayName("BACKGROUND 通道应有最大并发 1")
        fun `BACKGROUND lane should have max concurrent 1`() {
            val config = LaneConfig.BACKGROUND
            assertEquals(1, config.maxConcurrent)
            assertEquals(50, config.priority)
        }

        @Test
        @DisplayName("LOW_PRIORITY 通道应有最大并发 1")
        fun `LOW_PRIORITY lane should have max concurrent 1`() {
            val config = LaneConfig.LOW_PRIORITY
            assertEquals(1, config.maxConcurrent)
            assertEquals(10, config.priority)
        }
    }

    // ========== 任务提交测试 ==========

    @Nested
    @DisplayName("任务提交测试")
    inner class TaskSubmissionTests {

        @Test
        @DisplayName("应能提交任务到指定 Lane")
        fun `should submit task to lane`() {
            val executed = AtomicInteger(0)
            val latch = CountDownLatch(1)

            laneExecutor.submit(TaskLane.MAIN) {
                executed.incrementAndGet()
                latch.countDown()
            }

            assertTrue(latch.await(2, TimeUnit.SECONDS))
            assertEquals(1, executed.get())
        }
    }

    // ========== 并发控制测试 ==========

    @Nested
    @DisplayName("并发控制测试")
    inner class ConcurrencyTests {

        @Test
        @DisplayName("BACKGROUND 通道应限制并发为 1")
        fun `BACKGROUND lane should limit concurrency to 1`() {
            val concurrentCount = AtomicInteger(0)
            val maxConcurrent = AtomicInteger(0)
            val latch = CountDownLatch(3)

            repeat(3) {
                laneExecutor.submit(TaskLane.BACKGROUND) {
                    val current = concurrentCount.incrementAndGet()
                    maxConcurrent.updateAndGet { max -> maxOf(max, current) }
                    Thread.sleep(100)
                    concurrentCount.decrementAndGet()
                    latch.countDown()
                }
            }

            assertTrue(latch.await(3, TimeUnit.SECONDS))
            assertEquals(1, maxConcurrent.get(), "BACKGROUND 通道最大并发应为 1")
        }

        @Test
        @DisplayName("MAIN 通道应允许最多 3 个并发")
        fun `MAIN lane should allow max 3 concurrent`() {
            val concurrentCount = AtomicInteger(0)
            val maxConcurrent = AtomicInteger(0)
            val latch = CountDownLatch(5)

            repeat(5) {
                laneExecutor.submit(TaskLane.MAIN) {
                    val current = concurrentCount.incrementAndGet()
                    maxConcurrent.updateAndGet { max -> maxOf(max, current) }
                    Thread.sleep(100)
                    concurrentCount.decrementAndGet()
                    latch.countDown()
                }
            }

            assertTrue(latch.await(3, TimeUnit.SECONDS))
            assertTrue(maxConcurrent.get() <= 3, "MAIN 通道最大并发应为 3")
        }
    }

    // ========== 关闭测试 ==========

    @Nested
    @DisplayName("关闭测试")
    inner class ShutdownTests {

        @Test
        @DisplayName("shutdown 后不应接受新任务")
        fun `should not accept task after shutdown`() {
            laneExecutor.shutdown()

            assertThrows(IllegalStateException::class.java) {
                laneExecutor.submit(TaskLane.MAIN) { }
            }
        }
    }
}
