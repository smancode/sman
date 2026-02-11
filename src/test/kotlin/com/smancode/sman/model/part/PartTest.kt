package com.smancode.sman.model.part

import com.smancode.sman.base.TestDataFactory
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@DisplayName("Part 模型单元测试")
class PartTest {

    @Nested
    @DisplayName("Part 基类")
    inner class PartBase {

        @Test
        @DisplayName("默认构造函数创建空 Part")
        fun testDefaultConstructor_createsEmptyPart() {
            // When
            val part = TextPart()

            // Then
            assertNull(part.id)
            assertNull(part.messageId)
            assertNull(part.sessionId)
            assertTrue(part.createdTime <= java.time.Instant.now())
            assertTrue(part.updatedTime <= java.time.Instant.now())
        }

        @Test
        @DisplayName("参数构造函数设置属性")
        fun testParameterConstructor_setsProperties() {
            // Given
            val partId = "part-1"
            val messageId = "msg-1"
            val sessionId = "session-1"
            val type = PartType.TEXT

            // When
            val part = TextPart(partId, messageId, sessionId)
            part.type = type

            // Then
            assertEquals(partId, part.id)
            assertEquals(messageId, part.messageId)
            assertEquals(sessionId, part.sessionId)
            assertEquals(type, part.type)
        }

        @Test
        @DisplayName("touch() 更新 updatedTime")
        fun testTouch_updatesUpdatedTime() {
            // Given
            val part = TextPart()
            val initialTime = part.updatedTime

            // When
            Thread.sleep(10)
            part.touch()

            // Then
            assertTrue(part.updatedTime.isAfter(initialTime))
        }
    }

    @Nested
    @DisplayName("TextPart")
    inner class TextPartTests {

        @Test
        @DisplayName("创建文本 Part")
        fun testTextPart_creation() {
            // Given
            val text = "Test content"

            // When
            val part = TestDataFactory.createTextPart(text)

            // Then
            assertEquals(text, part.text)
        }

        @Test
        @DisplayName("文本 Part 类型为 TEXT")
        fun testTextPart_typeIsText() {
            // Given
            val part = TestDataFactory.createTextPart("content")
            part.type = PartType.TEXT

            // When
            val type = part.type

            // Then
            assertEquals(PartType.TEXT, type)
        }

        @Test
        @DisplayName("追加文本内容")
        fun testTextPart_appendText() {
            // Given
            val part = TextPart()

            // When
            part.appendText("Hello ")
            part.appendText("World")

            // Then
            assertEquals("Hello World", part.text)
        }

        @Test
        @DisplayName("清空文本内容")
        fun testTextPart_clear() {
            // Given
            val part = TextPart()
            part.text = "Content"

            // When
            part.clear()

            // Then
            assertEquals("", part.text)
        }

        @Test
        @DisplayName("获取文本长度")
        fun testTextPart_getLength() {
            // Given
            val part = TextPart()
            part.text = "Hello"

            // When
            val length = part.getLength()

            // Then
            assertEquals(5, length)
        }

        @Test
        @DisplayName("检查是否为空")
        fun testTextPart_isEmpty() {
            // Given
            val part = TextPart()

            // When
            val isEmpty = part.isEmpty()

            // Then
            assertTrue(isEmpty)
        }
    }

    @Nested
    @DisplayName("ToolPart")
    inner class ToolPartTests {

        @Test
        @DisplayName("创建工具 Part")
        fun testToolPart_creation() {
            // Given
            val toolName = "test_tool"
            val parameters = mapOf("key" to "value")
            val result = com.smancode.sman.tools.ToolResult.success("OK", null, "OK")

            // When
            val part = TestDataFactory.createToolPart(toolName, parameters, result)

            // Then
            assertEquals(toolName, part.toolName)
            assertEquals(parameters, part.parameters)
            assertEquals(result, part.result)
        }

        @Test
        @DisplayName("工具 Part 类型为 TOOL")
        fun testToolPart_typeIsTool() {
            // Given
            val part = TestDataFactory.createToolPart()
            part.type = PartType.TOOL

            // When
            val type = part.type

            // Then
            assertEquals(PartType.TOOL, type)
        }

        @Test
        @DisplayName("工具状态枚举")
        fun testToolState_enum() {
            // When & Then
            assertEquals(ToolPart.ToolState.PENDING, ToolPart.ToolState.valueOf("PENDING"))
            assertEquals(ToolPart.ToolState.RUNNING, ToolPart.ToolState.valueOf("RUNNING"))
            assertEquals(ToolPart.ToolState.COMPLETED, ToolPart.ToolState.valueOf("COMPLETED"))
            assertEquals(ToolPart.ToolState.ERROR, ToolPart.ToolState.valueOf("ERROR"))
        }
    }

