/**
 * SmanCode Core - Session 会话接口定义
 *
 * 会话管理多轮对话的上下文和历史
 */

import type { Message, Session, SessionStatus, Part, StreamEvent } from "../types"

// ============================================================================
// 会话存储接口
// ============================================================================

/** 会话存储接口 */
export interface SessionStore {
  /** 创建会话 */
  create(input: {
    projectId: string
    parentId?: string
    title?: string
  }): Promise<Session>

  /** 获取会话 */
  get(sessionId: string): Promise<Session | undefined>

  /** 更新会话 */
  update(sessionId: string, updates: Partial<Session>): Promise<Session>

  /** 删除会话 */
  delete(sessionId: string): Promise<void>

  /** 列出会话 */
  list(projectId: string, options?: {
    status?: SessionStatus
    limit?: number
    offset?: number
  }): Promise<Session[]>

  /** 获取子会话 */
  getChildren(parentId: string): Promise<Session[]>
}

// ============================================================================
// 消息存储接口
// ============================================================================

/** 消息存储接口 */
export interface MessageStore {
  /** 添加消息 */
  append(sessionId: string, message: Omit<Message, "id" | "sessionId" | "timestamp">): Promise<Message>

  /** 获取消息 */
  get(messageId: string): Promise<Message | undefined>

  /** 更新消息 */
  update(messageId: string, updates: Partial<Message>): Promise<Message>

  /** 删除消息 */
  delete(messageId: string): Promise<void>

  /** 获取会话消息流 */
  stream(sessionId: string, options?: {
    afterMessageId?: string
    limit?: number
  }): AsyncGenerator<Message>

  /** 获取消息历史 */
  getHistory(sessionId: string, options?: {
    limit?: number
    includeCompacted?: boolean
  }): Promise<Message[]>
}

// ============================================================================
// 上下文压缩接口
// ============================================================================

/** 压缩策略 */
export type CompactionStrategy = "prune" | "summarize" | "hybrid"

/** 压缩配置 */
export interface CompactionConfig {
  /** 策略 */
  strategy: CompactionStrategy
  /** 保留 token 下限 */
  reserveTokensFloor: number
  /** 触发压缩的阈值（占上下文窗口比例） */
  triggerThreshold: number
  /** 保留最近消息数 */
  preserveRecentMessages: number
  /** 最大压缩次数 */
  maxCompactionAttempts: number
}

/** 压缩结果 */
export interface CompactionResult {
  /** 压缩前 token 数 */
  originalTokens: number
  /** 压缩后 token 数 */
  compressedTokens: number
  /** 压缩比 */
  ratio: number
  /** 压缩摘要 */
  summary: string
}

/** 上下文压缩器接口 */
export interface ContextCompactor {
  /** 检查是否需要压缩 */
  needsCompaction(sessionId: string, tokenCount: number, contextWindow: number): Promise<boolean>

  /** 执行压缩 */
  compact(sessionId: string, config: CompactionConfig): Promise<CompactionResult>

  /** 裁剪旧消息 */
  prune(sessionId: string, options: {
    maxAge?: number
    maxTokens?: number
    preserveRecent?: number
  }): Promise<number>

  /** 生成摘要 */
  summarize(messages: Message[]): Promise<string>
}

// ============================================================================
// 会话管理器接口
// ============================================================================

/** 会话事件 */
export type SessionEvent =
  | { type: "session.created"; session: Session }
  | { type: "session.updated"; session: Session }
  | { type: "session.deleted"; sessionId: string }
  | { type: "message.appended"; sessionId: string; message: Message }
  | { type: "compaction.started"; sessionId: string }
  | { type: "compaction.completed"; sessionId: string; result: CompactionResult }

/** 事件监听器 */
export type SessionEventListener = (event: SessionEvent) => void | Promise<void>

/** 会话管理器接口 */
export interface SessionManager {
  /** 创建会话 */
  create(projectId: string, parentId?: string): Promise<Session>

  /** 获取会话 */
  get(sessionId: string): Promise<Session | undefined>

  /** 更新会话 */
  update(sessionId: string, updates: Partial<Session>): Promise<Session>

  /** 删除会话 */
  delete(sessionId: string): Promise<void>

  /** 列出会话 */
  list(projectId: string): Promise<Session[]>

  /** 追加消息 */
  appendMessage(sessionId: string, message: Omit<Message, "id" | "sessionId" | "timestamp">): Promise<Message>

  /** 获取消息历史 */
  getHistory(sessionId: string): Promise<Message[]>

  /** 检查压缩需求 */
  checkCompaction(sessionId: string): Promise<boolean>

  /** 执行压缩 */
  compact(sessionId: string): Promise<CompactionResult>

  /** 订阅事件 */
  subscribe(listener: SessionEventListener): () => void

  /** 关闭 */
  close(): Promise<void>
}

