/**
 * SmanCode Core - Tool 工具接口定义
 *
 * 工具是 Agent 与外部系统交互的桥梁
 */

import { z } from "zod"
import type { ToolInfo, ToolResult, ToolParameters, FilePart, StreamEvent } from "../types"

// ============================================================================
// 工具定义
// ============================================================================

/** 工具执行上下文 */
export interface ToolContext {
  /** 会话 ID */
  sessionId: string
  /** 消息 ID */
  messageId: string
  /** Agent 名称 */
  agent: string
  /** 工具调用 ID */
  callId: string
  /** 中止信号 */
  abortSignal: AbortSignal
  /** 历史消息 */
  messages: Array<{
    role: string
    content: string
  }>
  /** 发送流事件 */
  emit: (event: StreamEvent) => void
  /** 请求权限 */
  askPermission: (request: {
    permission: string
    patterns: string[]
    context?: Record<string, unknown>
  }) => Promise<boolean>
  /** 更新元数据 */
  updateMetadata: (metadata: { title?: string; metadata?: Record<string, unknown> }) => void
}

/** 工具定义 */
export interface ToolDefinition<T extends ToolParameters = ToolParameters> {
  /** 工具 ID */
  id: string
  /** 描述 */
  description: string
  /** 参数 Schema */
  parameters: T
  /** 是否危险 */
  dangerous?: boolean
  /** 需要确认 */
  requiresConfirmation?: boolean
  /** 初始化 */
  init?(ctx: ToolContext): Promise<void>
  /** 执行 */
  execute(args: z.infer<T>, ctx: ToolContext): Promise<ToolResult>
}

/** 工具工厂函数 */
export type ToolFactory<T extends ToolParameters = ToolParameters> = (
  ctx: ToolContext
) => Promise<ToolDefinition<T>>

// ============================================================================
// 工具注册表
// ============================================================================

/** 工具注册表接口 */
export interface ToolRegistry {
  /** 注册工具 */
  register(tool: ToolDefinition | ToolFactory): Promise<void>

  /** 注销工具 */
  unregister(id: string): Promise<void>

  /** 获取工具 */
  get(id: string, ctx: ToolContext): Promise<ToolDefinition | undefined>

  /** 获取所有工具信息 */
  getAll(ctx: ToolContext): Promise<ToolInfo[]>

  /** 检查工具是否存在 */
  has(id: string): Promise<boolean>

  /** 获取工具 Schema（用于 LLM） */
  getSchemas(ctx: ToolContext): Promise<Array<{
    name: string
    description: string
    parameters: Record<string, unknown>
  }>>
}

// ============================================================================
// 工具执行器
// ============================================================================

/** 工具执行选项 */
export interface ToolExecutionOptions {
  /** 超时时间（毫秒） */
  timeout?: number
  /** 重试次数 */
  retries?: number
  /** 是否需要确认 */
  requireConfirmation?: boolean
}

/** 工具执行器接口 */
export interface ToolExecutor {
  /** 执行工具 */
  execute(
    toolId: string,
    args: Record<string, unknown>,
    ctx: ToolContext,
    options?: ToolExecutionOptions
  ): Promise<ToolResult>

  /** 批量执行 */
  executeBatch(
    calls: Array<{
      id: string
      name: string
      args: Record<string, unknown>
    }>,
    ctx: ToolContext
  ): Promise<Array<{ id: string; result: ToolResult }>>
}

// ============================================================================
// 内置工具接口
// ============================================================================

/** 文件系统工具接口 */
export interface FileSystemTools {
  /** 读取文件 */
  readFile(path: string, ctx: ToolContext): Promise<ToolResult>

  /** 写入文件 */
  writeFile(path: string, content: string, ctx: ToolContext): Promise<ToolResult>

  /** 编辑文件 */
  editFile(path: string, edits: Array<{
    oldText: string
    newText: string
  }>, ctx: ToolContext): Promise<ToolResult>

  /** 搜索文件 */
  glob(pattern: string, ctx: ToolContext): Promise<ToolResult>

  /** 搜索内容 */
  grep(pattern: string, options: {
    path?: string
    type?: string
    ignoreCase?: boolean
  }, ctx: ToolContext): Promise<ToolResult>
}

/** Shell 工具接口 */
export interface ShellTools {
  /** 执行命令 */
  exec(command: string, options: {
    cwd?: string
    timeout?: number
    env?: Record<string, string>
  }, ctx: ToolContext): Promise<ToolResult>

  /** 执行脚本 */
  runScript(script: string, ctx: ToolContext): Promise<ToolResult>
}

/** 搜索工具接口 */
export interface SearchTools {
  /** 语义搜索 */
  semanticSearch(query: string, options: {
    topK?: number
    threshold?: number
    filter?: Record<string, unknown>
  }, ctx: ToolContext): Promise<ToolResult>

  /** Web 搜索 */
  webSearch(query: string, ctx: ToolContext): Promise<ToolResult>

  /** 代码搜索 */
  codeSearch(query: string, ctx: ToolContext): Promise<ToolResult>
}
