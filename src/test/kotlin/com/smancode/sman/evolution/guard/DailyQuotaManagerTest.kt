package com.smancode.sman.evolution.guard

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * DailyQuotaManager 测试
 */
@DisplayName("DailyQuotaManager 测试")
class DailyQuotaManagerTest {

    private lateinit var quotaManager: DailyQuotaManager

    @BeforeEach
    fun setUp() {
        quotaManager = DailyQuotaManager()
    }

    @Nested
    @DisplayName("问题配额测试")
    inner class QuestionQuotaTest {

        @Test
        @DisplayName("初始状态应可以生成问题")
        fun `should be able to generate question initially`() {
            // Given: 新项目
            val projectKey = "test-project"

            // When: 检查是否可以生成问题
            val canGenerate = quotaManager.canGenerateQuestion(projectKey)

            // Then: 应该可以
            assertTrue(canGenerate)
        }

        @Test
        @DisplayName("达到配额上限后不能生成问题")
        fun `should not be able to generate question after reaching limit`() {
            // Given: 配额上限为 3 的管理器
            val smallQuotaManager = DailyQuotaManager(maxQuestionsPerDay = 3)
            val projectKey = "test-project"

            // When: 生成 3 个问题后
            smallQuotaManager.recordQuestionGenerated(projectKey)
            smallQuotaManager.recordQuestionGenerated(projectKey)
            smallQuotaManager.recordQuestionGenerated(projectKey)

            // Then: 不应再能生成
            assertFalse(smallQuotaManager.canGenerateQuestion(projectKey))
        }

        @Test
        @DisplayName("空 projectKey 应抛出异常")
        fun `empty project key should throw exception`() {
            // When & Then: 空项目键应抛出异常
            org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
                quotaManager.canGenerateQuestion("")
            }
            org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
                quotaManager.recordQuestionGenerated("")
            }
        }
    }

    @Nested
    @DisplayName("探索配额测试")
    inner class ExplorationQuotaTest {

        @Test
        @DisplayName("初始状态应可以进行探索")
        fun `should be able to explore initially`() {
            // Given: 新项目
            val projectKey = "test-project"

            // When: 检查是否可以探索
            val canExplore = quotaManager.canExplore(projectKey)

            // Then: 应该可以
            assertTrue(canExplore)
        }

        @Test
        @DisplayName("达到配额上限后不能探索")
        fun `should not be able to explore after reaching limit`() {
            // Given: 配额上限为 2 的管理器
            val smallQuotaManager = DailyQuotaManager(maxExplorationsPerDay = 2)
            val projectKey = "test-project"

            // When: 进行 2 次探索后
            smallQuotaManager.recordExploration(projectKey)
            smallQuotaManager.recordExploration(projectKey)

            // Then: 不应再能探索
            assertFalse(smallQuotaManager.canExplore(projectKey))
        }

        @Test
        @DisplayName("空 projectKey 进行探索应抛出异常")
        fun `empty project key for exploration should throw exception`() {
            // When & Then: 空项目键应抛出异常
            org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
                quotaManager.canExplore("")
            }
            org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
                quotaManager.recordExploration("")
            }
        }
    }

    @Nested
    @DisplayName("配额信息测试")
    inner class QuotaInfoTest {

        @Test
        @DisplayName("应正确返回剩余配额信息")
        fun `should return correct remaining quota info`() {
            // Given: 配额上限为 10 的管理器
            val smallQuotaManager = DailyQuotaManager(maxQuestionsPerDay = 10, maxExplorationsPerDay = 20)
            val projectKey = "test-project"

            // When: 记录 3 个问题和 5 次探索
            repeat(3) { smallQuotaManager.recordQuestionGenerated(projectKey) }
            repeat(5) { smallQuotaManager.recordExploration(projectKey) }

            // Then: 剩余配额应正确
            val quotaInfo = smallQuotaManager.getRemainingQuota(projectKey)
            assertEquals(7, quotaInfo.questionsRemaining) // 10 - 3 = 7
            assertEquals(15, quotaInfo.explorationsRemaining) // 20 - 5 = 15
            assertTrue(quotaInfo.resetsAt > System.currentTimeMillis())
        }

        @Test
        @DisplayName("空 projectKey 获取配额信息应抛出异常")
        fun `empty project key for quota info should throw exception`() {
            // When & Then: 空项目键应抛出异常
            org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
                quotaManager.getRemainingQuota("")
            }
        }

        @Test
        @DisplayName("不同项目应有独立配额")
        fun `different projects should have independent quotas`() {
            // Given: 两个项目
            val smallQuotaManager = DailyQuotaManager(maxQuestionsPerDay = 5)

            // When: 项目 A 消耗 3 个配额
            repeat(3) { smallQuotaManager.recordQuestionGenerated("project-a") }

            // Then: 项目 B 的配额应不受影响
            assertEquals(2, smallQuotaManager.getRemainingQuota("project-a").questionsRemaining)
            assertEquals(5, smallQuotaManager.getRemainingQuota("project-b").questionsRemaining)
        }
    }

    @Nested
    @DisplayName("DailyQuota 数据类测试")
    inner class DailyQuotaDataClassTest {

        @Test
        @DisplayName("reset 应正确重置配额")
        fun `reset should correctly reset quota`() {
            // Given: 一个有使用记录的配额
            val quota = DailyQuota(
                questionsToday = 10,
                explorationsToday = 20,
                lastResetDate = "2024-01-01"
            )

            // When: 重置到新日期
            quota.reset("2024-01-02")

            // Then: 计数应重置
            assertEquals(0, quota.questionsToday)
            assertEquals(0, quota.explorationsToday)
            assertEquals("2024-01-02", quota.lastResetDate)
        }
    }
}
