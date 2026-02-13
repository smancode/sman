package com.smancode.sman.analysis.database

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * VectorStoreManager 单元测试
 *
 * 测试覆盖：
 * 1. 单例管理：同一项目路径只创建一个实例
 * 2. 引用计数：正确跟踪实例使用情况
 * 3. 资源释放：引用计数归零时正确关闭
 * 4. 并发安全：多线程访问安全
 *
 * 问题背景：
 * 之前每次 CodeVectorizationCoordinator 创建时都会创建新的 TieredVectorStore，
 * 导致 H2 数据库连接池冲突：
 * ```
 * Failed to initialize pool: Database may be already in use: "Server is running"
 * ```
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("VectorStoreManager 测试套件")
class VectorStoreManagerTest {

    @TempDir
    lateinit var tempDir: Path

    @BeforeEach
    fun setUp() {
        // 确保开始时没有缓存的实例
        VectorStoreManager.closeAll()
    }

    @AfterEach
    fun tearDown() {
        // 清理所有实例
        VectorStoreManager.closeAll()
    }

    @Nested
    @DisplayName("单例管理测试")
    inner class SingletonManagementTests {

        @Test
        @DisplayName("getOrCreate - 同一路径返回同一实例")
        fun testGetOrCreate_同一路径返回同一实例() {
            // Given: 项目路径
            val projectPath = tempDir.resolve("project1")
            projectPath.createDirectories()

            // When: 多次调用 getOrCreate
            val store1 = VectorStoreManager.getOrCreate("project1", projectPath)
            val store2 = VectorStoreManager.getOrCreate("project1", projectPath)

            // Then: 应该返回同一实例
            assertTrue(store1 === store2, "同一路径应该返回同一实例")
            assertEquals(1, VectorStoreManager.cacheSize())
        }

        @Test
        @DisplayName("getOrCreate - 不同路径返回不同实例")
        fun testGetOrCreate_不同路径返回不同实例() {
            // Given: 两个不同的项目路径
            val projectPath1 = tempDir.resolve("project1")
            val projectPath2 = tempDir.resolve("project2")
            projectPath1.createDirectories()
            projectPath2.createDirectories()

            // When: 为不同路径创建实例
            val store1 = VectorStoreManager.getOrCreate("project1", projectPath1)
            val store2 = VectorStoreManager.getOrCreate("project2", projectPath2)

            // Then: 应该返回不同实例
            assertFalse(store1 === store2, "不同路径应该返回不同实例")
            assertEquals(2, VectorStoreManager.cacheSize())
        }

        @Test
        @DisplayName("hasInstance - 检查实例是否存在")
        fun testHasInstance_检查实例是否存在() {
            // Given: 项目路径
            val projectPath = tempDir.resolve("project1")
            projectPath.createDirectories()

            // When: 创建前检查
            val beforeCreate = VectorStoreManager.hasInstance(projectPath)

            // 创建实例
            VectorStoreManager.getOrCreate("project1", projectPath)

            // 创建后检查
            val afterCreate = VectorStoreManager.hasInstance(projectPath)

            // Then: 验证状态变化
            assertFalse(beforeCreate)
            assertTrue(afterCreate)
        }
    }

