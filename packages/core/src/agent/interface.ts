/**
 * SmanCode Core - Agent 接口定义
 *
 * Agent 是执行任务的核心单元，支持多 Agent 协作
 */

import { z } from "zod"
import type { AgentInfo, AgentMode, Message, Part, ToolInfo, ModelInfo, StreamEvent } from "../types"

// ============================================================================
// Agent 配置
// ============================================================================

/** Agent 配置 */
export interface AgentConfig {
  /** Agent 名称 */
  name: string
  /** 描述 */
  description?: string
  /** 模式 */
  mode: AgentMode
  /** 使用的模型 */
  model?: {
    providerId: string
    modelId: string
  }
  /** 系统提示词 */
  systemPrompt?: string | ((ctx: AgentContext) => string | Promise<string>)
  /** 温度参数 */
  temperature?: number
  /** 最大步数 */
  maxSteps?: number
  /** 可用工具列表 */
  tools?: string[]
  /** 是否隐藏 */
  hidden?: boolean
  /** 初始化钩子 */
  onInit?: (ctx: AgentContext) => Promise<void>
  /** 执行前钩子 */
  onBeforeExecute?: (ctx: AgentContext, input: AgentInput) => Promise<void>
  /** 执行后钩子 */
  onAfterExecute?: (ctx: AgentContext, output: AgentOutput) => Promise<void>
}

// ============================================================================
// Agent 上下文
// ============================================================================

/** Agent 上下文 */
export interface AgentContext {
  /** 会话 ID */
  sessionId: string
  /** 项目 ID */
  projectId: string
  /** 用户 ID */
  userId?: string
  /** 父会话 ID（子任务场景） */
  parentId?: string
  /** 模型信息 */
  model: ModelInfo
  /** 可用工具 */
  tools: Map<string, ToolInfo>
  /** 权限规则 */
  permissions: PermissionRule[]
  /** 用户偏好 */
  preferences: Map<string, unknown>
  /** 中止信号 */
  abortSignal: AbortSignal
  /** 元数据 */
  metadata: Record<string, unknown>
  /** 发送流事件 */
  emit: (event: StreamEvent) => void
  /** 请求权限 */
  askPermission: (request: PermissionRequest) => Promise<boolean>
  /** 调用其他 Agent */
  invokeAgent: (name: string, input: AgentInput) => Promise<AgentOutput>
  /** 记录用户偏好 */
  recordPreference: (key: string, value: unknown) => Promise<void>
}

import type { PermissionRule, PermissionRequest } from "../types"

// ============================================================================
// Agent 输入/输出
// ============================================================================

/** Agent 输入 */
export interface AgentInput {
  /** 用户消息 */
  message: string
  /** 附件 */
  attachments?: Array<{
    name: string
    mimeType: string
    data: string | Buffer
  }>
  /** 历史消息 */
  history?: Message[]
  /** 上下文注入 */
  context?: {
    systemPrompt?: string
    knowledge?: string[]
    preferences?: Record<string, unknown>
  }
  /** 元数据 */
  metadata?: Record<string, unknown>
}

/** Agent 输出 */
export interface AgentOutput {
  /** 响应消息 */
  message: Message
  /** 是否完成 */
  finished: boolean
  /** 结束原因 */
  finishReason?: "stop" | "tool_calls" | "length" | "error"
  /** Token 使用 */
  tokens?: {
    input: number
    output: number
    cached?: number
  }
  /** 错误信息 */
  error?: string
}

// ============================================================================
// Agent 执行器
// ============================================================================

/** Agent 执行器接口 */
export interface AgentExecutor {
  /** Agent 信息 */
  info: AgentInfo

  /** 初始化 */
  init(): Promise<void>

  /** 执行 */
  execute(input: AgentInput, ctx: AgentContext): AsyncGenerator<StreamEvent, AgentOutput>

  /** 销毁 */
  destroy(): Promise<void>
}

// ============================================================================
// Agent 注册表
// ============================================================================

/** Agent 注册表接口 */
export interface AgentRegistry {
  /** 注册 Agent */
  register(config: AgentConfig): Promise<void>

  /** 注销 Agent */
  unregister(name: string): Promise<void>

  /** 获取 Agent */
  get(name: string): Promise<AgentExecutor | undefined>

  /** 获取所有 Agent */
  getAll(): Promise<AgentExecutor[]>

  /** 获取可用 Agent 列表（排除隐藏） */
  getAvailable(): Promise<AgentInfo[]>

  /** 动态生成 Agent */
  generate(description: string): Promise<AgentConfig>
}

// ============================================================================
// Agent 协调器
// ============================================================================

/** 任务优先级 */
export type TaskPriority = "low" | "normal" | "high" | "urgent"

/** 子任务 */
export interface Subtask {
  id: string
  agent: string
  description: string
  priority: TaskPriority
  status: "pending" | "running" | "completed" | "failed"
  input: AgentInput
  output?: AgentOutput
  error?: string
}

/** Agent 协调器接口 */
export interface AgentCoordinator {
  /** 创建会话 */
  createSession(projectId: string, parentId?: string): Promise<string>

  /** 执行主 Agent */
  execute(sessionId: string, input: AgentInput): AsyncGenerator<StreamEvent, AgentOutput>

  /** 创建子任务 */
  createSubtask(sessionId: string, subtask: Omit<Subtask, "id" | "status">): Promise<string>

  /** 执行子任务 */
  executeSubtask(subtaskId: string): AsyncGenerator<StreamEvent, AgentOutput>

  /** 获取子任务状态 */
  getSubtaskStatus(subtaskId: string): Promise<Subtask>

  /** 中止执行 */
  abort(sessionId: string): Promise<void>
}
