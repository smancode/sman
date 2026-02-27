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
import java.time.Instant

/**
 * 自迭代 vs 直接分析 对比测试
 * 生成对比报告到 docs/COMPARISON-Evolution-vs-Direct.md
 *
 * 核心改进：使用真实项目代码进行分析，而不是模拟数据
 */
@DisplayName("自迭代 vs 直接分析对比测试")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EvolutionComparisonTest {

    // 使用真实项目路径
    private val targetProjectPath = System.getenv("HOME") + "/projects/autoloop"

    private lateinit var tempDir: File
    private lateinit var puzzleStore: PuzzleStore
    private lateinit var llmService: LlmService
    private lateinit var loop: KnowledgeEvolutionLoop

    private var skipReason: String? = null

    // 测试结果收集
    private val results = mutableListOf<ComparisonResult>()

    data class ComparisonResult(
        val question: String,
        val category: String,
        val evolutionHypothesis: String?,
        val evolutionQuality: Double?,
        val directAnalysis: String,
        val evolutionTokens: Int,
        val directTokens: Int,
        val winner: String,
        val analysis: String,
        val usedRealCode: Boolean  // 是否使用了真实代码
    )

    @BeforeEach
    fun setUp() {
        tempDir = Files.createTempDirectory("comparison-test").toFile()
        puzzleStore = PuzzleStore(tempDir.absolutePath)

        // 检查目标项目是否存在
        val targetProject = File(targetProjectPath)
        if (!targetProject.exists()) {
            skipReason = "目标项目不存在: $targetProjectPath"
            return
        }

        try {
            llmService = SmanConfig.createLlmService()
            // 使用真实项目路径创建 KnowledgeEvolutionLoop
            loop = KnowledgeEvolutionLoop(
                puzzleStore = puzzleStore,
                llmService = llmService,
                projectPath = targetProjectPath
            )
        } catch (e: Exception) {
            skipReason = "LLM 服务初始化失败: ${e.message}"
        }
    }

    @AfterEach
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    @DisplayName("完整对比测试并生成报告")
    fun `run full comparison and generate report`() = runBlocking {
        if (skipReason != null) {
            Assumptions.assumeTrue(false) { skipReason }
            return@runBlocking
        }

        println("=".repeat(60))
        println("自迭代 vs 直接分析 对比测试")
        println("项目: ~/projects/autoloop")
        println("=".repeat(60))
        println()

        // 准备初始上下文（模拟已有知识）
        prepareInitialContext()

        // 测试问题列表
        val testCases = listOf(
            TestCase("技术", "项目采用了什么架构模式？"),
            TestCase("业务", "还款业务的核心逻辑是什么？"),
            TestCase("业务", "逾期罚息是如何计算的？"),
            TestCase("技术", "系统如何处理分布式事务？"),
            TestCase("复合", "状态机是如何驱动业务流程的？"),
            TestCase("业务", "放款流程有哪些业务约束？")
        )

        for (testCase in testCases) {
            println("\n--- 测试: ${testCase.category} - ${testCase.question.take(30)}... ---")

            // 1. 自迭代分析（有上下文）
            val evolutionResult = loop.evolve(Trigger.UserQuery(testCase.question))
            val evolutionHypothesis = evolutionResult.hypothesis
            val evolutionQuality = evolutionResult.evaluation?.qualityScore

            // 2. 直接分析（无上下文）
            val directPrompt = buildDirectAnalysisPrompt(testCase.question)
            val directAnalysis = llmService.simpleRequest(directPrompt)

            // 3. 对比分析
            val comparison = analyzeComparison(
                testCase, evolutionHypothesis, evolutionQuality, directAnalysis
            )
            results.add(comparison)

            println("自迭代 Hypothesis: ${evolutionHypothesis?.take(100)}...")
            println("自迭代 Quality: $evolutionQuality")
            println("胜出: ${comparison.winner}")
        }

        // 生成报告
        generateReport()
    }

    private fun prepareInitialContext() {
        // 模拟已有的项目知识
        puzzleStore.save(Puzzle(
            id = "api-endpoints",
            type = PuzzleType.API,
            status = PuzzleStatus.COMPLETED,
            content = """
                # API 入口分析

                项目主要 API 端点：
                - POST /api/loans - 创建贷款
                - POST /api/repayments - 还款
                - GET /api/repayments/{id} - 查询还款
                - POST /api/disburse - 放款

                技术栈：Spring Boot + Kotlin
            """.trimIndent(),
            completeness = 0.7,
            confidence = 0.8,
            lastUpdated = Instant.now(),
            filePath = "api-endpoints.md"
        ))

        puzzleStore.save(Puzzle(
            id = "business-rules",
            type = PuzzleType.RULE,
            status = PuzzleStatus.COMPLETED,
            content = """
                # 业务规则分析

                还款规则：
                1. 还款顺序：费用 > 罚息 > 利息 > 本金
                2. 宽限期：3-15 天
                3. 提前还款可能有违约金

                放款规则：
                1. 需要风控审核
                2. 需要签署合同
            """.trimIndent(),
            completeness = 0.6,
            confidence = 0.7,
            lastUpdated = Instant.now(),
            filePath = "business-rules.md"
        ))

        puzzleStore.save(Puzzle(
            id = "data-model",
            type = PuzzleType.DATA,
            status = PuzzleStatus.COMPLETED,
            content = """
                # 数据模型

                核心实体：
                - Loan (贷款)
                - Repayment (还款)
                - PaymentMethod (支付方式)
                - Borrower (借款人)

                关系：Borrower 1:N Loan 1:N Repayment
            """.trimIndent(),
            completeness = 0.5,
            confidence = 0.6,
            lastUpdated = Instant.now(),
            filePath = "data-model.md"
        ))
    }

    private fun buildDirectAnalysisPrompt(question: String): String {
        // 直接分析也提供真实代码（公平对比）
        val codeContext = readRealCodeSnippet()

        return """
            你是一个代码分析专家。请基于以下项目代码回答问题。

            项目路径: $targetProjectPath

            项目代码片段:
            $codeContext

            问题：$question

            要求：
            1. 基于提供的真实代码进行分析
            2. 提供具体的业务洞察
            3. 用中文回答
            4. 不要编造不存在的类或方法
        """.trimIndent()
    }

    /**
     * 读取真实代码片段（用于直接分析）
     */
    private fun readRealCodeSnippet(): String {
        val projectDir = File(targetProjectPath)
        if (!projectDir.exists()) return "（项目不存在）"

        val codeBuilder = StringBuilder()
        projectDir.walkTopDown()
            .filter { it.isFile && (it.name.endsWith(".kt") || it.name.endsWith(".java")) }
            .filter { !it.path.contains("/build/") && !it.path.contains("/test/") }
            .take(5)
            .forEach { file ->
                try {
                    codeBuilder.append("\n### ${file.name}\n")
                    codeBuilder.append(file.readText().take(1500))
                    codeBuilder.append("\n")
                } catch (e: Exception) {
                    // 忽略读取错误
                }
            }

        return codeBuilder.toString().take(8000)  // 限制总长度
    }

    private fun analyzeComparison(
        testCase: TestCase,
        evolutionHypothesis: String?,
        evolutionQuality: Double?,
        directAnalysis: String
    ): ComparisonResult {
        // 判断是否使用了真实代码
        val usedRealCode = evolutionHypothesis?.let { h ->
            // 检查是否引用了具体代码元素
            h.contains("class ") || h.contains("fun ") || h.contains("val ") ||
            h.contains("override") || h.contains("import ") ||
            h.contains("Controller") || h.contains("Service") || h.contains("Repository")
        } ?: false

        // 简单的胜出判断逻辑
        val winner = when {
            evolutionQuality == null -> "直接分析（自迭代解析失败）"
            evolutionQuality < 0.5 -> "直接分析（自迭代质量低）"
            evolutionHypothesis == null || evolutionHypothesis.length < 50 -> "直接分析（自迭代输出太短）"
            !usedRealCode -> "直接分析（自迭代未使用真实代码）"
            directAnalysis.contains("洞察") || directAnalysis.contains("关键") -> "平局"
            else -> "自迭代"
        }

        val analysis = buildString {
            appendLine("自迭代质量: $evolutionQuality")
            appendLine("使用真实代码: $usedRealCode")
            appendLine("自迭代是否利用上下文: ${evolutionHypothesis?.contains("已有") == true || evolutionHypothesis?.contains("知识") == true || evolutionHypothesis?.contains("API") == true}")
            appendLine("直接分析深度: ${if (directAnalysis.length > 500) "深" else "浅"}")
        }

        return ComparisonResult(
            question = testCase.question,
            category = testCase.category,
            evolutionHypothesis = evolutionHypothesis,
            evolutionQuality = evolutionQuality,
            directAnalysis = directAnalysis,
            evolutionTokens = (evolutionHypothesis?.length ?: 0) / 2,
            directTokens = directAnalysis.length / 2,
            winner = winner,
            analysis = analysis,
            usedRealCode = usedRealCode
        )
    }

    private fun generateReport() {
        val report = buildString {
            appendLine("# 自迭代 vs 直接分析 对比报告")
            appendLine()
            appendLine("> 版本: v1.0")
            appendLine("> 日期: ${java.time.LocalDate.now()}")
            appendLine("> 项目: ~/projects/autoloop")
            appendLine()
            appendLine("---")
            appendLine()
            appendLine("## 一、测试概述")
            appendLine()
            appendLine("本报告对比 **自迭代知识进化系统**（有上下文）与 **直接 LLM 分析**（无上下文）的输出质量。")
            appendLine()
            appendLine("### 测试配置")
            appendLine()
            appendLine("| 项目 | 值 |")
            appendLine("|------|----|")
            appendLine("| 测试项目 | ~/projects/autoloop (贷款系统) |")
            appendLine("| 初始 Puzzle 数量 | 3 个（API、规则、数据模型）|")
            appendLine("| 测试用例数 | ${results.size} |")
            appendLine("| 测试时间 | ${java.time.LocalDateTime.now()} |")
            appendLine()
            appendLine("---")
            appendLine()
            appendLine("## 二、对比结果汇总")
            appendLine()
            appendLine("| # | 类别 | 问题 | 自迭代质量 | 胜出方 |")
            appendLine("|---|------|------|-----------|--------|")

            results.forEachIndexed { index, result ->
                appendLine("| ${index + 1} | ${result.category} | ${result.question.take(20)}... | ${result.evolutionQuality ?: "N/A"} | ${result.winner} |")
            }

            appendLine()
            appendLine("---")
            appendLine()
            appendLine("## 三、详细对比")
            appendLine()

            results.forEachIndexed { index, result ->
                appendLine("### 测试 ${index + 1}: ${result.category} - ${result.question}")
                appendLine()
                appendLine("#### 自迭代分析（有上下文）")
                appendLine()
                appendLine("**Hypothesis:**")
                appendLine("```")
                appendLine(result.evolutionHypothesis ?: "N/A")
                appendLine("```")
                appendLine()
                appendLine("**质量评分:** ${result.evolutionQuality ?: "N/A"}")
                appendLine()
                appendLine("#### 直接分析（无上下文）")
                appendLine()
                appendLine("```")
                appendLine(result.directAnalysis.take(1000))
                if (result.directAnalysis.length > 1000) appendLine("...")
                appendLine("```")
                appendLine()
                appendLine("**分析:** ${result.analysis}")
                appendLine("**胜出:** ${result.winner}")
                appendLine()
                appendLine("---")
                appendLine()
            }

            appendLine("## 四、结论")
            appendLine()

            // 统计胜出情况
            val evolutionWins = results.count { it.winner.contains("自迭代") }
            val directWins = results.count { it.winner.contains("直接") }
            val ties = results.count { it.winner.contains("平局") }
            val avgQuality = results.mapNotNull { it.evolutionQuality }.average()
            val realCodeUsed = results.count { it.usedRealCode }

            appendLine("### 统计数据")
            appendLine()
            appendLine("| 指标 | 值 |")
            appendLine("|------|----|")
            appendLine("| 自迭代胜出 | $evolutionWins |")
            appendLine("| 直接分析胜出 | $directWins |")
            appendLine("| 平局 | $ties |")
            appendLine("| 自迭代平均质量 | ${String.format("%.2f", avgQuality)} |")
            appendLine("| 使用真实代码 | $realCodeUsed / ${results.size} |")
            appendLine()

            appendLine("### 总体评价")
            appendLine()
            when {
                realCodeUsed < results.size / 2 -> appendLine("⚠️ 自迭代系统未能有效使用真实代码，需要检查代码注入逻辑")
                evolutionWins > directWins -> appendLine("✅ 自迭代系统表现优于直接分析")
                directWins > evolutionWins -> appendLine("⚠️ 直接分析表现更好，需要优化自迭代系统")
                else -> appendLine("📊 两者表现相当")
            }
            appendLine()
            appendLine("---")
            appendLine()
            appendLine("## 五、改进建议")
            appendLine()
            appendLine("1. **真实代码注入**: 确保自迭代系统能读取并使用真实项目代码")
            appendLine("2. **Prompt 优化**: 让 LLM 更好地基于真实代码进行分析")
            appendLine("3. **上下文注入**: 增强上下文的格式化和可读性")
            appendLine("4. **评估标准**: 完善自动评估的质量标准")
            appendLine()
            appendLine("---")
            appendLine()
            appendLine("## 变更历史")
            appendLine()
            appendLine("| 版本 | 日期 | 变更内容 |")
            appendLine("|------|------|---------|")
            appendLine("| v1.1 | ${java.time.LocalDate.now()} | 加入真实代码读取，验证实战能力 |")
            appendLine("| v1.0 | ${java.time.LocalDate.now()} | 初始版本 |")
        }

        // 写入报告
        val reportFile = File("docs/COMPARISON-Evolution-vs-Direct.md")
        reportFile.writeText(report)
        println("\n报告已生成: docs/COMPARISON-Evolution-vs-Direct.md")
    }

    data class TestCase(
        val category: String,
        val question: String
    )
}
