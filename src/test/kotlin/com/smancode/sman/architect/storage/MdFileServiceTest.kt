package com.smancode.sman.architect.storage

import com.smancode.sman.analysis.model.AnalysisType
import com.smancode.sman.architect.model.EvaluationResult
import com.smancode.sman.architect.model.TodoItem
import com.smancode.sman.architect.model.TodoPriority
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * MdFileService 测试
 *
 * 测试 MD 文件的读写和元信息管理
 */
@DisplayName("MdFileService 测试")
class MdFileServiceTest {

    private lateinit var tempDir: Path
    private lateinit var mdFileService: MdFileService

    @BeforeEach
    fun setUp() {
        tempDir = Files.createTempDirectory("md-file-service-test")
        mdFileService = MdFileService(tempDir)
    }

    @AfterEach
    fun tearDown() {
        Files.walk(tempDir)
            .sorted(Comparator.reverseOrder())
            .forEach { Files.deleteIfExists(it) }
    }

    @Nested
    @DisplayName("基本操作测试")
    inner class BasicOperationTest {

        @Test
        @DisplayName("文件不存在时应返回 null")
        fun `should return null when file not exists`() {
            // When
            val content = mdFileService.readContent(AnalysisType.PROJECT_STRUCTURE)
            val metadata = mdFileService.readMetadata(AnalysisType.PROJECT_STRUCTURE)

            // Then
            assertNull(content)
            assertNull(metadata)
        }

        @Test
        @DisplayName("应该正确判断文件是否存在")
        fun `should check file exists`() {
            // Initially
            assertFalse(mdFileService.exists(AnalysisType.PROJECT_STRUCTURE))

            // After save
            mdFileService.saveWithMetadata(
                AnalysisType.PROJECT_STRUCTURE,
                "# 测试内容",
                EvaluationResult.complete("完成")
            )

            assertTrue(mdFileService.exists(AnalysisType.PROJECT_STRUCTURE))
        }
    }

    @Nested
    @DisplayName("保存和读取测试")
    inner class SaveAndReadTest {

        @Test
        @DisplayName("应该正确保存带元信息的 MD 文件")
        fun `should save md file with metadata`() {
            // Given
            val content = "# 项目结构分析\n\n这是分析内容..."
            val evaluation = EvaluationResult(
                completeness = 0.85,
                isComplete = true,
                summary = "分析基本完成",
                todos = listOf(TodoItem("补充细节", TodoPriority.MEDIUM)),
                followUpQuestions = emptyList()
            )

            // When
            val savedPath = mdFileService.saveWithMetadata(
                AnalysisType.PROJECT_STRUCTURE,
                content,
                evaluation
            )

            // Then
            assertTrue(Files.exists(savedPath))
            assertTrue(savedPath.toString().endsWith("01_project_structure.md"))
        }

        @Test
        @DisplayName("应该正确读取元信息")
        fun `should read metadata correctly`() {
            // Given
            val content = "# 测试内容"
            val evaluation = EvaluationResult(
                completeness = 0.75,
                isComplete = false,
                summary = "部分完成",
                todos = listOf(
                    TodoItem("重要待办", TodoPriority.HIGH),
                    TodoItem("次要待办", TodoPriority.LOW)
                ),
                followUpQuestions = listOf("追问1")
            )

            mdFileService.saveWithMetadata(AnalysisType.TECH_STACK, content, evaluation)

            // When
            val metadata = mdFileService.readMetadata(AnalysisType.TECH_STACK)

            // Then
            assertNotNull(metadata)
            assertEquals(AnalysisType.TECH_STACK, metadata!!.analysisType)
            assertEquals(0.75, metadata.completeness, 0.01)
            assertEquals(2, metadata.todos.size)
            assertEquals(TodoPriority.HIGH, metadata.todos[0].priority)
            assertEquals(TodoPriority.LOW, metadata.todos[1].priority)
        }

        @Test
        @DisplayName("应该正确读取内容区（不含元信息）")
        fun `should read content only`() {
            // Given
            val content = "# 项目结构\n\n## 概述\n这是概述内容..."
            val evaluation = EvaluationResult.complete("完成")

            mdFileService.saveWithMetadata(AnalysisType.PROJECT_STRUCTURE, content, evaluation)

            // When
            val contentOnly = mdFileService.readContentOnly(AnalysisType.PROJECT_STRUCTURE)

            // Then
            assertNotNull(contentOnly)
            assertFalse(contentOnly!!.contains("<!-- META"))
            assertTrue(contentOnly.contains("# 项目结构"))
        }
    }

