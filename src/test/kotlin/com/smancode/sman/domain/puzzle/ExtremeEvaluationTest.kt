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
 * 第4-10轮：极限评估 - 更高难度问题
 */
@DisplayName("第4-10轮：极限评估")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ExtremeEvaluationTest {

    private lateinit var tempDir: File
    private lateinit var puzzleStore: PuzzleStore
    private lateinit var llmService: LlmService
    private lateinit var loop: KnowledgeEvolutionLoop

    private var skipReason: String? = null

    @BeforeEach
    fun setUp() {
        tempDir = Files.createTempDirectory("eval-extreme").toFile()
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

    // ========== Round 4: 深层业务逻辑 ==========

    @Test
    @DisplayName("R4-1: 还款优先级算法")
    fun `round 4 - repayment priority algorithm`() = runBlocking {
        if (skipReason != null) { Assumptions.assumeTrue(false) { skipReason } }

        val result = loop.evolve(Trigger.UserQuery("还款时，多笔欠款的还款优先级算法是什么？"))
        println("R4-1 Hypothesis: ${result.hypothesis}")
        assertNotNull(result)
    }

    @Test
    @DisplayName("R4-2: 风控与业务流程的集成点")
    fun `round 4 - risk control integration`() = runBlocking {
        if (skipReason != null) { Assumptions.assumeTrue(false) { skipReason } }

        val result = loop.evolve(Trigger.UserQuery("风控模块与核心业务流程的集成点在哪里？"))
        println("R4-2 Hypothesis: ${result.hypothesis}")
        assertNotNull(result)
    }

    // ========== Round 5: 数据一致性 ==========

    @Test
    @DisplayName("R5-1: 分布式事务处理")
    fun `round 5 - distributed transaction`() = runBlocking {
        if (skipReason != null) { Assumptions.assumeTrue(false) { skipReason } }

        val result = loop.evolve(Trigger.UserQuery("系统如何处理分布式事务？有哪些一致性保障机制？"))
        println("R5-1 Hypothesis: ${result.hypothesis}")
        assertNotNull(result)
    }

    @Test
    @DisplayName("R5-2: 数据同步与幂等性")
    fun `round 5 - data sync idempotency`() = runBlocking {
        if (skipReason != null) { Assumptions.assumeTrue(false) { skipReason } }

        val result = loop.evolve(Trigger.UserQuery("外部系统回调时的数据同步和幂等性如何保证？"))
        println("R5-2 Hypothesis: ${result.hypothesis}")
        assertNotNull(result)
    }

    // ========== Round 6: 性能与扩展 ==========

    @Test
    @DisplayName("R6-1: 性能瓶颈分析")
    fun `round 6 - performance bottleneck`() = runBlocking {
        if (skipReason != null) { Assumptions.assumeTrue(false) { skipReason } }

        val result = loop.evolve(Trigger.UserQuery("系统的性能瓶颈可能在哪些地方？"))
        println("R6-1 Hypothesis: ${result.hypothesis}")
        assertNotNull(result)
    }

    @Test
    @DisplayName("R6-2: 水平扩展能力")
    fun `round 6 - horizontal scaling`() = runBlocking {
        if (skipReason != null) { Assumptions.assumeTrue(false) { skipReason } }

        val result = loop.evolve(Trigger.UserQuery("系统如何支持水平扩展？哪些组件需要改造？"))
        println("R6-2 Hypothesis: ${result.hypothesis}")
        assertNotNull(result)
    }

    // ========== Round 7: 异常处理 ==========

    @Test
    @DisplayName("R7-1: 异常处理策略")
    fun `round 7 - exception handling`() = runBlocking {
        if (skipReason != null) { Assumptions.assumeTrue(false) { skipReason } }

        val result = loop.evolve(Trigger.UserQuery("核心业务流程的异常处理策略是什么？有哪些降级机制？"))
        println("R7-1 Hypothesis: ${result.hypothesis}")
        assertNotNull(result)
    }

    @Test
    @DisplayName("R7-2: 补偿事务模式")
    fun `round 7 - compensation transaction`() = runBlocking {
        if (skipReason != null) { Assumptions.assumeTrue(false) { skipReason } }

        val result = loop.evolve(Trigger.UserQuery("失败时的补偿/回滚机制是如何设计的？"))
        println("R7-2 Hypothesis: ${result.hypothesis}")
        assertNotNull(result)
    }

    // ========== Round 8: 安全与合规 ==========

    @Test
    @DisplayName("R8-1: 安全架构")
    fun `round 8 - security architecture`() = runBlocking {
        if (skipReason != null) { Assumptions.assumeTrue(false) { skipReason } }

        val result = loop.evolve(Trigger.UserQuery("系统的安全架构是怎么设计的？有哪些防护措施？"))
        println("R8-1 Hypothesis: ${result.hypothesis}")
        assertNotNull(result)
    }

    @Test
    @DisplayName("R8-2: 合规审计")
    fun `round 8 - compliance audit`() = runBlocking {
        if (skipReason != null) { Assumptions.assumeTrue(false) { skipReason } }

        val result = loop.evolve(Trigger.UserQuery("有哪些合规审计机制？关键操作如何追溯？"))
        println("R8-2 Hypothesis: ${result.hypothesis}")
        assertNotNull(result)
    }

    // ========== Round 9: 运维与监控 ==========

    @Test
    @DisplayName("R9-1: 运维自动化")
    fun `round 9 - ops automation`() = runBlocking {
        if (skipReason != null) { Assumptions.assumeTrue(false) { skipReason } }

        val result = loop.evolve(Trigger.UserQuery("系统的运维自动化程度如何？有哪些自动化脚本/工具？"))
        println("R9-1 Hypothesis: ${result.hypothesis}")
        assertNotNull(result)
    }

    @Test
    @DisplayName("R9-2: 监控告警")
    fun `round 9 - monitoring alerting`() = runBlocking {
        if (skipReason != null) { Assumptions.assumeTrue(false) { skipReason } }

        val result = loop.evolve(Trigger.UserQuery("系统的监控和告警机制是什么？"))
        println("R9-2 Hypothesis: ${result.hypothesis}")
        assertNotNull(result)
    }

    // ========== Round 10: 极限挑战 ==========

    @Test
    @DisplayName("R10-1: 最复杂业务流程")
    fun `round 10 - most complex flow`() = runBlocking {
        if (skipReason != null) { Assumptions.assumeTrue(false) { skipReason } }

        // 有上下文
        puzzleStore.save(Puzzle(
            id = "p1", type = PuzzleType.API, status = PuzzleStatus.COMPLETED,
            content = "API: disburse, repay, repay-plan", completeness = 0.6, confidence = 0.6,
            lastUpdated = java.time.Instant.now(), filePath = "p1.md"
        ))
        puzzleStore.save(Puzzle(
            id = "p2", type = PuzzleType.RULE, status = PuzzleStatus.COMPLETED,
            content = "Rule: overdue penalty, interest calculation", completeness = 0.6, confidence = 0.6,
            lastUpdated = java.time.Instant.now(), filePath = "p2.md"
        ))

        val result = loop.evolve(Trigger.UserQuery("综合所有已有关于放款、还款、逾期、利息的知识，描述一个完整贷款业务流程的完整生命周期"))
        println("R10-1 Hypothesis: ${result.hypothesis}")
        println("R10-1 Quality: ${result.evaluation?.qualityScore}")
        assertNotNull(result)
    }

    @Test
    @DisplayName("R10-2: 系统全貌")
    fun `round 10 - system overview`() = runBlocking {
        if (skipReason != null) { Assumptions.assumeTrue(false) { skipReason } }

        val result = loop.evolve(Trigger.UserQuery("用最简洁的语言总结这个系统的全貌：它解决了什么问题？核心能力是什么？架构特点是什么？"))
        println("R10-2 Hypothesis: ${result.hypothesis}")
        println("R10-2 Quality: ${result.evaluation?.qualityScore}")
        assertNotNull(result)
    }
}
