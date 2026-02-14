package com.smancode.sman.evolution.loop

import com.smancode.sman.evolution.generator.QuestionGenerator
import com.smancode.sman.evolution.guard.DoomLoopGuard
import com.smancode.sman.evolution.memory.LearningRecordRepository
import com.smancode.sman.evolution.recorder.LearningRecorder
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 深度分析模式行为测试
 *
 * 验证深度分析模式下的行为差异：
 * - 问题数量：5 vs 3
 * - 探索步数：15 vs 10
 * - 触发代码向量化
 */
@DisplayName("深度分析模式行为测试")
class DeepAnalysisBehaviorTest {

    /**
     * 测试用配置计算器
     *
     * 模拟 SelfEvolutionLoop 中的配置计算逻辑
     */
    private fun calculateQuestionCount(config: EvolutionConfig): Int {
        return if (config.deepAnalysisEnabled) {
            5
        } else {
            config.questionsPerIteration
        }
    }

    private fun calculateExplorationSteps(config: EvolutionConfig): Int {
        return if (config.deepAnalysisEnabled) {
            15
        } else {
            config.maxExplorationSteps
        }
    }

    private fun shouldTriggerVectorization(config: EvolutionConfig): Boolean {
        return config.deepAnalysisEnabled
    }

    @Nested
    @DisplayName("问题数量测试")
    inner class QuestionCountTest {

        @Test
        @DisplayName("普通模式应使用配置的问题数量")
        fun `normal mode should use configured question count`() {
            // Given: 普通模式配置
            val config = EvolutionConfig.DEFAULT

            // When: 计算问题数量
            val questionCount = calculateQuestionCount(config)

            // Then: 应该使用配置的问题数量
            assertEquals(3, questionCount, "普通模式应生成 3 个问题")
        }

        @Test
        @DisplayName("深度分析模式应生成 5 个问题")
        fun `deep analysis mode should generate 5 questions`() {
            // Given: 深度分析模式配置
            val config = EvolutionConfig.DEEP_ANALYSIS

            // When: 计算问题数量
            val questionCount = calculateQuestionCount(config)

            // Then: 应该生成 5 个问题
            assertEquals(5, questionCount, "深度分析模式应生成 5 个问题")
        }

        @Test
        @DisplayName("深度分析模式的问题数量应比普通模式多")
        fun `deep analysis should generate more questions than normal mode`() {
            // Given: 两种配置
            val normalConfig = EvolutionConfig.DEFAULT
            val deepConfig = EvolutionConfig.DEEP_ANALYSIS

            // When: 计算问题数量
            val normalCount = calculateQuestionCount(normalConfig)
            val deepCount = calculateQuestionCount(deepConfig)

            // Then: 深度分析应该更多
            assertTrue(deepCount > normalCount, "深度分析 ($deepCount) 应该比普通模式 ($normalCount) 生成更多问题")
        }
    }

    @Nested
    @DisplayName("探索步数测试")
    inner class ExplorationStepsTest {

        @Test
        @DisplayName("普通模式应使用配置的探索步数")
        fun `normal mode should use configured exploration steps`() {
            // Given: 普通模式配置
            val config = EvolutionConfig.DEFAULT

            // When: 计算探索步数
            val steps = calculateExplorationSteps(config)

            // Then: 应该使用配置的探索步数
            assertEquals(10, steps, "普通模式最大探索步数应为 10")
        }

        @Test
        @DisplayName("深度分析模式应有 15 步探索")
        fun `deep analysis mode should have 15 exploration steps`() {
            // Given: 深度分析模式配置
            val config = EvolutionConfig.DEEP_ANALYSIS

            // When: 计算探索步数
            val steps = calculateExplorationSteps(config)

            // Then: 应该有 15 步探索
            assertEquals(15, steps, "深度分析模式最大探索步数应为 15")
        }

        @Test
        @DisplayName("深度分析模式的探索步数应比普通模式多")
        fun `deep analysis should have more exploration steps than normal mode`() {
            // Given: 两种配置
            val normalConfig = EvolutionConfig.DEFAULT
            val deepConfig = EvolutionConfig.DEEP_ANALYSIS

            // When: 计算探索步数
            val normalSteps = calculateExplorationSteps(normalConfig)
            val deepSteps = calculateExplorationSteps(deepConfig)

            // Then: 深度分析应该更多
            assertTrue(deepSteps > normalSteps, "深度分析 ($deepSteps) 应该比普通模式 ($normalSteps) 有更多探索步数")
        }
    }

    @Nested
    @DisplayName("代码向量化触发测试")
    inner class VectorizationTriggerTest {

        @Test
        @DisplayName("普通模式不应触发代码向量化")
        fun `normal mode should not trigger code vectorization`() {
            // Given: 普通模式配置
            val config = EvolutionConfig.DEFAULT

            // When: 检查是否触发向量化
            val shouldTrigger = shouldTriggerVectorization(config)

            // Then: 不应触发
            assertFalse(shouldTrigger, "普通模式不应触发代码向量化")
        }

        @Test
        @DisplayName("深度分析模式应触发代码向量化")
        fun `deep analysis mode should trigger code vectorization`() {
            // Given: 深度分析模式配置
            val config = EvolutionConfig.DEEP_ANALYSIS

            // When: 检查是否触发向量化
            val shouldTrigger = shouldTriggerVectorization(config)

            // Then: 应该触发
            assertTrue(shouldTrigger, "深度分析模式应触发代码向量化")
        }
    }