    @Nested
    @DisplayName("增量更新测试")
    inner class IncrementalUpdateTest {

        @Test
        @DisplayName("应该正确更新迭代次数")
        fun `should update iteration count`() {
            // Given
            val content = "# 测试"
            val evaluation = EvaluationResult(
                completeness = 0.5,
                isComplete = false,
                summary = "第一轮",
                todos = emptyList(),
                followUpQuestions = emptyList()
            )

            // First save
            mdFileService.saveWithMetadata(AnalysisType.PROJECT_STRUCTURE, content, evaluation)
            val metadata1 = mdFileService.readMetadata(AnalysisType.PROJECT_STRUCTURE)

            // Second save with previous metadata
            mdFileService.saveWithMetadata(
                AnalysisType.PROJECT_STRUCTURE,
                content,
                evaluation.copy(summary = "第二轮"),
                metadata1
            )
            val metadata2 = mdFileService.readMetadata(AnalysisType.PROJECT_STRUCTURE)

            // Then
            assertNotNull(metadata1)
            assertNotNull(metadata2)
            assertEquals(1, metadata1!!.iterationCount)
            assertEquals(2, metadata2!!.iterationCount)
        }

        @Test
        @DisplayName("应该正确更新时间戳")
        fun `should update timestamp on touch`() {
            // Given
            val content = "# 测试"
            val evaluation = EvaluationResult.complete("完成")

            mdFileService.saveWithMetadata(AnalysisType.PROJECT_STRUCTURE, content, evaluation)

            val beforeTouch = mdFileService.readMetadata(AnalysisType.PROJECT_STRUCTURE)!!.lastModified

            // Wait a bit
            Thread.sleep(10)

            // When
            mdFileService.touchTimestamp(AnalysisType.PROJECT_STRUCTURE)

            // Then
            val afterTouch = mdFileService.readMetadata(AnalysisType.PROJECT_STRUCTURE)!!.lastModified
            assertTrue(afterTouch.isAfter(beforeTouch))
        }
    }

    @Nested
    @DisplayName("批量查询测试")
    inner class BatchQueryTest {

        @Test
        @DisplayName("应该正确获取未完成的分析类型")
        fun `should get incomplete types`() {
            // Given
            mdFileService.saveWithMetadata(
                AnalysisType.PROJECT_STRUCTURE,
                "# 内容",
                EvaluationResult(completeness = 0.9, isComplete = true, summary = "完成", todos = emptyList(), followUpQuestions = emptyList())
            )
            mdFileService.saveWithMetadata(
                AnalysisType.TECH_STACK,
                "# 内容",
                EvaluationResult(completeness = 0.5, isComplete = false, summary = "未完成", todos = emptyList(), followUpQuestions = emptyList())
            )

            // When
            val incompleteTypes = mdFileService.getIncompleteTypes(0.8)

            // Then
            assertFalse(incompleteTypes.contains(AnalysisType.PROJECT_STRUCTURE))
            assertTrue(incompleteTypes.contains(AnalysisType.TECH_STACK))
        }

        @Test
        @DisplayName("应该正确获取有 TODO 的类型")
        fun `should get types with todos`() {
            // Given
            mdFileService.saveWithMetadata(
                AnalysisType.PROJECT_STRUCTURE,
                "# 内容",
                EvaluationResult(
                    completeness = 0.8,
                    isComplete = true,
                    summary = "完成但有 TODO",
                    todos = listOf(TodoItem("待办", TodoPriority.HIGH)),
                    followUpQuestions = emptyList()
                )
            )

            // When
            val typesWithTodos = mdFileService.getTypesWithTodos()

            // Then
            assertEquals(1, typesWithTodos.size)
            assertEquals(AnalysisType.PROJECT_STRUCTURE, typesWithTodos[0].first)
            assertEquals(1, typesWithTodos[0].second.size)
        }
    }
}
