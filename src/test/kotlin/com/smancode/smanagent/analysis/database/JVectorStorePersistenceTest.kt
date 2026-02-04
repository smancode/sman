package com.smancode.smanagent.analysis.database

import com.smancode.smanagent.analysis.config.JVectorConfig
import com.smancode.smanagent.analysis.config.VectorDatabaseConfig
import com.smancode.smanagent.analysis.config.VectorDbType
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * JVectorStore H2 持久化功能测试
 *
 * 测试场景：
 * 1. 添加向量后，能从 H2 恢复
 * 2. 删除向量后，H2 中也被删除
 * 3. 重启 JVectorStore 后，能从 H2 加载数据
 * 4. 批量添加向量后，能正确恢复
 */
@DisplayName("JVectorStore H2 持久化测试")
class JVectorStorePersistenceTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var config: VectorDatabaseConfig
    private lateinit var jVectorStore: JVectorStore

    @BeforeEach
    fun setUp() {
        // 创建临时目录的配置
        config = VectorDatabaseConfig.create(
            projectKey = "test_persistence",
            type = VectorDbType.JVECTOR,
            jvector = JVectorConfig(
                dimension = 128,  // 使用较小的维度加快测试
                M = 16,
                efConstruction = 100,
                efSearch = 50
            ),
            baseDir = tempDir.toAbsolutePath().toString(),
            vectorDimension = 128
        )

        jVectorStore = JVectorStore(config)
    }

    @AfterEach
    fun tearDown() {
        jVectorStore.close()
    }

    @Test
    @DisplayName("测试 1: 添加向量后能从 H2 恢复")
    fun testAddAndRecoverFromH2() {
        // Given: 添加一个向量片段
        val fragment = createTestFragment("test-1", "测试内容1")
        jVectorStore.add(fragment)

        // When: 关闭并重新创建 JVectorStore（模拟重启）
        jVectorStore.close()
        val newStore = JVectorStore(config)

        // Then: 能从 H2 恢复向量
        val recovered = newStore.get("test-1")
        assertNotNull(recovered, "应该能从 H2 恢复向量")
        assertEquals("test-1", recovered.id)
        assertEquals("测试内容1", recovered.content)
        assertEquals(128, recovered.vector?.size)

        newStore.close()
    }

    @Test
    @DisplayName("测试 2: 删除向量后 H2 中也被删除")
    fun testDeleteRemovesFromH2() {
        // Given: 添加一个向量片段
        val fragment = createTestFragment("test-2", "测试内容2")
        jVectorStore.add(fragment)

        // When: 删除向量
        jVectorStore.delete("test-2")

        // Then: 关闭并重新创建，向量不存在
        jVectorStore.close()
        val newStore = JVectorStore(config)

        val recovered = newStore.get("test-2")
        assertEquals(null, recovered, "H2 中应该已被删除")

        newStore.close()
    }

    @Test
    @DisplayName("测试 3: 批量添加向量后能正确恢复")
    fun testBatchAddAndRecover() {
        // Given: 批量添加 10 个向量
        val fragments = (1..10).map { i ->
            createTestFragment("batch-$i", "批量内容$i")
        }

        fragments.forEach { jVectorStore.add(it) }

        // When: 关闭并重新创建 JVectorStore
        jVectorStore.close()
        val newStore = JVectorStore(config)

        // Then: 所有向量都能恢复
        for (i in 1..10) {
            val recovered = newStore.get("batch-$i")
            assertNotNull(recovered, "应该能恢复向量 batch-$i")
            assertEquals("批量内容$i", recovered.content)
        }

        // 验证总数
        val stats = newStore.getStats()
        assertEquals(10, stats["totalVectors"])

        newStore.close()
    }

    @Test
    @DisplayName("测试 4: 更新向量后 H2 中也被更新")
    fun testUpdateVectorInH2() {
        // Given: 添加一个向量
        val fragment1 = createTestFragment("update-test", "原始内容")
        jVectorStore.add(fragment1)

        // When: 更新向量（使用相同 ID）
        val newVector = FloatArray(128) { it.toFloat() }
        val fragment2 = com.smancode.smanagent.analysis.model.VectorFragment(
            id = "update-test",
            title = "更新标题",
            content = "更新内容",
            fullContent = "更新完整内容",
            tags = listOf("updated"),
            metadata = mapOf("version" to "2"),
            vector = newVector
        )
        jVectorStore.add(fragment2)

        // Then: 关闭并重新创建，得到更新后的数据
        jVectorStore.close()
        val newStore = JVectorStore(config)

        val recovered = newStore.get("update-test")
        assertNotNull(recovered)
        assertEquals("更新标题", recovered.title)
        assertEquals("更新内容", recovered.content)
        assertEquals("更新完整内容", recovered.fullContent)
        assertEquals(listOf("updated"), recovered.tags)
        assertEquals("2", recovered.metadata["version"])

        newStore.close()
    }

    @Test
    @DisplayName("测试 5: 前缀删除多个向量")
    fun testPrefixDelete() {
        // Given: 添加多个同前缀向量
        jVectorStore.add(createTestFragment("class:Handler1", "Handler1"))
        jVectorStore.add(createTestFragment("class:Handler2", "Handler2"))
        jVectorStore.add(createTestFragment("method:Handler1.execute", "execute方法"))
        jVectorStore.add(createTestFragment("other:Service", "Service"))

        // When: 删除 class: 前缀的所有向量
        jVectorStore.delete("class:")

        // Then: 只有 class: 前缀的被删除
        jVectorStore.close()
        val newStore = JVectorStore(config)

        assertEquals(null, newStore.get("class:Handler1"), "class:Handler1 应被删除")
        assertEquals(null, newStore.get("class:Handler2"), "class:Handler2 应被删除")
        assertNotNull(newStore.get("method:Handler1.execute"), "method: 前缀不应被删除")
        assertNotNull(newStore.get("other:Service"), "other: 前缀不应被删除")

        val stats = newStore.getStats()
        assertEquals(2, stats["totalVectors"], "应该剩余 2 个向量")

        newStore.close()
    }

    @Test
    @DisplayName("测试 6: 搜索功能在重启后正常工作")
    fun testSearchAfterRecovery() {
        // Given: 添加向量并构建索引
        val queryVector = FloatArray(128) { 0.5f }
        jVectorStore.add(createTestFragment("search-1", "搜索测试1", floatArrayOf(1f) + FloatArray(127) { 0f }))
        jVectorStore.add(createTestFragment("search-2", "搜索测试2", floatArrayOf(0f, 1f) + FloatArray(126) { 0f }))
        jVectorStore.add(createTestFragment("search-3", "搜索测试3", floatArrayOf(0f, 0f, 1f) + FloatArray(125) { 0f }))

        // When: 关闭并重新创建
        jVectorStore.close()
        val newStore = JVectorStore(config)

        // Then: 搜索功能正常
        val results = newStore.search(queryVector, topK = 3)
        assertTrue(results.size >= 1, "应该能搜索到结果")
        assertTrue(results.size <= 3, "结果不应超过 topK")

        newStore.close()
    }

    @Test
    @DisplayName("测试 7: 大量向量持久化和恢复性能")
    fun testLargeScalePersistence() {
        // Given: 添加 100 个向量
        val count = 100
        repeat(count) { i ->
            val vector = FloatArray(128) { j -> (i * 0.01f + j * 0.001f) }
            jVectorStore.add(createTestFragment("perf-$i", "性能测试$i", vector))
        }

        // When: 关闭并重新创建
        val startTime = System.currentTimeMillis()
        jVectorStore.close()
        val newStore = JVectorStore(config)
        val loadTime = System.currentTimeMillis() - startTime

        // Then: 所有向量都恢复，加载时间合理
        val stats = newStore.getStats()
        assertEquals(count, stats["totalVectors"])
        assertTrue(loadTime < 5000, "加载时间应该 < 5秒，实际: ${loadTime}ms")

        newStore.close()
    }

    /**
     * 创建测试向量片段
     */
    private fun createTestFragment(
        id: String,
        content: String,
        vector: FloatArray = FloatArray(128) { (Math.random().toFloat()) }
    ): com.smancode.smanagent.analysis.model.VectorFragment {
        return com.smancode.smanagent.analysis.model.VectorFragment(
            id = id,
            title = "测试标题-$id",
            content = content,
            fullContent = "完整内容-$content",
            tags = listOf("test", "unit"),
            metadata = mapOf(
                "fileName" to "$id.java",
                "type" to "class"
            ),
            vector = vector
        )
    }
}
