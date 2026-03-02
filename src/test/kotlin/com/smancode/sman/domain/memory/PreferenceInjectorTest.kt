package com.smancode.sman.domain.memory

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * PreferenceInjector 测试
 *
 * TDD: 先写测试，再实现功能
 */
@DisplayName("用户偏好注入器测试")
class PreferenceInjectorTest {

    @TempDir
    lateinit var tempDir: java.io.File

    /**
     * 测试：加载用户偏好
     */
    @Test
    @DisplayName("加载用户偏好 - 应返回格式化的偏好字符串")
    fun testLoadPreferences_returnsFormattedPreferences() {
        // Given: 创建内存存储和测试数据
        val store = FileBasedMemoryStore(tempDir.absolutePath)
        
        // 添加测试偏好
        val preference = ProjectMemory(
            id = UUID.randomUUID().toString(),
            projectId = "test-project",
            memoryType = MemoryType.USER_PREFERENCE,
            key = "code_style",
            value = "使用 val 而不是 var",
            confidence = 0.9,
            source = MemorySource.EXPLICIT_INPUT,
            createdAt = Instant.now(),
            lastAccessedAt = Instant.now(),
            accessCount = 1
        )
        store.save(preference)

        // When: 创建 PreferenceInjector 并加载偏好
        val injector = PreferenceInjector(store)
        val result = injector.injectPreferences("test-project")

        // Then: 验证结果
        assertTrue(result.contains("code_style"), "应包含偏好键")
    }

    /**
     * 测试：空偏好
     */
    @Test
    @DisplayName("空偏好 - 应返回空字符串")
    fun testEmptyPreferences_returnsEmptyString() {
        // Given: 空的存储
        val store = FileBasedMemoryStore(tempDir.absolutePath)
        
        // When: 加载偏好
        val injector = PreferenceInjector(store)
        val result = injector.injectPreferences("empty-project")

        // Then: 应返回空字符串
        assertEquals("", result, "空项目应返回空字符串")
    }

    /**
     * 测试：过滤低置信度
     */
    @Test
    @DisplayName("过滤低置信度 - 只返回置信度 >= 0.5 的偏好")
    fun testFilterLowConfidence() {
        // Given: 创建不同置信度的偏好
        val store = FileBasedMemoryStore(tempDir.absolutePath)
        
        val lowConfidence = ProjectMemory(
            id = UUID.randomUUID().toString(),
            projectId = "test-project",
            memoryType = MemoryType.USER_PREFERENCE,
            key = "unreliable",
            value = "不可靠的偏好",
            confidence = 0.3,
            source = MemorySource.IMPLICIT_LEARNING,
            createdAt = Instant.now(),
            lastAccessedAt = Instant.now(),
            accessCount = 1
        )
        
        val reliable = ProjectMemory(
            id = UUID.randomUUID().toString(),
            projectId = "test-project",
            memoryType = MemoryType.USER_PREFERENCE,
            key = "reliable",
            value = "可靠的偏好",
            confidence = 0.8,
            source = MemorySource.EXPLICIT_INPUT,
            createdAt = Instant.now(),
            lastAccessedAt = Instant.now(),
            accessCount = 5
        )
        
        store.save(lowConfidence)
        store.save(reliable)

        // When: 加载偏好（使用默认阈值 0.5）
        val injector = PreferenceInjector(store)
        val result = injector.injectPreferences("test-project", minConfidence = 0.5)

        // Then: 只包含可靠的偏好
        assertTrue(result.contains("reliable"), "应包含可靠偏好")
        assertTrue(!result.contains("unreliable"), "不应包含低置信度偏好")
    }
}
