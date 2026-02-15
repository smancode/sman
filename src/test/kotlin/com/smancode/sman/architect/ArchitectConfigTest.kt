package com.smancode.sman.architect

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * ArchitectConfig 测试
 *
 * 测试架构师配置
 */
@DisplayName("ArchitectConfig 测试")
class ArchitectConfigTest {

    @Nested
    @DisplayName("完成度阈值测试")
    inner class CompletionThresholdTest {

        @Test
        @DisplayName("普通模式应该使用普通阈值")
        fun `should use normal threshold in normal mode`() {
            // Given
            val config = ArchitectConfig(
                enabled = true,
                deepModeEnabled = false,
                completionThresholdNormal = 0.7,
                completionThresholdDeep = 0.9
            )

            // When
            val threshold = config.getCompletionThreshold()

            // Then
            assertEquals(0.7, threshold, 0.01)
        }

        @Test
        @DisplayName("深度模式应该使用深度阈值")
        fun `should use deep threshold in deep mode`() {
            // Given
            val config = ArchitectConfig(
                enabled = true,
                deepModeEnabled = true,
                completionThresholdNormal = 0.7,
                completionThresholdDeep = 0.9
            )

            // When
            val threshold = config.getCompletionThreshold()

            // Then
            assertEquals(0.9, threshold, 0.01)
        }
    }

    @Nested
    @DisplayName("默认值测试")
    inner class DefaultValueTest {

        @Test
        @DisplayName("应该有正确的默认值")
        fun `should have correct defaults`() {
            // Given
            val config = ArchitectConfig()

            // Then
            assertFalse(config.enabled)
            assertEquals(5, config.maxIterationsPerMd)
            assertFalse(config.deepModeEnabled)
            assertEquals(0.9, config.completionThresholdDeep, 0.01)
            assertEquals(0.7, config.completionThresholdNormal, 0.01)
            assertTrue(config.incrementalCheckEnabled)
            assertEquals(300000L, config.intervalMs)
        }
    }
}
