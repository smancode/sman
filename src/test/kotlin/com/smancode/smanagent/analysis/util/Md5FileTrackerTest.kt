package com.smancode.smanagent.analysis.util

import com.smancode.smanagent.analysis.model.FileSnapshot
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertNotNull

/**
 * MD5 文件追踪服务测试
 */
class Md5FileTrackerTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var tracker: Md5FileTracker

    /**
     * 设置测试环境
     */
    @org.junit.jupiter.api.BeforeEach
    fun setUp() {
        tracker = Md5FileTracker(tempDir)
    }

    /**
     * 测试：追踪新文件
     */
    @Test
    fun `test track new file`() {
        // Given: 创建新文件
        val testFile = tempDir.resolve("Test.kt")
        testFile.toFile().writeText("content")

        // When: 追踪文件
        val snapshot = tracker.trackFile(testFile)

        // Then: 返回快照
        assertNotNull(snapshot)
        assertEquals(testFile.fileName.toString(), snapshot.fileName)
        assertTrue(snapshot.md5.isNotEmpty())
    }

    /**
     * 测试：检测文件变化
     */
    @Test
    fun `test detect file changes`() {
        // Given: 创建并追踪文件
        val testFile = tempDir.resolve("Test.kt")
        testFile.toFile().writeText("content")
        val original = tracker.trackFile(testFile)

        // When: 修改文件内容
        Thread.sleep(10) // 确保修改时间不同
        testFile.toFile().writeText("modified content")
        val modified = tracker.trackFile(testFile)

        // Then: MD5 不同
        assertTrue(original.md5 != modified.md5)
    }

    /**
     * 测试：检测未变化文件
     */
    @Test
    fun `test detect unchanged file`() {
        // Given: 创建并追踪文件
        val testFile = tempDir.resolve("Test.kt")
        testFile.toFile().writeText("content")
        val original = tracker.trackFile(testFile)

        // When: 不修改文件
        val unchanged = tracker.trackFile(testFile)

        // Then: MD5 相同
        assertEquals(original.md5, unchanged.md5)
    }

    /**
     * 测试：批量追踪
     */
    @Test
    fun `test batch track files`() {
        // Given: 创建多个文件
        val file1 = tempDir.resolve("File1.kt")
        val file2 = tempDir.resolve("File2.kt")
        val file3 = tempDir.resolve("File3.kt")
        file1.toFile().writeText("content1")
        file2.toFile().writeText("content2")
        file3.toFile().writeText("content3")

        // When: 批量追踪
        val snapshots = tracker.trackFiles(listOf(file1, file2, file3))

        // Then: 返回所有快照
        assertEquals(3, snapshots.size)
    }

    /**
     * 测试：获取变化文件
     */
    @Test
    fun `test get changed files`() {
        // Given: 创建并追踪文件
        val file1 = tempDir.resolve("File1.kt")
        val file2 = tempDir.resolve("File2.kt")
        file1.toFile().writeText("content1")
        file2.toFile().writeText("content2")
        tracker.trackFiles(listOf(file1, file2))

        // When: 修改 file1
        Thread.sleep(10)
        file1.toFile().writeText("modified")

        // When: 获取变化文件
        val changed = tracker.getChangedFiles(listOf(file1, file2))

        // Then: 只返回 file1
        assertEquals(1, changed.size)
        assertEquals("File1.kt", changed[0].fileName)
    }

    /**
     * 测试：保存和加载缓存
     */
    @Test
    fun `test save and load cache`() {
        // Given: 创建并追踪文件
        val testFile = tempDir.resolve("Test.kt")
        testFile.toFile().writeText("content")
        tracker.trackFile(testFile)

        // When: 保存缓存
        val cacheFile = tempDir.resolve(".md5_cache.json")
        tracker.saveCache(cacheFile)

        // When: 创建新 tracker 并加载缓存
        val newTracker = Md5FileTracker(tempDir)
        newTracker.loadCache(cacheFile)

        // Then: 缓存恢复成功
        val snapshot = newTracker.getSnapshot(testFile)
        assertNotNull(snapshot)
    }
}