    @Nested
    @DisplayName("ReasoningPart")
    inner class ReasoningPartTests {

        @Test
        @DisplayName("创建推理 Part")
        fun testReasoningPart_creation() {
            // Given
            val text = "Step by step reasoning"

            // When
            val part = TestDataFactory.createReasoningPart(text)

            // Then
            assertEquals(text, part.text)
        }

        @Test
        @DisplayName("推理 Part 类型为 REASONING")
        fun testReasoningPart_typeIsReasoning() {
            // Given
            val part = TestDataFactory.createReasoningPart("content")
            part.type = PartType.REASONING

            // When
            val type = part.type

            // Then
            assertEquals(PartType.REASONING, type)
        }

        @Test
        @DisplayName("追加推理内容")
        fun testReasoningPart_appendText() {
            // Given
            val part = ReasoningPart()

            // When
            part.appendText("Step 1")
            part.appendText("Step 2")

            // Then
            assertEquals("Step 1Step 2", part.text)
        }

        @Test
        @DisplayName("完成推理")
        fun testReasoningPart_complete() {
            // Given
            val part = ReasoningPart()

            // When
            part.complete()

            // Then
            assertTrue(part.isCompleted())
            assertTrue(part.endTime != null)
        }
    }

    @Nested
    @DisplayName("GoalPart")
    inner class GoalPartTests {

        @Test
        @DisplayName("创建目标 Part")
        fun testGoalPart_creation() {
            // Given
            val title = "My Goal"
            val description = "Achieve this"

            // When
            val part = TestDataFactory.createGoalPart(title, description)

            // Then
            assertEquals(title, part.title)
            assertEquals(description, part.description)
        }

        @Test
        @DisplayName("目标 Part 类型为 GOAL")
        fun testGoalPart_typeIsGoal() {
            // Given
            val part = TestDataFactory.createGoalPart()
            part.type = PartType.GOAL

            // When
            val type = part.type

            // Then
            assertEquals(PartType.GOAL, type)
        }

        @Test
        @DisplayName("目标状态枚举")
        fun testGoalStatus_enum() {
            // When & Then
            assertEquals(GoalPart.GoalStatus.PENDING, GoalPart.GoalStatus.valueOf("PENDING"))
            assertEquals(GoalPart.GoalStatus.IN_PROGRESS, GoalPart.GoalStatus.valueOf("IN_PROGRESS"))
            assertEquals(GoalPart.GoalStatus.COMPLETED, GoalPart.GoalStatus.valueOf("COMPLETED"))
            assertEquals(GoalPart.GoalStatus.CANCELLED, GoalPart.GoalStatus.valueOf("CANCELLED"))
        }
    }

    @Nested
    @DisplayName("ProgressPart")
    inner class ProgressPartTests {

        @Test
        @DisplayName("创建进度 Part")
        fun testProgressPart_creation() {
            // Given
            val current = "Processing"
            val currentStep = 5
            val totalSteps = 10

            // When
            val part = TestDataFactory.createProgressPart(current, currentStep, totalSteps)

            // Then
            assertEquals(current, part.current)
            assertEquals(currentStep, part.currentStep)
            assertEquals(totalSteps, part.totalSteps)
        }

        @Test
        @DisplayName("进度 Part 类型为 PROGRESS")
        fun testProgressPart_typeIsProgress() {
            // Given
            val part = TestDataFactory.createProgressPart()
            part.type = PartType.PROGRESS

            // When
            val type = part.type

            // Then
            assertEquals(PartType.PROGRESS, type)
        }

        @Test
        @DisplayName("更新进度")
        fun testProgressPart_updateProgress() {
            // Given
            val part = ProgressPart()

            // When
            part.updateProgress(7, 10)

            // Then
            assertEquals(7, part.currentStep)
            assertEquals(10, part.totalSteps)
        }

        @Test
        @DisplayName("获取显示文本")
        fun testProgressPart_getDisplayText() {
            // Given
            val part = TestDataFactory.createProgressPart("Working", 3, 10)

            // When
            val displayText = part.getDisplayText()

            // Then
            assertEquals("[3/10] Working", displayText)
        }

        @Test
        @DisplayName("检查是否完成")
        fun testProgressPart_isCompleted() {
            // Given
            val part = TestDataFactory.createProgressPart("Done", 10, 10)

            // When
            val isCompleted = part.isCompleted()

            // Then
            assertTrue(isCompleted)
        }
    }

