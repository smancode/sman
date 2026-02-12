package com.smancode.sman.analysis.database

import com.smancode.sman.analysis.config.JVectorConfig
import com.smancode.sman.analysis.config.VectorDatabaseConfig
import com.smancode.sman.analysis.config.VectorDbType
import com.smancode.sman.analysis.paths.ProjectPaths
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertTrue

/**
 * H2 数据库服务测试
 * 专门测试 CREATE TABLE 语法是否正确
 */
class H2DatabaseServiceTest {

    @TempDir
    lateinit var tempDir: Path

    /**
     * 测试：H2 数据库表结构初始化
     *
     * 这个测试验证 CREATE TABLE IF NOT EXISTS 语法在 H2 2.2.224 下是否正常工作
     */
    @Test
    fun `test H2 create table syntax`() = runBlocking {
        // Given: 创建配置
        val paths = ProjectPaths.forProject(tempDir)
        val config = VectorDatabaseConfig(
            projectKey = "test_project",
            type = VectorDbType.JVECTOR,
            jvector = JVectorConfig(),
            databasePath = paths.databaseFile.toString()
        )

        // When: 初始化数据库
        val h2Service = H2DatabaseService(config)
        h2Service.init()

        // Then: 没有抛出异常，表创建成功
        // 如果 CREATE TABLE 语法有错误，这里会抛出异常
        assertTrue(true, "H2 表结构初始化成功")

        // Cleanup
        h2Service.close()
    }

    /**
     * 测试：多次初始化（验证 IF NOT EXISTS 子句）
     */
    @Test
    fun `test H2 create table idempotent`() = runBlocking {
        // Given: 创建配置
        val paths = ProjectPaths.forProject(tempDir)
        val config = VectorDatabaseConfig(
            projectKey = "test_project",
            type = VectorDbType.JVECTOR,
            jvector = JVectorConfig(),
            databasePath = paths.databaseFile.toString()
        )

        val h2Service = H2DatabaseService(config)

        // When: 多次初始化
        h2Service.init()  // 第一次
        h2Service.init()  // 第二次（应该不会报错）
        h2Service.init()  // 第三次（应该不会报错）

        // Then: 全部成功
        assertTrue(true, "多次初始化成功")

        // Cleanup
        h2Service.close()
    }

    /**
     * 测试：向量片段表的 CRUD 操作
     */
    @Test
    fun `test H2 vector fragments CRUD`() = runBlocking {
        // Given: 初始化数据库
        val paths = ProjectPaths.forProject(tempDir)
        val config = VectorDatabaseConfig(
            projectKey = "test_project",
            type = VectorDbType.JVECTOR,
            jvector = JVectorConfig(),
            databasePath = paths.databaseFile.toString()
        )

        val h2Service = H2DatabaseService(config)
        h2Service.init()

        // Given: 创建向量片段
        val fragment = com.smancode.sman.analysis.model.VectorFragment(
            id = "test_fragment_1",
            title = "测试片段",
            content = "测试内容",
            fullContent = "完整内容",
            tags = listOf("test", "h2"),
            metadata = mapOf("source" to "test"),
            vector = floatArrayOf(0.1f, 0.2f, 0.3f, 0.4f, 0.5f)
        )

        // When: 保存片段
        h2Service.saveVectorFragment(fragment)

        // When: 获取片段
        val retrieved = h2Service.getVectorFragment("test_fragment_1")

        // Then: 验证
        assert(retrieved != null) { "片段应该存在" }
        assert(retrieved?.id == "test_fragment_1") { "ID 应该匹配" }
        assert(retrieved?.title == "测试片段") { "标题应该匹配" }

        // Cleanup
        h2Service.close()
    }
}
