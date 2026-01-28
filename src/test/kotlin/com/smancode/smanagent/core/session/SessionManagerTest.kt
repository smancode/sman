package com.smancode.smanagent.core.session

import com.smancode.smanagent.base.TestDataFactory
import com.smancode.smanagent.model.session.SessionStatus
import com.smancode.smanagent.smancode.core.SessionManager
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@DisplayName("SessionManager 单元测试")
class SessionManagerTest {

    private lateinit var sessionManager: SessionManager

    @BeforeEach
    fun setUp() {
        sessionManager = SessionManager()
    }

    @Nested
    @DisplayName("会话注册")
    inner class RegisterSession {

        @Test
        @DisplayName("注册有效会话成功")
        fun testRegisterSession_validSession_success() {
            // Given
            val session = TestDataFactory.createTestSession("session-1", "project-1")

            // When
            sessionManager.registerSession(session)

            // Then
            val retrieved = sessionManager.getSession("session-1")
            assertNotNull(retrieved)
            assertEquals("session-1", retrieved.id)
        }

        @Test
        @DisplayName("注册 null 会话不抛异常")
        fun testRegisterSession_nullSession_noException() {
            // When & Then
            sessionManager.registerSession(null)
            // 不抛异常即为通过
        }

        @Test
        @DisplayName("注册没有 ID 的会话不抛异常")
        fun testRegisterSession_sessionWithoutId_noException() {
            // Given
            val session = TestDataFactory.createTestSession()
            session.id = null

            // When & Then
            sessionManager.registerSession(session)
        }

        @Test
        @DisplayName("重复注册相同会话覆盖")
        fun testRegisterSession_duplicateSession_overwrites() {
            // Given
            val session1 = TestDataFactory.createTestSession("session-1", "project-1")
            val session2 = TestDataFactory.createTestSession("session-1", "project-2")

            // When
            sessionManager.registerSession(session1)
            sessionManager.registerSession(session2)

            // Then
            val retrieved = sessionManager.getSession("session-1")
            assertEquals("project-2", retrieved?.projectInfo?.projectKey)
        }
    }

    @Nested
    @DisplayName("获取或注册会话")
    inner class GetOrRegister {

        @Test
        @DisplayName("获取已存在的会话")
        fun testGetOrRegister_existingSession_returnsExisting() {
            // Given
            val session = TestDataFactory.createTestSession("session-1", "project-1")
            sessionManager.registerSession(session)

            // When
            val result = sessionManager.getOrRegister(session)

            // Then
            assertNotNull(result)
            assertEquals("session-1", result.id)
        }

        @Test
        @DisplayName("注册不存在的会话")
        fun testGetOrRegister_nonExistingSession_registersAndReturns() {
            // Given
            val session = TestDataFactory.createTestSession("session-1", "project-1")

            // When
            val result = sessionManager.getOrRegister(session)

            // Then
            assertNotNull(result)
            assertEquals("session-1", result.id)
            val retrieved = sessionManager.getSession("session-1")
            assertEquals("session-1", retrieved?.id)
        }

        @Test
        @DisplayName("null 会话返回 null")
        fun testGetOrRegister_nullSession_returnsNull() {
            // When
            val result = sessionManager.getOrRegister(null)

            // Then
            assertNull(result)
        }

        @Test
        @DisplayName("没有 ID 的会话返回 null")
        fun testGetOrRegister_sessionWithoutId_returnsNull() {
            // Given
            val session = TestDataFactory.createTestSession()
            session.id = null

            // When
            val result = sessionManager.getOrRegister(session)

            // Then
            assertNull(result)
        }
    }

    @Nested
    @DisplayName("创建根会话")
    inner class CreateRootSession {

        @Test
        @DisplayName("创建根会话成功")
        fun testCreateRootSession_success() {
            // When
            val session = sessionManager.createRootSession("project-1")

            // Then
            assertNotNull(session.id)
            assertEquals("project-1", session.projectInfo?.projectKey)
            assertEquals(SessionStatus.IDLE, session.status)

            val retrieved = sessionManager.getSession(session.id!!)
            assertNotNull(retrieved)
        }

        @Test
        @DisplayName("创建多个根会话 ID 不同")
        fun testCreateRootSession_multipleSessions_differentIds() {
            // When
            val session1 = sessionManager.createRootSession("project-1")
            val session2 = sessionManager.createRootSession("project-2")

            // Then
            assertTrue(session1.id != session2.id)
        }
    }

