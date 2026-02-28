package com.smancode.sman.domain.puzzle

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * KnowledgeBaseVersionStore 单元测试
 *
 * 测试覆盖：
 * 1. 版本创建：初始版本、版本号递增
 * 2. 快照存储：保存和加载
 * 3. 变更检测：checksum 比较
 * 4. 版本索引：index.json 管理
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("KnowledgeBaseVersionStore 测试套件")
class KnowledgeBaseVersionStoreTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var versionStore: KnowledgeBaseVersionStore

    @BeforeEach
    fun setUp() {
        versionStore = KnowledgeBaseVersionStore(tempDir.toString())
    }

    // ========== 版本创建测试 ==========

    @Nested
    @DisplayName("版本创建测试")
    inner class VersionCreationTests {

        @Test
        @DisplayName("createVersion - 空知识库应创建初始版本 v1")
        fun createVersion_EmptyKnowledgeBase_ShouldCreateVersion1() {
            // Given: 空知识库
            val puzzles = emptyList<com.smancode.sman.shared.model.Puzzle>()

            // When
            val version = versionStore.createVersion(
                puzzles = puzzles,
                trigger = VersionTrigger.AUTO,
                description = "初始版本"
            )

            // Then
            assertNotNull(version)
            assertEquals(1, version.versionNumber)
            assertEquals(0, version.puzzleCount)
            assertEquals(VersionTrigger.AUTO, version.trigger)
            assertEquals("初始版本", version.description)
        }

        @Test
        @DisplayName("createVersion - 版本号应递增")
        fun createVersion_ShouldIncrementVersionNumber() {
            // Given: 创建第一个版本
            versionStore.createVersion(
                puzzles = emptyList(),
                trigger = VersionTrigger.MANUAL,
                description = "v1"
            )

            // When: 创建第二个版本
            val version2 = versionStore.createVersion(
                puzzles = createTestPuzzles(2),
                trigger = VersionTrigger.AUTO,
                description = "v2"
            )

            // Then
            assertEquals(2, version2.versionNumber)
            assertEquals(2, version2.puzzleCount)
        }

        @Test
        @DisplayName("createVersion - 应生成唯一 ID")
        fun createVersion_ShouldGenerateUniqueId() {
            // Given
            val puzzles = createTestPuzzles(1)

            // When
            val version1 = versionStore.createVersion(puzzles, VersionTrigger.AUTO, null)
            Thread.sleep(10) // 确保时间戳不同
            val version2 = versionStore.createVersion(puzzles, VersionTrigger.AUTO, null)

            // Then
            assertTrue(version1.id != version2.id, "版本 ID 应该唯一")
            assertTrue(version1.id.startsWith("v${version1.versionNumber}-"), "版本 ID 应以版本号开头")
        }

        @Test
        @DisplayName("createVersion - 应计算正确的 checksum")
        fun createVersion_ShouldCalculateCorrectChecksum() {
            // Given
            val puzzles1 = createTestPuzzles(1, "content1")
            val puzzles2 = createTestPuzzles(1, "content2")

            // When
            val version1 = versionStore.createVersion(puzzles1, VersionTrigger.MANUAL, null)
            val version2 = versionStore.createVersion(puzzles2, VersionTrigger.MANUAL, null)

            // Then
            assertTrue(version1.checksum.isNotBlank(), "checksum 不应为空")
            assertTrue(version2.checksum.isNotBlank(), "checksum 不应为空")
            assertTrue(version1.checksum != version2.checksum, "不同内容应有不同 checksum")
        }
    }

    // ========== 快照存储测试 ==========

    @Nested
    @DisplayName("快照存储测试")
    inner class SnapshotStorageTests {

        @Test
        @DisplayName("saveSnapshot and loadSnapshot - 应正确保存和加载")
        fun saveAndLoadSnapshot_ShouldPersistCorrectly() {
            // Given
            val puzzles = createTestPuzzles(3)
            val version = versionStore.createVersion(
                puzzles = puzzles,
                trigger = VersionTrigger.MANUAL,
                description = "测试快照"
            )

            // When
            val loadedSnapshot = versionStore.loadSnapshot(version.id)

            // Then
            assertNotNull(loadedSnapshot)
            assertEquals(version.id, loadedSnapshot.version.id)
            assertEquals(3, loadedSnapshot.puzzles.size)
            assertEquals("测试快照", loadedSnapshot.version.description)
        }

        @Test
        @DisplayName("loadSnapshot - 不存在的快照返回 null")
        fun loadSnapshot_NonExistent_ReturnsNull() {
            // When
            val result = versionStore.loadSnapshot("non-existent-id")

            // Then
            assertNull(result)
        }

        @Test
        @DisplayName("listVersions - 应返回所有版本列表")
        fun listVersions_ShouldReturnAllVersions() {
            // Given
            versionStore.createVersion(createTestPuzzles(1), VersionTrigger.AUTO, "v1")
            versionStore.createVersion(createTestPuzzles(2), VersionTrigger.AUTO, "v2")
            versionStore.createVersion(createTestPuzzles(3), VersionTrigger.MANUAL, "v3")

            // When
            val versions = versionStore.listVersions()

            // Then
            assertEquals(3, versions.size)
            assertEquals(1, versions[0].versionNumber)
            assertEquals(2, versions[1].versionNumber)
            assertEquals(3, versions[2].versionNumber)
        }
    }

    // ========== 变更检测测试 ==========

    @Nested
    @DisplayName("变更检测测试")
    inner class ChangeDetectionTests {

        @Test
        @DisplayName("hasChangesSince - 无变更应返回 false")
        fun hasChangesSince_NoChanges_ReturnsFalse() {
            // Given
            val puzzles = createTestPuzzles(2)
            val version = versionStore.createVersion(puzzles, VersionTrigger.MANUAL, null)

            // When: 使用相同的 puzzles 检测
            val hasChanges = versionStore.hasChangesSince(version.checksum, puzzles)

            // Then
            assertFalse(hasChanges, "相同内容不应检测到变更")
        }

        @Test
        @DisplayName("hasChangesSince - 有变更应返回 true")
        fun hasChangesSince_HasChanges_ReturnsTrue() {
            // Given
            val puzzles1 = createTestPuzzles(2, "original")
            val version = versionStore.createVersion(puzzles1, VersionTrigger.MANUAL, null)

            // When: puzzles 发生变化
            val puzzles2 = createTestPuzzles(3, "modified")
            val hasChanges = versionStore.hasChangesSince(version.checksum, puzzles2)

            // Then
            assertTrue(hasChanges, "内容变更应检测到变化")
        }

        @Test
        @DisplayName("hasChangesSince - 空 checksum 应返回 true")
        fun hasChangesSince_EmptyChecksum_ReturnsTrue() {
            // Given
            val puzzles = createTestPuzzles(1)

            // When
            val hasChanges = versionStore.hasChangesSince("", puzzles)

            // Then
            assertTrue(hasChanges, "空 checksum 应视为有变更")
        }
    }

    // ========== 版本索引测试 ==========

    @Nested
    @DisplayName("版本索引测试")
    inner class VersionIndexTests {

        @Test
        @DisplayName("getCurrentVersion - 应返回最新版本号")
        fun getCurrentVersion_ShouldReturnLatestVersionNumber() {
            // Given
            versionStore.createVersion(emptyList(), VersionTrigger.AUTO, "v1")
            versionStore.createVersion(emptyList(), VersionTrigger.AUTO, "v2")
            versionStore.createVersion(emptyList(), VersionTrigger.MANUAL, "v3")

            // When
            val currentVersion = versionStore.getCurrentVersion()

            // Then
            assertEquals(3, currentVersion)
        }

        @Test
        @DisplayName("getCurrentVersion - 无版本时返回 0")
        fun getCurrentVersion_NoVersions_ReturnsZero() {
            // When
            val currentVersion = versionStore.getCurrentVersion()

            // Then
            assertEquals(0, currentVersion)
        }

        @Test
        @DisplayName("getLatestChecksum - 应返回最新版本的 checksum")
        fun getLatestChecksum_ShouldReturnLatestChecksum() {
            // Given
            val puzzles1 = createTestPuzzles(1, "content1")
            val puzzles2 = createTestPuzzles(2, "content2")
            versionStore.createVersion(puzzles1, VersionTrigger.AUTO, null)
            val version2 = versionStore.createVersion(puzzles2, VersionTrigger.AUTO, null)

            // When
            val checksum = versionStore.getLatestChecksum()

            // Then
            assertEquals(version2.checksum, checksum)
        }

        @Test
        @DisplayName("getLatestChecksum - 无版本时返回空字符串")
        fun getLatestChecksum_NoVersions_ReturnsEmptyString() {
            // When
            val checksum = versionStore.getLatestChecksum()

            // Then
            assertEquals("", checksum)
        }
    }

    // ========== 触发类型测试 ==========

    @Nested
    @DisplayName("触发类型测试")
    inner class TriggerTypeTests {

        @Test
        @DisplayName("createVersion - MANUAL 触发应正确记录")
        fun createVersion_ManualTrigger_ShouldRecordCorrectly() {
            // When
            val version = versionStore.createVersion(
                puzzles = emptyList(),
                trigger = VersionTrigger.MANUAL,
                description = "手动触发"
            )

            // Then
            assertEquals(VersionTrigger.MANUAL, version.trigger)
        }

        @Test
        @DisplayName("createVersion - AUTO 触发应正确记录")
        fun createVersion_AutoTrigger_ShouldRecordCorrectly() {
            // When
            val version = versionStore.createVersion(
                puzzles = emptyList(),
                trigger = VersionTrigger.AUTO,
                description = null
            )

            // Then
            assertEquals(VersionTrigger.AUTO, version.trigger)
        }

        @Test
        @DisplayName("createVersion - ITERATION 触发应正确记录")
        fun createVersion_IterationTrigger_ShouldRecordCorrectly() {
            // Given: ITERATION 触发需要有拼图数据
            val puzzles = createTestPuzzles(1)

            // When
            val version = versionStore.createVersion(
                puzzles = puzzles,
                trigger = VersionTrigger.ITERATION,
                description = "迭代触发"
            )

            // Then
            assertEquals(VersionTrigger.ITERATION, version.trigger)
        }

        @Test
        @DisplayName("createVersion - ITERATION 触发无拼图应抛异常")
        fun createVersion_IterationTriggerWithoutPuzzles_ShouldThrowException() {
            // When & Then
            val result = runCatching {
                versionStore.createVersion(
                    puzzles = emptyList(),
                    trigger = VersionTrigger.ITERATION,
                    description = "迭代触发"
                )
            }

            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull() is IllegalArgumentException)
        }
    }

    // ========== Helper ==========

    private fun createTestPuzzles(
        count: Int,
        contentPrefix: String = "content"
    ): List<com.smancode.sman.shared.model.Puzzle> {
        return (1..count).map { i ->
            com.smancode.sman.shared.model.Puzzle(
                id = "puzzle-$i",
                type = com.smancode.sman.shared.model.PuzzleType.STRUCTURE,
                status = com.smancode.sman.shared.model.PuzzleStatus.COMPLETED,
                content = "# $contentPrefix $i\n\nTest content for puzzle $i",
                completeness = 0.8,
                confidence = 0.9,
                lastUpdated = Instant.now(),
                filePath = ".sman/puzzles/puzzle-$i.md"
            )
        }
    }
}
