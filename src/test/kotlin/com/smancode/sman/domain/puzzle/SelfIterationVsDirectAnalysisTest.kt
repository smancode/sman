package com.smancode.sman.domain.puzzle

import com.smancode.sman.config.SmanConfig
import com.smancode.sman.infra.storage.PuzzleStore
import com.smancode.sman.smancode.llm.LlmService
import com.smancode.sman.shared.model.Puzzle
import com.smancode.sman.shared.model.PuzzleStatus
import com.smancode.sman.shared.model.PuzzleType
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import java.io.File
import java.nio.file.Files

/**
 * 自迭代 vs 直接分析 对比评估测试
 *
 * 目标：验证自迭代系统是否比直接 LLM 分析更有价值
 */
@DisplayName("自迭代 vs 直接分析 对比评估")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SelfIterationVsDirectAnalysisTest {

    private lateinit var tempDir: File
    private lateinit var puzzleStore: PuzzleStore
    private lateinit var llmService: LlmService
    private lateinit var loop: KnowledgeEvolutionLoop

    private var skipReason: String? = null

    @BeforeEach
    fun setUp() {
        tempDir = Files.createTempDirectory("eval-test").toFile()
        puzzleStore = PuzzleStore(tempDir.absolutePath)

        // 使用 SmanConfig 创建 LLM 服务（会自动加载配置）
        try {
            llmService = SmanConfig.createLlmService()
            loop = KnowledgeEvolutionLoop(puzzleStore, llmService)
        } catch (e: Exception) {
            skipReason = "LLM 服务初始化失败: ${e.message}"
            println("跳过测试: $skipReason")
        }
    }

    @AfterEach
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    @DisplayName("第1轮：空上下文 - 自迭代 vs 直接分析")
    fun `round 1 - empty context comparison`() = runBlocking {
        if (skipReason != null) {
            Assumptions.assumeTrue(false) { skipReason }
        }

        // 自迭代：无现有拼图
        val selfIterResult = loop.evolve(Trigger.UserQuery("分析 autoloop 项目的核心业务逻辑"))

        // 直接分析：构建一个"裸"Prompt（模拟无上下文）
        val directPrompt = EvolutionPromptBuilder.build("eval-1", EvolutionContext(
            iterationId = "eval-1",
            triggerDescription = "用户问题: 分析 autoloop 项目的核心业务逻辑",
            existingPuzzles = emptyList(),
            timestamp = java.time.Instant.now()
        ))
        val directResult = llmService.simpleRequest(directPrompt)

        println("=== 自迭代结果 ===")
        println("Hypothesis: ${selfIterResult.hypothesis}")
        println("Quality: ${selfIterResult.evaluation?.qualityScore}")
        println("Puzzles created: ${selfIterResult.puzzlesCreated}")
        println()
        println("=== 直接分析结果 ===")
        println(directResult.take(1000))

        // 验证：两者都应该有输出
        assertNotNull(selfIterResult)
        assertTrue(directResult.isNotBlank())
    }

    @Test
    @DisplayName("第2轮：有上下文 - 自迭代利用已有知识")
    fun `round 2 - with context comparison`() = runBlocking {
        if (skipReason != null) {
            Assumptions.assumeTrue(false) { skipReason }
        }

        // 先创建一个初始拼图（模拟已有知识）
        val initialPuzzle = Puzzle(
            id = "api-repay-handler",
            type = PuzzleType.API,
            status = PuzzleStatus.COMPLETED,
            content = """
# RepayHandler API 分析

## 基本信息
- 路径: loan/src/main/java/com/autoloop/loan/handler/RepayHandler.java
- 端点: /api/loan/repay

## API 列表
- GET /{repaymentId} - 查询还款记录
- GET /loan/{loanId} - 查询贷款下的还款
- GET /{loanId}/can-repay - 检查是否可以还款
- GET /{loanId}/total-repaid - 查询已还金额
- POST /xml - XML 格式还款
            """.trimIndent(),
            completeness = 0.5,
            confidence = 0.6,
            lastUpdated = java.time.Instant.now(),
            filePath = ".sman/puzzles/api-repay-handler.md"
        )
        puzzleStore.save(initialPuzzle)

        // 自迭代：有上下文
        val selfIterResult = loop.evolve(Trigger.UserQuery("深入分析还款业务逻辑和规则"))

        // 直接分析：无上下文（只给基本任务）
        val directPrompt = """
# 分析任务

分析 ~/projects/autoloop 的还款业务逻辑。

要求：
1. 禁止输出目录树
2. 禁止浅层描述
3. 必须提取业务规则
4. 必须有洞察
        """.trimIndent()
        val directResult = llmService.simpleRequest(directPrompt)

        println("=== 自迭代（有上下文）===")
        println("Hypothesis: ${selfIterResult.hypothesis}")
        println("Quality: ${selfIterResult.evaluation?.qualityScore}")
        println()
        println("=== 直接分析（无上下文）===")
        println(directResult.take(500))

        // 验证
        assertNotNull(selfIterResult)
    }

    @Test
    @DisplayName("第3轮：多轮迭代 - 知识累积效果")
    fun `round 3 - multi-round iteration`() = runBlocking {
        if (skipReason != null) {
            Assumptions.assumeTrue(false) { skipReason }
        }

        // 创建多个初始拼图
        val puzzles = listOf(
            Puzzle(
                id = "api-repay",
                type = PuzzleType.API,
                status = PuzzleStatus.COMPLETED,
                content = "还款 API: /api/loan/repay",
                completeness = 0.5,
                confidence = 0.6,
                lastUpdated = java.time.Instant.now(),
                filePath = ".sman/puzzles/api-repay.md"
            ),
            Puzzle(
                id = "api-disburse",
                type = PuzzleType.API,
                status = PuzzleStatus.COMPLETED,
                content = "放款 API: /api/loan/disburse",
                completeness = 0.5,
                confidence = 0.6,
                lastUpdated = java.time.Instant.now(),
                filePath = ".sman/puzzles/api-disburse.md"
            )
        )
        puzzles.forEach { puzzleStore.save(it) }

        // 第1次迭代
        val iter1 = loop.evolve(Trigger.UserQuery("分析放款和还款的关系"))

        // 第2次迭代（基于前一次的结果）
        val iter2 = loop.evolve(Trigger.UserQuery("分析资金流转流程"))

        println("=== 迭代1 ===")
        println("Hypothesis: ${iter1.hypothesis}")
        println("Quality: ${iter1.evaluation?.qualityScore}")
        println()
        println("=== 迭代2 ===")
        println("Hypothesis: ${iter2.hypothesis}")
        println("Quality: ${iter2.evaluation?.qualityScore}")

        assertNotNull(iter1)
        assertNotNull(iter2)
    }
}
