package com.smancode.sman.architect.model

import com.smancode.sman.analysis.model.AnalysisType
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * FileChangeImpact 测试
 *
 * 测试文件变更影响分析结果
 */
@DisplayName("FileChangeImpact 测试")
class FileChangeImpactTest {

    @Nested
    @DisplayName("工厂方法测试")
    inner class FactoryMethodTest {

        @Test
        @DisplayName("应该创建无变更结果")
        fun `should create no change result`() {
            // When
            val impact = FileChangeImpact.noChange(AnalysisType.PROJECT_STRUCTURE)

            // Then
            assertFalse(impact.needsUpdate)
            assertEquals(FileChangeImpact.ImpactLevel.LOW, impact.impactLevel)
            assertTrue(impact.changedFiles.isEmpty())
            assertEquals("无文件变更", impact.reason)
        }

        @Test
        @DisplayName("应该创建高影响变更结果")
        fun `should create high impact result`() {
            // Given
            val files = listOf("src/main/kotlin/Core.kt", "build.gradle.kts")

            // When
            val impact = FileChangeImpact.highImpact(
                AnalysisType.PROJECT_STRUCTURE,
                files,
                listOf("模块结构"),
                "核心文件变更"
            )

            // Then
            assertTrue(impact.needsUpdate)
            assertEquals(FileChangeImpact.ImpactLevel.HIGH, impact.impactLevel)
            assertEquals(2, impact.changedFiles.size)
            assertEquals("核心文件变更", impact.reason)
        }

        @Test
        @DisplayName("应该创建中等影响变更结果")
        fun `should create medium impact result`() {
            // When
            val impact = FileChangeImpact.mediumImpact(
                AnalysisType.TECH_STACK,
                listOf("src/main/kotlin/Util.kt")
            )

            // Then
            assertTrue(impact.needsUpdate)
            assertEquals(FileChangeImpact.ImpactLevel.MEDIUM, impact.impactLevel)
        }

        @Test
        @DisplayName("应该创建低影响变更结果")
        fun `should create low impact result`() {
            // When
            val impact = FileChangeImpact.lowImpact(
                AnalysisType.API_ENTRIES,
                listOf("README.md")
            )

            // Then
            assertFalse(impact.needsUpdate)
            assertEquals(FileChangeImpact.ImpactLevel.LOW, impact.impactLevel)
        }
    }

    @Nested
    @DisplayName("ImpactLevel 测试")
    inner class ImpactLevelTest {

        @Test
        @DisplayName("应该正确解析影响级别")
        fun `should parse impact level`() {
            assertEquals(FileChangeImpact.ImpactLevel.HIGH, FileChangeImpact.ImpactLevel.fromString("HIGH"))
            assertEquals(FileChangeImpact.ImpactLevel.MEDIUM, FileChangeImpact.ImpactLevel.fromString("MEDIUM"))
            assertEquals(FileChangeImpact.ImpactLevel.LOW, FileChangeImpact.ImpactLevel.fromString("LOW"))
            // 未知值默认返回 LOW
            assertEquals(FileChangeImpact.ImpactLevel.LOW, FileChangeImpact.ImpactLevel.fromString("unknown"))
        }
    }

    @Nested
    @DisplayName("格式化测试")
    inner class FormatTest {

        @Test
        @DisplayName("应该正确格式化摘要")
        fun `should format summary`() {
            // Given
            val impact = FileChangeImpact(
                analysisType = AnalysisType.PROJECT_STRUCTURE,
                changedFiles = listOf("file1.kt", "file2.kt"),
                needsUpdate = true,
                impactLevel = FileChangeImpact.ImpactLevel.HIGH,
                affectedSections = listOf("模块结构"),
                reason = "核心变更"
            )

            // When
            val summary = impact.formatSummary()

            // Then
            assertTrue(summary.contains("项目结构分析"))
            assertTrue(summary.contains("变更文件数: 2"))
            assertTrue(summary.contains("HIGH"))
            assertTrue(summary.contains("需要更新: 是"))
            assertTrue(summary.contains("核心变更"))
        }
    }
}
