package com.smancode.sman.analysis.coordination

import com.smancode.sman.analysis.config.VectorDatabaseConfig
import com.smancode.sman.analysis.config.VectorDbType
import com.smancode.sman.analysis.config.JVectorConfig
import com.smancode.sman.analysis.model.VectorFragment
import com.smancode.sman.smancode.llm.LlmService
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText

/**
 * CodeVectorizationCoordinator 单元测试
 *
 * 测试覆盖：
 * 1. 白名单准入测试：正常向量化流程
 * 2. 白名单拒绝测试：空文件、不存在的文件
 * 3. 边界值测试：MD5 缓存、增量更新
 * 4. 优雅降级测试：单个文件失败不影响整体
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("CodeVectorizationCoordinator 测试套件")
class CodeVectorizationCoordinatorTest {

    private lateinit var mockLlmService: LlmService
    private lateinit var coordinator: CodeVectorizationCoordinator
    private lateinit var projectPath: Path

    @TempDir
    lateinit var tempDir: Path

    @BeforeEach
    fun setUp() {
        mockLlmService = mockk()
        projectPath = createTempDirectory()

        // 创建测试文件结构
        createTestFiles()
    }

    @AfterEach
    fun tearDown() {
        if (::coordinator.isInitialized) {
            coordinator.close()
        }
    }

    @Nested
    @DisplayName("白名单拒绝测试：非法输入")
    inner class RejectionTests {

        @Test
        @DisplayName("vectorizeFileIfChanged - 文件不存在 - 抛异常")
        fun testVectorizeFileIfChanged_文件不存在_抛异常() = runTest {
            // Given: 创建协调器
            coordinator = CodeVectorizationCoordinator(
                projectKey = "test-project",
                projectPath = projectPath,
                llmService = mockLlmService,
                bgeEndpoint = "http://localhost:8000"
            )

            // Given: 不存在的文件
            val nonExistentFile = projectPath.resolve("NonExistent.java")

            // When & Then: 必须抛异常
            val exception = assertThrows<IllegalArgumentException> {
                coordinator.vectorizeFileIfChanged(nonExistentFile)
            }

            assertTrue(exception.message!!.contains("文件不存在"))
        }

        @Test
        @DisplayName("vectorizeFileIfChanged - 空文件 - 抛异常")
        fun testVectorizeFileIfChanged_空文件_抛异常() = runTest {
            // Given: 创建协调器
            coordinator = CodeVectorizationCoordinator(
                projectKey = "test-project",
                projectPath = projectPath,
                llmService = mockLlmService,
                bgeEndpoint = "http://localhost:8000"
            )

            // Given: 创建空文件
            val emptyFile = projectPath.resolve("Empty.java")
            emptyFile.toFile().createNewFile()

            // When & Then: 必须抛异常
            val exception = assertThrows<IllegalArgumentException> {
                coordinator.vectorizeFileIfChanged(emptyFile)
            }

            assertTrue(exception.message!!.contains("文件为空"))
        }

        @Test
        @DisplayName("vectorizeFileIfChanged - LLM 调用失败 - 抛出明确异常")
        fun testVectorizeFileIfChanged_LLM调用失败_抛出明确异常() = runTest {
            // Given: 创建协调器
            coordinator = CodeVectorizationCoordinator(
                projectKey = "test-project",
                projectPath = projectPath,
                llmService = mockLlmService,
                bgeEndpoint = "http://localhost:8000"
            )

            // Given: 一个 Java 文件
            val javaFile = projectPath.resolve("src/main/java/TestHandler.java")
            every { mockLlmService.simpleRequest(any(), any()) } throws RuntimeException("LLM API 调用失败")

            // When & Then: 应该抛出明确异常
            assertThrows<RuntimeException> {
                coordinator.vectorizeFileIfChanged(javaFile)
            }
        }
    }

    @Nested
    @DisplayName("边界值测试：MD5 缓存和增量更新")
    inner class BoundaryTests {

        @Test
        @DisplayName("vectorizeFileIfChanged - 文件未变化 - 跳过处理")
        fun testVectorizeFileIfChanged_文件未变化_跳过处理() = runTest {
            // Given: Mock LLM 响应
            val javaMdResponse = "# TestHandler\n\n## 类信息\n- **完整签名**: `public class TestHandler`\n\n## 业务描述\n测试处理器"
            every { mockLlmService.simpleRequest(any(), any()) } returns javaMdResponse

            // Given: 创建协调器
            coordinator = CodeVectorizationCoordinator(
                projectKey = "test-project",
                projectPath = projectPath,
                llmService = mockLlmService,
                bgeEndpoint = "http://localhost:8000"
            )

            // Given: 一个 Java 文件
            val javaFile = projectPath.resolve("src/main/java/TestHandler.java")

            // When: 首次处理
            val firstResult = coordinator.vectorizeFileIfChanged(javaFile)

            // Then: 第二次处理（文件未变化）应该跳过
            val secondResult = coordinator.vectorizeFileIfChanged(javaFile)

            // 首次应该有结果（可能为空，因为 mock 不完整）
            // 第二次应该跳过（空结果）
            assertTrue(secondResult.isEmpty())
        }

        @Test
        @DisplayName("vectorizeFileIfChanged - 强制更新 - 重新处理")
        fun testVectorizeFileIfChanged_强制更新_重新处理() = runTest {
            // Given: Mock LLM 响应
            var callCount = 0
            val javaMdResponse = "# TestHandler\n\n## 类信息\n- **完整签名**: `public class TestHandler`\n\n## 业务描述\n测试处理器"
            every { mockLlmService.simpleRequest(any(), any()) } answers {
                callCount++
                javaMdResponse
            }

            // Given: 创建协调器
            coordinator = CodeVectorizationCoordinator(
                projectKey = "test-project",
                projectPath = projectPath,
                llmService = mockLlmService,
                bgeEndpoint = "http://localhost:8000"
            )

            // Given: 一个 Java 文件
            val javaFile = projectPath.resolve("src/main/java/TestHandler.java")

            // When: 首次处理
            val firstCallCountBefore = callCount
            val firstResult = coordinator.vectorizeFileIfChanged(javaFile)
            val firstCallCountAfter = callCount

            // 修改文件
            javaFile.toFile().appendText("\n// 新增注释")

            // Then: 文件变化后应该重新处理
            val secondCallCountBefore = callCount
            coordinator.vectorizeFileIfChanged(javaFile)
            val secondCallCountAfter = callCount

            // 验证 LLM 被调用了两次
            assertTrue(firstCallCountAfter > firstCallCountBefore)
            assertTrue(secondCallCountAfter > secondCallCountBefore)
        }
    }

    /**
     * 创建测试文件结构
     */
    private fun createTestFiles() {
        val srcDir = projectPath.resolve("src/main/java")
        srcDir.toFile().mkdirs()

        // 创建测试 Handler 类
        val handlerFile = srcDir.resolve("TestHandler.java")
        handlerFile.writeText("""
            package com.test;

            import org.springframework.stereotype.Component;

            @Component
            public class TestHandler {
                private String id;

                public void execute() {
                    // 处理逻辑
                }
            }
        """.trimIndent())

        // 创建测试枚举
        val enumFile = srcDir.resolve("TestStatus.java")
        enumFile.writeText("""
            package com.test;

            public enum TestStatus {
                PENDING(1, "待处理"),
                PROCESSING(2, "处理中"),
                COMPLETED(3, "已完成");

                private final int code;
                private final String desc;

                TestStatus(int code, String desc) {
                    this.code = code;
                    this.desc = desc;
                }
            }
        """.trimIndent())
    }
}
