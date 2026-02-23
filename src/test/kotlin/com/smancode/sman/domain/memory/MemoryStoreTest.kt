package com.smancode.sman.domain.memory

import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.Instant

@DisplayName("MemoryStore 测试套件")
class MemoryStoreTest {

    @TempDir
    lateinit var tempDir: File

    private lateinit var memoryStore: MemoryStore
    private val testProjectId = "test-project"

    @BeforeEach
    fun setUp() {
        memoryStore = FileBasedMemoryStore(tempDir.absolutePath)
    }

    // ========== 基础 CRUD 测试 ==========

    @Nested
    @DisplayName("基础 CRUD 测试")
    inner class BasicCrudTests {

        @Test
        @DisplayName("save and load - 应正确持久化和读取")
        fun `save and load should persist and retrieve memory`() {
            val memory = createTestMemory(
                key = "user-service-definition",
                value = "用户服务负责处理用户注册、登录和个人信息管理"
            )

            val saveResult = memoryStore.save(memory)
            assertTrue(saveResult.isSuccess)

            val loadResult = memoryStore.load(testProjectId, "user-service-definition")
            assertTrue(loadResult.isSuccess)

            val loaded = loadResult.getOrThrow()
            assertNotNull(loaded)
            assertEquals("user-service-definition", loaded?.key)
            assertEquals("用户服务负责处理用户注册、登录和个人信息管理", loaded?.value)
            assertEquals(MemoryType.DOMAIN_TERM, loaded?.memoryType)
        }

        @Test
        @DisplayName("load - 不存在的记忆返回 null")
        fun `load should return null for non-existent memory`() {
            val result = memoryStore.load(testProjectId, "non-existent-key")
            assertTrue(result.isSuccess)
            assertNull(result.getOrThrow())
        }

        @Test
        @DisplayName("loadAll - 应返回所有记忆")
        fun `loadAll should return all memories`() {
            memoryStore.save(createTestMemory(key = "rule-1", value = "规则1"))
            memoryStore.save(createTestMemory(key = "rule-2", value = "规则2"))
            memoryStore.save(createTestMemory(key = "rule-3", value = "规则3"))

            val result = memoryStore.loadAll(testProjectId)
            assertTrue(result.isSuccess)
            assertEquals(3, result.getOrThrow().size)
        }

        @Test
        @DisplayName("loadAll - 空项目返回空列表")
        fun `loadAll should return empty list for empty project`() {
            val result = memoryStore.loadAll("empty-project")
            assertTrue(result.isSuccess)
            assertTrue(result.getOrThrow().isEmpty())
        }

        @Test
        @DisplayName("delete - 应从存储中移除")
        fun `delete should remove memory`() {
            memoryStore.save(createTestMemory(key = "to-delete"))

            val deleteResult = memoryStore.delete(testProjectId, "to-delete")
            assertTrue(deleteResult.isSuccess)

            val loadResult = memoryStore.load(testProjectId, "to-delete")
            assertNull(loadResult.getOrThrow())
        }

        @Test
        @DisplayName("delete - 删除不存在的记忆应成功")
        fun `delete should succeed for non-existent memory`() {
            val result = memoryStore.delete(testProjectId, "non-existent")
            assertTrue(result.isSuccess)
        }
    }

    // ========== 查询测试 ==========

    @Nested
    @DisplayName("查询测试")
    inner class QueryTests {

        @Test
        @DisplayName("findByType - 应返回指定类型的记忆")
        fun `findByType should return memories of specified type`() {
            memoryStore.save(createTestMemory(
                key = "term-1",
                memoryType = MemoryType.DOMAIN_TERM
            ))
            memoryStore.save(createTestMemory(
                key = "rule-1",
                memoryType = MemoryType.BUSINESS_RULE
            ))
            memoryStore.save(createTestMemory(
                key = "term-2",
                memoryType = MemoryType.DOMAIN_TERM
            ))

            val result = memoryStore.findByType(testProjectId, MemoryType.DOMAIN_TERM)
            assertTrue(result.isSuccess)
            val memories = result.getOrThrow()
            assertEquals(2, memories.size)
            assertTrue(memories.all { it.memoryType == MemoryType.DOMAIN_TERM })
        }

        @Test
        @DisplayName("findByType - 无匹配类型返回空列表")
        fun `findByType should return empty list when no match`() {
            memoryStore.save(createTestMemory(memoryType = MemoryType.DOMAIN_TERM))

            val result = memoryStore.findByType(testProjectId, MemoryType.BUSINESS_RULE)
            assertTrue(result.isSuccess)
            assertTrue(result.getOrThrow().isEmpty())
        }
    }

    // ========== 访问统计测试 ==========

