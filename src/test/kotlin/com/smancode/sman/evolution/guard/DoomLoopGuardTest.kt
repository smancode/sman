package com.smancode.sman.evolution.guard

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * DoomLoopGuard 测试
 */
@DisplayName("DoomLoopGuard 测试")
class DoomLoopGuardTest {

    private lateinit var guard: DoomLoopGuard
    private lateinit var toolCallDeduplicator: ToolCallDeduplicator
    private lateinit var backoffStateManager: BackoffStateManager
    private lateinit var dailyQuotaManager: DailyQuotaManager

    @BeforeEach
    fun setUp() {
        toolCallDeduplicator = ToolCallDeduplicator()
        backoffStateManager = BackoffStateManager()
        dailyQuotaManager = DailyQuotaManager()
        guard = DoomLoopGuard(
            toolCallDeduplicator = toolCallDeduplicator,
            backoffStateManager = backoffStateManager,
            dailyQuotaManager = dailyQuotaManager
        )
    }

    @Nested
    @DisplayName("问题跳过检查测试")
    inner class ShouldSkipQuestionTest {

        @Test
        @DisplayName("正常情况下不应跳过问题")
        fun `should not skip question in normal case`() {
            // Given: 正常状态
            val projectKey = "test-project"

            // When: 检查是否应跳过
            val result = guard.shouldSkipQuestion(projectKey)

            // Then: 不应跳过
            assertFalse(result.shouldSkip)
            assertNull(result.reason)
        }

        @Test
        @DisplayName("退避期应跳过问题")
        fun `should skip question during backoff period`() {
            // Given: 处于退避期的项目
            val projectKey = "test-project"
            backoffStateManager.recordError(projectKey)

            // When: 检查是否应跳过
            val result = guard.shouldSkipQuestion(projectKey)

            // Then: 应跳过
            assertTrue(result.shouldSkip)
            assertEquals("在退避期中", result.reason)
            val remainingBackoff = result.remainingBackoff
            assertTrue(remainingBackoff != null && remainingBackoff > 0)
        }

        @Test
        @DisplayName("达到配额上限应跳过问题")
        fun `should skip question when quota exceeded`() {
            // Given: 配额上限为 1 的管理器
            val smallQuotaManager = DailyQuotaManager(maxQuestionsPerDay = 1)
            val guardWithSmallQuota = DoomLoopGuard(
                toolCallDeduplicator = toolCallDeduplicator,
                backoffStateManager = backoffStateManager,
                dailyQuotaManager = smallQuotaManager
            )
            val projectKey = "test-project"

            // 消耗配额
            smallQuotaManager.recordQuestionGenerated(projectKey)

            // When: 检查是否应跳过
            val result = guardWithSmallQuota.shouldSkipQuestion(projectKey)

            // Then: 应跳过
            assertTrue(result.shouldSkip)
            assertEquals("已达每日配额", result.reason)
        }

        @Test
        @DisplayName("空 projectKey 应抛出异常")
        fun `empty project key should throw exception`() {
            // When & Then: 空项目键应抛出异常
            org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
                guard.shouldSkipQuestion("")
            }
        }
    }

    @Nested
    @DisplayName("工具调用跳过检查测试")
    inner class ShouldSkipToolCallTest {

        @Test
        @DisplayName("新工具调用不应跳过")
        fun `should not skip new tool call`() {
            // Given: 新的工具调用
            val toolName = "read_file"
            val params = mapOf("path" to "/src/main.kt")

            // When: 检查是否应跳过
            val result = guard.shouldSkipToolCall(toolName, params)

            // Then: 不应跳过
            assertFalse(result.shouldSkip)
        }

        @Test
        @DisplayName("重复工具调用应跳过并返回缓存结果")
        fun `should skip duplicate tool call and return cached result`() {
            // Given: 已执行过的工具调用
            val toolName = "read_file"
            val params = mapOf("path" to "/src/main.kt")
            val expectedResult = "file content"
            guard.recordToolCall(toolName, params, expectedResult)

            // When: 检查是否应跳过
            val result = guard.shouldSkipToolCall(toolName, params)

            // Then: 应跳过并返回缓存结果
            assertTrue(result.shouldSkip)
            assertEquals("工具调用已执行过", result.reason)
            assertEquals(expectedResult, result.cachedResult)
        }

        @Test
        @DisplayName("空 toolName 应抛出异常")
        fun `empty tool name should throw exception`() {
            // When & Then: 空工具名应抛出异常
            org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
                guard.shouldSkipToolCall("", mapOf("key" to "value"))
            }
            org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
                guard.recordToolCall("", mapOf("key" to "value"), "result")
            }
        }
    }

    @Nested
    @DisplayName("记录操作测试")
    inner class RecordOperationTest {

        @Test
        @DisplayName("记录成功应重置退避状态")
        fun `recording success should reset backoff state`() {
            // Given: 处于退避期的项目
            val projectKey = "test-project"
            backoffStateManager.recordError(projectKey)
            assertTrue(backoffStateManager.isInBackoff(projectKey))

            // When: 记录成功
            guard.recordSuccess(projectKey)

            // Then: 不应在退避期
            assertFalse(backoffStateManager.isInBackoff(projectKey))
        }

        @Test
        @DisplayName("记录失败应触发退避")
        fun `recording failure should trigger backoff`() {
            // Given: 正常状态的项目
            val projectKey = "test-project"
            assertFalse(backoffStateManager.isInBackoff(projectKey))

            // When: 记录失败
            guard.recordFailure(projectKey)

            // Then: 应在退避期
            assertTrue(backoffStateManager.isInBackoff(projectKey))
        }

        @Test
        @DisplayName("空 projectKey 记录操作应抛出异常")
        fun `empty project key for record operation should throw exception`() {
            // When & Then: 空项目键应抛出异常
            org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
                guard.recordSuccess("")
            }
            org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
                guard.recordFailure("")
            }
        }
    }

    @Nested
    @DisplayName("状态查询测试")
    inner class StateQueryTest {

        @Test
        @DisplayName("应能获取退避状态")
        fun `should be able to get backoff state`() {
            // Given: 有错误记录的项目
            val projectKey = "test-project"
            guard.recordFailure(projectKey)

            // When: 获取退避状态
            val state = guard.getBackoffState(projectKey)

            // Then: 状态应正确
            assertEquals(1, state.consecutiveErrors)
        }

        @Test
        @DisplayName("应能获取配额信息")
        fun `should be able to get quota info`() {
            // Given: 有记录的项目
            val projectKey = "test-project"
            guard.recordSuccess(projectKey)

            // When: 获取配额信息
            val quota = guard.getRemainingQuota(projectKey)

            // Then: 配额信息应有效
            assertTrue(quota.questionsRemaining >= 0)
            assertTrue(quota.explorationsRemaining >= 0)
        }

        @Test
        @DisplayName("空 projectKey 查询状态应抛出异常")
        fun `empty project key for state query should throw exception`() {
            // When & Then: 空项目键应抛出异常
            org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
                guard.getBackoffState("")
            }
            org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
                guard.getRemainingQuota("")
            }
        }
    }

    @Nested
    @DisplayName("工厂方法测试")
    inner class FactoryMethodTest {

        @Test
        @DisplayName("createDefault 应创建可用的实例")
        fun `createDefault should create usable instance`() {
            // When: 使用工厂方法创建
            val defaultGuard = DoomLoopGuard.createDefault()

            // Then: 应该能正常使用
            val result = defaultGuard.shouldSkipQuestion("test-project")
            assertFalse(result.shouldSkip)
        }
    }

    @Nested
    @DisplayName("DoomLoopCheckResult 测试")
    inner class CheckResultTest {

        @Test
        @DisplayName("pass 应创建通过的结果")
        fun `pass should create passing result`() {
            // When: 创建 pass 结果
            val result = DoomLoopCheckResult.pass()

            // Then: 应该是通过的
            assertFalse(result.shouldSkip)
            assertNull(result.reason)
        }

        @Test
        @DisplayName("skip 应创建跳过的结果")
        fun `skip should create skipping result`() {
            // When: 创建 skip 结果
            val result = DoomLoopCheckResult.skip(
                reason = "测试原因",
                remainingBackoff = 1000L,
                cachedResult = "cached"
            )

            // Then: 应该是跳过的
            assertTrue(result.shouldSkip)
            assertEquals("测试原因", result.reason)
            assertEquals(1000L, result.remainingBackoff)
            assertEquals("cached", result.cachedResult)
        }
    }
}
