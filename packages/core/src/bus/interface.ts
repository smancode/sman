/**
 * SmanCode Core - Event Bus 事件总线接口定义
 *
 * 事件驱动的通信机制
 */

// ============================================================================
// 事件定义
// ============================================================================

/** 事件基础接口 */
export interface Event<T = unknown> {
  type: string
  payload: T
  timestamp: number
  metadata?: Record<string, unknown>
}

/** 事件定义器 */
export type EventDefinition<T = unknown> = {
  type: string
  schema?: unknown // Zod schema for validation
}

// ============================================================================
// 事件监听器
// ============================================================================

/** 事件监听器 */
export type EventListener<T = unknown> = (event: Event<T>) => void | Promise<void>

/** 订阅选项 */
export interface SubscribeOptions {
  /** 是否只触发一次 */
  once?: boolean
  /** 优先级（数值越大越先执行） */
  priority?: number
}

// ============================================================================
// 事件总线接口
// ============================================================================

/** 事件总线接口 */
export interface EventBus {
  /** 发布事件 */
  publish<T = unknown>(type: string, payload: T, metadata?: Record<string, unknown>): Promise<void>

  /** 订阅事件 */
  subscribe<T = unknown>(
    type: string,
    listener: EventListener<T>,
    options?: SubscribeOptions
  ): () => void

  /** 取消订阅 */
  unsubscribe(type: string, listener: EventListener): void

  /** 订阅所有事件 */
  subscribeAll(listener: EventListener): () => void

  /** 检查是否有监听器 */
  hasListeners(type: string): boolean

  /** 获取监听器数量 */
  listenerCount(type: string): number

  /** 清除所有监听器 */
  clear(): void
}

// ============================================================================
// 预定义事件
// ============================================================================

/** 会话事件 */
export const SessionEvents = {
  CREATED: "session.created",
  UPDATED: "session.updated",
  DELETED: "session.deleted",
  MESSAGE_APPENDED: "session.message_appended",
  COMPACTION_STARTED: "session.compaction_started",
  COMPACTION_COMPLETED: "session.compaction_completed",
} as const

/** Agent 事件 */
export const AgentEvents = {
  EXECUTION_STARTED: "agent.execution_started",
  EXECUTION_COMPLETED: "agent.execution_completed",
  EXECUTION_ERROR: "agent.execution_error",
  TOOL_CALLED: "agent.tool_called",
  SUBTASK_CREATED: "agent.subtask_created",
} as const

/** 工具事件 */
export const ToolEvents = {
  EXECUTE_BEFORE: "tool.execute_before",
  EXECUTE_AFTER: "tool.execute_after",
  EXECUTE_ERROR: "tool.execute_error",
  PERMISSION_ASKED: "tool.permission_asked",
} as const

/** 记忆事件 */
export const MemoryEvents = {
  STORED: "memory.stored",
  ACCESSED: "memory.accessed",
  COMPACTED: "memory.compacted",
} as const

/** 用户偏好事件 */
export const PreferenceEvents = {
  UPDATED: "preference.updated",
  LEARNED: "preference.learned",
} as const
