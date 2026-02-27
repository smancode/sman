package com.smancode.sman.domain.puzzle

import com.smancode.sman.infra.storage.PuzzleStore
import com.smancode.sman.smancode.llm.LlmService
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import java.io.File
import java.nio.file.Files

/**
 * 真实 LLM 集成测试
 *
 * 注意：此测试需要配置 LLM API Key：
 * - 环境变量: LLM_API_KEY
 * - 或配置文件: sman.properties
 *
 * 默认禁用，需要通过设置环境变量 ENABLE_LLM_INTEGRATION_TEST=true 来启用
 */
@DisplayName("KnowledgeEvolutionLoop 真实集成测试")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Disabled("需要配置 LLM_API_KEY 和 ENABLE_LLM_INTEGRATION_TEST=true 才能运行")
class KnowledgeEvolutionLoopIntegrationTest {

    private lateinit var tempDir: File
    private lateinit var puzzleStore: PuzzleStore
    private lateinit var llmService: LlmService
    private lateinit var loop: KnowledgeEvolutionLoop

    private var skipReason: String? = null

    @BeforeEach
    fun setUp() {
        tempDir = Files.createTempDirectory("evolution-integration-test").toFile()
        puzzleStore = PuzzleStore(tempDir.absolutePath)

        // 检查是否配置了 LLM API
        val apiKey = System.getenv("LLM_API_KEY")
            ?: try {
                // 尝试从配置读取
                val configFile = File("src/main/resources/sman.properties")
                if (configFile.exists()) {
                    val content = configFile.readText()
                    val match = Regex("""llm\.api\.key\s*=\s*(.+)""").find(content)
                    match?.groupValues?.get(1)?.trim()
                } else null
            } catch (e: Exception) {
                null
            }

        if (apiKey.isNullOrBlank()) {
            skipReason = "未配置 LLM_API_KEY 环境变量，跳过真实测试"
            return
        }

        try {
            val poolConfig = com.smancode.sman.smancode.llm.config.LlmPoolConfig()
            poolConfig.retry.maxRetries = 1
            poolConfig.retry.baseDelay = 1000
            llmService = LlmService(poolConfig)
            loop = KnowledgeEvolutionLoop(puzzleStore, llmService)
        } catch (e: Exception) {
            skipReason = "LLM 服务初始化失败: ${e.message}，跳过测试"
        }
    }

    @AfterEach
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    @DisplayName("真实 LLM 调用测试")
    fun `should work with real LLM`() {
        // 跳过如果未配置
        Assumptions.assumeTrue(skipReason == null) {
            "跳过原因: $skipReason"
        }

        // 执行真实的知识进化
        val result = kotlinx.coroutines.runBlocking {
            loop.evolve(Trigger.UserQuery("分析这个项目的结构"))
        }

        // 验证结果
        assertNotNull(result)
        assertEquals(IterationStatus.COMPLETED, result.status)
        assertNotNull(result.hypothesis)
        assertTrue(result.hypothesis!!.isNotBlank())

        // 如果有创建拼图，验证拼图存在
        if (result.puzzlesCreated > 0) {
            val puzzles = puzzleStore.loadAll().getOrNull() ?: emptyList()
            assertTrue(puzzles.isNotEmpty())
            puzzles.forEach { puzzle ->
                assertTrue(puzzle.content.length > 10)
            }
        }
    }

    @Test
    @DisplayName("文件变更触发真实测试")
    fun `should respond to file change with real LLM`() {
        Assumptions.assumeTrue(skipReason == null) {
            "跳过原因: $skipReason"
        }

        // 先创建一个测试文件
        val testFile = File(tempDir, "UserService.kt")
        testFile.writeText("class UserService")

        val result = kotlinx.coroutines.runBlocking {
            loop.evolve(Trigger.FileChange(listOf("UserService.kt")))
        }

        assertNotNull(result)
        assertEquals(IterationStatus.COMPLETED, result.status)
    }

    @Test
    @DisplayName("验证假设生成")
    fun `should generate hypothesis`() {
        Assumptions.assumeTrue(skipReason == null) {
            "跳过原因: $skipReason"
        }

        val result = kotlinx.coroutines.runBlocking {
            loop.evolve(Trigger.UserQuery("这个项目使用什么技术栈？"))
        }

        // 假设应该被生成
        assertNotNull(result.hypothesis)
        assertTrue(result.hypothesis!!.isNotBlank())
        println("生成的假设: ${result.hypothesis}")
    }

    @Test
    @DisplayName("评估结果质量")
    fun `should evaluate result quality`() {
        Assumptions.assumeTrue(skipReason == null) {
            "跳过原因: $skipReason"
        }

        val result = kotlinx.coroutines.runBlocking {
            loop.evolve(Trigger.UserQuery("详细分析项目的认证模块"))
        }

        // 验证评估结果存在
        assertNotNull(result.evaluation)
        assertTrue(result.evaluation!!.qualityScore in 0.0..1.0)
        println("质量评分: ${result.evaluation!!.qualityScore}")
        println("假设验证: ${result.evaluation!!.hypothesisConfirmed}")
    }
}
