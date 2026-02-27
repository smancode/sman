package com.smancode.sman.domain.puzzle

import com.smancode.sman.infra.storage.PuzzleStore
import com.smancode.sman.shared.model.Puzzle
import com.smancode.sman.shared.model.PuzzleStatus
import com.smancode.sman.shared.model.PuzzleType
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import java.io.File
import java.nio.file.Files
import java.time.Instant

/**
 * E2E 测试：验证完整的拼图注入流程
 *
 * 流程：
 * 1. PuzzleStore 存储拼图
 * 2. PuzzleIndexBuilder 构建索引
 * 3. DynamicPromptInjector 注入索引到 System Prompt
 * 4. LLM 通过 load_puzzle / search_puzzles 获取完整内容
 */
@DisplayName("拼图索引注入 E2E 测试")
class PuzzleIndexInjectionE2ETest {

    private lateinit var tempDir: File
    private lateinit var puzzleStore: PuzzleStore
    private lateinit var indexBuilder: PuzzleIndexBuilder

    @BeforeEach
    fun setUp() {
        tempDir = Files.createTempDirectory("puzzle-e2e-test").toFile()
        puzzleStore = PuzzleStore(tempDir.absolutePath)
        indexBuilder = PuzzleIndexBuilder(puzzleStore)
    }

    @AfterEach
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    // ========== 完整流程测试 ==========

    @Test
    @DisplayName("应完成完整的拼图注入流程")
    fun `should complete full puzzle injection flow`() {
        // 1. 准备数据：创建多个拼图
        val puzzles = listOf(
            createPuzzle("api-user", PuzzleType.API, """
                # User API

                用户管理相关接口。

                ## 接口列表
                - POST /api/users - 创建用户
                - GET /api/users/{id} - 获取用户
                - PUT /api/users/{id} - 更新用户
                - DELETE /api/users/{id} - 删除用户
            """.trimIndent()),
            createPuzzle("api-order", PuzzleType.API, """
                # Order API

                订单处理接口。

                ## 接口列表
                - POST /api/orders - 创建订单
                - GET /api/orders/{id} - 获取订单
            """.trimIndent()),
            createPuzzle("flow-order", PuzzleType.FLOW, """
                # Order Flow

                订单处理流程。

                1. 用户下单
                2. 库存检查
                3. 支付处理
                4. 订单确认
            """.trimIndent())
        )

        // 2. 存储拼图
        puzzles.forEach { puzzle ->
            val result = puzzleStore.save(puzzle)
            assertTrue(result.isSuccess, "拼图 ${puzzle.id} 保存失败")
        }

        // 3. 构建索引
        val index = indexBuilder.buildIndex(maxTokens = 500)
        assertTrue(index.isNotEmpty(), "索引不应为空")

        // 4. 验证索引内容
        assertTrue(index.contains("api-user"), "索引应包含 api-user")
        assertTrue(index.contains("api-order"), "索引应包含 api-order")
        assertTrue(index.contains("flow-order"), "索引应包含 flow-order")
        assertTrue(index.contains("load_puzzle"), "索引应包含使用说明")

        // 5. 验证索引 Token 限制
        val estimatedTokens = (index.length * 0.4).toInt()
        assertTrue(estimatedTokens <= 550, "索引 Token 数应接近限制: $estimatedTokens")
    }

    @Test
    @DisplayName("应正确处理相关性搜索")
    fun `should handle relevant search correctly`() {
        // 1. 准备数据
        val puzzles = listOf(
            createPuzzle("api-user", PuzzleType.API, "用户管理 登录 注册 认证"),
            createPuzzle("api-order", PuzzleType.API, "订单 购物 支付"),
            createPuzzle("flow-payment", PuzzleType.FLOW, "支付流程 支付宝 微信")
        )

        puzzles.forEach { puzzleStore.save(it) }

        // 2. 搜索与支付相关的内容
        val index = indexBuilder.buildRelevantIndex("支付", maxTokens = 500)

        // 3. 验证搜索结果
        assertTrue(index.isNotEmpty(), "搜索结果不应为空")

        // flow-payment 和 api-order 都包含"支付"
        // 但 API 类型优先级更高，所以 api-order 可能排在前面
        val hasPaymentFlow = index.contains("flow-payment") || index.contains("api-order")
        assertTrue(hasPaymentFlow, "搜索结果应包含支付相关拼图")
    }

    @Test
    @DisplayName("索引应包含正确的表头和格式")
    fun `should have correct table format`() {
        // 1. 准备数据
        val puzzle = createPuzzle("test-api", PuzzleType.API, "# Test API\n\n测试接口")
        puzzleStore.save(puzzle)

        // 2. 构建索引
        val index = indexBuilder.buildIndex()

        // 3. 验证格式
        assertTrue(index.contains("## Available Project Knowledge"), "应包含标题")
        assertTrue(index.contains("| ID |"), "应包含 ID 列")
        assertTrue(index.contains("load_puzzle"), "应包含工具使用说明")
        assertTrue(index.contains("search_puzzles"), "应包含搜索工具说明")
    }

    @Test
    @DisplayName("应正确处理空拼图列表")
    fun `should handle empty puzzle list`() {
        val index = indexBuilder.buildIndex()
        assertEquals("", index, "空列表应返回空索引")
    }

    @Test
    @DisplayName("应正确处理大量拼图")
    fun `should handle large number of puzzles`() {
        // 1. 创建大量拼图
        (1..50).forEach { i ->
            val puzzle = createPuzzle(
                "api-$i",
                PuzzleType.API,
                "# API $i\n\n${"内容 ".repeat(20)}"
            )
            puzzleStore.save(puzzle)
        }

        // 2. 构建索引（限制 Token）
        val index = indexBuilder.buildIndex(maxTokens = 300)

        // 3. 验证索引被截断
        val estimatedTokens = (index.length * 0.4).toInt()
        assertTrue(estimatedTokens <= 350, "索引应在 Token 限制内: $estimatedTokens")

        // 4. 索引不应包含所有拼图
        val includedCount = index.lines().count { it.contains("| api-") }
        assertTrue(includedCount < 50, "索引应被截断，不应包含所有拼图")
    }

    // ========== 辅助方法 ==========

    private fun createPuzzle(id: String, type: PuzzleType, content: String): Puzzle {
        return Puzzle(
            id = id,
            type = type,
            status = PuzzleStatus.COMPLETED,
            content = content,
            completeness = 0.85,
            confidence = 0.9,
            lastUpdated = Instant.now(),
            filePath = ".sman/puzzles/$id.md"
        )
    }
}
