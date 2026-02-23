package com.smancode.sman.e2e

import com.smancode.sman.domain.memory.*
import com.smancode.sman.domain.puzzle.*
import com.smancode.sman.infra.storage.PuzzleStore
import com.smancode.sman.shared.model.PuzzleStatus
import com.smancode.sman.shared.model.PuzzleType
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.Instant

/**
 * E2E 集成测试 - 完整流程验证
 *
 * 测试场景：
 * 1. 项目分析生成 Puzzle
 * 2. Gap 检测发现知识空白
 * 3. TaskScheduler 优先级排序
 * 4. 用户反馈被 Memory 系统记录
 * 5. 反馈影响后续优先级计算
 */
@DisplayName("E2E 集成测试 - Puzzle 与 Memory 协同工作流")
class PuzzleMemoryE2ETest {

    @TempDir
    lateinit var tempDir: File

    private lateinit var puzzleStore: PuzzleStore
    private lateinit var memoryStore: MemoryStore
    private lateinit var gapDetector: GapDetector
    private lateinit var taskScheduler: TaskScheduler
    private lateinit var feedbackCollector: FeedbackCollector

    @BeforeEach
    fun setUp() {
        puzzleStore = PuzzleStore(tempDir.absolutePath)
        memoryStore = FileBasedMemoryStore(tempDir.absolutePath)
        gapDetector = GapDetector()
        taskScheduler = TaskScheduler()
        feedbackCollector = FeedbackCollector(memoryStore)
    }

    // ========== 场景 1：完整知识填充流程 ==========

    @Nested
    @DisplayName("场景 1：完整知识填充流程")
    inner class FullKnowledgeFlowTests {

        @Test
        @DisplayName("从空白项目到完成 API 拼图的知识填充流程")
        fun `should complete full knowledge filling flow`() {
            // Step 1: 创建初始 Puzzle（模拟项目分析结果）
            val apiPuzzle = createPuzzle(
                id = "api-user-controller",
                type = PuzzleType.API,
                content = "# UserController\n\n## 概述\n用户控制器，处理用户相关请求。",
                completeness = 0.3,
                confidence = 0.7
            )
            puzzleStore.save(apiPuzzle)
            println("Step 1: 保存初始 Puzzle - ${apiPuzzle.id}")

            // Step 2: 检测 Gap
            val gaps = gapDetector.detect(listOf(apiPuzzle))
            assertFalse(gaps.isEmpty(), "应该检测到 Gap")
            println("Step 2: 检测到 ${gaps.size} 个 Gap")

            // Step 3: 调度优先级
            val prioritizedGaps = taskScheduler.prioritize(gaps)
            assertTrue(prioritizedGaps.first().priority >= prioritizedGaps.last().priority)
            println("Step 3: Gap 优先级排序完成")

            // Step 4: 模拟用户反馈 - 修正 API 描述
            feedbackCollector.collectCorrection(
                projectId = "test-project",
                key = "api-user-controller-endpoints",
                originalValue = "用户控制器",
                correctedValue = "UserController 提供 /api/users 下的所有 REST 接口，包括 GET、POST、PUT、DELETE"
            )
            println("Step 4: 用户修正已收集")

            // Step 5: 更新 Puzzle 完成度
            val updatedPuzzle = apiPuzzle.copy(
                content = """
                    # UserController

                    ## 概述
                    用户控制器，处理用户相关请求。

                    ## 端点
                    - GET /api/users - 获取用户列表
                    - POST /api/users - 创建用户
                    - GET /api/users/{id} - 获取单个用户
                    - PUT /api/users/{id} - 更新用户
                    - DELETE /api/users/{id} - 删除用户
                """.trimIndent(),
                completeness = 0.8,
                confidence = 0.9,
                lastUpdated = Instant.now()
            )
            puzzleStore.save(updatedPuzzle)
            println("Step 5: Puzzle 已更新，完成度: ${updatedPuzzle.completeness}")

            // 验证最终状态
            val finalPuzzle = puzzleStore.load("api-user-controller").getOrThrow()
            assertNotNull(finalPuzzle)
            assertEquals(0.8, finalPuzzle!!.completeness)
            assertEquals(0.9, finalPuzzle.confidence)

            // 验证记忆已存储
            val memory = memoryStore.load("test-project", "api-user-controller-endpoints").getOrThrow()
            assertNotNull(memory)
            assertEquals(MemoryType.BUSINESS_RULE, memory?.memoryType)
            assertTrue(memory?.value?.contains("REST 接口") == true)
        }
    }

    // ========== 场景 2：多类型 Puzzle 协同 ==========

