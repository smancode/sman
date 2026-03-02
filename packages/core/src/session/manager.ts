/**
 * SmanCode Core - Session Manager 实现
 */

import type { SessionManager, SessionStore, MessageStore, ContextCompactor, SessionEventListener, SessionEvent } from "./interface"
import type { Session, Message } from "../types"
import { ulid } from "ulid"

/** 内存会话存储 */
export class InMemorySessionStore implements SessionStore {
  private sessions = new Map<string, Session>()

  async create(input: { projectId: string; parentId?: string; title?: string }): Promise<Session> {
    const now = Date.now()
    const session: Session = {
      id: ulid(),
      projectId: input.projectId,
      parentId: input.parentId,
      title: input.title || `Session ${now}`,
      status: "active",
      tokens: { input: 0, output: 0, total: 0 },
      timestamp: { created: now, updated: now },
    }
    this.sessions.set(session.id, session)
    return session
  }

  async get(sessionId: string): Promise<Session | undefined> {
    return this.sessions.get(sessionId)
  }

  async update(sessionId: string, updates: Partial<Session>): Promise<Session> {
    const session = this.sessions.get(sessionId)
    if (!session) throw new Error(`Session not found: ${sessionId}`)
    const updated = {
      ...session,
      ...updates,
      timestamp: { ...session.timestamp, updated: Date.now() },
    }
    this.sessions.set(sessionId, updated)
    return updated
  }

  async delete(sessionId: string): Promise<void> {
    this.sessions.delete(sessionId)
  }

  async list(projectId: string, options?: { status?: string; limit?: number; offset?: number }): Promise<Session[]> {
    let sessions = Array.from(this.sessions.values()).filter(s => s.projectId === projectId)
    if (options?.status) {
      sessions = sessions.filter(s => s.status === options.status)
    }
    if (options?.offset) {
      sessions = sessions.slice(options.offset)
    }
    if (options?.limit) {
      sessions = sessions.slice(0, options.limit)
    }
    return sessions
  }

  async getChildren(parentId: string): Promise<Session[]> {
    return Array.from(this.sessions.values()).filter(s => s.parentId === parentId)
  }
}

/** 内存消息存储 */
export class InMemoryMessageStore implements MessageStore {
  private messages = new Map<string, Map<string, Message>>()

  async append(sessionId: string, message: Omit<Message, "id" | "sessionId" | "timestamp">): Promise<Message> {
    if (!this.messages.has(sessionId)) {
      this.messages.set(sessionId, new Map())
    }
    const now = Date.now()
    const fullMessage: Message = {
      ...message,
      id: ulid(),
      sessionId,
      timestamp: { created: now, updated: now },
    }
    this.messages.get(sessionId)!.set(fullMessage.id, fullMessage)
    return fullMessage
  }

  async get(messageId: string): Promise<Message | undefined> {
    for (const sessionMessages of this.messages.values()) {
      const msg = sessionMessages.get(messageId)
      if (msg) return msg
    }
    return undefined
  }

  async update(messageId: string, updates: Partial<Message>): Promise<Message> {
    for (const sessionMessages of this.messages.values()) {
      const msg = sessionMessages.get(messageId)
      if (msg) {
        const updated = {
          ...msg,
          ...updates,
          timestamp: { ...msg.timestamp, updated: Date.now() },
        }
        sessionMessages.set(messageId, updated)
        return updated
      }
    }
    throw new Error(`Message not found: ${messageId}`)
  }

  async delete(messageId: string): Promise<void> {
    for (const sessionMessages of this.messages.values()) {
      if (sessionMessages.has(messageId)) {
        sessionMessages.delete(messageId)
        return
      }
    }
  }

  async *stream(sessionId: string, options?: { afterMessageId?: string; limit?: number }): AsyncGenerator<Message> {
    const sessionMessages = this.messages.get(sessionId)
    if (!sessionMessages) return

    let found = !options?.afterMessageId
    let count = 0
    for (const msg of sessionMessages.values()) {
      if (!found) {
        if (msg.id === options?.afterMessageId) found = true
        continue
      }
      yield msg
      count++
      if (options?.limit && count >= options.limit) break
    }
  }

