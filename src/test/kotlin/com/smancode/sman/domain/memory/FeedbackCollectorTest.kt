package com.smancode.sman.domain.memory

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import java.time.Instant

@DisplayName("FeedbackCollector 测试套件")
class FeedbackCollectorTest {

    private lateinit var memoryStore: MemoryStore
    private lateinit var feedbackCollector: FeedbackCollector
    private val testProjectId = "test-project"

    @BeforeEach
    fun setUp() {
        memoryStore = mockk(relaxed = true)
        feedbackCollector = FeedbackCollector(memoryStore)
    }

    // ========== 修正收集测试 ==========

    @Nested
    @DisplayName("修正收集测试")
    inner class CorrectionCollectionTests {

        @Test
        @DisplayName("collectCorrection - 应创建 BUSINESS_RULE 类型的记忆")
        fun `collectCorrection should create BUSINESS_RULE memory`() {
            // 需要显式 mock load 返回 null（表示新记忆）
            every { memoryStore.load(any(), any()) } returns Result.success(null)
            every { memoryStore.save(any()) } returns Result.success(Unit)

            val originalValue = "用户年龄必须是整数"
            val correctedValue = "用户年龄必须是 18-120 之间的整数"

            feedbackCollector.collectCorrection(
                projectId = testProjectId,
                key = "age-validation",
                originalValue = originalValue,
                correctedValue = correctedValue
            )

            verify {
                memoryStore.save(match {
                    it.projectId == testProjectId &&
                    it.key == "age-validation" &&
                    it.memoryType == MemoryType.BUSINESS_RULE &&
                    it.value == correctedValue &&
                    it.source == MemorySource.FEEDBACK_CORRECTION &&
                    it.confidence == 0.9
                })
            }
        }

        @Test
        @DisplayName("collectCorrection - 相同修正应提高置信度")
        fun `collectCorrection should increase confidence when repeated`() {
            val existingMemory = createTestMemory(
                key = "age-validation",
                confidence = 0.7
            )
            every { memoryStore.load(testProjectId, "age-validation") } returns Result.success(existingMemory)

            feedbackCollector.collectCorrection(
                projectId = testProjectId,
                key = "age-validation",
                originalValue = "原值",
                correctedValue = "修正值"
            )

            verify {
                memoryStore.save(match {
                    it.confidence > 0.7
                })
            }
        }
    }

    // ========== 显式偏好收集测试 ==========

    @Nested
    @DisplayName("显式偏好收集测试")
    inner class ExplicitPreferenceTests {

        @Test
        @DisplayName("collectExplicitPreference - 应创建 USER_PREFERENCE 类型记忆")
        fun `collectExplicitPreference should create USER_PREFERENCE memory`() {
            feedbackCollector.collectExplicitPreference(
                projectId = testProjectId,
                key = "code-style-braces",
                value = "使用 K&R 风格大括号"
            )

            verify {
                memoryStore.save(match {
                    it.projectId == testProjectId &&
                    it.key == "code-style-braces" &&
                    it.memoryType == MemoryType.USER_PREFERENCE &&
                    it.value == "使用 K&R 风格大括号" &&
                    it.source == MemorySource.EXPLICIT_INPUT &&
                    it.confidence == 1.0
                })
            }
        }

        @Test
        @DisplayName("collectExplicitPreference - 空 key 应抛出异常")
        fun `collectExplicitPreference should throw when key is blank`() {
            assertThrows<IllegalArgumentException> {
                feedbackCollector.collectExplicitPreference(
                    projectId = testProjectId,
                    key = "",
                    value = "值"
                )
            }
        }
    }

    // ========== 隐式反馈收集测试 ==========

