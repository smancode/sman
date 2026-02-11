package com.smancode.sman.analysis.storage

import com.smancode.sman.analysis.base.VectorTestBase
import com.smancode.sman.analysis.model.VectorFragment
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * VectorRepository 单元测试
 *
 * 测试策略：
 * 1. 测试 add 和 get（CRUD 基础）
 * 2. 测试 delete（精确删除、前缀删除、通配符删除）
 * 3. 测试 cleanupMdVectors（清理旧向量）
 * 4. 测试 search（向量召回）
 */
@DisplayName("VectorRepository 单元测试")
class VectorRepositoryTest : VectorTestBase() {

    @Test
    @DisplayName("测试 add：添加单个向量")
    fun testAdd() = runTest {
        // Given: 一个向量片段
        val fragment = createTestFragment("class:TestClass", "TestClass")

        // When: 添加向量
        repository.add(fragment)

        // Then: 应该能获取到
        val retrieved = repository.get("class:TestClass")
        assertNotNull(retrieved)
        assertEquals("class:TestClass", retrieved?.id)
        assertEquals("TestClass", retrieved?.title)
    }

    @Test
    @DisplayName("测试 add：覆盖已存在的向量")
    fun testAddOverwrite() = runTest {
        // Given: 已存在的向量
        val original = createTestFragment("class:TestClass", "Original")
        repository.add(original)

        // When: 添加同 ID 的新向量
        val updated = createTestFragment("class:TestClass", "Updated")
        repository.add(updated)

        // Then: 应该返回新向量
        val retrieved = repository.get("class:TestClass")
        assertEquals("Updated", retrieved?.title)
    }

    @Test
    @DisplayName("测试 get：不存在的向量返回 null")
    fun testGetNotFound() = runTest {
        // When: 获取不存在的向量
        val result = repository.get("nonexistent")

        // Then: 应该返回 null
        assertNull(result)
    }

    @Test
    @DisplayName("测试 delete：精确删除")
    fun testDeleteExact() = runTest {
        // Given: 三个向量
        repository.add(createTestFragment("class:A", "ClassA"))
        repository.add(createTestFragment("method:A.method1", "Method1"))
        repository.add(createTestFragment("method:A.method2", "Method2"))

        // When: 删除一个
        repository.delete("method:A.method1")

        // Then: 只有两个剩余
        assertNull(repository.get("method:A.method1"))
        assertNotNull(repository.get("class:A"))
        assertNotNull(repository.get("method:A.method2"))
    }

    @Test
    @DisplayName("测试 delete：前缀匹配删除")
    fun testDeleteByPrefix() = runTest {
        // Given: 多个向量
        repository.add(createTestFragment("class:A", "ClassA"))
        repository.add(createTestFragment("method:A.method1", "Method1"))
        repository.add(createTestFragment("method:A.method2", "Method2"))
        repository.add(createTestFragment("class:B", "ClassB"))

        // When: 删除所有 method:A 开头的
        repository.delete("method:A")

        // Then: method:A.* 被删除，其他保留
        assertNull(repository.get("method:A.method1"))
        assertNull(repository.get("method:A.method2"))
        assertNotNull(repository.get("class:A"))
        assertNotNull(repository.get("class:B"))
    }

    @Test
    @DisplayName("测试 deleteByPattern：通配符删除")
    fun testDeleteByPattern() = runTest {
        // Given: 包含 .md 后缀的旧向量
        repository.add(createTestFragment("method:A.md.method1", "OldMethod1"))
        repository.add(createTestFragment("method:A.md.method2", "OldMethod2"))
        repository.add(createTestFragment("method:A.method1", "NewMethod1"))
        repository.add(createTestFragment("method:A.method2", "NewMethod2"))

        // When: 删除所有包含 .md 的向量
        val deletedCount = repository.deleteByPattern("%.md%")

        // Then: 只删除旧向量，保留新向量
        assertEquals(2, deletedCount)
        assertNull(repository.get("method:A.md.method1"))
        assertNull(repository.get("method:A.md.method2"))
        assertNotNull(repository.get("method:A.method1"))
        assertNotNull(repository.get("method:A.method2"))
    }

    @Test
    @DisplayName("测试 cleanupMdVectors：清理所有 .md 向量")
    fun testCleanupMdVectors() = runTest {
        // Given: 混合的旧向量和新向量
        repository.add(createTestFragment("class:A.md", "OldClass"))
        repository.add(createTestFragment("method:A.md.method1", "OldMethod1"))
        repository.add(createTestFragment("method:A.md.method2", "OldMethod2"))
        repository.add(createTestFragment("class:A", "NewClass"))
        repository.add(createTestFragment("method:A.method1", "NewMethod1"))
        repository.add(createTestFragment("class:B", "ClassB"))

        // When: 清理所有 .md 向量
        val deletedCount = repository.cleanupMdVectors()

        // Then: 删除所有包含 .md 的向量
        assertEquals(3, deletedCount)
        assertNull(repository.get("class:A.md"))
        assertNull(repository.get("method:A.md.method1"))
        assertNull(repository.get("method:A.md.method2"))
        assertNotNull(repository.get("class:A"))
        assertNotNull(repository.get("method:A.method1"))
        assertNotNull(repository.get("class:B"))
    }

    @Test
    @DisplayName("测试 search：向量召回")
    fun testSearch() = runTest {
        // Given: 添加多个向量（带实际的向量值）
        val queryVector = FloatArray(1024) { 0.1f }

        repository.add(VectorFragment(
            id = "class:A",
            title = "ClassA",
            content = "A class for testing",
            fullContent = "",
            tags = listOf("class"),
            metadata = mapOf("type" to "class"),
            vector = FloatArray(1024) { 0.5f }
        ))

        repository.add(VectorFragment(
            id = "class:B",
            title = "ClassB",
            content = "Another class",
            fullContent = "",
            tags = listOf("class"),
            metadata = mapOf("type" to "class"),
            vector = FloatArray(1024) { 0.3f }
        ))

        // When: 搜索
        val results = repository.search(queryVector, topK = 2)

        // Then: 应该返回结果
        assertTrue(results.size <= 2)
        assertTrue(results.any { it.id == "class:A" } || results.any { it.id == "class:B" })
    }

    @Test
    @DisplayName("测试 search：topK 限制")
    fun testSearchTopK() = runTest {
        // Given: 5 个向量
        val queryVector = FloatArray(1024) { 0.1f }

        repeat(5) { i ->
            repository.add(VectorFragment(
                id = "item:$i",
                title = "Item$i",
                content = "Item $i",
                fullContent = "",
                tags = listOf("item"),
                metadata = emptyMap(),
                vector = FloatArray(1024) { (i + 1) * 0.1f }
            ))
        }

        // When: 搜索 topK=3
        val results = repository.search(queryVector, topK = 3)

        // Then: 只返回 3 个
        assertEquals(3, results.size)
    }

    @Test
    @DisplayName("测试 cleanupMdVectors：无 .md 向量时返回 0")
    fun testCleanupMdVectorsWhenNoMdVectors() = runTest {
        // Given: 只有新向量
        repository.add(createTestFragment("class:A", "ClassA"))
        repository.add(createTestFragment("method:A.method1", "Method1"))

        // When: 清理
        val deletedCount = repository.cleanupMdVectors()

        // Then: 应该返回 0
        assertEquals(0, deletedCount)
        assertNotNull(repository.get("class:A"))
        assertNotNull(repository.get("method:A.method1"))
    }
}