  async getHistory(sessionId: string, options?: { limit?: number; includeCompacted?: boolean }): Promise<Message[]> {
    const sessionMessages = this.messages.get(sessionId)
    if (!sessionMessages) return []

    let messages = Array.from(sessionMessages.values())
    if (!options?.includeCompacted) {
      messages = messages.filter(m => !m.parts.some(p => p.type === "compaction"))
    }
    if (options?.limit) {
      messages = messages.slice(-options.limit)
    }
    return messages
  }
}

/** 简单压缩器 */
export class SimpleCompactor implements ContextCompactor {
  async needsCompaction(sessionId: string, tokenCount: number, contextWindow: number): Promise<boolean> {
    return tokenCount > contextWindow * 0.8
  }

  async compact(sessionId: string, config: import("./interface").CompactionConfig): Promise<import("./interface").CompactionResult> {
    // 基础实现
    return {
      originalTokens: 10000,
      compressedTokens: 2000,
      ratio: 0.2,
      summary: "Session compressed",
    }
  }

  async prune(sessionId: string, options: { maxAge?: number; maxTokens?: number; preserveRecent?: number }): Promise<number> {
    // 基础实现
    return 0
  }

  async summarize(messages: Message[]): Promise<string> {
    return `Summary of ${messages.length} messages`
  }
}

/** Session Manager 实现 */
export class SessionManagerImpl implements SessionManager {
  private sessionStore: SessionStore
  private messageStore: MessageStore
  private compactor: ContextCompactor
  private listeners: Set<SessionEventListener> = new Set()

  constructor(
    sessionStore?: SessionStore,
    messageStore?: MessageStore,
    compactor?: ContextCompactor
  ) {
    this.sessionStore = sessionStore ?? new InMemorySessionStore()
    this.messageStore = messageStore ?? new InMemoryMessageStore()
    this.compactor = compactor ?? new SimpleCompactor()
  }

  private emit(event: SessionEvent): void {
    for (const listener of this.listeners) {
      listener(event)
    }
  }

  async create(projectId: string, parentId?: string): Promise<Session> {
    const session = await this.sessionStore.create({ projectId, parentId })
    this.emit({ type: "session.created", session })
    return session
  }

  async get(sessionId: string): Promise<Session | undefined> {
    return this.sessionStore.get(sessionId)
  }

  async update(sessionId: string, updates: Partial<Session>): Promise<Session> {
    const session = await this.sessionStore.update(sessionId, updates)
    this.emit({ type: "session.updated", session })
    return session
  }

  async delete(sessionId: string): Promise<void> {
    await this.sessionStore.delete(sessionId)
    this.emit({ type: "session.deleted", sessionId })
  }

  async list(projectId: string): Promise<Session[]> {
    return this.sessionStore.list(projectId)
  }

  async appendMessage(sessionId: string, message: Omit<Message, "id" | "sessionId" | "timestamp">): Promise<Message> {
    const fullMessage = await this.messageStore.append(sessionId, message)
    const session = await this.get(sessionId)
    if (session) {
      this.emit({ type: "message.appended", sessionId, message: fullMessage })
    }
    return fullMessage
  }

  async getHistory(sessionId: string): Promise<Message[]> {
    return this.messageStore.getHistory(sessionId)
  }

  async checkCompaction(sessionId: string): Promise<boolean> {
    const session = await this.get(sessionId)
    if (!session) return false
    // 基础实现
    return false
  }

  async compact(sessionId: string): Promise<import("./interface").CompactionResult> {
    this.emit({ type: "compaction.started", sessionId })
    const result = await this.compactor.compact(sessionId, {
      strategy: "hybrid",
      reserveTokensFloor: 20000,
      triggerThreshold: 0.8,
      preserveRecentMessages: 5,
      maxCompactionAttempts: 3,
    })
    this.emit({ type: "compaction.completed", sessionId, result })
    return result
  }

  subscribe(listener: SessionEventListener): () => void {
    this.listeners.add(listener)
    return () => this.listeners.delete(listener)
  }

  async close(): Promise<void> {
    this.listeners.clear()
  }
}

/** 创建默认 Session Manager */
export function createSessionManager(): SessionManager {
  return new SessionManagerImpl()
}
