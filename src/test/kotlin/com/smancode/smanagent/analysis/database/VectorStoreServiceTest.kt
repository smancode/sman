package com.smancode.smanagent.analysis.database

import com.smancode.smanagent.analysis.config.JVectorConfig
import com.smancode.smanagent.analysis.config.VectorDatabaseConfig
import com.smancode.smanagent.analysis.config.VectorDbType
import com.smancode.smanagent.analysis.model.VectorFragment
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * 向量存储服务测试
 */
class VectorStoreServiceTest {

    @TempDir
    lateinit var tempDir: Path

    /**
     * 测试：添加向量片段
     */
    @Test
    fun `test add vector fragment`() {
        // Given: 创建配置和服务
        val config = VectorDatabaseConfig.create(
            projectKey = "test_project",
            type = VectorDbType.MEMORY,
            jvector = JVectorConfig(),
            baseDir = tempDir.toString()
        )
        val service = MemoryVectorStore(config)

        // Given: 创建向量片段
        val fragment = VectorFragment(
            id = "test_id",
            title = "Test Title",
            content = "Test Content",
            fullContent = "Full Content",
            tags = listOf("test"),
            metadata = mapOf("key" to "value")
        )

        // When: 添加向量
        service.add(fragment)

        // Then: 添加成功
        val retrieved = service.get("test_id")
        assertNotNull(retrieved)
        assertEquals("test_id", retrieved?.id)
    }

    /**
     * 测试：搜索向量
     */
    @Test
    fun `test search vectors`() {
        // Given: 创建配置和服务
        val config = VectorDatabaseConfig.create(
            projectKey = "test_project",
            type = VectorDbType.MEMORY,
            jvector = JVectorConfig(),
            baseDir = tempDir.toString()
        )
        val service = MemoryVectorStore(config)

        // Given: 添加向量片段
        val fragment1 = VectorFragment(
            id = "test_1",
            title = "Test 1",
            content = "content 1",
            fullContent = "full 1",
            tags = listOf("tag1"),
            metadata = emptyMap()
        )
        val fragment2 = VectorFragment(
            id = "test_2",
            title = "Test 2",
            content = "content 2",
            fullContent = "full 2",
            tags = listOf("tag2"),
            metadata = emptyMap()
        )
        service.add(fragment1)
        service.add(fragment2)

        // When: 搜索
        val query = floatArrayOf(1.0f, 2.0f, 3.0f)
        val results = service.search(query, topK = 2)

        // Then: 返回结果
        assertTrue(results.size <= 2)
    }

    /**
     * 测试：白名单校验 - 空 ID
     */
    @Test
    fun `test validate parameters - missing id`() {
        // Given: 创建配置
        val config = VectorDatabaseConfig.create(
            projectKey = "test_project",
            type = VectorDbType.MEMORY,
            jvector = JVectorConfig(),
            baseDir = tempDir.toString()
        )
        val service = MemoryVectorStore(config)

        // When: 添加 id 为空的片段
        val fragment = VectorFragment(
            id = "",  // 空 ID
            title = "Test",
            content = "Content",
            fullContent = "Full",
            tags = emptyList(),
            metadata = emptyMap()
        )

        // Then: 抛异常
        try {
            service.add(fragment)
            fail("Expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("id") == true)
        }
    }

    /**
     * 测试：白名单校验 - topK 参数
     */
    @Test
    fun `test validate parameters - topK must be positive`() {
        // Given: 创建配置
        val config = VectorDatabaseConfig.create(
            projectKey = "test_project",
            type = VectorDbType.MEMORY,
            jvector = JVectorConfig(),
            baseDir = tempDir.toString()
        )
        val service = MemoryVectorStore(config)

        // When: 搜索使用 topK = 0
        val query = floatArrayOf(1.0f, 2.0f, 3.0f)

        // Then: 抛异常
        try {
            service.search(query, topK = 0)
            fail("Expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("topK") == true)
        }
    }
}
