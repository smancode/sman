package com.smancode.sman.model.message

import com.smancode.sman.base.TestDataFactory
import com.smancode.sman.model.part.TextPart
import com.smancode.sman.model.part.ToolPart
import com.smancode.sman.tools.ToolResult
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@DisplayName("Message 单元测试")
class MessageTest {

    private lateinit var message: Message

    @BeforeEach
    fun setUp() {
        message = TestDataFactory.createTestMessage()
    }

    @Nested
    @DisplayName("构造函数")
    inner class Constructors {

        @Test
        @DisplayName("默认构造函数创建空消息")
        fun testDefaultConstructor_createsEmptyMessage() {
            // When
            val msg = Message()

            // Then
            assertTrue(msg.parts.isEmpty())
            assertTrue(msg.createdTime <= Instant.now())
            assertTrue(msg.updatedTime <= Instant.now())
        }

        @Test
        @DisplayName("参数构造函数设置基本属性")
        fun testParameterConstructor_setsBasicProperties() {
            // When
            val msg = Message("msg-1", "session-1", Role.USER, "Hello")

            // Then
            assertEquals("msg-1", msg.id)
            assertEquals("session-1", msg.sessionId)
            assertEquals(Role.USER, msg.role)
            assertEquals("Hello", msg.content)
        }
    }

    @Nested
    @DisplayName("Part 管理")
    inner class PartManagement {

        @Test
        @DisplayName("添加 Part 成功")
        fun testAddPart_success() {
            // Given
            val part = TestDataFactory.createTextPart("Test content")
            val initialUpdateTime = message.updatedTime

            // When
            Thread.sleep(10) // 确保时间不同
            message.addPart(part)

            // Then
            assertEquals(1, message.parts.size)
            assertEquals(part, message.parts[0])
            assertTrue(message.updatedTime.isAfter(initialUpdateTime))
        }

        @Test
        @DisplayName("添加多个 Part")
        fun testAddPart_multiple_partsAdded() {
            // Given
            val textPart = TestDataFactory.createTextPart("Text")
            val toolPart = TestDataFactory.createToolPart()

            // When
            message.addPart(textPart)
            message.addPart(toolPart)

            // Then
            assertEquals(2, message.parts.size)
        }

        @Test
        @DisplayName("移除存在的 Part 成功")
        fun testRemovePart_existing_success() {
            // Given
            val part = TestDataFactory.createTextPart("Content")
            message.addPart(part)
            val initialUpdateTime = message.updatedTime

            // When
            Thread.sleep(10)
            val removed = message.removePart(part.id!!)

            // Then
            assertEquals(part, removed)
            assertTrue(message.parts.isEmpty())
            assertTrue(message.updatedTime.isAfter(initialUpdateTime))
        }

        @Test
        @DisplayName("移除不存在的 Part 返回 null")
        fun testRemovePart_nonExisting_returnsNull() {
            // When
            val removed = message.removePart("non-existing")

            // Then
            assertNull(removed)
        }

        @Test
        @DisplayName("获取存在的 Part")
        fun testGetPart_existing_returnsPart() {
            // Given
            val part = TestDataFactory.createTextPart("Content")
            message.addPart(part)

            // When
            val retrieved = message.getPart(part.id!!)

            // Then
            assertEquals(part, retrieved)
        }

        @Test
        @DisplayName("获取不存在的 Part 返回 null")
        fun testGetPart_nonExisting_returnsNull() {
            // When
            val retrieved = message.getPart("non-existing")

            // Then
            assertNull(retrieved)
        }
    }

    @Nested
    @DisplayName("时间戳管理")
    inner class TimestampManagement {

        @Test
        @DisplayName("touch() 更新 updatedTime")
        fun testTouch_updatesUpdatedTime() {
            // Given
            val initialTime = message.updatedTime

            // When
            Thread.sleep(10)
            message.touch()

            // Then
            assertTrue(message.updatedTime.isAfter(initialTime))
        }

        @Test
        @DisplayName("设置 content 触发 touch()")
        fun testSetContent_triggersTouch() {
            // Given
            val initialTime = message.updatedTime

            // When
            Thread.sleep(10)
            message.content = "New content"

            // Then
            assertTrue(message.updatedTime.isAfter(initialTime))
        }

        @Test
        @DisplayName("设置 parts 触发 touch()")
        fun testSetParts_triggersTouch() {
            // Given
            val initialTime = message.updatedTime

            // When
            Thread.sleep(10)
            message.parts = mutableListOf()

            // Then
            assertTrue(message.updatedTime.isAfter(initialTime))
        }

        @Test
        @DisplayName("getTotalDuration() 返回正确时长")
        fun testGetTotalDuration_returnsCorrectDuration() {
            // Given
            val msg = Message()
            val createdTime = msg.createdTime
            val expectedDuration = 100L

            // When
            msg.updatedTime = createdTime.plus(expectedDuration, ChronoUnit.MILLIS)

            // Then
            val duration = msg.getTotalDuration()
            assertTrue(duration >= expectedDuration)
        }
    }

    @Nested
    @DisplayName("消息类型检查")
    inner class MessageTypeChecks {

        @Test
        @DisplayName("用户消息检查")
        fun testIsUserMessage_correct() {
            // Given
            message.role = Role.USER

            // When
            val isUser = message.isUserMessage()

            // Then
            assertTrue(isUser)
        }

        @Test
        @DisplayName("助手消息检查")
        fun testIsAssistantMessage_correct() {
            // Given
            message.role = Role.ASSISTANT

            // When
            val isAssistant = message.isAssistantMessage()

            // Then
            assertTrue(isAssistant)
        }

        @Test
        @DisplayName("系统消息检查")
        fun testIsSystemMessage_correct() {
            // Given
            message.role = Role.SYSTEM

            // When
            val isSystem = message.isSystemMessage()

            // Then
            assertTrue(isSystem)
        }

        @Test
        @DisplayName("空角色检查返回 false")
        fun testTypeChecks_nullRole_returnsFalse() {
            // Given
            message.role = null

            // When & Then
            assertTrue(!message.isUserMessage())
            assertTrue(!message.isAssistantMessage())
            assertTrue(!message.isSystemMessage())
        }
    }
}
