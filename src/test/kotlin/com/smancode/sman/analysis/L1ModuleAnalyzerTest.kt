package com.smancode.sman.analysis

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertTrue

@DisplayName("模块分析 L1 测试")
class L1ModuleAnalyzerTest {
    @TempDir lateinit var tempDir: File

    @Test
    @DisplayName("L1: 分析模块类")
    fun testAnalyzeModuleClasses() {
        val srcDir = File(tempDir, "src/main/java/com/example/module")
        srcDir.mkdirs()
        File(srcDir, "UserService.java").writeText("""
            package com.example.module;
            public class UserService {
                public void save() {}
            }
        """.trimIndent())

        val analyzer = L1ModuleAnalyzer(tempDir.absolutePath, "module")
        val result = analyzer.analyze()

        assertTrue(result.classes.any { it.name == "UserService" })
    }

    @Test
    @DisplayName("L1: 提取接口")
    fun testExtractInterfaces() {
        val srcDir = File(tempDir, "src/main/java/com/example")
        srcDir.mkdirs()
        File(srcDir, "UserRepository.java").writeText("""
            package com.example;
            public interface UserRepository {
                User findById(Long id);
            }
        """.trimIndent())

        val analyzer = L1ModuleAnalyzer(tempDir.absolutePath, "example")
        val result = analyzer.analyze()

        assertTrue(result.interfaces.any { it.name == "UserRepository" })
    }
}
