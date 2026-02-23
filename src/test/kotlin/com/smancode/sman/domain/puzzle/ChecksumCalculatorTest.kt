package com.smancode.sman.domain.puzzle

import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.io.TempDir
import java.io.File

@DisplayName("ChecksumCalculator 测试套件")
class ChecksumCalculatorTest {

    @TempDir
    lateinit var tempDir: File

    private lateinit var calculator: ChecksumCalculator

    @BeforeEach
    fun setUp() {
        calculator = ChecksumCalculator()
    }

    // ========== 单文件测试 ==========

    @Nested
    @DisplayName("单文件 checksum 测试")
    inner class SingleFileTests {

        @Test
        @DisplayName("calculate - 应计算文件的 SHA256 checksum")
        fun `calculate should compute SHA256 checksum`() {
            val file = createFile("test.txt", "Hello, World!")

            val checksum = calculator.calculate(file)

            assertNotNull(checksum)
            assertTrue(checksum.startsWith("sha256:"))
            assertTrue(checksum.length > 10)
        }

        @Test
        @DisplayName("calculate - 相同内容应产生相同 checksum")
        fun `calculate should produce same checksum for same content`() {
            val file1 = createFile("test1.txt", "Same content")
            val file2 = createFile("test2.txt", "Same content")

            val checksum1 = calculator.calculate(file1)
            val checksum2 = calculator.calculate(file2)

            assertEquals(checksum1, checksum2)
        }

        @Test
        @DisplayName("calculate - 不同内容应产生不同 checksum")
        fun `calculate should produce different checksum for different content`() {
            val file1 = createFile("test1.txt", "Content A")
            val file2 = createFile("test2.txt", "Content B")

            val checksum1 = calculator.calculate(file1)
            val checksum2 = calculator.calculate(file2)

            assertNotEquals(checksum1, checksum2)
        }

        @Test
        @DisplayName("calculate - 空文件应返回有效 checksum")
        fun `calculate should return valid checksum for empty file`() {
            val file = createFile("empty.txt", "")

            val checksum = calculator.calculate(file)

            assertNotNull(checksum)
            assertTrue(checksum.startsWith("sha256:"))
        }

        @Test
        @DisplayName("calculate - 不存在的文件应抛出异常")
        fun `calculate should throw for non-existent file`() {
            val file = File(tempDir, "non-existent.txt")

            assertThrows<IllegalArgumentException> {
                calculator.calculate(file)
            }
        }
    }

    // ========== 多文件测试 ==========

    @Nested
    @DisplayName("多文件 checksum 测试")
    inner class MultiFileTests {

        @Test
        @DisplayName("calculateMultiple - 应计算多个文件的合并 checksum")
        fun `calculateMultiple should compute combined checksum`() {
            val files = listOf(
                createFile("a.txt", "Content A"),
                createFile("b.txt", "Content B")
            )

            val checksum = calculator.calculateMultiple(files)

            assertNotNull(checksum)
            assertTrue(checksum.startsWith("sha256:"))
        }

        @Test
        @DisplayName("calculateMultiple - 文件列表相同应产生相同 checksum（忽略顺序）")
        fun `calculateMultiple should produce same checksum for same files`() {
            val files1 = listOf(
                createFile("a.txt", "Content A"),
                createFile("b.txt", "Content B")
            )
            val files2 = listOf(
                createFile("b.txt", "Content B"),
                createFile("a.txt", "Content A")
            )

            val checksum1 = calculator.calculateMultiple(files1)
            val checksum2 = calculator.calculateMultiple(files2)

            // 由于按路径排序，相同文件集合应产生相同 checksum
            assertEquals(checksum1, checksum2)
        }

        @Test
        @DisplayName("calculateMultiple - 空列表应返回空字符串")
        fun `calculateMultiple should return empty string for empty list`() {
            val checksum = calculator.calculateMultiple(emptyList())

            assertEquals("", checksum)
        }
    }

    // ========== 变更检测测试 ==========

    @Nested
    @DisplayName("变更检测测试")
    inner class ChangeDetectionTests {

        @Test
        @DisplayName("hasChanged - 内容变更应返回 true")
        fun `hasChanged should return true when content changed`() {
            val file = createFile("test.txt", "Original content")
            val originalChecksum = calculator.calculate(file)

            file.writeText("Modified content")
            val hasChanged = calculator.hasChanged(file, originalChecksum)

            assertTrue(hasChanged)
        }

        @Test
        @DisplayName("hasChanged - 内容未变更应返回 false")
        fun `hasChanged should return false when content unchanged`() {
            val file = createFile("test.txt", "Same content")
            val originalChecksum = calculator.calculate(file)

            val hasChanged = calculator.hasChanged(file, originalChecksum)

            assertFalse(hasChanged)
        }

        @Test
        @DisplayName("hasChanged - 空旧 checksum 应返回 true")
        fun `hasChanged should return true when old checksum is empty`() {
            val file = createFile("test.txt", "Some content")

            val hasChanged = calculator.hasChanged(file, "")

            assertTrue(hasChanged)
        }
    }

    // ========== 目录 checksum 测试 ==========

    @Nested
    @DisplayName("目录 checksum 测试")
    inner class DirectoryTests {

        @Test
        @DisplayName("calculateDirectory - 应计算目录下所有文件的 checksum")
        fun `calculateDirectory should compute checksum for all files`() {
            val dir = File(tempDir, "testDir")
            dir.mkdirs()
            createFile("testDir/a.kt", "fun a() {}")
            createFile("testDir/b.kt", "fun b() {}")

            val checksum = calculator.calculateDirectory(dir)

            assertNotNull(checksum)
            assertTrue(checksum.startsWith("sha256:"))
        }

        @Test
        @DisplayName("calculateDirectory - 增加文件应改变 checksum")
        fun `calculateDirectory should change when file added`() {
            val dir = File(tempDir, "testDir")
            dir.mkdirs()
            createFile("testDir/a.kt", "fun a() {}")

            val checksum1 = calculator.calculateDirectory(dir)

            createFile("testDir/b.kt", "fun b() {}")
            val checksum2 = calculator.calculateDirectory(dir)

            assertNotEquals(checksum1, checksum2)
        }

        @Test
        @DisplayName("calculateDirectory - 空目录应返回空 checksum")
        fun `calculateDirectory should return empty for empty directory`() {
            val dir = File(tempDir, "emptyDir")
            dir.mkdirs()

            val checksum = calculator.calculateDirectory(dir)

            assertEquals("", checksum)
        }
    }

    // ========== 辅助方法 ==========

    private fun createFile(name: String, content: String): File {
        val file = File(tempDir, name)
        file.parentFile?.mkdirs()
        file.writeText(content)
        return file
    }
}
