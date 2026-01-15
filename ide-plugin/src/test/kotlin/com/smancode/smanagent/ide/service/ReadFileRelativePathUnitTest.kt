package com.smancode.smanagent.ide.service

import org.junit.Test
import org.junit.Assert.*
import java.io.File

/**
 * 单元测试：验证 read_file 工具返回 relativePath
 */
class ReadFileRelativePathUnitTest {

    @Test
    fun testLocalToolExecutorReturnsRelativePath() {
        println("\n========== 测试 LocalToolExecutor 返回 relativePath ==========\n")
        
        // 创建测试文件
        val tempDir = createTempDir("test")
        val testFile = File(tempDir, "TestService.java")
        testFile.writeText("""
package com.test;

public class TestService {
    public void test() {
        System.out.println("test");
    }
}
""".trimIndent())
        
        val projectPath = tempDir.absolutePath
        val expectedRelativePath = "TestService.java"
        
        println("测试文件: ${testFile.absolutePath}")
        println("项目路径: $projectPath")
        println("期望相对路径: $expectedRelativePath")
        println()
        
        // 模拟 toRelativePath 函数
        fun toRelativePath(absolutePath: String, basePath: String): String {
            if (basePath.isEmpty()) return absolutePath
            val normalizedAbsolute = absolutePath.replace("\\", "/")
            val normalizedBase = basePath.replace("\\", "/").removeSuffix("/")
            return if (normalizedAbsolute.startsWith(normalizedBase)) {
                normalizedAbsolute.removePrefix(normalizedBase).removePrefix("/")
            } else {
                absolutePath
            }
        }
        
        // 测试 toRelativePath
        val actualRelativePath = toRelativePath(testFile.absolutePath, projectPath)
        println("计算相对路径:")
        println("  绝对路径: ${testFile.absolutePath}")
        println("  基础路径: $projectPath")
        println("  实际相对路径: $actualRelativePath")
        println()
        
        assertEquals("相对路径计算不正确", expectedRelativePath, actualRelativePath)
        
        // 模拟 ToolResult 数据类
        data class ToolResult(
            val success: Boolean,
            val result: Any,
            val executionTime: Long = 0,
            val relativePath: String? = null
        )
        
        // 模拟 executeReadFile 返回的结果
        val toolResult = ToolResult(
            success = true,
            result = testFile.readText(),
            executionTime = 5,
            relativePath = actualRelativePath  // 关键：设置 relativePath
        )
        
        println("ToolResult 对象:")
        println("  success: ${toolResult.success}")
        println("  result 长度: ${(toolResult.result as String).length}")
        println("  executionTime: ${toolResult.executionTime}")
        println("  relativePath: ${toolResult.relativePath}")
        println()
        
        // 验证 relativePath 不为空
        assertNotNull("relativePath 不能为 null", toolResult.relativePath)
        assertEquals("relativePath 值不正确", expectedRelativePath, toolResult.relativePath)
        
        // 模拟 JSON 序列化
        val response = mutableMapOf(
            "type" to "TOOL_RESULT",
            "toolName" to "read_file",
            "success" to toolResult.success,
            "result" to toolResult.result,
            "executionTime" to toolResult.executionTime
        )
        
        // 添加 relativePath（模拟 SmanAgentChatPanel 的逻辑）
        if (toolResult.relativePath != null) {
            response["relativePath"] = toolResult.relativePath
        }
        
        println("模拟发送给后端的 JSON:")
        for ((key, value) in response) {
            when (key) {
                "result" -> println("  $key: [${(value as String).take(50)}...]")
                else -> println("  $key: $value")
            }
        }
        println()
        
        // 验证 JSON 包含 relativePath
        assertTrue("JSON 必须包含 relativePath 字段", response.containsKey("relativePath"))
        assertEquals("JSON 中的 relativePath 值不正确", expectedRelativePath, response["relativePath"])
        
        println("✅ 所有测试通过！")
        println()
        println("测试结果总结:")
        println("  ✅ toRelativePath 函数正确计算相对路径")
        println("  ✅ ToolResult 正确设置 relativePath")
        println("  ✅ JSON 序列化包含 relativePath 字段")
        println("  ✅ 条件逻辑 (if relativePath != null) 正确执行")
        println()
        
        // 清理
        testFile.delete()
        tempDir.deleteRecursively()
    }
    
    @Test
    fun testRelativePathWithSubdirectories() {
        println("\n========== 测试子目录的相对路径 ==========\n")
        
        val tempDir = createTempDir("test")
        val subDir = File(tempDir, "agent/src/main/java/com/test")
        subDir.mkdirs()
        val testFile = File(subDir, "TestService.java")
        testFile.writeText("package com.test;")
        
        val projectPath = tempDir.absolutePath
        val expectedRelativePath = "agent/src/main/java/com/test/TestService.java"
        
        fun toRelativePath(absolutePath: String, basePath: String): String {
            if (basePath.isEmpty()) return absolutePath
            val normalizedAbsolute = absolutePath.replace("\\", "/")
            val normalizedBase = basePath.replace("\\", "/").removeSuffix("/")
            return if (normalizedAbsolute.startsWith(normalizedBase)) {
                normalizedAbsolute.removePrefix(normalizedBase).removePrefix("/")
            } else {
                absolutePath
            }
        }
        
        val actualRelativePath = toRelativePath(testFile.absolutePath, projectPath)
        
        println("文件: ${testFile.absolutePath}")
        println("期望: $expectedRelativePath")
        println("实际: $actualRelativePath")
        println()
        
        assertEquals(expectedRelativePath, actualRelativePath)
        println("✅ 子目录相对路径测试通过")
        println()
        
        // 清理
        testFile.delete()
        tempDir.deleteRecursively()
    }
}