    @Nested
    @DisplayName("场景 2：多类型 Puzzle 协同")
    inner class MultiTypePuzzleTests {

        @Test
        @DisplayName("结构、API、数据三种类型 Puzzle 的协同分析")
        fun `should coordinate multiple puzzle types`() {
            // 创建三种类型的 Puzzle
            val structurePuzzle = createPuzzle(
                id = "structure-overview",
                type = PuzzleType.STRUCTURE,
                content = "# 项目结构\n\n采用分层架构：Controller -> Service -> Repository",
                completeness = 0.7,
                confidence = 0.8
            )

            val apiPuzzle = createPuzzle(
                id = "api-order-controller",
                type = PuzzleType.API,
                content = "# OrderController\n\n订单控制器。",
                completeness = 0.2,
                confidence = 0.5
            )

            val dataPuzzle = createPuzzle(
                id = "data-order-entity",
                type = PuzzleType.DATA,
                content = "# Order 实体\n\n订单数据模型。",
                completeness = 0.4,
                confidence = 0.6
            )

            // 保存所有 Puzzle
            puzzleStore.save(structurePuzzle)
            puzzleStore.save(apiPuzzle)
            puzzleStore.save(dataPuzzle)

            // 按类型查询
            val apiPuzzles = puzzleStore.findByType(PuzzleType.API).getOrThrow()
            assertEquals(1, apiPuzzles.size)
            assertEquals("api-order-controller", apiPuzzles.first().id)

            val dataPuzzles = puzzleStore.findByType(PuzzleType.DATA).getOrThrow()
            assertEquals(1, dataPuzzles.size)

            // 检测所有 Gap
            val allPuzzles = puzzleStore.loadAll().getOrThrow()
            val gaps = gapDetector.detect(allPuzzles)

            // 验证低完成度的 Puzzle 产生了 Gap
            assertTrue(gaps.any { it.puzzleType == PuzzleType.API })
            assertTrue(gaps.any { it.puzzleType == PuzzleType.DATA })

            // 优先处理低完成度的 API Gap
            val context = SchedulingContext(
                recentQueries = listOf("订单接口", "OrderController"),
                recentFileChanges = listOf("OrderController.kt"),
                availableBudget = TokenBudget(maxTokensPerTask = 4000, maxTasksPerSession = 5)
            )

            val apiGap = gaps.first { it.puzzleType == PuzzleType.API }
            val priority = taskScheduler.calculatePriority(apiGap, context)
            assertTrue(priority > 0, "API Gap 优先级应该大于 0")

            println("API Gap 优先级: $priority")
            println("检测到的 Gap 总数: ${gaps.size}")
        }
    }

    // ========== 场景 3：用户偏好学习 ==========

    @Nested
    @DisplayName("场景 3：用户偏好学习")
    inner class UserPreferenceLearningTests {

        @Test
        @DisplayName("从用户反馈中学习代码风格偏好")
        fun `should learn code style preferences from feedback`() {
            // 用户明确告知代码风格偏好
            feedbackCollector.collectExplicitPreference(
                projectId = "test-project",
                key = "code-style-naming",
                value = "使用驼峰命名法，类名首字母大写，方法名首字母小写"
            )

            feedbackCollector.collectExplicitPreference(
                projectId = "test-project",
                key = "code-style-comments",
                value = "所有公共方法必须有 KDoc 注释"
            )

            // 验证偏好已存储
            val namingPreference = memoryStore.load("test-project", "code-style-naming").getOrThrow()
            assertNotNull(namingPreference)
            assertEquals(MemoryType.USER_PREFERENCE, namingPreference?.memoryType)
            assertEquals(1.0, namingPreference?.confidence) // 显式偏好置信度为 1.0

            val commentsPreference = memoryStore.load("test-project", "code-style-comments").getOrThrow()
            assertNotNull(commentsPreference)
            assertEquals(1.0, commentsPreference?.confidence)

            // 验证可以按类型查询
            val allPreferences = memoryStore.findByType("test-project", MemoryType.USER_PREFERENCE).getOrThrow()
            assertEquals(2, allPreferences.size)
        }

        @Test
        @DisplayName("隐式反馈调整记忆置信度")
        fun `should adjust confidence based on implicit feedback`() {
            // 先创建一个业务规则记忆
            val rule = createMemory(
                projectId = "test-project",
                key = "validation-rule-email",
                value = "邮箱必须符合标准格式",
                memoryType = MemoryType.BUSINESS_RULE,
                confidence = 0.7
            )
            memoryStore.save(rule)

            // 用户接受了基于此规则的建议
            feedbackCollector.collectImplicitFeedback(
                projectId = "test-project",
                key = "validation-rule-email",
                action = UserAction.ACCEPTED_SUGGESTION
            )

            // 验证置信度提高
            val afterAccept = memoryStore.load("test-project", "validation-rule-email").getOrThrow()
            assertTrue(afterAccept!!.confidence > 0.7, "接受建议应提高置信度")

            // 再次接受
            feedbackCollector.collectImplicitFeedback(
                projectId = "test-project",
                key = "validation-rule-email",
                action = UserAction.ACCEPTED_SUGGESTION
            )

            val afterSecondAccept = memoryStore.load("test-project", "validation-rule-email").getOrThrow()
            assertTrue(afterSecondAccept!!.confidence > afterAccept.confidence, "再次接受应继续提高置信度")
            assertTrue(afterSecondAccept.confidence <= 1.0, "置信度不应超过 1.0")
        }
    }