    @Nested
    @DisplayName("创建子会话")
    inner class CreateChildSession {

        @Test
        @DisplayName("创建子会话成功")
        fun testCreateChildSession_success() {
            // Given
            val parent = sessionManager.createRootSession("project-1")

            // When
            val child = sessionManager.createChildSession(parent.id!!)

            // Then
            assertNotNull(child.id)
            assertEquals(parent.projectInfo, child.projectInfo)
            assertEquals(parent.webSocketSessionId, child.webSocketSessionId)

            val retrieved = sessionManager.getSession(child.id!!)
            assertNotNull(retrieved)

            val parentId = sessionManager.getParentSessionId(child.id!!)
            assertEquals(parent.id, parentId)
        }

        @Test
        @DisplayName("父会话不存在抛异常")
        fun testCreateChildSession_nonExistingParent_throwsException() {
            // When & Then
            val exception = kotlin.runCatching {
                sessionManager.createChildSession("non-existing")
            }.exceptionOrNull()

            assertTrue(exception is IllegalArgumentException)
            assertTrue(exception?.message?.contains("父会话不存在") == true)
        }

        @Test
        @DisplayName("子会话不继承父会话消息")
        fun testCreateChildSession_doesNotInheritMessages() {
            // Given
            val parent = sessionManager.createRootSession("project-1")
            val message = TestDataFactory.createUserMessage("Parent message")
            parent.messages.add(message)

            // When
            val child = sessionManager.createChildSession(parent.id!!)

            // Then
            assertTrue(child.messages.isEmpty())
        }
    }

    @Nested
    @DisplayName("获取会话")
    inner class GetSession {

        @Test
        @DisplayName("获取存在的会话")
        fun testGetSession_existing_returnsSession() {
            // Given
            val session = sessionManager.createRootSession("project-1")

            // When
            val retrieved = sessionManager.getSession(session.id!!)

            // Then
            assertNotNull(retrieved)
            assertEquals(session.id, retrieved.id)
        }

        @Test
        @DisplayName("获取不存在的会话返回 null")
        fun testGetSession_nonExisting_returnsNull() {
            // When
            val retrieved = sessionManager.getSession("non-existing")

            // Then
            assertNull(retrieved)
        }
    }

    @Nested
    @DisplayName("获取或创建会话")
    inner class GetOrCreateSession {

        @Test
        @DisplayName("获取已存在的会话")
        fun testGetOrCreateSession_existing_returnsExisting() {
            // Given
            val created = sessionManager.createRootSession("project-1")

            // When
            val retrieved = sessionManager.getOrCreateSession(created.id, "project-1")

            // Then
            assertEquals(created.id, retrieved.id)
        }

        @Test
        @DisplayName("sessionId 为空创建新会话")
        fun testGetOrCreateSession_nullSessionId_createsNew() {
            // When
            val session = sessionManager.getOrCreateSession(null, "project-1")

            // Then
            assertNotNull(session.id)
            assertEquals("project-1", session.projectInfo?.projectKey)
        }

        @Test
        @DisplayName("sessionId 为空字符串创建新会话")
        fun testGetOrCreateSession_emptySessionId_createsNew() {
            // When
            val session = sessionManager.getOrCreateSession("", "project-1")

            // Then
            assertNotNull(session.id)
            assertEquals("project-1", session.projectInfo?.projectKey)
        }

        @Test
        @DisplayName("不存在的 sessionId 创建新会话")
        fun testGetOrCreateSession_nonExisting_createsNew() {
            // When
            val session = sessionManager.getOrCreateSession("new-session", "project-1")

            // Then
            assertEquals("new-session", session.id)
        }
    }

    @Nested
    @DisplayName("结束会话")
    inner class EndSession {

        @Test
        @DisplayName("结束会话状态变为 IDLE")
        fun testEndSession_success() {
            // Given
            val session = sessionManager.createRootSession("project-1")
            session.status = SessionStatus.BUSY

            // When
            sessionManager.endSession(session.id!!)

            // Then
            assertEquals(SessionStatus.IDLE, session.status)
        }

        @Test
        @DisplayName("结束不存在的会话不抛异常")
        fun testEndSession_nonExisting_noException() {
            // When & Then
            sessionManager.endSession("non-existing")
        }
    }

    @Nested
    @DisplayName("获取父会话ID")
    inner class GetParentSessionId {

        @Test
        @DisplayName("获取子会话的父会话ID")
        fun testGetParentSessionId_childSession_returnsParentId() {
            // Given
            val parent = sessionManager.createRootSession("project-1")
            val child = sessionManager.createChildSession(parent.id!!)

            // When
            val parentId = sessionManager.getParentSessionId(child.id!!)

            // Then
            assertEquals(parent.id, parentId)
        }

        @Test
        @DisplayName("根会话没有父会话ID")
        fun testGetParentSessionId_rootSession_returnsNull() {
            // Given
            val session = sessionManager.createRootSession("project-1")

            // When
            val parentId = sessionManager.getParentSessionId(session.id!!)

            // Then
            assertNull(parentId)
        }
    }