    @Nested
    @DisplayName("访问统计测试")
    inner class AccessStatisticsTests {

        @Test
        @DisplayName("touch - 应更新访问时间和次数")
        fun `touch should update access time and count`() {
            val original = createTestMemory(
                key = "touched",
                accessCount = 5
            )
            memoryStore.save(original)

            Thread.sleep(10) // 确保时间有差异
            val touchResult = memoryStore.touch(testProjectId, "touched")
            assertTrue(touchResult.isSuccess)

            val loaded = memoryStore.load(testProjectId, "touched").getOrThrow()
            assertNotNull(loaded)
            assertTrue(loaded!!.lastAccessedAt.isAfter(original.lastAccessedAt))
            assertEquals(6, loaded.accessCount)
        }

        @Test
        @DisplayName("touch - 不存在的记忆应返回失败")
        fun `touch should fail for non-existent memory`() {
            val result = memoryStore.touch(testProjectId, "non-existent")
            assertTrue(result.isFailure)
        }
    }

    // ========== 覆盖更新测试 ==========

    @Nested
    @DisplayName("覆盖更新测试")
    inner class UpdateTests {

        @Test
        @DisplayName("save - 相同 projectId 和 key 应覆盖原有内容")
        fun `save should overwrite existing memory with same key`() {
            val original = createTestMemory(key = "rule-1", value = "原始规则")
            memoryStore.save(original)

            val updated = createTestMemory(key = "rule-1", value = "更新后的规则")
            memoryStore.save(updated)

            val loaded = memoryStore.load(testProjectId, "rule-1").getOrThrow()
            assertEquals("更新后的规则", loaded?.value)
        }
    }

    // ========== 白名单校验测试 ==========

    @Nested
    @DisplayName("白名单校验测试")
    inner class ValidationTests {

        @Test
        @DisplayName("save - 空 projectId 应抛出异常")
        fun `save should throw when projectId is blank`() {
            val memory = createTestMemory(projectId = "")
            assertThrows<IllegalArgumentException> {
                memoryStore.save(memory).getOrThrow()
            }
        }

        @Test
        @DisplayName("save - 空 key 应抛出异常")
        fun `save should throw when key is blank`() {
            val memory = createTestMemory(key = "")
            assertThrows<IllegalArgumentException> {
                memoryStore.save(memory).getOrThrow()
            }
        }

        @Test
        @DisplayName("save - confidence 小于 0 应抛出异常")
        fun `save should throw when confidence is less than 0`() {
            val memory = createTestMemory(confidence = -0.1)
            assertThrows<IllegalArgumentException> {
                memoryStore.save(memory).getOrThrow()
            }
        }

        @Test
        @DisplayName("save - confidence 大于 1 应抛出异常")
        fun `save should throw when confidence is greater than 1`() {
            val memory = createTestMemory(confidence = 1.1)
            assertThrows<IllegalArgumentException> {
                memoryStore.save(memory).getOrThrow()
            }
        }

        @Test
        @DisplayName("save - 边界值 0 和 1 应通过校验")
        fun `save should accept boundary values 0 and 1`() {
            val memory0 = createTestMemory(key = "boundary-0", confidence = 0.0)
            val memory1 = createTestMemory(key = "boundary-1", confidence = 1.0)

            assertDoesNotThrow { memoryStore.save(memory0).getOrThrow() }
            assertDoesNotThrow { memoryStore.save(memory1).getOrThrow() }
        }
    }

    // ========== Markdown 格式测试 ==========

    @Nested
    @DisplayName("Markdown 格式测试")
    inner class MarkdownFormatTests {

        @Test
        @DisplayName("save - 应创建有效的 markdown 文件")
        fun `save should create valid markdown file`() {
            val memory = createTestMemory(
                key = "test-memory",
                value = "这是记忆内容"
            )
            memoryStore.save(memory)

            val memoryFile = File(tempDir, ".sman/memories/$testProjectId/test-memory.md")
            assertTrue(memoryFile.exists())

            val content = memoryFile.readText()
            assertTrue(content.startsWith("---"))
            assertTrue(content.contains("key: test-memory"))
            assertTrue(content.contains("这是记忆内容"))
        }

        @Test
        @DisplayName("save - 应正确保存中文内容")
        fun `save should correctly save Chinese content`() {
            val memory = createTestMemory(
                key = "中文键",
                value = "这是中文内容，包含特殊字符：你好世界！"
            )
            memoryStore.save(memory)

            val loaded = memoryStore.load(testProjectId, "中文键").getOrThrow()
            assertEquals("这是中文内容，包含特殊字符：你好世界！", loaded?.value)
        }
    }

    // ========== 辅助方法 ==========

    private fun createTestMemory(
        projectId: String = testProjectId,
        key: String = "test-key",
        value: String = "test-value",
        memoryType: MemoryType = MemoryType.DOMAIN_TERM,
        confidence: Double = 0.8,
        accessCount: Int = 0
    ): ProjectMemory {
        val now = Instant.now()
        return ProjectMemory(
            id = "${projectId}_$key",
            projectId = projectId,
            memoryType = memoryType,
            key = key,
            value = value,
            confidence = confidence,
            source = MemorySource.EXPLICIT_INPUT,
            createdAt = now,
            lastAccessedAt = now,
            accessCount = accessCount
        )
    }
}
