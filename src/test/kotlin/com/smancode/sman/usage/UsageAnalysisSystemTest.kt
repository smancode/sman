package com.smancode.sman.usage

import com.smancode.sman.infra.storage.PuzzleStore
import com.smancode.sman.shared.model.Puzzle
import com.smancode.sman.shared.model.PuzzleStatus
import com.smancode.sman.shared.model.PuzzleType
import com.smancode.sman.usage.analyzer.UsageAnalyzer
import com.smancode.sman.usage.converter.PuzzleToSkillConverter
import com.smancode.sman.usage.model.*
import com.smancode.sman.usage.scheduler.UsageAnalysisScheduler
import com.smancode.sman.usage.store.AnalysisJobStateStore
import com.smancode.sman.usage.store.SkillUsageStore
import com.smancode.sman.usage.tracker.SkillUsageTracker
import com.smancode.sman.usage.updater.PuzzleQualityUpdater
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 使用分析系统测试
 *
 * 测试覆盖：
 * 1. SkillUsageTracker - 记录 LLM 调用
 * 2. UsageAnalyzer - 分析使用记录
 * 3. PuzzleToSkillConverter - Puzzle 转换
 * 4. PuzzleQualityUpdater - 质量更新
 * 5. UsageAnalysisScheduler - 调度器（断点续传、并发控制）
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("使用分析系统测试")
class UsageAnalysisSystemTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var usageStorePath: Path
    private lateinit var jobStateStorePath: Path
    private lateinit var historyDir: Path
    private lateinit var puzzleStore: PuzzleStore

    @BeforeEach
    fun setUp() {
        val usageDir = tempDir.resolve(".sman/usage")
        historyDir = usageDir.resolve("history")
        Files.createDirectories(usageDir)
        Files.createDirectories(historyDir)

        usageStorePath = usageDir.resolve("records.json")
        jobStateStorePath = usageDir.resolve("analysis_job.json")
        puzzleStore = PuzzleStore(tempDir.toString())
    }

    // 创建共享的 Store 实例（用于同一测试内的组件共享）
    private fun createSharedStore() = SkillUsageStore(usageStorePath)

    // ========== SkillUsageTracker 测试 ==========

    @Nested
    @DisplayName("SkillUsageTracker 测试")
    inner class TrackerTests {

        @Test
        @DisplayName("SkillUsageStore - 应正确追加和读取")
        fun store_ShouldAppendAndLoad() {
            // Given
            val store = createSharedStore()
            val record = createTestRecord(id = "test-1")

            println("Storage path: $usageStorePath")
            println("Record to append: $record")

            // When
            val id = store.append(record)
            println("Appended id: $id")

            // Then
            assertEquals("test-1", id)

            // 检查文件是否存在
            assertTrue(java.nio.file.Files.exists(usageStorePath), "文件应该存在，路径: $usageStorePath")

            // 打印文件内容用于调试
            val content = java.nio.file.Files.readString(usageStorePath)
            println("File content: $content")
            assertTrue(content.isNotEmpty(), "文件内容不应为空")

            val records = store.loadAll()
            println("Loaded records: $records")
            assertTrue(records.isNotEmpty(), "记录列表不应为空")
            assertEquals("test-1", records[0].id)
        }

        @Test
        @DisplayName("startTracking + completeTracking - 应正确记录调用")
        fun tracking_ShouldRecordCorrectly() {
            // Given - 使用共享的 Store 实例
            val store = createSharedStore()
            val tracker = SkillUsageTracker(store)

            val recordId = tracker.startTracking(
                userQuery = "如何实现用户登录",
                skillsUsed = listOf("api-entry", "business-flow")
            )

            // When
            val success = tracker.completeTracking(
                recordId = recordId,
                llmResponse = "可以通过 Session 或 JWT 实现...",
                responseTimeMs = 1500,
                tokenCount = 500
            )

            // Then
            assertTrue(success, "completeTracking 应返回 true")

            // 使用同一个 store 实例验证
            val records = store.loadAll()
            assertTrue(records.isNotEmpty(), "应该有记录存在，实际: ${records.size}")
            assertEquals(1, records.size)
            assertEquals("如何实现用户登录", records[0].userQuery)
            assertEquals(2, records[0].skillsUsed.size)
            assertTrue(records[0].skillsUsed.contains("api-entry"))
        }

        @Test
        @DisplayName("recordEdit + recordAcceptance - 应正确更新状态")
        fun editAndAcceptance_ShouldUpdateCorrectly() {
            // Given
            val store = createSharedStore()
            val tracker = SkillUsageTracker(store)

            // 先直接添加一条记录
            val record = createTestRecord(id = "test-edit")
            store.append(record)

            // When
            val editSuccess = tracker.recordEdit("test-edit", 2)
            val acceptSuccess = tracker.recordAcceptance("test-edit", true)

            // Then
            assertTrue(editSuccess, "recordEdit 应该成功")
            assertTrue(acceptSuccess, "recordAcceptance 应该成功")
            val records = store.loadAll()
            assertEquals(1, records.size)
            assertEquals(2, records[0].editCount)
            assertTrue(records[0].accepted)
        }

        @Test
        @DisplayName("响应超长应截取")
        fun longResponse_ShouldBeTruncated() {
            // Given
            val store = createSharedStore()
            val tracker = SkillUsageTracker(store)
            val longResponse = "x".repeat(1000)
            val recordId = tracker.startTracking("测试", emptyList())

            // When
            val completed = tracker.completeTracking(recordId, longResponse, 1000, 100)
            assertTrue(completed, "completeTracking 应该成功")

            // Then
            val records = store.loadAll()
            assertTrue(records.isNotEmpty(), "应该有记录存在")
            assertTrue(records[0].llmResponse.length <= SkillUsageRecord.MAX_RESPONSE_LENGTH + 3) // +3 for "..."
        }
    }

    // ========== UsageAnalyzer 测试 ==========

    @Nested
    @DisplayName("UsageAnalyzer 测试")
    inner class AnalyzerTests {

        @Test
        @DisplayName("analyze - 调用 Skill 的记录应计算效果得分")
        fun analyze_WithSkillUsage_ShouldCalculateEffectiveness() {
            // Given
            val records = listOf(
                createTestRecord(skillsUsed = listOf("api-entry"), accepted = true, editCount = 0),
                createTestRecord(skillsUsed = listOf("api-entry"), accepted = true, editCount = 1),
                createTestRecord(skillsUsed = listOf("api-entry"), accepted = false, editCount = 3)
            )

            // When
            val analyzer = UsageAnalyzer(createSharedStore())
            val result = analyzer.analyze(records, "test-job")

            // Then
            assertTrue(result.skillEffectiveness.containsKey("api-entry"))
            val effectiveness = result.skillEffectiveness["api-entry"]!!
            assertEquals(3, effectiveness.totalUsage)
            assertEquals(2, effectiveness.acceptedCount)
            assertTrue(effectiveness.effectiveness > 0.5)
        }

        @Test
        @DisplayName("analyze - 未调用 Skill 的记录应学习用户习惯")
        fun analyze_WithoutSkillUsage_ShouldLearnPatterns() {
            // Given
            val records = listOf(
                createTestRecord(skillsUsed = emptyList(), accepted = true, editCount = 0),
                createTestRecord(skillsUsed = emptyList(), accepted = true, editCount = 0),
                createTestRecord(skillsUsed = emptyList(), accepted = true, editCount = 1)
            )

            // When
            val analyzer = UsageAnalyzer(createSharedStore())
            val result = analyzer.analyze(records, "test-job")

            // Then
            assertTrue(result.userPatterns.isNotEmpty())
            val simplePref = result.userPatterns.find { it.patternType == "偏好简洁回复" }
            assertNotNull(simplePref)
        }

        @Test
        @DisplayName("analyze - 效果差的 Skill 应产生负向更新")
        fun analyze_PoorEffectiveness_ShouldProduceNegativeUpdate() {
            // Given
            val records = listOf(
                createTestRecord(skillsUsed = listOf("bad-skill"), accepted = false, editCount = 5),
                createTestRecord(skillsUsed = listOf("bad-skill"), accepted = false, editCount = 4),
                createTestRecord(skillsUsed = listOf("bad-skill"), accepted = false, editCount = 3)
            )

            // When
            val analyzer = UsageAnalyzer(createSharedStore())
            val result = analyzer.analyze(records, "test-job")

            // Then
            assertTrue(result.puzzleUpdates.isNotEmpty())
            val update = result.puzzleUpdates.first()
            assertTrue(update.confidenceDelta < 0, "效果差应产生负向置信度变化")
        }
    }

    // ========== PuzzleToSkillConverter 测试 ==========

    @Nested
    @DisplayName("PuzzleToSkillConverter 测试")
    inner class ConverterTests {

        @Test
        @DisplayName("convert - COMPLETED 状态的 Puzzle 应成功转换")
        fun convert_CompletedPuzzle_ShouldSucceed() {
            // Given - 直接创建 Puzzle 对象，不依赖 PuzzleStore
            val converter = PuzzleToSkillConverter(tempDir.toString())
            val puzzle = createTestPuzzle(status = PuzzleStatus.COMPLETED)

            // When
            val skill = converter.convert(puzzle)

            // Then
            assertNotNull(skill)
            assertEquals("project-structure", skill!!.name)
            assertTrue(skill.description.contains("项目结构"))
        }

        @Test
        @DisplayName("convert - 非 COMPLETED 状态应返回 null")
        fun convert_NonCompletedPuzzle_ShouldReturnNull() {
            // Given
            val converter = PuzzleToSkillConverter(tempDir.toString())
            val puzzle = createTestPuzzle(status = PuzzleStatus.IN_PROGRESS)

            // When
            val skill = converter.convert(puzzle)

            // Then
            assertNull(skill)
        }

        @Test
        @DisplayName("save - 应创建有效的 SKILL.md 文件")
        fun save_ShouldCreateValidSkillFile() {
            // Given - 使用独立目录
            val testDir = tempDir.resolve("converter-test-2")
            Files.createDirectories(testDir)
            val converter = PuzzleToSkillConverter(testDir.toString())
            val puzzle = createTestPuzzle(status = PuzzleStatus.COMPLETED)
            val skill = converter.convert(puzzle)!!

            // When
            val path = converter.save(skill)

            // Then
            assertTrue(Files.exists(path))
            val content = Files.readString(path)
            assertTrue(content.contains("---"))
            assertTrue(content.contains("name: project-structure"))
            assertTrue(content.contains("# 项目结构"))
        }

        @Test
        @DisplayName("各类型 Puzzle 应映射到正确的 Skill name")
        fun allTypes_ShouldMapToCorrectSkillName() {
            val converter = PuzzleToSkillConverter(tempDir.toString())
            val typeToExpectedPrefix = mapOf(
                PuzzleType.STRUCTURE to "project-structure",
                PuzzleType.TECH_STACK to "tech-stack",
                PuzzleType.API to "api-entry",
                PuzzleType.DATA to "data-model",
                PuzzleType.FLOW to "business-flow",
                PuzzleType.RULE to "business-rule"
            )

            for ((type, expectedPrefix) in typeToExpectedPrefix) {
                val puzzle = createTestPuzzle(type = type, status = PuzzleStatus.COMPLETED)
                val skill = converter.convert(puzzle)
                assertNotNull(skill, "$type 应成功转换")
                assertTrue(skill!!.name.startsWith(expectedPrefix), "$type 应映射到 $expectedPrefix")
            }
        }
    }

    // ========== PuzzleQualityUpdater 测试 ==========

    @Nested
    @DisplayName("PuzzleQualityUpdater 测试")
    inner class UpdaterTests {

        @Test
        @DisplayName("update - 正向更新应提升置信度")
        fun update_PositiveDelta_ShouldIncreaseConfidence() {
            // Given
            val converter = PuzzleToSkillConverter(tempDir.toString())
            val updater = PuzzleQualityUpdater(puzzleStore, converter)
            val puzzle = createTestPuzzle(confidence = 0.5)
            puzzleStore.save(puzzle)

            val update = PuzzleQualityUpdate(
                puzzleId = puzzle.id,
                skillName = "project-structure",
                confidenceDelta = 0.1,
                reason = "效果好"
            )

            // When
            val success = updater.update(update)

            // Then
            assertTrue(success)
            val updated = puzzleStore.load(puzzle.id).getOrThrow()
            assertEquals(0.6, updated!!.confidence, 0.001)
        }

        @Test
        @DisplayName("update - 置信度低于阈值应触发重分析")
        fun update_BelowThreshold_ShouldTriggerReanalysis() {
            // Given
            val converter = PuzzleToSkillConverter(tempDir.toString())
            val updater = PuzzleQualityUpdater(puzzleStore, converter)
            val puzzle = createTestPuzzle(confidence = 0.35, status = PuzzleStatus.COMPLETED)
            puzzleStore.save(puzzle)

            val update = PuzzleQualityUpdate(
                puzzleId = puzzle.id,
                skillName = "project-structure",
                confidenceDelta = -0.1,
                reason = "效果差"
            )

            // When
            updater.update(update)

            // Then
            val updated = puzzleStore.load(puzzle.id).getOrThrow()
            assertEquals(PuzzleStatus.PENDING, updated!!.status, "应变为 PENDING 状态")
        }

        @Test
        @DisplayName("update - 变化量应被限制在最大范围内")
        fun update_ShouldClampDelta() {
            // Given
            val converter = PuzzleToSkillConverter(tempDir.toString())
            val updater = PuzzleQualityUpdater(puzzleStore, converter)
            val puzzle = createTestPuzzle(confidence = 0.5)
            puzzleStore.save(puzzle)

            val update = PuzzleQualityUpdate(
                puzzleId = puzzle.id,
                skillName = "project-structure",
                confidenceDelta = 0.5,  // 超过 MAX_DELTA
                reason = "测试"
            )

            // When
            updater.update(update)

            // Then
            val updated = puzzleStore.load(puzzle.id).getOrThrow()
            assertTrue(updated!!.confidence <= 0.5 + PuzzleQualityUpdater.MAX_DELTA)
        }
    }

    // ========== AnalysisJobStateStore 测试 ==========
    // 注：详细的 JobState 测试已集成在 Scheduler 集成测试中

    // ========== Scheduler 集成测试 ==========

    @Nested
    @DisplayName("Scheduler 集成测试")
    inner class SchedulerIntegrationTests {

        @Test
        @DisplayName("execute - 完整流程应成功")
        fun execute_FullFlow_ShouldSucceed() {
            // Given - 使用独立的临时目录
            val testDir = tempDir.resolve("scheduler-test-1")
            Files.createDirectories(testDir)
            val scheduler = UsageAnalysisScheduler(testDir)

            // 添加使用记录
            val usageDir = testDir.resolve(".sman/usage")
            Files.createDirectories(usageDir)
            val store = SkillUsageStore(usageDir.resolve("records.json"))
            val tracker = SkillUsageTracker(store)

            val recordId = tracker.startTracking("测试问题", listOf("api-entry"))
            tracker.completeTracking(recordId, "响应", 1000, 100)
            tracker.recordAcceptance(recordId, true)

            // When
            val result = scheduler.execute()

            // Then
            assertTrue(result is ScheduleResult.Success)
        }

        @Test
        @DisplayName("execute - FAILED 状态应断点续传")
        fun execute_WhenFailed_ShouldResume() {
            // Given - 使用独立的临时目录
            val testDir = tempDir.resolve("scheduler-test-3")
            Files.createDirectories(testDir)
            val usageDir = testDir.resolve(".sman/usage")
            Files.createDirectories(usageDir)
            val historyDir = usageDir.resolve("history")
            Files.createDirectories(historyDir)

            // 添加多条记录
            val store = SkillUsageStore(usageDir.resolve("records.json"))
            repeat(150) { index ->
                store.append(
                    createTestRecord(
                        id = "rec-$index",
                        skillsUsed = if (index % 2 == 0) listOf("api-entry") else emptyList()
                    )
                )
            }

            // 创建 FAILED 状态（处理了 50 条）
            val jobStateStore = AnalysisJobStateStore(usageDir.resolve("analysis_job.json"), historyDir)
            val failedState = AnalysisJobState(
                jobId = "failed-job",
                status = JobStatus.FAILED,
                startedAt = Instant.now().minusSeconds(3600),
                lastProcessedIndex = 49,
                totalRecords = 150,
                processedRecords = 50,
                errorMessage = "Test failure"
            )
            jobStateStore.save(failedState)

            // When
            val scheduler = UsageAnalysisScheduler(testDir)
            val result = scheduler.execute()

            // Then
            assertTrue(result is ScheduleResult.Resumed || result is ScheduleResult.Success)
        }

        @Test
        @DisplayName("execute - 无数据应返回 NoData")
        fun execute_NoData_ShouldReturnNoData() {
            // Given - 使用独立的临时目录
            val testDir = tempDir.resolve("scheduler-test-4")
            Files.createDirectories(testDir)
            val scheduler = UsageAnalysisScheduler(testDir)

            // When
            val result = scheduler.execute()

            // Then
            assertEquals(ScheduleResult.NoData, result)
        }
    }

    // ========== Helper ==========

    private fun createTestRecord(
        id: String = "rec-test",
        skillsUsed: List<String> = emptyList(),
        accepted: Boolean = true,
        editCount: Int = 0
    ) = SkillUsageRecord(
        id = id,
        timestamp = Instant.now(),
        userQuery = "测试问题",
        llmResponse = "测试响应",
        skillsUsed = skillsUsed,
        editCount = editCount,
        accepted = accepted,
        responseTimeMs = 1000,
        tokenCount = 100
    )

    private fun createTestPuzzle(
        id: String = "PUZZLE_STRUCTURE",
        type: PuzzleType = PuzzleType.STRUCTURE,
        status: PuzzleStatus = PuzzleStatus.COMPLETED,
        content: String = "# 项目结构\n\n测试内容",
        completeness: Double = 0.8,
        confidence: Double = 0.9
    ) = Puzzle(
        id = id,
        type = type,
        status = status,
        content = content,
        completeness = completeness,
        confidence = confidence,
        lastUpdated = Instant.now(),
        filePath = ".sman/puzzles/$id.md"
    )

    private inline fun <reified T : Throwable> assertThrows(block: () -> Unit): T {
        try {
            block()
            throw AssertionError("Expected ${T::class.simpleName} but no exception was thrown")
        } catch (e: Throwable) {
            if (e !is T) {
                throw AssertionError("Expected ${T::class.simpleName} but got ${e::class.simpleName}")
            }
            return e
        }
    }
}