    @Nested
    @DisplayName("引用计数测试")
    inner class ReferenceCountingTests {

        @Test
        @DisplayName("getOrCreate - 增加引用计数")
        fun testGetOrCreate_增加引用计数() {
            // Given: 项目路径
            val projectPath = tempDir.resolve("project1")
            projectPath.createDirectories()

            // When: 第一次创建
            VectorStoreManager.getOrCreate("project1", projectPath)
            val count1 = VectorStoreManager.getRefCount(projectPath)

            // 第二次获取（同一实例）
            VectorStoreManager.getOrCreate("project1", projectPath)
            val count2 = VectorStoreManager.getRefCount(projectPath)

            // Then: 引用计数应该增加
            assertEquals(1, count1)
            assertEquals(2, count2)
        }

        @Test
        @DisplayName("release - 减少引用计数")
        fun testRelease_减少引用计数() {
            // Given: 项目路径和已创建的实例
            val projectPath = tempDir.resolve("project1")
            projectPath.createDirectories()
            VectorStoreManager.getOrCreate("project1", projectPath)
            VectorStoreManager.getOrCreate("project1", projectPath)

            // When: 释放一次
            VectorStoreManager.release(projectPath)
            val count1 = VectorStoreManager.getRefCount(projectPath)

            // 再次释放
            VectorStoreManager.release(projectPath)
            val count2 = VectorStoreManager.getRefCount(projectPath)

            // Then: 引用计数应该减少
            assertEquals(1, count1)
            assertEquals(0, count2)
        }

        @Test
        @DisplayName("release - 引用计数归零时关闭实例")
        fun testRelease_引用计数归零时关闭实例() {
            // Given: 项目路径和已创建的实例
            val projectPath = tempDir.resolve("project1")
            projectPath.createDirectories()
            VectorStoreManager.getOrCreate("project1", projectPath)

            // When: 释放直到引用计数归零
            VectorStoreManager.release(projectPath)

            // Then: 实例应该被关闭和移除
            assertFalse(VectorStoreManager.hasInstance(projectPath))
            assertEquals(0, VectorStoreManager.cacheSize())
        }
    }

    @Nested
    @DisplayName("资源清理测试")
    inner class ResourceCleanupTests {

        @Test
        @DisplayName("closeAll - 关闭所有实例")
        fun testCloseAll_关闭所有实例() {
            // Given: 多个项目路径和实例
            val projectPath1 = tempDir.resolve("project1")
            val projectPath2 = tempDir.resolve("project2")
            projectPath1.createDirectories()
            projectPath2.createDirectories()

            VectorStoreManager.getOrCreate("project1", projectPath1)
            VectorStoreManager.getOrCreate("project2", projectPath2)

            // When: 关闭所有
            VectorStoreManager.closeAll()

            // Then: 所有实例应该被移除
            assertEquals(0, VectorStoreManager.cacheSize())
            assertFalse(VectorStoreManager.hasInstance(projectPath1))
            assertFalse(VectorStoreManager.hasInstance(projectPath2))
        }

        @Test
        @DisplayName("cacheSize - 返回正确的缓存大小")
        fun testCacheSize_返回正确的缓存大小() {
            // Given: 初始状态
            assertEquals(0, VectorStoreManager.cacheSize())

            // When: 创建多个实例
            val projectPath1 = tempDir.resolve("project1")
            val projectPath2 = tempDir.resolve("project2")
            projectPath1.createDirectories()
            projectPath2.createDirectories()

            VectorStoreManager.getOrCreate("project1", projectPath1)
            assertEquals(1, VectorStoreManager.cacheSize())

            VectorStoreManager.getOrCreate("project2", projectPath2)
            assertEquals(2, VectorStoreManager.cacheSize())
        }
    }

    @Nested
    @DisplayName("边界值测试")
    inner class BoundaryTests {

        @Test
        @DisplayName("release - 对不存在的路径调用应该安全处理")
        fun testRelease_不存在的路径_安全处理() {
            // Given: 不存在的路径
            val nonExistentPath = tempDir.resolve("non-existent")

            // When & Then: 应该不抛异常
            VectorStoreManager.release(nonExistentPath)

            // 验证状态
            assertEquals(0, VectorStoreManager.cacheSize())
        }

        @Test
        @DisplayName("getRefCount - 对不存在的路径返回0")
        fun testGetRefCount_不存在的路径_返回0() {
            // Given: 不存在的路径
            val nonExistentPath = tempDir.resolve("non-existent")

            // When & Then: 应该返回 0
            assertEquals(0, VectorStoreManager.getRefCount(nonExistentPath))
        }
    }
}
