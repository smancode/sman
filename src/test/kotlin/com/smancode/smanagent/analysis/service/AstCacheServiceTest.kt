package com.smancode.smanagent.analysis.service

import com.smancode.smanagent.analysis.model.ClassAstInfo
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * AST 缓存服务测试
 */
class AstCacheServiceTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var service: AstCacheService

    /**
     * 设置测试环境
     */
    @org.junit.jupiter.api.BeforeEach
    fun setUp() {
        service = AstCacheService(tempDir)
    }

    /**
     * 测试：存储类 AST 信息
     */
    @Test
    fun `test save class ast info`() {
        // Given: 创建类 AST 信息
        val ast = ClassAstInfo(
            className = "com.example.Test",
            simpleName = "Test",
            packageName = "com.example",
            methods = emptyList(),
            fields = emptyList()
        )

        // When: 存储 AST 信息
        service.putClassAst("com.example.Test", ast)

        // Then: 存储成功
        val retrieved = service.getClassAst("com.example.Test")
        assertNotNull(retrieved)
        assertEquals("com.example.Test", retrieved.className)
    }

    /**
     * 测试：不存在的类返回 null
     */
    @Test
    fun `test get non-existent class returns null`() {
        // Given: 不存储任何类

        // When: 查询不存在的类
        val result = service.getClassAst("com.example.NonExistent")

        // Then: 返回 null
        assertEquals(null, result)
    }

    /**
     * 测试：识别热点类
     */
    @Test
    fun `test identify hot classes`() {
        // Given: 模拟项目结构（这里简化测试）

        // When: 识别热点类
        // (实际实现会扫描 PSI)

        // Then: 返回热点类列表
        // (这里需要实际的 PSI 环境，暂时跳过)
    }
}
