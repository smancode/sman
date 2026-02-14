package com.smancode.sman.evolution.model

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * LearningRecord 数据类测试
 */
@DisplayName("LearningRecord 测试")
class LearningRecordTest {

    @Nested
    @DisplayName("equals 和 hashCode 测试")
    inner class EqualsHashCodeTest {

        @Test
        @DisplayName("相同 ID 的记录应相等")
        fun `records with same id should be equal`() {
            // Given: 两个相同 ID 的记录
            val record1 = createLearningRecord(id = "test-id-1")
            val record2 = createLearningRecord(id = "test-id-1")

            // When & Then: 应该相等
            assertEquals(record1, record2)
            assertEquals(record1.hashCode(), record2.hashCode())
        }

        @Test
        @DisplayName("不同 ID 的记录不应相等")
        fun `records with different id should not be equal`() {
            // Given: 两个不同 ID 的记录
            val record1 = createLearningRecord(id = "test-id-1")
            record1.questionVector = floatArrayOf(0.1f, 0.2f, 0.3f)
            val record2 = createLearningRecord(id = "test-id-2")
            record2.questionVector = floatArrayOf(0.1f, 0.2f, 0.3f)

            // When & Then: 不应该相等
            assertFalse(record1 == record2)
        }

        @Test
        @DisplayName("向量字段不参与 equals 比较")
        fun `vector fields should not affect equality`() {
            // Given: 两个记录有相同的序列化字段但不同的向量
            val record1 = createLearningRecord(id = "test-id")
            record1.questionVector = floatArrayOf(0.1f, 0.2f, 0.3f)
            record1.answerVector = floatArrayOf(0.4f, 0.5f, 0.6f)

            val record2 = createLearningRecord(id = "test-id")
            record2.questionVector = floatArrayOf(0.9f, 0.8f, 0.7f)
            record2.answerVector = floatArrayOf(0.6f, 0.5f, 0.4f)

            // When & Then: 向量不同但应该相等
            assertTrue(record1 == record2)
            assertEquals(record1.hashCode(), record2.hashCode())
        }
    }

    @Nested
    @DisplayName("向量字段测试")
    inner class VectorFieldTest {

        @Test
        @DisplayName("向量字段默认为 null")
        fun `vector fields should be null by default`() {
            // Given: 创建一个新记录
            val record = createLearningRecord()

            // When & Then: 向量字段应为 null
            assertEquals(null, record.questionVector)
            assertEquals(null, record.answerVector)
        }

        @Test
        @DisplayName("向量字段可以正确设置和读取")
        fun `vector fields can be set and retrieved`() {
            // Given: 创建记录并设置向量
            val record = createLearningRecord()
            val questionVec = floatArrayOf(0.1f, 0.2f, 0.3f)
            val answerVec = floatArrayOf(0.4f, 0.5f, 0.6f)

            // When: 设置向量字段
            record.questionVector = questionVec
            record.answerVector = answerVec

            // Then: 应该能正确读取
            assertEquals(questionVec.toList(), record.questionVector?.toList())
            assertEquals(answerVec.toList(), record.answerVector?.toList())
        }
    }

    @Nested
    @DisplayName("探索路径测试")
    inner class ExplorationPathTest {

        @Test
        @DisplayName("探索路径应正确记录工具调用步骤")
        fun `exploration path should record tool call steps`() {
            // Given: 创建工具调用步骤
            val steps = listOf(
                ToolCallStep(
                    toolName = "read_file",
                    parameters = mapOf("path" to "/src/main.kt"),
                    resultSummary = "读取成功",
                    timestamp = System.currentTimeMillis()
                ),
                ToolCallStep(
                    toolName = "search_code",
                    parameters = mapOf("pattern" to "fun main"),
                    resultSummary = "找到 3 个匹配",
                    timestamp = System.currentTimeMillis()
                )
            )

            // When: 创建包含探索路径的记录
            val record = createLearningRecord(explorationPath = steps)

            // Then: 路径应正确保存
            assertEquals(2, record.explorationPath.size)
            assertEquals("read_file", record.explorationPath[0].toolName)
            assertEquals("search_code", record.explorationPath[1].toolName)
        }

        @Test
        @DisplayName("空探索路径应被允许")
        fun `empty exploration path should be allowed`() {
            // When: 创建空探索路径的记录
            val record = createLearningRecord(explorationPath = emptyList())

            // Then: 路径应为空
            assertTrue(record.explorationPath.isEmpty())
        }
    }

    /**
     * 创建测试用 LearningRecord
     */
    private fun createLearningRecord(
        id: String = "test-id",
        projectKey: String = "test-project",
        question: String = "测试问题",
        answer: String = "测试答案",
        explorationPath: List<ToolCallStep> = emptyList(),
        confidence: Double = 0.9,
        sourceFiles: List<String> = emptyList(),
        relatedRecords: List<String> = emptyList(),
        tags: List<String> = emptyList(),
        domain: String? = null,
        metadata: Map<String, String> = emptyMap()
    ): LearningRecord {
        return LearningRecord(
            id = id,
            projectKey = projectKey,
            createdAt = System.currentTimeMillis(),
            question = question,
            questionType = QuestionType.BUSINESS_LOGIC,
            answer = answer,
            explorationPath = explorationPath,
            confidence = confidence,
            sourceFiles = sourceFiles,
            relatedRecords = relatedRecords,
            tags = tags,
            domain = domain,
            metadata = metadata
        )
    }
}
