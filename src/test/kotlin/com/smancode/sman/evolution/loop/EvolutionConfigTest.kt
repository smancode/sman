package com.smancode.sman.evolution.loop

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * EvolutionConfig 测试
 *
 * 验证配置参数的正确性，特别是深度分析配置。
 */
@DisplayName("EvolutionConfig 测试")
class EvolutionConfigTest {

    @Nested
    @DisplayName("默认配置测试")
    inner class DefaultConfigTest {

        @Test
        @DisplayName("默认配置应该是保守的（个人使用）")
        fun `default config should be conservative for personal use`() {
            // When: 获取默认配置
            val config = EvolutionConfig.DEFAULT

            // Then: 应该是保守的配置
            assertTrue(config.enabled)
            assertFalse(config.deepAnalysisEnabled)
            assertEquals(3, config.questionsPerIteration, "默认每次迭代应生成 3 个问题")
            assertEquals(10, config.maxExplorationSteps, "默认最大探索步数应为 10")
        }

        @Test
        @DisplayName("默认配置的 Token 预算应该合理")
        fun `default config should have reasonable token budget`() {
            // When: 获取默认配置
            val config = EvolutionConfig.DEFAULT

            // Then: Token 预算应该合理
            assertEquals(8000, config.maxTokensPerIteration, "默认每次迭代 Token 预算应为 8000")
        }
    }

    @Nested
    @DisplayName("深度分析配置测试")
    inner class DeepAnalysisConfigTest {

        @Test
        @DisplayName("深度分析配置应该是激进的（企业使用）")
        fun `deep analysis config should be aggressive for enterprise use`() {
            // When: 获取深度分析配置
            val config = EvolutionConfig.DEEP_ANALYSIS

            // Then: 应该是激进的配置
            assertTrue(config.enabled)
            assertTrue(config.deepAnalysisEnabled, "深度分析应该启用")
            assertEquals(5, config.questionsPerIteration, "深度分析每次迭代应生成 5 个问题")
            assertEquals(15, config.maxExplorationSteps, "深度分析最大探索步数应为 15")
        }

        @Test
        @DisplayName("深度分析应生成更多问题")
        fun `deep analysis should generate more questions`() {
            // Given: 默认配置和深度分析配置
            val defaultConfig = EvolutionConfig.DEFAULT
            val deepConfig = EvolutionConfig.DEEP_ANALYSIS

            // Then: 深度分析应生成更多问题
            assertTrue(
                deepConfig.questionsPerIteration > defaultConfig.questionsPerIteration,
                "深度分析的问题数量 (${deepConfig.questionsPerIteration}) 应该大于默认 (${defaultConfig.questionsPerIteration})"
            )
        }

        @Test
        @DisplayName("深度分析应有更深探索步数")
        fun `deep analysis should have deeper exploration steps`() {
            // Given: 默认配置和深度分析配置
            val defaultConfig = EvolutionConfig.DEFAULT
            val deepConfig = EvolutionConfig.DEEP_ANALYSIS

            // Then: 深度分析应有更深探索步数
            assertTrue(
                deepConfig.maxExplorationSteps > defaultConfig.maxExplorationSteps,
                "深度分析的探索步数 (${deepConfig.maxExplorationSteps}) 应该大于默认 (${defaultConfig.maxExplorationSteps})"
            )
        }
    }

    @Nested
    @DisplayName("快速测试配置测试")
    inner class FastTestConfigTest {

        @Test
        @DisplayName("快速测试配置应有最小值")
        fun `fast test config should have minimal values`() {
            // When: 获取快速测试配置
            val config = EvolutionConfig.FAST_TEST

            // Then: 应该有最小值
            assertTrue(config.enabled)
            assertEquals(1000L, config.intervalMs, "快速测试间隔应为 1 秒")
            assertEquals(1, config.questionsPerIteration, "快速测试每次迭代应生成 1 个问题")
            assertEquals(3, config.maxExplorationSteps, "快速测试最大探索步数应为 3")
            assertEquals(5, config.maxDailyQuestions, "快速测试每日配额应为 5")
        }
    }

    @Nested
    @DisplayName("禁用配置测试")
    inner class DisabledConfigTest {

        @Test
        @DisplayName("禁用配置应该明确禁用")
        fun `disabled config should be explicitly disabled`() {
            // When: 获取禁用配置
            val config = EvolutionConfig.DISABLED

            // Then: 应该是禁用的
            assertFalse(config.enabled)
        }
    }

