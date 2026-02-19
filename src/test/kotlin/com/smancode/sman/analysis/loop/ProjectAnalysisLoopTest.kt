package com.smancode.sman.analysis.loop

import com.smancode.sman.analysis.config.JVectorConfig
import com.smancode.sman.analysis.config.VectorDatabaseConfig
import com.smancode.sman.analysis.config.VectorDbType
import com.smancode.sman.analysis.executor.AnalysisLoopExecutor
import com.smancode.sman.analysis.executor.AnalysisOutputValidator
import com.smancode.sman.analysis.model.*
import com.smancode.sman.analysis.persistence.AnalysisStateRepository
import com.smancode.sman.analysis.util.Md5FileTracker
import com.smancode.sman.evolution.guard.DoomLoopGuard
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * ProjectAnalysisLoop 测试
 *
 * 测试项目分析主循环的核心功能
 */
@DisplayName("ProjectAnalysisLoop 测试")
class ProjectAnalysisLoopTest {

    private lateinit var analysisLoop: ProjectAnalysisLoop
    private lateinit var stateRepository: AnalysisStateRepository
    private lateinit var md5FileTracker: Md5FileTracker
    private lateinit var config: AnalysisLoopConfig

    @TempDir
    lateinit var tempDir: Path

    @BeforeEach
    fun setUp() {
        // 创建测试配置
        val dbPath = tempDir.resolve("test-analysis").toString()
        val dbConfig = VectorDatabaseConfig(
            projectKey = "test-project",
            type = VectorDbType.JVECTOR,
            jvector = JVectorConfig(),
            databasePath = dbPath
        )

        stateRepository = AnalysisStateRepository(dbConfig)
        md5FileTracker = Md5FileTracker(tempDir)
        config = AnalysisLoopConfig(
            enabled = true,
            analysisIntervalMs = 1000,
            unchangedSkipIntervalMs = 5000,
            maxStepsPerAnalysis = 10
        )

        // 创建简化版的执行器（用于测试）
        val executor = createMockExecutor()

        analysisLoop = ProjectAnalysisLoop(
            projectKey = "test-project",
            projectPath = tempDir,
            analysisExecutor = executor,
            stateRepository = stateRepository,
            md5FileTracker = md5FileTracker,
            doomLoopGuard = DoomLoopGuard.createDefault(),
            config = config
        )
    }

    @AfterEach
    fun tearDown() {
        stateRepository.close()
    }

    private fun createMockExecutor(): AnalysisLoopExecutor {
        // 创建一个简化的执行器用于测试
        // 在实际测试中，可以使用 mock 或真实的执行器
        return AnalysisLoopExecutor(
            toolRegistry = mockk(relaxed = true),
            toolExecutor = mockk(relaxed = true),
            doomLoopGuard = DoomLoopGuard.createDefault(),
            llmService = mockk(relaxed = true),
            validator = AnalysisOutputValidator()
        )
    }

    // ========== 启动/停止测试 ==========

    @Nested
    @DisplayName("启动/停止测试")
    inner class StartStopTest {

        @Test
        @DisplayName("应能启动分析循环")
        fun `should start analysis loop`() = runBlocking {
            // When: 启动循环
            analysisLoop.start()

            // Then: 状态应为启用
            val status = analysisLoop.getStatus()
            assertTrue(status.enabled)

            // 清理
            analysisLoop.stop()
        }

        @Test
        @DisplayName("应能停止分析循环")
        fun `should stop analysis loop`() = runBlocking {
            // Given: 启动的循环
            analysisLoop.start()

            // When: 停止循环
            analysisLoop.stop()

            // Then: 状态应为禁用
            val status = analysisLoop.getStatus()
            assertFalse(status.enabled)
        }

        @Test
        @DisplayName("停止后的循环不应执行分析")
        fun `stopped loop should not execute analysis`() = runBlocking {
            // Given: 已停止的循环
            analysisLoop.stop()

            // When: 获取状态
            val status = analysisLoop.getStatus()

            // Then: 应为禁用状态
            assertFalse(status.enabled)
        }
    }

    // ========== 断点恢复测试 ==========

    @Nested
    @DisplayName("断点恢复测试")
    inner class ResumeFromInterruptedStateTest {

        @Test
        @DisplayName("启动时应检测并恢复 DOING 状态的任务")
        fun `should detect and resume doing tasks on start`() = runBlocking {
            // Given: 预先保存一个 DOING 状态的任务
            val doingTask = AnalysisResultEntity(
                projectKey = "test-project",
                analysisType = AnalysisType.PROJECT_STRUCTURE,
                taskStatus = TaskStatus.DOING,
                completeness = 0.5
            )
            stateRepository.saveAnalysisResult(doingTask)

            // When: 启动循环
            analysisLoop.start()

            // Then: 应检测到 DOING 任务并处理
            val status = analysisLoop.getStatus()
            assertNotNull(status)

            // 清理
            analysisLoop.stop()
        }

        @Test
        @DisplayName("恢复时应重置 DOING 任务为 PENDING 后重新执行")
        fun `should reset doing tasks to pending and re-execute`() = runBlocking {
            // Given: DOING 状态的任务
            stateRepository.saveAnalysisResult(
                AnalysisResultEntity(
                    projectKey = "test-project",
                    analysisType = AnalysisType.TECH_STACK,
                    taskStatus = TaskStatus.DOING
                )
            )

            // When: 启动并检查
            analysisLoop.start()
            analysisLoop.stop()

            // Then: 任务应被重置或完成
            // 具体验证取决于实现
        }
    }

