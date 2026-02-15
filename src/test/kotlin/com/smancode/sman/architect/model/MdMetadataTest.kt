package com.smancode.sman.architect.model

import com.smancode.sman.analysis.model.AnalysisType
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * MdMetadata 测试
 *
 * 测试 MD 文件元信息的序列化和解析
 */
@DisplayName("MdMetadata 测试")
class MdMetadataTest {

    @Nested
    @DisplayName("序列化测试")
    inner class SerializationTest {

        @Test
        @DisplayName("应该正确序列化为注释格式")
        fun `should serialize to comment format`() {
            // Given
            val metadata = MdMetadata(
                analysisType = AnalysisType.PROJECT_STRUCTURE,
                lastModified = Instant.parse("2026-02-15T10:30:00.00Z"),
                completeness = 0.85,
                todos = listOf(
                    TodoItem("分析多模块依赖关系", TodoPriority.HIGH),
                    TodoItem("补充架构图", TodoPriority.MEDIUM)
                ),
                iterationCount = 3,
                version = 2
            )

            // When
            val comment = metadata.toComment()

            // Then
            assertTrue(comment.startsWith("<!-- META"))
            assertTrue(comment.endsWith("-->"))
            assertTrue(comment.contains("analysisType: project_structure"))
            assertTrue(comment.contains("completeness: 0.85"))
            assertTrue(comment.contains("iterationCount: 3"))
            assertTrue(comment.contains("version: 2"))
        }

        @Test
        @DisplayName("空 TODO 列表应该正确序列化")
        fun `should serialize empty todos`() {
            // Given
            val metadata = MdMetadata(
                analysisType = AnalysisType.TECH_STACK,
                lastModified = Instant.now(),
                completeness = 1.0,
                todos = emptyList()
            )

            // When
            val comment = metadata.toComment()

            // Then
            assertTrue(comment.contains("todos:"))
            assertTrue(comment.contains("[]"))
        }
    }

    @Nested
    @DisplayName("解析测试")
    inner class ParsingTest {

        @Test
        @DisplayName("应该正确解析元信息注释")
        fun `should parse metadata comment`() {
            // Given
            val content = """
<!-- META
lastModified: 2026-02-15T10:30:00.00Z
analysisType: project_structure
completeness: 0.85
todos:
  - "[HIGH] 分析多模块依赖关系"
  - "[MEDIUM] 补充架构图"
iterationCount: 3
version: 2
-->

# 项目结构分析报告
            """.trimIndent()

            // When
            val metadata = MdMetadata.fromContent(content)

            // Then
            assertNotNull(metadata)
            assertEquals(AnalysisType.PROJECT_STRUCTURE, metadata!!.analysisType)
            assertEquals(Instant.parse("2026-02-15T10:30:00.00Z"), metadata.lastModified)
            assertEquals(0.85, metadata.completeness, 0.01)
            assertEquals(3, metadata.iterationCount)
            assertEquals(2, metadata.version)
            assertEquals(2, metadata.todos.size)
            assertEquals("分析多模块依赖关系", metadata.todos[0].content)
            assertEquals(TodoPriority.HIGH, metadata.todos[0].priority)
        }

        @Test
        @DisplayName("无元信息时应返回 null")
        fun `should return null when no metadata`() {
            // Given
            val content = """
# 项目结构分析报告

这是内容...
            """.trimIndent()

            // When
            val metadata = MdMetadata.fromContent(content)

            // Then
            assertNull(metadata)
        }

        @Test
        @DisplayName("应该处理空 TODO 列表")
        fun `should handle empty todo list`() {
            // Given
            val content = """
<!-- META
lastModified: 2026-02-15T10:30:00.00Z
analysisType: tech_stack
completeness: 1.0
todos:
  []
iterationCount: 1
version: 1
-->
            """.trimIndent()

            // When
            val metadata = MdMetadata.fromContent(content)

            // Then
            assertNotNull(metadata)
            assertTrue(metadata!!.todos.isEmpty())
        }
    }

    @Nested
    @DisplayName("往返测试")
    inner class RoundTripTest {

        @Test
        @DisplayName("序列化后解析应该得到相同结果")
        fun `should round trip correctly`() {
            // Given
            val original = MdMetadata(
                analysisType = AnalysisType.API_ENTRIES,
                lastModified = Instant.now(),
                completeness = 0.75,
                todos = listOf(
                    TodoItem("补充 REST API 文档", TodoPriority.HIGH),
                    TodoItem("添加请求示例", TodoPriority.LOW)
                ),
                iterationCount = 2,
                version = 1
            )

            // When
            val comment = original.toComment()
            val parsed = MdMetadata.fromContent(comment)

            // Then
            assertNotNull(parsed)
            assertEquals(original.analysisType, parsed!!.analysisType)
            assertEquals(original.completeness, parsed.completeness, 0.01)
            assertEquals(original.iterationCount, parsed.iterationCount)
            assertEquals(original.todos.size, parsed.todos.size)
        }
    }

    @Nested
    @DisplayName("needsMoreAnalysis 测试")
    inner class NeedsMoreAnalysisTest {

        @Test
        @DisplayName("完成度低于阈值时应需要继续分析")
        fun `should need more analysis when below threshold`() {
            val metadata = MdMetadata(
                analysisType = AnalysisType.PROJECT_STRUCTURE,
                lastModified = Instant.now(),
                completeness = 0.5
            )
            assertTrue(metadata.needsMoreAnalysis)
        }

        @Test
        @DisplayName("完成度达到阈值时不需要继续分析")
        fun `should not need more analysis when at threshold`() {
            val metadata = MdMetadata(
                analysisType = AnalysisType.PROJECT_STRUCTURE,
                lastModified = Instant.now(),
                completeness = 0.9
            )
            assertFalse(metadata.needsMoreAnalysis)
        }
    }
}
