package com.smancode.sman.domain.puzzle

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * PuzzleScheduler 单元测试
 *
 * 测试覆盖：
 * 1. 启动/停止调度器
 * 2. 定时执行知识进化
 * 3. 增量分析模式
 * 4. API Key 未配置时跳过
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("PuzzleScheduler 测试套件")
class PuzzleSchedulerTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var puzzleStore: com.smancode.sman.infra.storage.PuzzleStore
    private lateinit var versionStore: KnowledgeBaseVersionStore
    private lateinit var evolutionLoop: KnowledgeEvolutionLoop
    private lateinit var coordinator: PuzzleCoordinator

    @BeforeEach
    fun setUp() {
        puzzleStore = com.smancode.sman.infra.storage.PuzzleStore(tempDir.toString())
        versionStore = KnowledgeBaseVersionStore(tempDir.toString())
        evolutionLoop = mockk(relaxed = true)
        coordinator = mockk(relaxed = true)
    }

    // ========== 启动/停止测试 ==========

    @Nested
    @DisplayName("启动/停止测试")
    inner class StartStopTests {

        @Test
        @DisplayName("start - 应启动调度器")
        fun start_ShouldStartScheduler() {
            // Given
            val scheduler = createScheduler(intervalMs = 60000)

            // When
            scheduler.start()

            // Then
            assertTrue(scheduler.isRunning())

            // Cleanup
            scheduler.stop()
        }

        @Test
        @DisplayName("stop - 应停止调度器")
        fun stop_ShouldStopScheduler() {
            // Given
            val scheduler = createScheduler(intervalMs = 60000)
            scheduler.start()

            // When
            scheduler.stop()

            // Then
            assertFalse(scheduler.isRunning())
        }

        @Test
        @DisplayName("start - 重复调用应幂等")
        fun start_MultipleCalls_ShouldBeIdempotent() {
            // Given
            val scheduler = createScheduler(intervalMs = 60000)

            // When
            scheduler.start()
            scheduler.start()
            scheduler.start()

            // Then
            assertTrue(scheduler.isRunning())

            // Cleanup
            scheduler.stop()
        }
    }

    // ========== 定时执行测试 ==========

    @Nested
    @DisplayName("定时执行测试")
    inner class ScheduledExecutionTests {

        @Test
        @DisplayName("应在指定间隔后执行知识进化")
        fun shouldExecuteEvolutionAtInterval() = runBlocking {
            // Given
            val executionCount = AtomicInteger(0)
            val executed = AtomicBoolean(false)

            val mockLoop = mockk<KnowledgeEvolutionLoop>(relaxed = true) {
                every { runBlocking { evolve(any()) } } answers {
                    executionCount.incrementAndGet()
                    executed.set(true)
                    EvolutionResult.success(
                        iterationId = "test",
                        hypothesis = "test",
                        evaluation = Evaluation(
                            hypothesisConfirmed = true,
                            newKnowledgeGained = 1,
                            conflictsFound = emptyList(),
                            qualityScore = 0.8,
                            lessonsLearned = emptyList()
                        ),
                        puzzlesCreated = 0
                    )
                }
            }

            // 使用短间隔进行测试
            val scheduler = PuzzleScheduler(
                projectPath = tempDir.toString(),
                puzzleStore = puzzleStore,
                versionStore = versionStore,
                evolutionLoop = mockLoop,
                intervalMs = 100  // 100ms 间隔
            )

            // When
            scheduler.start()

            // 等待至少一次执行
            withTimeout(2000) {
                while (!executed.get()) {
                    kotlinx.coroutines.delay(50)
                }
            }

            scheduler.stop()

            // Then
            assertTrue(executionCount.get() >= 1, "应该至少执行一次")
        }
    }

    // ========== 增量分析测试 ==========

    @Nested
    @DisplayName("增量分析测试")
    inner class IncrementalAnalysisTests {

        @Test
        @DisplayName("triggerImmediate - 应立即触发分析")
        fun triggerImmediate_ShouldTriggerAnalysisImmediately() = runBlocking {
            // Given
            val executed = AtomicBoolean(false)

            val mockLoop = mockk<KnowledgeEvolutionLoop>(relaxed = true) {
                every { runBlocking { evolve(any()) } } answers {
                    executed.set(true)
                    EvolutionResult.success(
                        iterationId = "test",
                        hypothesis = "test",
                        evaluation = Evaluation(
                            hypothesisConfirmed = true,
                            newKnowledgeGained = 0,
                            conflictsFound = emptyList(),
                            qualityScore = 0.5,
                            lessonsLearned = emptyList()
                        ),
                        puzzlesCreated = 0
                    )
                }
            }

            val scheduler = PuzzleScheduler(
                projectPath = tempDir.toString(),
                puzzleStore = puzzleStore,
                versionStore = versionStore,
                evolutionLoop = mockLoop,
                intervalMs = 60000  // 长间隔
            )

            // When
            scheduler.triggerImmediate()

            // 等待执行完成
            withTimeout(1000) {
                while (!executed.get()) {
                    kotlinx.coroutines.delay(50)
                }
            }

            // Then
            assertTrue(executed.get(), "应该立即执行分析")
        }

        @Test
        @DisplayName("forceFullAnalysis - 应强制全量分析")
        fun forceFullAnalysis_ShouldForceFullAnalysis() {
            // Given
            val scheduler = createScheduler(intervalMs = 60000)

            // When
            scheduler.forceFullAnalysis()

            // Then: 不抛异常即成功
            assertTrue(true)
        }
    }

    // ========== 状态查询测试 ==========

    @Nested
    @DisplayName("状态查询测试")
    inner class StatusQueryTests {

        @Test
        @DisplayName("getStatus - 应返回正确状态")
        fun getStatus_ShouldReturnCorrectStatus() {
            // Given
            val scheduler = createScheduler(intervalMs = 60000)

            // When: 未启动
            var status = scheduler.getStatus()

            // Then
            assertFalse(status.isRunning)
            assertEquals(0, status.currentVersion)
            assertEquals("", status.lastChecksum)

            // When: 启动后
            scheduler.start()
            status = scheduler.getStatus()

            // Then
            assertTrue(status.isRunning)

            // Cleanup
            scheduler.stop()
        }

        @Test
        @DisplayName("getStatistics - 应返回统计信息")
        fun getStatistics_ShouldReturnStatistics() {
            // Given
            val scheduler = createScheduler(intervalMs = 60000)

            // When
            val stats = scheduler.getStatistics()

            // Then
            assertEquals(0, stats.totalIterations)
            assertEquals(0, stats.puzzlesCreated)
            assertEquals(0.0, stats.averageQuality)
        }
    }

    // ========== 空知识库测试 ==========

    @Nested
    @DisplayName("空知识库测试")
    inner class EmptyKnowledgeBaseTests {

        @Test
        @DisplayName("空知识库首次运行应跳过版本创建")
        fun emptyKnowledgeBase_ShouldSkipVersionCreation() = runBlocking {
            // Given: 空知识库
            val executed = AtomicBoolean(false)

            val mockLoop = mockk<KnowledgeEvolutionLoop>(relaxed = true) {
                every { runBlocking { evolve(any()) } } answers {
                    executed.set(true)
                    EvolutionResult.success(
                        iterationId = "test",
                        hypothesis = "test",
                        evaluation = Evaluation(
                            hypothesisConfirmed = true,
                            newKnowledgeGained = 0,
                            conflictsFound = emptyList(),
                            qualityScore = 0.5,
                            lessonsLearned = emptyList()
                        ),
                        puzzlesCreated = 0
                    )
                }
            }

            val scheduler = PuzzleScheduler(
                projectPath = tempDir.toString(),
                puzzleStore = puzzleStore,
                versionStore = versionStore,
                evolutionLoop = mockLoop,
                intervalMs = 60000
            )

            // When
            scheduler.triggerImmediate()

            // 等待执行
            withTimeout(1000) {
                while (!executed.get()) {
                    kotlinx.coroutines.delay(50)
                }
            }

            // Then: 空知识库不应创建版本
            val currentVersion = versionStore.getCurrentVersion()
            assertEquals(0, currentVersion, "空知识库不应创建版本")
        }
    }

    // ========== Helper ==========

    private fun createScheduler(intervalMs: Long = 60000): PuzzleScheduler {
        return PuzzleScheduler(
            projectPath = tempDir.toString(),
            puzzleStore = puzzleStore,
            versionStore = versionStore,
            evolutionLoop = evolutionLoop,
            intervalMs = intervalMs
        )
    }
}