    @Nested
    @DisplayName("Token 消耗对比测试")
    inner class TokenConsumptionTest {

        @Test
        @DisplayName("深度分析模式的预期 Token 消耗应更高")
        fun `deep analysis mode should have higher expected token consumption`() {
            // Given: 两种配置
            val normalConfig = EvolutionConfig.DEFAULT
            val deepConfig = EvolutionConfig.DEEP_ANALYSIS

            // When: 计算预期的最大 Token 消耗
            // 假设每个问题消耗 500 Token，每个探索步骤消耗 200 Token
            val tokenPerQuestion = 500
            val tokenPerStep = 200

            val normalQuestions = calculateQuestionCount(normalConfig)
            val normalSteps = calculateExplorationSteps(normalConfig)
            val normalExpectedTokens = normalQuestions * tokenPerQuestion + normalSteps * tokenPerStep

            val deepQuestions = calculateQuestionCount(deepConfig)
            val deepSteps = calculateExplorationSteps(deepConfig)
            val deepExpectedTokens = deepQuestions * tokenPerQuestion + deepSteps * tokenPerStep

            // Then: 深度分析的预期消耗应该更高
            assertTrue(
                deepExpectedTokens > normalExpectedTokens,
                "深度分析预期 Token ($deepExpectedTokens) 应该高于普通模式 ($normalExpectedTokens)"
            )

            // 计算差异百分比
            val increasePercentage = ((deepExpectedTokens - normalExpectedTokens).toDouble() / normalExpectedTokens) * 100
            println("Token 消耗增加: ${"%.1f".format(increasePercentage)}%")
            println("  普通模式: $normalExpectedTokens Token (${normalQuestions} 问题 x $tokenPerQuestion + ${normalSteps} 步 x $tokenPerStep)")
            println("  深度分析: $deepExpectedTokens Token (${deepQuestions} 问题 x $tokenPerQuestion + ${deepSteps} 步 x $tokenPerStep)")
        }
    }

    @Nested
    @DisplayName("配置切换测试")
    inner class ConfigSwitchTest {

        @Test
        @DisplayName("动态切换到深度分析模式应使用深度参数")
        fun `switching to deep analysis should use deep parameters`() {
            // Given: 初始普通配置
            var config = EvolutionConfig.DEFAULT
            assertFalse(config.deepAnalysisEnabled)

            // When: 切换到深度分析
            config = EvolutionConfig.DEEP_ANALYSIS
            val questionCount = calculateQuestionCount(config)
            val steps = calculateExplorationSteps(config)
            val shouldVectorize = shouldTriggerVectorization(config)

            // Then: 应该使用深度参数
            assertEquals(5, questionCount)
            assertEquals(15, steps)
            assertTrue(shouldVectorize)
        }

        @Test
        @DisplayName("动态切换到普通模式应使用普通参数")
        fun `switching to normal mode should use normal parameters`() {
            // Given: 初始深度分析配置
            var config = EvolutionConfig.DEEP_ANALYSIS
            assertTrue(config.deepAnalysisEnabled)

            // When: 切换到普通模式
            config = EvolutionConfig.DEFAULT
            val questionCount = calculateQuestionCount(config)
            val steps = calculateExplorationSteps(config)
            val shouldVectorize = shouldTriggerVectorization(config)

            // Then: 应该使用普通参数
            assertEquals(3, questionCount)
            assertEquals(10, steps)
            assertFalse(shouldVectorize)
        }
    }

    @Nested
    @DisplayName("企业 vs 个人场景测试")
    inner class EnterpriseVsPersonalScenarioTest {

        @Test
        @DisplayName("企业场景：深度分析应该是默认选择")
        fun `enterprise scenario deep analysis should be preferred choice`() {
            // Given: 企业场景（不关心 Token 成本）
            val config = EvolutionConfig.DEEP_ANALYSIS

            // Then: 应该启用所有功能
            assertTrue(config.enabled, "企业场景应该启用自进化")
            assertTrue(config.deepAnalysisEnabled, "企业场景应该启用深度分析")
            assertTrue(config.questionsPerIteration >= 5, "企业场景应该有足够的问题数量")
            assertTrue(config.maxExplorationSteps >= 15, "企业场景应该有足够的探索深度")
        }

        @Test
        @DisplayName("个人场景：普通模式应该是默认选择")
        fun `personal scenario normal mode should be preferred choice`() {
            // Given: 个人场景（关心 Token 成本）
            val config = EvolutionConfig.DEFAULT

            // Then: 应该使用保守配置
            assertTrue(config.enabled, "个人场景应该启用自进化")
            assertFalse(config.deepAnalysisEnabled, "个人场景应该使用普通模式")
            assertEquals(3, config.questionsPerIteration, "个人场景问题数量应该适中")
            assertEquals(10, config.maxExplorationSteps, "个人场景探索步数应该适中")
        }

        @Test
        @DisplayName("个人场景：可以选择关闭自进化")
        fun `personal scenario can choose to disable auto evolution`() {
            // Given: 个人场景选择关闭
            val config = EvolutionConfig.DISABLED

            // Then: 应该完全关闭
            assertFalse(config.enabled, "应该关闭自进化")
        }
    }
}