    @Nested
    @DisplayName("获取根会话ID")
    inner class GetRootSessionId {

        @Test
        @DisplayName("根会话返回自身ID")
        fun testGetRootSessionId_rootSession_returnsOwnId() {
            // Given
            val session = sessionManager.createRootSession("project-1")

            // When
            val rootId = sessionManager.getRootSessionId(session.id!!)

            // Then
            assertEquals(session.id, rootId)
        }

        @Test
        @DisplayName("子会话返回根会话ID")
        fun testGetRootSessionId_childSession_returnsRootId() {
            // Given
            val root = sessionManager.createRootSession("project-1")
            val child = sessionManager.createChildSession(root.id!!)

            // When
            val rootId = sessionManager.getRootSessionId(child.id!!)

            // Then
            assertEquals(root.id, rootId)
        }

        @Test
        @DisplayName("多层嵌套返回根会话ID")
        fun testGetRootSessionId_nestedChildren_returnsRootId() {
            // Given
            val root = sessionManager.createRootSession("project-1")
            val child1 = sessionManager.createChildSession(root.id!!)
            val child2 = sessionManager.createChildSession(child1.id!!)

            // When
            val rootId = sessionManager.getRootSessionId(child2.id!!)

            // Then
            assertEquals(root.id, rootId)
        }
    }

    @Nested
    @DisplayName("清理子会话")
    inner class CleanupChildSession {

        @Test
        @DisplayName("清理子会话成功")
        fun testCleanupChildSession_success() {
            // Given
            val parent = sessionManager.createRootSession("project-1")
            val child = sessionManager.createChildSession(parent.id!!)
            child.messages.add(TestDataFactory.createUserMessage("Test"))

            // When
            sessionManager.cleanupChildSession(child.id!!)

            // Then
            val retrieved = sessionManager.getSession(child.id!!)
            assertNull(retrieved)
            assertNull(sessionManager.getParentSessionId(child.id!!))
        }

        @Test
        @DisplayName("清理不存在的子会话不抛异常")
        fun testCleanupChildSession_nonExisting_noException() {
            // When & Then
            sessionManager.cleanupChildSession("non-existing")
        }
    }

    @Nested
    @DisplayName("清理会话")
    inner class CleanupSession {

        @Test
        @DisplayName("清理根会话及其子会话")
        fun testCleanupSession_rootWithChildren_removesAll() {
            // Given
            val root = sessionManager.createRootSession("project-1")
            val child1 = sessionManager.createChildSession(root.id!!)
            val child2 = sessionManager.createChildSession(root.id!!)

            // When
            sessionManager.cleanupSession(root.id!!)

            // Then
            assertNull(sessionManager.getSession(root.id!!))
            assertNull(sessionManager.getSession(child1.id!!))
            assertNull(sessionManager.getSession(child2.id!!))
        }

        @Test
        @DisplayName("清理子会话不影响其他会话")
        fun testCleanupSession_child_doesNotAffectOthers() {
            // Given
            val root1 = sessionManager.createRootSession("project-1")
            val root2 = sessionManager.createRootSession("project-2")
            val child = sessionManager.createChildSession(root1.id!!)

            // When
            sessionManager.cleanupSession(child.id!!)

            // Then
            assertNotNull(sessionManager.getSession(root1.id!!))
            assertNotNull(sessionManager.getSession(root2.id!!))
        }
    }

    @Nested
    @DisplayName("获取统计信息")
    inner class GetStats {

        @Test
        @DisplayName("空管理器统计正确")
        fun testGetStats_emptyManager_allZero() {
            // When
            val stats = sessionManager.getStats()

            // Then
            assertEquals(0, stats.total)
            assertEquals(0, stats.root)
            assertEquals(0, stats.child)
        }

        @Test
        @DisplayName("只有根会话统计正确")
        fun testGetStats_onlyRoot_correct() {
            // Given
            sessionManager.createRootSession("project-1")
            sessionManager.createRootSession("project-2")

            // When
            val stats = sessionManager.getStats()

            // Then
            assertEquals(2, stats.total)
            assertEquals(2, stats.root)
            assertEquals(0, stats.child)
        }

        @Test
        @DisplayName("有子会话统计正确")
        fun testGetStats_withChildren_correct() {
            // Given
            val root = sessionManager.createRootSession("project-1")
            sessionManager.createChildSession(root.id!!)
            sessionManager.createChildSession(root.id!!)

            // When
            val stats = sessionManager.getStats()

            // Then
            assertEquals(3, stats.total)
            assertEquals(1, stats.root)
            assertEquals(2, stats.child)
        }
    }
}
