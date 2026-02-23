package com.smancode.sman.infra.storage

import com.smancode.sman.shared.model.Puzzle
import com.smancode.sman.shared.model.PuzzleStatus
import com.smancode.sman.shared.model.PuzzleType
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * PuzzleStore 单元测试
 *
 * 测试覆盖：
 * 1. 基础 CRUD：save, load, loadAll, delete
 * 2. Markdown 格式：验证文件格式正确性
 * 3. 白名单校验：参数不满足时抛异常
 * 4. 边界值：空目录、不存在的文件
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("PuzzleStore 测试套件")
class PuzzleStoreTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var puzzleStore: PuzzleStore

    @BeforeEach
    fun setUp() {
        puzzleStore = PuzzleStore(tempDir.toString())
    }

    // ========== 基础 CRUD 测试 ==========

    @Nested
    @DisplayName("基础 CRUD 测试")
    inner class CrudTests {

        @Test
        @DisplayName("save and load - 应正确持久化和读取")
        fun saveAndLoad_ShouldPersistAndRetrieveCorrectly() {
            // Given
            val puzzle = createTestPuzzle()

            // When
            val saveResult = puzzleStore.save(puzzle)
            val loaded = puzzleStore.load(puzzle.id)

            // Then
            assertTrue(saveResult.isSuccess, "save 应该成功")
            assertTrue(loaded.isSuccess, "load 应该成功")
            val puzzleData = loaded.getOrThrow()
            assertNotNull(puzzleData)
            assertEquals(puzzle.id, puzzleData.id)
            assertEquals(puzzle.type, puzzleData.type)
            assertEquals(puzzle.content, puzzleData.content)
            assertEquals(puzzle.status, puzzleData.status)
            assertEquals(puzzle.completeness, puzzleData.completeness, 0.001)
            assertEquals(puzzle.confidence, puzzleData.confidence, 0.001)
        }

        @Test
        @DisplayName("load - 不存在的 puzzle 返回 null")
        fun loadNonExistentPuzzle_ShouldReturnNull() {
            // When
            val result = puzzleStore.load("non-existent-id")

            // Then
            assertTrue(result.isSuccess)
            assertNull(result.getOrThrow())
        }

        @Test
        @DisplayName("loadAll - 应返回所有 puzzles")
        fun loadAll_ShouldReturnAllPuzzles() {
            // Given
            val puzzle1 = createTestPuzzle(id = "puzzle-1", type = PuzzleType.STRUCTURE)
            val puzzle2 = createTestPuzzle(id = "puzzle-2", type = PuzzleType.TECH_STACK)
            puzzleStore.save(puzzle1)
            puzzleStore.save(puzzle2)

            // When
            val result = puzzleStore.loadAll()

            // Then
            assertTrue(result.isSuccess)
            val puzzles = result.getOrThrow()
            assertEquals(2, puzzles.size)
            assertTrue(puzzles.any { it.id == "puzzle-1" })
            assertTrue(puzzles.any { it.id == "puzzle-2" })
        }

        @Test
        @DisplayName("loadAll - 空目录返回空列表")
        fun loadAll_EmptyDirectory_ReturnsEmptyList() {
            // When
            val result = puzzleStore.loadAll()

            // Then
            assertTrue(result.isSuccess)
            val puzzles = result.getOrThrow()
            assertEquals(0, puzzles.size)
        }

        @Test
        @DisplayName("delete - 应从存储中移除")
        fun delete_ShouldRemoveFromStorage() {
            // Given
            val puzzle = createTestPuzzle()
            puzzleStore.save(puzzle)

            // When
            val deleteResult = puzzleStore.delete(puzzle.id)
            val loaded = puzzleStore.load(puzzle.id)

            // Then
            assertTrue(deleteResult.isSuccess)
            assertTrue(loaded.isSuccess)
            assertNull(loaded.getOrThrow())
        }

        @Test
        @DisplayName("delete - 删除不存在的 puzzle 应成功")
        fun deleteNonExistentPuzzle_ShouldSucceed() {
            // When
            val result = puzzleStore.delete("non-existent-id")

            // Then
            assertTrue(result.isSuccess)
        }
    }

    // ========== Markdown 格式测试 ==========

    @Nested
    @DisplayName("Markdown 格式测试")
    inner class MarkdownFormatTests {

        @Test
        @DisplayName("save - 应创建有效的 markdown 文件")
        fun save_ShouldCreateValidMarkdownFile() {
            // Given
            val puzzle = createTestPuzzle(content = "# Test Content\n\nThis is test.")

            // When
            puzzleStore.save(puzzle)

            // Then
            val puzzleFile = tempDir.resolve(".sman/puzzles/${puzzle.id}.md").toFile()
            assertTrue(puzzleFile.exists(), "markdown 文件应该存在")
            val content = puzzleFile.readText()
            assertTrue(content.contains("# Test Content"), "内容应包含 markdown 标题")
            assertTrue(content.contains("---"), "应包含 YAML frontmatter 分隔符")
            assertTrue(content.contains("id: ${puzzle.id}"), "frontmatter 应包含 id")
            assertTrue(content.contains("type: ${puzzle.type.name}"), "frontmatter 应包含 type")
        }

        @Test
        @DisplayName("save - 应正确保存中文内容")
        fun save_ShouldHandleChineseContent() {
            // Given
            val puzzle = createTestPuzzle(content = "# 中文测试\n\n这是中文内容。")

            // When
            puzzleStore.save(puzzle)
            val loaded = puzzleStore.load(puzzle.id)

            // Then
            assertTrue(loaded.isSuccess)
            val puzzleData = loaded.getOrThrow()
            assertNotNull(puzzleData)
            assertEquals("# 中文测试\n\n这是中文内容。", puzzleData.content)
        }
    }

    // ========== 白名单校验测试 ==========

    @Nested
    @DisplayName("白名单校验测试")
    inner class ValidationTests {

        @Test
        @DisplayName("save - 空 id 应抛出异常")
        fun saveWithEmptyId_ShouldThrowException() {
            // Given
            val puzzle = createTestPuzzle(id = "")

            // When & Then
            val result = puzzleStore.save(puzzle)
            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull() is IllegalArgumentException)
        }

        @Test
        @DisplayName("save - 空白 id 应抛出异常")
        fun saveWithBlankId_ShouldThrowException() {
            // Given
            val puzzle = createTestPuzzle(id = "   ")

            // When & Then
            val result = puzzleStore.save(puzzle)
            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull() is IllegalArgumentException)
        }

        @Test
        @DisplayName("save - completeness 大于 1 应抛出异常")
        fun saveWithCompletenessGreaterThan1_ShouldThrowException() {
            // Given
            val puzzle = createTestPuzzle(completeness = 1.5)

            // When & Then
            val result = puzzleStore.save(puzzle)
            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull() is IllegalArgumentException)
        }

        @Test
        @DisplayName("save - completeness 小于 0 应抛出异常")
        fun saveWithNegativeCompleteness_ShouldThrowException() {
            // Given
            val puzzle = createTestPuzzle(completeness = -0.1)

            // When & Then
            val result = puzzleStore.save(puzzle)
            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull() is IllegalArgumentException)
        }

        @Test
        @DisplayName("save - confidence 大于 1 应抛出异常")
        fun saveWithConfidenceGreaterThan1_ShouldThrowException() {
            // Given
            val puzzle = createTestPuzzle(confidence = 2.0)

            // When & Then
            val result = puzzleStore.save(puzzle)
            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull() is IllegalArgumentException)
        }

        @Test
        @DisplayName("save - confidence 小于 0 应抛出异常")
        fun saveWithNegativeConfidence_ShouldThrowException() {
            // Given
            val puzzle = createTestPuzzle(confidence = -0.5)

            // When & Then
            val result = puzzleStore.save(puzzle)
            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull() is IllegalArgumentException)
        }

        @Test
        @DisplayName("save - 边界值 0 和 1 应通过校验")
        fun saveWithBoundaryValues_ShouldPassValidation() {
            // Given
            val puzzle1 = createTestPuzzle(id = "puzzle-0", completeness = 0.0, confidence = 0.0)
            val puzzle2 = createTestPuzzle(id = "puzzle-1", completeness = 1.0, confidence = 1.0)

            // When & Then
            assertTrue(puzzleStore.save(puzzle1).isSuccess)
            assertTrue(puzzleStore.save(puzzle2).isSuccess)
        }
    }

    // ========== 查询测试 ==========

    @Nested
    @DisplayName("查询测试")
    inner class QueryTests {

        @Test
        @DisplayName("findByType - 应返回指定类型的 puzzles")
        fun findByType_ShouldReturnPuzzlesOfSpecifiedType() {
            // Given
            val puzzle1 = createTestPuzzle(id = "puzzle-1", type = PuzzleType.STRUCTURE)
            val puzzle2 = createTestPuzzle(id = "puzzle-2", type = PuzzleType.STRUCTURE)
            val puzzle3 = createTestPuzzle(id = "puzzle-3", type = PuzzleType.API)
            puzzleStore.save(puzzle1)
            puzzleStore.save(puzzle2)
            puzzleStore.save(puzzle3)

            // When
            val result = puzzleStore.findByType(PuzzleType.STRUCTURE)

            // Then
            assertTrue(result.isSuccess)
            val puzzles = result.getOrThrow()
            assertEquals(2, puzzles.size)
            assertTrue(puzzles.all { it.type == PuzzleType.STRUCTURE })
        }

        @Test
        @DisplayName("findByStatus - 应返回指定状态的 puzzles")
        fun findByStatus_ShouldReturnPuzzlesOfSpecifiedStatus() {
            // Given
            val puzzle1 = createTestPuzzle(id = "puzzle-1", status = PuzzleStatus.COMPLETED)
            val puzzle2 = createTestPuzzle(id = "puzzle-2", status = PuzzleStatus.COMPLETED)
            val puzzle3 = createTestPuzzle(id = "puzzle-3", status = PuzzleStatus.IN_PROGRESS)
            puzzleStore.save(puzzle1)
            puzzleStore.save(puzzle2)
            puzzleStore.save(puzzle3)

            // When
            val result = puzzleStore.findByStatus(PuzzleStatus.COMPLETED)

            // Then
            assertTrue(result.isSuccess)
            val puzzles = result.getOrThrow()
            assertEquals(2, puzzles.size)
            assertTrue(puzzles.all { it.status == PuzzleStatus.COMPLETED })
        }
    }

    // ========== 覆盖更新测试 ==========

    @Nested
    @DisplayName("覆盖更新测试")
    inner class UpdateTests {

        @Test
        @DisplayName("save - 相同 id 应覆盖原有内容")
        fun saveWithSameId_ShouldOverwriteExistingContent() {
            // Given
            val puzzle1 = createTestPuzzle(
                id = "same-id",
                content = "Original Content",
                completeness = 0.5
            )
            puzzleStore.save(puzzle1)

            // When
            val puzzle2 = createTestPuzzle(
                id = "same-id",
                content = "Updated Content",
                completeness = 0.9
            )
            puzzleStore.save(puzzle2)
            val loaded = puzzleStore.load("same-id")

            // Then
            assertTrue(loaded.isSuccess)
            val puzzleData = loaded.getOrThrow()
            assertNotNull(puzzleData)
            assertEquals("Updated Content", puzzleData.content)
            assertEquals(0.9, puzzleData.completeness, 0.001)
        }
    }

    // ========== Helper ==========

    private fun createTestPuzzle(
        id: String = "test-puzzle",
        type: PuzzleType = PuzzleType.STRUCTURE,
        status: PuzzleStatus = PuzzleStatus.COMPLETED,
        content: String = "# Test Puzzle",
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
}
