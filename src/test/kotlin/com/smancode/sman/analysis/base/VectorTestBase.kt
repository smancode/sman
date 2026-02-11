package com.smancode.sman.analysis.base

import com.smancode.sman.analysis.config.JVectorConfig
import com.smancode.sman.analysis.config.VectorDatabaseConfig
import com.smancode.sman.analysis.config.VectorDbType
import com.smancode.sman.analysis.model.VectorFragment
import com.smancode.sman.analysis.storage.TieredVectorRepository
import com.smancode.sman.analysis.storage.VectorRepository
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectory

/**
 * 向量化测试基类
 *
 * 提供通用的测试工具方法和设置
 */
abstract class VectorTestBase {

    @TempDir
    lateinit var tempDir: Path

    protected lateinit var projectKey: String
    protected lateinit var projectPath: Path
    protected lateinit var repository: VectorRepository

    @BeforeEach
    open fun setup() = runTest {
        projectKey = createProjectKey()
        projectPath = tempDir.resolve("test_project").createDirectory()
        repository = createRepository()
    }

    @AfterEach
    open fun tearDown() = runTest {
        if (::repository.isInitialized) {
            repository.close()
        }
    }

    /**
     * 创建项目 Key（子类可覆盖）
     */
    protected open fun createProjectKey(): String = "test_project"

    /**
     * 创建向量仓库
     */
    protected suspend fun createRepository(): VectorRepository {
        return TieredVectorRepository(
            projectKey = projectKey,
            projectPath = projectPath,
            config = createVectorConfig()
        )
    }

    /**
     * 创建向量配置
     */
    protected fun createVectorConfig(): VectorDatabaseConfig {
        return VectorDatabaseConfig.create(
            projectKey = projectKey,
            type = VectorDbType.JVECTOR,
            jvector = JVectorConfig()
        )
    }

    /**
     * 创建测试向量片段
     */
    protected fun createTestFragment(
        id: String,
        title: String,
        content: String = "Test content for $title",
        vector: FloatArray = floatArrayOf()
    ): VectorFragment {
        return VectorFragment(
            id = id,
            title = title,
            content = content,
            fullContent = "",
            tags = listOf("test"),
            metadata = mapOf("type" to "test"),
            vector = vector
        )
    }

    /**
     * 创建带向量值的测试片段
     */
    protected fun createTestFragmentWithVector(
        id: String,
        title: String,
        content: String = "Test content for $title",
        vectorValue: Float = 0.5f
    ): VectorFragment {
        return VectorFragment(
            id = id,
            title = title,
            content = content,
            fullContent = "",
            tags = listOf("test"),
            metadata = mapOf("type" to "test"),
            vector = FloatArray(1024) { vectorValue }
        )
    }

    /**
     * 辅助方法：断言为假
     */
    protected fun assertFalse(condition: Boolean, message: String? = null) {
        if (condition) {
            throw AssertionError(message ?: "Expected false but was true")
        }
    }

    /**
     * 辅助方法：断言向量 ID 不包含 .md 后缀
     */
    protected fun assertNoMdSuffixInIds(vectors: List<VectorFragment>) {
        vectors.forEach { vector ->
            assertFalse(
                vector.id.contains(".md"),
                "向量 ID 不应包含 .md 后缀: ${vector.id}"
            )
        }
    }
}