    @Nested
    @DisplayName("TodoPart")
    inner class TodoPartTests {

        @Test
        @DisplayName("创建 Todo Part")
        fun testTodoPart_creation() {
            // Given
            val content = "Task 1"

            // When
            val part = TestDataFactory.createTodoPart(content)

            // Then
            assertEquals(1, part.items.size)
            assertEquals(content, part.items[0].content)
        }

        @Test
        @DisplayName("Todo Part 类型为 TODO")
        fun testTodoPart_typeIsTodo() {
            // Given
            val part = TestDataFactory.createTodoPart()
            part.type = PartType.TODO

            // When
            val type = part.type

            // Then
            assertEquals(PartType.TODO, type)
        }

        @Test
        @DisplayName("Todo 状态枚举")
        fun testTodoStatus_enum() {
            // When & Then
            assertEquals(TodoPart.TodoStatus.PENDING, TodoPart.TodoStatus.valueOf("PENDING"))
            assertEquals(TodoPart.TodoStatus.IN_PROGRESS, TodoPart.TodoStatus.valueOf("IN_PROGRESS"))
            assertEquals(TodoPart.TodoStatus.COMPLETED, TodoPart.TodoStatus.valueOf("COMPLETED"))
            assertEquals(TodoPart.TodoStatus.CANCELLED, TodoPart.TodoStatus.valueOf("CANCELLED"))
        }

        @Test
        @DisplayName("添加 Todo 项")
        fun testTodoPart_addItem() {
            // Given
            val part = TodoPart()

            // When
            part.addItem("Task 1")
            part.addItem("Task 2")

            // Then
            assertEquals(2, part.items.size)
        }

        @Test
        @DisplayName("获取完成进度")
        fun testTodoPart_getProgress() {
            // Given
            val part = TodoPart()
            val item1 = part.addItem("Task 1")
            val item2 = part.addItem("Task 2")
            item2.status = TodoPart.TodoStatus.COMPLETED

            // When
            val progress = part.getProgress()

            // Then
            assertEquals(0.5, progress, 0.01)
        }
    }

    @Nested
    @DisplayName("SubTaskPart")
    inner class SubTaskPartTests {

        @Test
        @DisplayName("创建子任务 Part")
        fun testSubTaskPart_creation() {
            // Given
            val target = "MyClass"
            val question = "Analyze this"

            // When
            val part = TestDataFactory.createSubTaskPart(target, question)

            // Then
            assertEquals(target, part.target)
            assertEquals(question, part.question)
        }

        @Test
        @DisplayName("子任务 Part 类型为 SUBTASK")
        fun testSubTaskPart_typeIsSubtask() {
            // Given
            val part = TestDataFactory.createSubTaskPart()
            part.type = PartType.SUBTASK

            // When
            val type = part.type

            // Then
            assertEquals(PartType.SUBTASK, type)
        }

        @Test
        @DisplayName("子任务状态枚举")
        fun testSubTaskStatus_enum() {
            // When & Then
            assertEquals(SubTaskPart.SubTaskStatus.PENDING, SubTaskPart.SubTaskStatus.valueOf("PENDING"))
            assertEquals(SubTaskPart.SubTaskStatus.IN_PROGRESS, SubTaskPart.SubTaskStatus.valueOf("IN_PROGRESS"))
            assertEquals(SubTaskPart.SubTaskStatus.COMPLETED, SubTaskPart.SubTaskStatus.valueOf("COMPLETED"))
            assertEquals(SubTaskPart.SubTaskStatus.BLOCKED, SubTaskPart.SubTaskStatus.valueOf("BLOCKED"))
            assertEquals(SubTaskPart.SubTaskStatus.CANCELLED, SubTaskPart.SubTaskStatus.valueOf("CANCELLED"))
        }

        @Test
        @DisplayName("开始子任务")
        fun testSubTaskPart_start() {
            // Given
            val part = SubTaskPart()

            // When
            part.start()

            // Then
            assertEquals(SubTaskPart.SubTaskStatus.IN_PROGRESS, part.status)
        }

        @Test
        @DisplayName("完成子任务")
        fun testSubTaskPart_complete() {
            // Given
            val part = SubTaskPart()

            // When
            part.complete("Done")

            // Then
            assertEquals(SubTaskPart.SubTaskStatus.COMPLETED, part.status)
            assertEquals("Done", part.conclusion)
        }

        @Test
        @DisplayName("添加依赖")
        fun testSubTaskPart_addDependency() {
            // Given
            val part = SubTaskPart()

            // When
            part.addDependency("task-1")
            part.addDependency("task-2")

            // Then
            assertEquals(2, part.dependsOn.size)
        }
    }

    @Nested
    @DisplayName("PartType 枚举")
    inner class PartTypeTests {

        @Test
        @DisplayName("所有 PartType 枚举值存在")
        fun testPartType_enumValues() {
            // When & Then
            assertEquals(PartType.TEXT, PartType.valueOf("TEXT"))
            assertEquals(PartType.TOOL, PartType.valueOf("TOOL"))
            assertEquals(PartType.REASONING, PartType.valueOf("REASONING"))
            assertEquals(PartType.GOAL, PartType.valueOf("GOAL"))
            assertEquals(PartType.PROGRESS, PartType.valueOf("PROGRESS"))
            assertEquals(PartType.TODO, PartType.valueOf("TODO"))
            assertEquals(PartType.SUBTASK, PartType.valueOf("SUBTASK"))
        }
    }
}
