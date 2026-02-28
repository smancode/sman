package com.smancode.sman.domain.puzzle

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 经验库测试
 *
 * 验证系统能积累和复用分析"屎山代码"的经验
 */
class ExperienceStoreTest {

    @Test
    fun `should load builtin experiences`() {
        // Given
        val store = ExperienceStore()

        // When
        val experiences = store.getAll()

        // Then
        println("=== 内置经验 ===")
        experiences.forEach { exp ->
            println("[${exp.type}] ${exp.scenario.take(50)}...")
            println("  模式: ${exp.pattern}")
            println("  置信度: ${exp.confidence}")
        }

        // 验证内置经验
        assertTrue(experiences.isNotEmpty(), "应该有内置经验")
        assertTrue(
            experiences.any { it.type == ExperienceType.CONFIG_LINK },
            "应该有 CONFIG_LINK 类型的经验"
        )
    }

    @Test
    fun `should find applicable experience for transaction pattern`() {
        // Given
        val store = ExperienceStore()

        // When: 搜索 transactionService.execute 模式
        val applicable = store.findApplicable(
            "transactionService.execute(\"2001\", context)",
            "代码调用看起来没有完成"
        )

        // Then
        println("=== 适用的经验 ===")
        applicable.forEach { exp ->
            println("- ${exp.id}: ${exp.scenario.take(50)}...")
        }

        // 应该找到 CONFIG_LINK 类型的经验
        assertTrue(
            applicable.any { it.type == ExperienceType.CONFIG_LINK },
            "应该找到 CONFIG_LINK 经验"
        )
    }

    @Test
    fun `should find experience for MyBatis Mapper`() {
        // Given
        val store = ExperienceStore()

        // When: 搜索 Mapper 接口
        val applicable = store.findApplicable(
            "@Mapper interface UserMapper",
            "MyBatis"
        )

        // Then
        println("=== Mapper 相关经验 ===")
        applicable.forEach { exp ->
            println("- ${exp.id}: ${exp.scenario}")
        }

        assertTrue(applicable.isNotEmpty(), "应该找到 Mapper 相关经验")
    }

    @Test
    fun `should add new experience from user hint`() {
        // Given
        val store = ExperienceStore()
        val initialCount = store.getAll().size

        // When: 添加新经验
        store.addExperience(AnalysisExperience(
            id = "exp-user-001",
            type = ExperienceType.CONFIG_LINK,
            source = ExperienceSource.USER_HINT,
            scenario = "用户提示：Spring Event 是通过 @EventListener 注解隐式调用的",
            pattern = "@EventListener|ApplicationEvent",
            solution = "搜索发布该事件的地方，建立隐式调用关系"
        ))

        // Then
        val newCount = store.getAll().size
        assertEquals(initialCount + 1, newCount, "经验数量应该 +1")
    }

    @Test
    fun `should increase confidence on success`() {
        // Given
        val store = ExperienceStore()
        val experience = store.getAll().first()
        val initialConfidence = experience.confidence

        // When: 记录成功
        store.recordSuccess(experience.id)

        // Then
        val updated = store.getAll().first { it.id == experience.id }
        assertTrue(
            updated.confidence >= initialConfidence,
            "成功后置信度应该增加或保持"
        )
        println("置信度变化: $initialConfidence -> ${updated.confidence}")
    }

    @Test
    fun `should decrease confidence on failure`() {
        // Given
        val store = ExperienceStore()
        val experience = store.getAll().first()
        val initialConfidence = experience.confidence

        // When: 记录失败
        store.recordFailure(experience.id)

        // Then
        val updated = store.getAll().first { it.id == experience.id }
        assertTrue(
            updated.confidence <= initialConfidence,
            "失败后置信度应该降低或保持"
        )
        println("置信度变化: $initialConfidence -> ${updated.confidence}")
    }

    @Test
    fun `should format experiences for prompt`() {
        // Given
        val store = ExperienceStore()

        // When
        val formatted = store.formatForPrompt()

        // Then
        println("=== 格式化后的 Prompt 片段 ===")
        println(formatted)

        assertTrue(formatted.isNotEmpty(), "应该有内容")
        assertTrue(formatted.contains("经验"), "应该包含'经验'")
        assertTrue(formatted.contains("场景"), "应该包含'场景'")
    }
}
