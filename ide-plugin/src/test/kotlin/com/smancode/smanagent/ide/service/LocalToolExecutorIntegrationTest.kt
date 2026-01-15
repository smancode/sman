package com.smancode.smanagent.ide.service

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Test
import java.io.File

/**
 * LocalToolExecutor 集成测试
 * <p>
 * 测试 read_file 工具在真实 IntelliJ 环境中的行为
 */
class LocalToolExecutorIntegrationTest : BasePlatformTestCase() {

    @Test
    fun testReadFileWithPsiFile() {
        println("\n=== 集成测试：测试 PSI 文件读取 ===\n")

        // 创建测试文件
        val testFile = File(tempDir, "TestFile.java")
        val testContent = """
package com.test;

import java.util.List;
import java.util.Map;

/**
 * 测试类
 */
public class TestFile {

    public void method1() {
        System.out.println("Method 1");
    }

    public void method2() {
        System.out.println("Method 2");
    }

    public void method3() {
        System.out.println("Method 3");
    }
}
""".trimIndent()

        testFile.writeText(testContent)

        println("创建测试文件: ${testFile.absolutePath}")
        println("文件实际行数: ${testContent.lines().size}")
        println("文件实际长度: ${testContent.length}")
        println()

        // 在项目中找到该文件
        val virtualFile = myFixture.findFileInTempDir("TestFile.java")
        assertNotNull("找不到测试文件", virtualFile)

        // 使用 PSI 读取
        val psiFile = myFixture.psiManager.findFile(virtualFile)
        assertNotNull("无法创建 PSI 文件", psiFile)

        val psiContent = psiFile.text
        println("PSI 读取结果:")
        println("  psiFile.text.length: ${psiContent.length}")
        println("  psiFile.text.lines().size: ${psiContent.lines().size}")
        println()

        // 使用 InputStream 读取
        val streamContent = virtualFile.inputStream.use { it.bufferedReader().readText() }
        println("InputStream 读取结果:")
        println("  streamContent.length: ${streamContent.length}")
        println("  streamContent.lines().size: ${streamContent.lines().size}")
        println()

        // 比较
        println("=== 比较 ===")
        println("文件实际行数: ${testContent.lines().size}")
        println("PSI 读取行数: ${psiContent.lines().size}")
        println("Stream 读取行数: ${streamContent.lines().size}")
        println()

        if (psiContent.lines().size != testContent.lines().size) {
            println("❌ PSI 读取行数不一致！")
            println("PSI 内容前 500 字符:")
            println(psiContent.take(500))
            println()
            println("文件内容前 500 字符:")
            println(testContent.take(500))
        } else {
            println("✅ PSI 读取行数正确")
        }

        assertEquals("PSI 应该读取完整内容", testContent, psiContent)
    }

    @Test
    fun testReadFileLines() {
        println("\n=== 集成测试：测试行号读取 ===\n")

        // 创建一个 20 行的测试文件
        val testFile = File(tempDir, "LinesTest.java")
        val lines = (1..20).map { "Line $it" }
        val testContent = lines.joinToString("\n")

        testFile.writeText(testContent)

        println("创建测试文件: ${testFile.absolutePath}")
        println("文件实际行数: ${lines.size}")
        println()

        val virtualFile = myFixture.findFileInTempDir("LinesTest.java")
        val psiFile = myFixture.psiManager.findFile(virtualFile)

        val psiContent = psiFile.text
        val psiLines = psiContent.lines()

        println("PSI 读取:")
        println("  总行数: ${psiLines.size}")
        println("  前 5 行: ${psiLines.take(5)}")
        println("  后 5 行: ${psiLines.takeLast(5)}")
        println()

        // 测试 subList
        val startIndex = 0
        val endIndex = 10
        val selectedLines = psiLines.toList().subList(startIndex, endIndex)

        println("subList($startIndex, $endIndex):")
        println("  返回行数: ${selectedLines.size}")
        println("  内容: $selectedLines")
        println()

        assertEquals(10, selectedLines.size, "subList(0, 10) 应该返回 10 行")
        assertEquals("Line 1", selectedLines.first())
        assertEquals("Line 10", selectedLines.last())
    }
}

fun main() {
    println("运行集成测试需要 IntelliJ 测试环境")
    println("请使用: ./gradlew test --tests LocalToolExecutorIntegrationTest")
}