    @Nested
    @DisplayName("自定义配置测试")
    inner class CustomConfigTest {

        @Test
        @DisplayName("应该能创建自定义深度分析配置")
        fun `should be able to create custom deep analysis config`() {
            // When: 创建自定义配置
            val customConfig = EvolutionConfig(
                enabled = true,
                deepAnalysisEnabled = true,
                questionsPerIteration = 10,
                maxExplorationSteps = 20
            )

            // Then: 配置应该正确
            assertTrue(customConfig.enabled)
            assertTrue(customConfig.deepAnalysisEnabled)
            assertEquals(10, customConfig.questionsPerIteration)
            assertEquals(20, customConfig.maxExplorationSteps)
        }

        @Test
        @DisplayName("应该能创建保守配置（深度分析关闭）")
        fun `should be able to create conservative config with deep analysis off`() {
            // When: 创建保守配置
            val conservativeConfig = EvolutionConfig(
                enabled = true,
                deepAnalysisEnabled = false,
                questionsPerIteration = 2,
                maxExplorationSteps = 5
            )

            // Then: 配置应该正确
            assertTrue(conservativeConfig.enabled)
            assertFalse(conservativeConfig.deepAnalysisEnabled)
            assertEquals(2, conservativeConfig.questionsPerIteration)
            assertEquals(5, conservativeConfig.maxExplorationSteps)
        }
    }

    @Nested
    @DisplayName("配置对比测试")
    inner class ConfigComparisonTest {

        @Test
        @DisplayName("深度分析与默认配置的问题数量差异应为 2")
        fun `question count difference between deep and default should be 2`() {
            // Given: 默认配置和深度分析配置
            val defaultConfig = EvolutionConfig.DEFAULT
            val deepConfig = EvolutionConfig.DEEP_ANALYSIS

            // Then: 问题数量差异应为 2
            val difference = deepConfig.questionsPerIteration - defaultConfig.questionsPerIteration
            assertEquals(2, difference, "深度分析应比默认多 2 个问题（5 - 3 = 2）")
        }

        @Test
        @DisplayName("深度分析与默认配置的探索步数差异应为 5")
        fun `exploration steps difference between deep and default should be 5`() {
            // Given: 默认配置和深度分析配置
            val defaultConfig = EvolutionConfig.DEFAULT
            val deepConfig = EvolutionConfig.DEEP_ANALYSIS

            // Then: 探索步数差异应为 5
            val difference = deepConfig.maxExplorationSteps - defaultConfig.maxExplorationSteps
            assertEquals(5, difference, "深度分析应比默认多 5 步探索（15 - 10 = 5）")
        }
    }

    @Nested
    @DisplayName("智能控制配置测试")
    inner class SmartControlConfigTest {

        @Test
        @DisplayName("默认配置的连续重复问题阈值应为 5")
        fun `default consecutive duplicate question threshold should be 5`() {
            // When: 获取默认配置
            val config = EvolutionConfig.DEFAULT

            // Then: 阈值应为 5
            assertEquals(5, config.maxConsecutiveDuplicateQuestions)
        }

        @Test
        @DisplayName("默认配置的项目学完阈值应为 100")
        fun `default project fully learned threshold should be 100`() {
            // When: 获取默认配置
            val config = EvolutionConfig.DEFAULT

            // Then: 阈值应为 100
            assertEquals(100, config.projectFullyLearnedThreshold)
        }

        @Test
        @DisplayName("快速测试配置的学完阈值应更低")
        fun `fast test config should have lower fully learned threshold`() {
            // Given: 默认配置和快速测试配置
            val defaultConfig = EvolutionConfig.DEFAULT
            val fastTestConfig = EvolutionConfig.FAST_TEST

            // Then: 快速测试的阈值应该更低
            assertTrue(
                fastTestConfig.projectFullyLearnedThreshold < defaultConfig.projectFullyLearnedThreshold,
                "快速测试的学完阈值 (${fastTestConfig.projectFullyLearnedThreshold}) 应该小于默认 (${defaultConfig.projectFullyLearnedThreshold})"
            )
        }
    }
}