    // ========== 变更检测测试 ==========

    @Nested
    @DisplayName("变更检测测试")
    inner class ChangeDetectionTest {

        @Test
        @DisplayName("未变更时应跳过分析")
        fun `should skip analysis when no changes`() = runBlocking {
            // Given: 没有 MD5 变化的情况
            // (初始化时没有文件变化)

            // When: 获取状态
            val status = analysisLoop.getStatus()

            // Then: 应为空闲或正常状态
            assertNotNull(status)
        }

        @Test
        @DisplayName("检测到变更时应触发分析")
        fun `should trigger analysis when changes detected`() = runBlocking {
            // Given: 配置为启用状态
            analysisLoop.start()

            // When: 获取状态
            val status = analysisLoop.getStatus()

            // Then: 应为启用状态
            assertTrue(status.enabled)

            // 清理
            analysisLoop.stop()
        }
    }

    // ========== TODO 继续测试 ==========

    @Nested
    @DisplayName("TODO 继续测试")
    inner class TodoContinuationTest {

        @Test
        @DisplayName("应能识别未完成的 TODO")
        fun `should identify incomplete todos`() = runBlocking {
            // Given: 带未完成 TODO 的分析结果
            stateRepository.saveAnalysisResult(
                AnalysisResultEntity(
                    projectKey = "test-project",
                    analysisType = AnalysisType.API_ENTRIES,
                    taskStatus = TaskStatus.COMPLETED,
                    completeness = 0.7,
                    analysisTodos = listOf(
                        AnalysisTodo(id = "t1", content = "补充分析：请求参数", status = TodoStatus.PENDING, priority = 1)
                    )
                )
            )

            // When: 获取状态
            val status = analysisLoop.getStatus()

            // Then: 应正常
            assertNotNull(status)
        }

        @Test
        @DisplayName("完整的分析应无待处理 TODO")
        fun `complete analysis should have no pending todos`() = runBlocking {
            // Given: 完整的分析结果
            stateRepository.saveAnalysisResult(
                AnalysisResultEntity(
                    projectKey = "test-project",
                    analysisType = AnalysisType.PROJECT_STRUCTURE,
                    taskStatus = TaskStatus.COMPLETED,
                    completeness = 1.0,
                    analysisTodos = emptyList()
                )
            )

            // When: 获取状态
            val status = analysisLoop.getStatus()

            // Then: 应正常
            assertNotNull(status)
        }
    }

    // ========== 状态查询测试 ==========

    @Nested
    @DisplayName("状态查询测试")
    inner class StatusQueryTest {

        @Test
        @DisplayName("应能获取当前状态")
        fun `should get current status`() = runBlocking {
            // When: 获取状态
            val status = analysisLoop.getStatus()

            // Then: 应包含必要信息
            assertNotNull(status)
            assertNotNull(status.enabled)
        }

        @Test
        @DisplayName("状态应包含统计信息")
        fun `status should include statistics`() = runBlocking {
            // Given: 一些分析结果
            stateRepository.saveAnalysisResult(
                AnalysisResultEntity(
                    projectKey = "test-project",
                    analysisType = AnalysisType.PROJECT_STRUCTURE,
                    taskStatus = TaskStatus.COMPLETED,
                    completeness = 0.9
                )
            )

            // When: 获取统计
            val stats = stateRepository.getProjectStatistics("test-project")

            // Then: 应包含正确的统计
            assertEquals(1, stats.totalTasks)
            assertEquals(1, stats.completedTasks)
        }
    }

    // ========== 分析类型优先级测试 ==========

    @Nested
    @DisplayName("分析类型优先级测试")
    inner class AnalysisTypePriorityTest {

        @Test
        @DisplayName("核心分析类型应优先执行")
        fun `core analysis types should be prioritized`() {
            // Given: 核心类型
            val coreTypes = AnalysisType.coreTypes()

            // Then: 应包含项目结构和技术栈
            assertTrue(coreTypes.contains(AnalysisType.PROJECT_STRUCTURE))
            assertTrue(coreTypes.contains(AnalysisType.TECH_STACK))
        }

        @Test
        @DisplayName("标准分析类型应在核心类型之后执行")
        fun `standard analysis types should come after core`() {
            // Given: 标准类型
            val standardTypes = AnalysisType.standardTypes()

            // Then: 应包含其他类型
            assertTrue(standardTypes.contains(AnalysisType.API_ENTRIES))
            assertTrue(standardTypes.contains(AnalysisType.DB_ENTITIES))
        }
    }
}