    @Nested
    @DisplayName("隐式反馈收集测试")
    inner class ImplicitFeedbackTests {

        @Test
        @DisplayName("collectImplicitFeedback - ACCEPTED_SUGGESTION 应提高置信度")
        fun `collectImplicitFeedback with ACCEPTED_SUGGESTION should increase confidence`() {
            val existingMemory = createTestMemory(
                key = "suggestion-1",
                confidence = 0.5
            )
            every { memoryStore.load(testProjectId, "suggestion-1") } returns Result.success(existingMemory)

            feedbackCollector.collectImplicitFeedback(
                projectId = testProjectId,
                key = "suggestion-1",
                action = UserAction.ACCEPTED_SUGGESTION
            )

            verify {
                memoryStore.save(match {
                    it.confidence > 0.5
                })
            }
        }

        @Test
        @DisplayName("collectImplicitFeedback - REJECTED_SUGGESTION 应降低置信度")
        fun `collectImplicitFeedback with REJECTED_SUGGESTION should decrease confidence`() {
            val existingMemory = createTestMemory(
                key = "suggestion-1",
                confidence = 0.8
            )
            every { memoryStore.load(testProjectId, "suggestion-1") } returns Result.success(existingMemory)

            feedbackCollector.collectImplicitFeedback(
                projectId = testProjectId,
                key = "suggestion-1",
                action = UserAction.REJECTED_SUGGESTION
            )

            verify {
                memoryStore.save(match {
                    it.confidence < 0.8
                })
            }
        }

        @Test
        @DisplayName("collectImplicitFeedback - REPEATED_ACTION 应大幅提高置信度")
        fun `collectImplicitFeedback with REPEATED_ACTION should significantly increase confidence`() {
            val existingMemory = createTestMemory(
                key = "pattern-1",
                confidence = 0.5
            )
            every { memoryStore.load(testProjectId, "pattern-1") } returns Result.success(existingMemory)
            every { memoryStore.save(any()) } returns Result.success(Unit)

            feedbackCollector.collectImplicitFeedback(
                projectId = testProjectId,
                key = "pattern-1",
                action = UserAction.REPEATED_ACTION
            )

            verify {
                memoryStore.save(match {
                    // 0.5 + 0.3 = 0.8，验证置信度显著提高
                    it.confidence >= 0.8
                })
            }
        }

        @Test
        @DisplayName("collectImplicitFeedback - 不存在的记忆应忽略")
        fun `collectImplicitFeedback should ignore non-existent memory`() {
            every { memoryStore.load(testProjectId, "non-existent") } returns Result.success(null)

            // 应该不抛出异常，也不调用 save
            feedbackCollector.collectImplicitFeedback(
                projectId = testProjectId,
                key = "non-existent",
                action = UserAction.ACCEPTED_SUGGESTION
            )

            verify(exactly = 0) { memoryStore.save(any()) }
        }
    }

    // ========== 置信度调整测试 ==========

    @Nested
    @DisplayName("置信度调整测试")
    inner class ConfidenceAdjustmentTests {

        @Test
        @DisplayName("置信度不应超过 1.0")
        fun `confidence should not exceed 1_0`() {
            val existingMemory = createTestMemory(
                key = "max-confidence",
                confidence = 0.95
            )
            every { memoryStore.load(testProjectId, "max-confidence") } returns Result.success(existingMemory)

            feedbackCollector.collectImplicitFeedback(
                projectId = testProjectId,
                key = "max-confidence",
                action = UserAction.ACCEPTED_SUGGESTION
            )

            verify {
                memoryStore.save(match {
                    it.confidence <= 1.0
                })
            }
        }

        @Test
        @DisplayName("置信度不应低于 0.0")
        fun `confidence should not go below 0_0`() {
            val existingMemory = createTestMemory(
                key = "min-confidence",
                confidence = 0.1
            )
            every { memoryStore.load(testProjectId, "min-confidence") } returns Result.success(existingMemory)

            feedbackCollector.collectImplicitFeedback(
                projectId = testProjectId,
                key = "min-confidence",
                action = UserAction.REJECTED_SUGGESTION
            )

            verify {
                memoryStore.save(match {
                    it.confidence >= 0.0
                })
            }
        }
    }

    // ========== 白名单校验测试 ==========

    @Nested
    @DisplayName("白名单校验测试")
    inner class ValidationTests {

        @Test
        @DisplayName("collectCorrection - 空 projectId 应抛出异常")
        fun `collectCorrection should throw when projectId is blank`() {
            assertThrows<IllegalArgumentException> {
                feedbackCollector.collectCorrection(
                    projectId = "",
                    key = "key",
                    originalValue = "原值",
                    correctedValue = "修正值"
                )
            }
        }

        @Test
        @DisplayName("collectExplicitPreference - 空 value 应抛出异常")
        fun `collectExplicitPreference should throw when value is blank`() {
            assertThrows<IllegalArgumentException> {
                feedbackCollector.collectExplicitPreference(
                    projectId = testProjectId,
                    key = "key",
                    value = ""
                )
            }
        }
    }

    // ========== 辅助方法 ==========

    private fun createTestMemory(
        projectId: String = testProjectId,
        key: String = "test-key",
        value: String = "test-value",
        memoryType: MemoryType = MemoryType.BUSINESS_RULE,
        confidence: Double = 0.5
    ): ProjectMemory {
        val now = Instant.now()
        return ProjectMemory(
            id = "${projectId}_$key",
            projectId = projectId,
            memoryType = memoryType,
            key = key,
            value = value,
            confidence = confidence,
            source = MemorySource.FEEDBACK_CORRECTION,
            createdAt = now,
            lastAccessedAt = now,
            accessCount = 0
        )
    }
}
