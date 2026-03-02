package com.smancode.sman.analysis

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ProjectAnalyzer L0 测试
 */
@DisplayName("项目结构分析 L0 测试")
class ProjectAnalyzerL0Test {

    @TempDir
    lateinit var tempDir: File

    @Test
    @DisplayName("L0: 检测技术栈 - Spring Boot")
    fun testDetectSpringBoot() {
        // Given
        val pom = File(tempDir, "pom.xml")
        pom.writeText("""<project><dependencies><dependency><artifactId>spring-boot-starter-web</artifactId></dependency></dependencies></project>""")
        
        // When
        val analyzer = L0StructureAnalyzer(tempDir.absolutePath)
        val result = analyzer.analyze()
        
        // Then
        assertTrue(result.techStack.frameworks.any { it.contains("Spring") })
    }

    @Test
    @DisplayName("L0: 检测技术栈 - MySQL")
    fun testDetectMySQL() {
        val pom = File(tempDir, "pom.xml")
        pom.writeText("""<project><dependencies><dependency><artifactId>mysql-connector-java</artifactId></dependency></dependencies></project>""")
        
        val analyzer = L0StructureAnalyzer(tempDir.absolutePath)
        val result = analyzer.analyze()
        
        assertTrue(result.techStack.databases.any { it.contains("MySQL") })
    }

    @Test
    @DisplayName("L0: 空项目应返回空结果")
    fun testEmptyProject() {
        val analyzer = L0StructureAnalyzer(tempDir.absolutePath)
        val result = analyzer.analyze()
        
        assertTrue(result.modules.isEmpty())
        assertTrue(result.techStack.languages.isEmpty())
    }

    @Test
    @DisplayName("L0: 默认检测 Java")
    fun testDefaultJava() {
        val pom = File(tempDir, "pom.xml")
        pom.writeText("""<project></project>""")
        
        val analyzer = L0StructureAnalyzer(tempDir.absolutePath)
        val result = analyzer.analyze()
        
        assertTrue(result.techStack.languages.contains("Java"))
    }

    @Test
    @DisplayName("L0: 统计代码规模")
    fun testStatistics() {
        val srcDir = File(tempDir, "src/main/java")
        srcDir.mkdirs()
        File(srcDir, "Test.java").writeText("class Test {}")
        
        val analyzer = L0StructureAnalyzer(tempDir.absolutePath)
        val result = analyzer.analyze()
        
        assertTrue(result.statistics.totalFiles >= 1)
    }
}
