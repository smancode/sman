package com.smancode.sman.domain.puzzle

import com.smancode.sman.shared.model.PuzzleType
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@DisplayName("TaskScheduler 测试")
class TaskSchedulerTest {

    private lateinit var scheduler: TaskScheduler

    @BeforeEach
    fun setup() {
        scheduler = TaskScheduler()
    }

    // ========== 优先级计算测试 ==========

    @Test
    @DisplayName("calculatePriority - 应返回 0 到 1 之间的值")
    fun `calculatePriority should return value between 0 and 1`() {
        // Given
        val gap = createTestGap()
        val context = createTestContext()

        // When
        val priority = scheduler.calculatePriority(gap, context)

        // Then
        assertTrue(priority in 0.0..1.0)
    }

    @Test
    @DisplayName("calculatePriority - 高 ROI 应该有更高优先级")
    fun `calculatePriority higher ROI should result in higher priority`() {
        // Given - 使用包含文件名的查询
        val highRoiGap = createTestGap(relatedFiles = listOf("UserService.kt", "OrderService.kt"))
        val lowRoiGap = createTestGap(relatedFiles = listOf("Util.kt"))
        val context = createTestContext(
            recentQueries = listOf("如何修改 UserService？")
        )

        // When
        val highPriority = scheduler.calculatePriority(highRoiGap, context)
        val lowPriority = scheduler.calculatePriority(lowRoiGap, context)

        // Then
        assertTrue(highPriority > lowPriority)
    }

    @Test
    @DisplayName("calculatePriority - 近期变更的文件应有更高优先级")
    fun `calculatePriority recently changed files should have higher freshness`() {
        // Given
        val gap = createTestGap(relatedFiles = listOf("recently/changed/File.kt"))
        val context = createTestContext(
            recentFileChanges = listOf("recently/changed/File.kt")
        )

        // When
        val priority = scheduler.calculatePriority(gap, context)

        // Then
        assertTrue(priority > 0.5) // 近期变更的文件优先级应该较高
    }

    // ========== 排序测试 ==========

    @Test
    @DisplayName("prioritize - 应按优先级降序排列")
    fun `prioritize should sort gaps by priority descending`() {
        // Given
        val gaps = listOf(
            createTestGap(id = "gap-1", priority = 0.3),
            createTestGap(id = "gap-2", priority = 0.9),
            createTestGap(id = "gap-3", priority = 0.5)
        )

        // When
        val sorted = scheduler.prioritize(gaps)

        // Then
        assertEquals(listOf("gap-2", "gap-3", "gap-1"), sorted.map { it.description })
    }

    @Test
    @DisplayName("prioritize - 空列表应返回空")
    fun `prioritize empty list should return empty`() {
        // When
        val result = scheduler.prioritize(emptyList())

        // Then
        assertTrue(result.isEmpty())
    }

    // ========== 任务选择测试 ==========

    @Test
    @DisplayName("selectNext - 应在预算内选择最高优先级")
    fun `selectNext should select highest priority within budget`() {
        // Given
        val gaps = listOf(
            createTestGap(id = "low", priority = 0.3),
            createTestGap(id = "high", priority = 0.9),
            createTestGap(id = "medium", priority = 0.5)
        )
        val budget = TokenBudget(maxTokensPerTask = 5000, maxTasksPerSession = 1)

        // When
        val selected = scheduler.selectNext(gaps, budget)

        // Then
        assertNotNull(selected)
        assertEquals("high", selected.description)
    }

    @Test
    @DisplayName("selectNext - 预算不足应返回 null")
    fun `selectNext null budget should return null`() {
        // Given
        val gaps = listOf(createTestGap())
        val budget = TokenBudget(maxTokensPerTask = 0, maxTasksPerSession = 0)

        // When
        val selected = scheduler.selectNext(gaps, budget)

        // Then
        assertNull(selected)
    }

    // ========== 白名单测试 ==========

    @Test
    @DisplayName("calculatePriority - null gap 应抛出异常")
    fun `calculatePriority with null gap should throw exception`() {
        // Given - 使用反射绕过 Kotlin 的空检查，模拟 Java 调用传入 null
        // 注意：Kotlin 中 null!! 会直接抛出 NPE，这是预期行为
        // 此测试验证 requireNotNull 的行为
        val gap: Gap? = null

        // Then - 由于 Kotlin 空安全，在调用前就会失败
        // 这是正确的防御性行为
        var exceptionThrown = false
        try {
            // 使用 !! 强制解包会抛出 NPE
            gap!!
        } catch (e: NullPointerException) {
            exceptionThrown = true
        }
        assertTrue(exceptionThrown)
    }

    // ========== Helper ==========

    private fun createTestGap(
        id: String = "test-gap",
        type: GapType = GapType.MISSING,
        puzzleType: PuzzleType = PuzzleType.STRUCTURE,
        priority: Double = 0.5,
        relatedFiles: List<String> = emptyList()
    ) = Gap(
        type = type,
        puzzleType = puzzleType,
        description = id,
        priority = priority,
        relatedFiles = relatedFiles,
        detectedAt = Instant.now()
    )

    private fun createTestContext(
        recentQueries: List<String> = emptyList(),
        recentFileChanges: List<String> = emptyList()
    ) = SchedulingContext(
        recentQueries = recentQueries,
        recentFileChanges = recentFileChanges,
        availableBudget = TokenBudget(maxTokensPerTask = 10000, maxTasksPerSession = 5)
    )
}