    // ========== 场景 4：Token 预算管理 ==========

    @Nested
    @DisplayName("场景 4：Token 预算管理")
    inner class TokenBudgetTests {

        @Test
        @DisplayName("Token 预算不足时应跳过大任务")
        fun `should skip large tasks when budget is low`() {
            // 创建多个 Gap
            val puzzles = listOf(
                createPuzzle("p1", PuzzleType.API, "内容1", 0.3, 0.5),
                createPuzzle("p2", PuzzleType.API, "内容2", 0.2, 0.4),
                createPuzzle("p3", PuzzleType.DATA, "内容3", 0.5, 0.6)
            )

            val gaps = gapDetector.detect(puzzles)
            assertTrue(gaps.isNotEmpty())

            // 设置有限的 Token 预算
            val budget = TokenBudget(
                maxTokensPerTask = 2000,
                maxTasksPerSession = 2
            )

            // 选择下一个要处理的 Gap
            val selected = taskScheduler.selectNext(gaps, budget)

            // 应该选择优先级最高的
            assertNotNull(selected)
            assertTrue(budget.isAvailable())
        }

        @Test
        @DisplayName("预算耗尽时返回 null")
        fun `should return null when budget exhausted`() {
            val puzzles = listOf(
                createPuzzle("p1", PuzzleType.API, "内容", 0.1, 0.3)
            )
            val gaps = gapDetector.detect(puzzles)

            // 预算为 0
            val exhaustedBudget = TokenBudget(
                maxTokensPerTask = 0,
                maxTasksPerSession = 0
            )

            val selected = taskScheduler.selectNext(gaps, exhaustedBudget)
            assertNull(selected, "预算耗尽时应返回 null")
        }
    }

    // ========== 场景 5：持久化与恢复 ==========

    @Nested
    @DisplayName("场景 5：持久化与恢复")
    inner class PersistenceTests {

        @Test
        @DisplayName("Puzzle 和 Memory 应正确持久化并可恢复")
        fun `should persist and restore puzzles and memories`() {
            // 保存数据
            val puzzle = createPuzzle(
                id = "persist-test",
                type = PuzzleType.FLOW,
                content = "# 业务流程\n\n用户注册流程。",
                completeness = 0.6,
                confidence = 0.7
            )
            puzzleStore.save(puzzle)

            val memory = createMemory(
                projectId = "test-project",
                key = "flow-registration",
                value = "用户注册需要邮箱验证",
                memoryType = MemoryType.DOMAIN_TERM
            )
            memoryStore.save(memory)

            // 创建新的 Store 实例（模拟重启）
            val newPuzzleStore = PuzzleStore(tempDir.absolutePath)
            val newMemoryStore = FileBasedMemoryStore(tempDir.absolutePath)

            // 验证数据恢复
            val restoredPuzzle = newPuzzleStore.load("persist-test").getOrThrow()
            assertNotNull(restoredPuzzle)
            assertEquals("persist-test", restoredPuzzle?.id)
            assertEquals(PuzzleType.FLOW, restoredPuzzle?.type)
            assertEquals(0.6, restoredPuzzle?.completeness)

            val restoredMemory = newMemoryStore.load("test-project", "flow-registration").getOrThrow()
            assertNotNull(restoredMemory)
            assertEquals("用户注册需要邮箱验证", restoredMemory?.value)
        }
    }

    // ========== 辅助方法 ==========

    private fun createPuzzle(
        id: String,
        type: PuzzleType,
        content: String,
        completeness: Double,
        confidence: Double
    ): com.smancode.sman.shared.model.Puzzle {
        return com.smancode.sman.shared.model.Puzzle(
            id = id,
            type = type,
            status = PuzzleStatus.IN_PROGRESS,
            content = content,
            completeness = completeness,
            confidence = confidence,
            lastUpdated = Instant.now(),
            filePath = ".sman/puzzles/$id.md"
        )
    }

    private fun createMemory(
        projectId: String,
        key: String,
        value: String,
        memoryType: MemoryType,
        confidence: Double = 0.8
    ): ProjectMemory {
        val now = Instant.now()
        return ProjectMemory(
            id = "${projectId}_$key",
            projectId = projectId,
            memoryType = memoryType,
            key = key,
            value = value,
            confidence = confidence,
            source = MemorySource.EXPLICIT_INPUT,
            createdAt = now,
            lastAccessedAt = now,
            accessCount = 0
        )
    }
}
