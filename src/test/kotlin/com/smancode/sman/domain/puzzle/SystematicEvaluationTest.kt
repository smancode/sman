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
 * 第3轮：系统化对比评估
 *
 * 设计三类问题：
 * 1. 技术问题 - 架构、技术选型、代码结构
 * 2. 业务问题 - 业务逻辑、规则、流程
 * 3. 复合问题 - 技术+业务的综合性问题
 */
@DisplayName("第3轮：系统化对比评估")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SystematicEvaluationTest {

    private lateinit var tempDir: File
    private lateinit var puzzleStore: PuzzleStore
    private lateinit var llmService: LlmService
    private lateinit var loop: KnowledgeEvolutionLoop

    private var skipReason: String? = null

    @BeforeEach
    fun setUp() {
        tempDir = Files.createTempDirectory("eval-round3").toFile()
        puzzleStore = PuzzleStore(tempDir.absolutePath)

        try {
            llmService = SmanConfig.createLlmService()
            loop = KnowledgeEvolutionLoop(puzzleStore, llmService)
        } catch (e: Exception) {
            skipReason = "LLM 服务初始化失败: ${e.message}"
        }
    }

    @AfterEach
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    // ========== 技术问题 ==========

    @Test
    @DisplayName("技术问题1: 项目使用了哪些设计模式")
    fun `technical question 1 - design patterns`() = runBlocking {
        if (skipReason != null) {
            Assumptions.assumeTrue(false) { skipReason }
        }

        // 自迭代
        val selfResult = loop.evolve(Trigger.UserQuery("这个项目使用了哪些设计模式？"))

        // 直接分析
        val directPrompt = """
分析 ~/projects/autoloop 项目：

问题：这个项目使用了哪些设计模式？

要求：
1. 禁止输出目录树
2. 禁止浅层描述
3. 必须提取技术模式：设计模式、架构模式、代码组织模式
        """.trimIndent()
        val directResult = llmService.simpleRequest(directPrompt)

        println("=== 技术问题1: 设计模式 ===")
        println("自迭代 Hypothesis: ${selfResult.hypothesis}")
        println("自迭代 Quality: ${selfResult.evaluation?.qualityScore}")
        println()
        println("直接分析:")
        println(directResult.take(500))
        println()

        assertNotNull(selfResult)
    }

    @Test
    @DisplayName("技术问题2: 模块之间的依赖关系")
    fun `technical question 2 - module dependencies`() = runBlocking {
        if (skipReason != null) {
            Assumptions.assumeTrue(false) { skipReason }
        }

        // 有上下文
        val initialPuzzle = Puzzle(
            id = "tech-arch",
            type = PuzzleType.STRUCTURE,
            status = PuzzleStatus.COMPLETED,
            content = "项目结构: loan模块、core模块、web模块",
            completeness = 0.5,
            confidence = 0.5,
            lastUpdated = java.time.Instant.now(),
            filePath = ".sman/puzzles/tech-arch.md"
        )
        puzzleStore.save(initialPuzzle)

        val selfResult = loop.evolve(Trigger.UserQuery("模块之间的依赖关系是什么？"))

        val directPrompt = "分析 autoloop 项目的模块依赖关系"
        val directResult = llmService.simpleRequest(directPrompt)

        println("=== 技术问题2: 模块依赖 ===")
        println("自迭代 Hypothesis: ${selfResult.hypothesis}")
        println()
        println("直接分析:")
        println(directResult.take(500))

        assertNotNull(selfResult)
    }

    // ========== 业务问题 ==========

    @Test
    @DisplayName("业务问题1: 贷款逾期的处理规则")
    fun `business question 1 - overdue rules`() = runBlocking {
        if (skipReason != null) {
            Assumptions.assumeTrue(false) { skipReason }
        }

        val selfResult = loop.evolve(Trigger.UserQuery("贷款逾期的处理规则是什么？"))

        val directPrompt = """
分析 autoloop 项目的贷款逾期处理规则：

要求：
1. 禁止输出目录树
2. 禁止浅层描述
3. 必须提取业务规则：触发条件、处理流程、计算公式
        """.trimIndent()
        val directResult = llmService.simpleRequest(directPrompt)

        println("=== 业务问题1: 逾期规则 ===")
        println("自迭代 Hypothesis: ${selfResult.hypothesis}")
        println("自迭代 Quality: ${selfResult.evaluation?.qualityScore}")
        println("自迭代 Results: ${selfResult.evaluation?.newKnowledgeGained}")
        println()
        println("直接分析:")
        println(directResult.take(800))

        assertNotNull(selfResult)
    }

    @Test
    @DisplayName("业务问题2: 利息计算的核心逻辑")
    fun `business question 2 - interest calculation`() = runBlocking {
        if (skipReason != null) {
            Assumptions.assumeTrue(false) { skipReason }
        }

        val selfResult = loop.evolve(Trigger.UserQuery("利息计算的核心逻辑是什么？"))

        val directPrompt = "分析 autoloop 的利息计算逻辑"
        val directResult = llmService.simpleRequest(directPrompt)

        println("=== 业务问题2: 利息计算 ===")
        println("自迭代 Hypothesis: ${selfResult.hypothesis}")
        println()
        println("直接分析:")
        println(directResult.take(500))

        assertNotNull(selfResult)
    }

    // ========== 复合问题 ==========

    @Test
    @DisplayName("复合问题1: 状态机设计与业务规则的关系")
    fun `complex question 1 - state machine and business rules`() = runBlocking {
        if (skipReason != null) {
            Assumptions.assumeTrue(false) { skipReason }
        }

        // 有上下文
        val puzzles = listOf(
            Puzzle(
                id = "biz-overdue",
                type = PuzzleType.RULE,
                status = PuzzleStatus.COMPLETED,
                content = "逾期规则: 超过还款日未还即逾期，产生罚息",
                completeness = 0.5,
                confidence = 0.5,
                lastUpdated = java.time.Instant.now(),
                filePath = ".sman/puzzles/biz-overdue.md"
            ),
            Puzzle(
                id = "tech-state",
                type = PuzzleType.FLOW,
                status = PuzzleStatus.COMPLETED,
                content = "状态: Draft->Active->Overdue->Settled",
                completeness = 0.5,
                confidence = 0.5,
                lastUpdated = java.time.Instant.now(),
                filePath = ".sman/puzzles/tech-state.md"
            )
        )
        puzzles.forEach { puzzleStore.save(it) }

        val selfResult = loop.evolve(Trigger.UserQuery("状态机设计与业务规则的关系是什么？"))

        val directPrompt = """
分析 autoloop 项目：

问题：状态机设计与业务规则的关系是什么？

要求：
1. 禁止输出目录树
2. 禁止浅层描述
3. 必须分析状态变迁如何触发业务规则
        """.trimIndent()
        val directResult = llmService.simpleRequest(directPrompt)

        println("=== 复合问题1: 状态机与业务规则 ===")
        println("自迭代 Hypothesis: ${selfResult.hypothesis}")
        println("自迭代 Quality: ${selfResult.evaluation?.qualityScore}")
        println()
        println("直接分析:")
        println(directResult.take(800))

        assertNotNull(selfResult)
    }

    @Test
    @DisplayName("复合问题2: 放款流程中的技术实现与业务约束")
    fun `complex question 2 - disburse flow`() = runBlocking {
        if (skipReason != null) {
            Assumptions.assumeTrue(false) { skipReason }
        }

        val selfResult = loop.evolve(Trigger.UserQuery("放款流程中的技术实现与业务约束是什么？"))

        val directPrompt = "分析 autoloop 放款流程中的技术实现与业务约束"
        val directResult = llmService.simpleRequest(directPrompt)

        println("=== 复合问题2: 放款流程 ===")
        println("自迭代 Hypothesis: ${selfResult.hypothesis}")
        println()
        println("直接分析:")
        println(directResult.take(500))

        assertNotNull(selfResult)
    }
}
