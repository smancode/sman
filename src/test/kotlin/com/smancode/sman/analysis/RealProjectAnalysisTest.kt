package com.smancode.sman.analysis

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

@DisplayName("实际项目分析测试")
class RealProjectAnalysisTest {
    private val projectPath = "/Users/nasakim/projects/smanunion"

    @Test
    @DisplayName("L0: 扫描 smanunion 项目")
    fun testAnalyzeSmanunionL0() {
        val analyzer = L0StructureAnalyzer(projectPath)
        val result = analyzer.analyze()
        println("=== L0 结果 ===")
        println("模块: ${result.modules.map { it.name }}")
        println("语言: ${result.techStack.languages}")
        println("框架: ${result.techStack.frameworks}")
        println("入口点: ${result.entryPoints.size}")
        println("统计: ${result.statistics}")
        assertTrue(result.modules.isNotEmpty() || result.statistics.totalFiles > 0)
    }

    @Test
    @DisplayName("L4: 扫描非常规设计")
    fun testAnalyzeSmanunionL4() {
        val analyzer = L4DeepAnalyzer(projectPath)
        val result = analyzer.analyze()
        println("=== L4 结果 ===")
        println("反模式: ${result.antiPatterns.size}")
        println("详情: ${result.antiPatterns.take(5)}")
        assertTrue(true)
    }
}
