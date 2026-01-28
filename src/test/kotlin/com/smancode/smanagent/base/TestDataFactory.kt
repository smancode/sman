package com.smancode.smanagent.base

import com.smancode.smanagent.model.message.Message
import com.smancode.smanagent.model.message.Role
import com.smancode.smanagent.model.part.*
import com.smancode.smanagent.model.session.ProjectInfo
import com.smancode.smanagent.model.session.Session
import com.smancode.smanagent.model.session.SessionStatus
import java.time.Instant
import java.util.*

/**
 * 测试数据工厂
 *
 * 创建测试用的标准数据对象
 */
object TestDataFactory {

    /**
     * 创建测试会话
     */
    fun createTestSession(
        sessionId: String = "test-session-1",
        projectKey: String = "test-project"
    ): Session {
        val session = Session()
        session.id = sessionId
        session.status = SessionStatus.IDLE
        session.createdTime = Instant.now()

        val projectInfo = ProjectInfo()
        projectInfo.projectKey = projectKey
        projectInfo.projectPath = "/test/path/project"
        session.projectInfo = projectInfo

        return session
    }

    /**
     * 创建测试消息
     */
    fun createTestMessage(
        messageId: String = "test-message-1",
        sessionId: String = "test-session-1",
        role: Role = Role.USER,
        content: String = "Test content"
    ): Message {
        return Message(messageId, sessionId, role, content)
    }

    /**
     * 创建用户消息
     */
    fun createUserMessage(
        content: String = "User input"
    ): Message {
        val message = Message()
        message.role = Role.USER
        message.content = content
        message.createdTime = Instant.now()
        return message
    }

    /**
     * 创建助手消息
     */
    fun createAssistantMessage(
        content: String = "Assistant response"
    ): Message {
        val message = Message()
        message.role = Role.ASSISTANT
        message.content = content
        message.createdTime = Instant.now()
        return message
    }

    /**
     * 创建文本 Part
     */
    fun createTextPart(
        text: String = "Text content"
    ): TextPart {
        val part = TextPart()
        part.id = randomId()
        part.text = text
        return part
    }

    /**
     * 创建工具 Part
     */
    fun createToolPart(
        toolName: String = "test_tool",
        parameters: Map<String, Any>? = null,
        result: com.smancode.smanagent.tools.ToolResult? = null
    ): ToolPart {
        val part = ToolPart()
        part.id = randomId()
        part.toolName = toolName
        part.parameters = parameters
        part.result = result
        return part
    }

    /**
     * 创建推理 Part
     */
    fun createReasoningPart(
        text: String = "Reasoning content"
    ): ReasoningPart {
        val part = ReasoningPart()
        part.id = randomId()
        part.text = text
        return part
    }

    /**
     * 创建目标 Part
     */
    fun createGoalPart(
        title: String = "Goal title",
        description: String = "Goal content"
    ): GoalPart {
        val part = GoalPart()
        part.id = randomId()
        part.title = title
        part.description = description
        return part
    }

    /**
     * 创建进度 Part
     */
    fun createProgressPart(
        current: String = "Processing",
        currentStep: Int? = 1,
        totalSteps: Int? = 10
    ): ProgressPart {
        val part = ProgressPart()
        part.id = randomId()
        part.current = current
        part.currentStep = currentStep
        part.totalSteps = totalSteps
        return part
    }

    /**
     * 创建 Todo Part
     */
    fun createTodoPart(
        content: String = "Test todo",
        status: TodoPart.TodoStatus = TodoPart.TodoStatus.PENDING
    ): TodoPart {
        val part = TodoPart()
        part.id = randomId()
        val item = TodoPart.TodoItem()
        item.content = content
        item.status = status
        part.items.add(item)
        return part
    }

    /**
     * 创建子任务 Part
     */
    fun createSubTaskPart(
        target: String = "Test target",
        question: String = "Test subtask",
        status: SubTaskPart.SubTaskStatus = SubTaskPart.SubTaskStatus.PENDING
    ): SubTaskPart {
        val part = SubTaskPart()
        part.id = randomId()
        part.target = target
        part.question = question
        part.status = status
        return part
    }

    /**
     * 生成随机 ID
     */
    fun randomId(): String {
        return UUID.randomUUID().toString()
    }

    /**
     * 生成随机会话 ID
     */
    fun randomSessionId(): String {
        return "session-${System.currentTimeMillis()}-${(Math.random() * 10000).toInt()}"
    }
}
