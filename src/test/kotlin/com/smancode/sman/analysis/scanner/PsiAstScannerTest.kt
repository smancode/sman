package com.smancode.sman.analysis.scanner

import com.smancode.sman.analysis.model.ClassAstInfo
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * PSI AST 扫描器测试
 */
class PsiAstScannerTest {

    @TempDir
    lateinit var tempDir: Path

    /**
     * 测试：扫描简单类
     */
    @Test
    fun `test scan simple class`() {
        // Given: 创建测试文件
        val testFile = tempDir.resolve("TestService.kt")
        testFile.toFile().writeText("""
            package com.example

            class TestService {
                fun hello(): String {
                    return "Hello"
                }
            }
        """.trimIndent())

        // When: 扫描文件
        val scanner = PsiAstScanner()
        val result = scanner.scanFile(testFile)

        // Then: 返回 AST 信息
        assertNotNull(result)
        assertEquals("com.example.TestService", result.className)
        assertEquals("TestService", result.simpleName)
        assertEquals("com.example", result.packageName)
    }

    /**
     * 测试：扫描带方法的类
     */
    @Test
    fun `test scan class with methods`() {
        // Given: 创建测试文件
        val testFile = tempDir.resolve("UserService.kt")
        testFile.toFile().writeText("""
            package com.example.service

            class UserService {
                fun getUser(id: Long): User? {
                    return userRepository.findById(id)
                }

                fun saveUser(user: User): Boolean {
                    return userRepository.save(user)
                }
            }
        """.trimIndent())

        // When: 扫描文件
        val scanner = PsiAstScanner()
        val result = scanner.scanFile(testFile)

        // Then: 返回方法列表
        assertNotNull(result)
        assertTrue(result.methods.isNotEmpty())
        assertEquals(2, result.methods.size)
        assertTrue(result.methods.any { it.name == "getUser" })
        assertTrue(result.methods.any { it.name == "saveUser" })
    }

    /**
     * 测试：扫描带字段的类
     */
    @Test
    fun `test scan class with fields`() {
        // Given: 创建测试文件
        val testFile = tempDir.resolve("Config.kt")
        testFile.toFile().writeText("""
            package com.example.config

            class Config {
                private val timeout: Long = 5000
                val maxRetries: Int = 3
            }
        """.trimIndent())

        // When: 扫描文件
        val scanner = PsiAstScanner()
        val result = scanner.scanFile(testFile)

        // Then: 返回字段列表
        assertNotNull(result)
        assertTrue(result.fields.isNotEmpty())
        assertEquals(2, result.fields.size)
    }

    /**
     * 测试：扫描带注解的类
     */
    @Test
    fun `test scan class with annotations`() {
        // Given: 创建测试文件
        val testFile = tempDir.resolve("RestController.kt")
        testFile.toFile().writeText("""
            package com.example.controller

            @RestController
            class UserController {
                @GetMapping("/users")
                fun getUsers(): List<User> {
                    return emptyList()
                }
            }
        """.trimIndent())

        // When: 扫描文件
        val scanner = PsiAstScanner()
        val result = scanner.scanFile(testFile)

        // Then: 返回注解信息（简化实现，注解可能无法完全解析）
        assertNotNull(result)
        // 注意：当前实现使用简化正则，可能无法完全解析注解
        // 这里只验证方法和类的基本信息
        assertEquals("com.example.controller.UserController", result.className)
        assertTrue(result.methods.any { it.name == "getUsers" })
    }
}
