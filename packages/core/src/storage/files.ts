/**
 * SmanCode Core - File-based Storage Implementations
 *
 * 存储结构:
 * <project>/.sman/
 * ├── MEMORY.md              # 项目记忆（长期）
 * ├── puzzles/               # 拼图块存储
 * │   ├── status.json        # 拼图状态汇总
 * │   └── PUZZLE_*.md        # 各类型拼图
 * ├── sessions/              # 会话存储
 * │   └── <session-id>.jsonl # JSONL 格式消息
 * └── cache/                 # 缓存（不入 Git）
 *     ├── md5.json
 *     └── vectors/           # 向量索引
 */

import * as fs from "fs"
import * as path from "path"
import type { Session, Message, SessionStatus } from "../types"
import type { SessionStore, MessageStore, KeyValueStore, MarkdownStore, MarkdownDocument } from "./interface"
import { ulid } from "ulid"

// ============================================================================
// 路径工具
// ============================================================================

/** 获取 .sman 目录路径 */
export function getSmanDir(projectPath: string): string {
  return path.join(projectPath, ".sman")
}

/** 获取会话目录路径 */
export function getSessionsDir(projectPath: string): string {
  return path.join(getSmanDir(projectPath), "sessions")
}

/** 获取拼图目录路径 */
export function getPuzzlesDir(projectPath: string): string {
  return path.join(getSmanDir(projectPath), "puzzles")
}

/** 获取缓存目录路径 */
export function getCacheDir(projectPath: string): string {
  return path.join(getSmanDir(projectPath), "cache")
}

/** 确保目录存在 */
export function ensureDir(dir: string): void {
  if (!fs.existsSync(dir)) {
    fs.mkdirSync(dir, { recursive: true })
  }
}

// ============================================================================
// JSONL Session Store
// ============================================================================

/** JSONL 文件会话存储 */
export class JsonlSessionStore implements SessionStore {
  private projectPath: string
  private sessionsCache: Map<string, Session> = new Map()
  private metadataFile: string

  constructor(projectPath: string) {
    this.projectPath = projectPath
    this.metadataFile = path.join(getSessionsDir(projectPath), "metadata.json")
    this.loadMetadata()
  }

  private loadMetadata(): void {
    ensureDir(getSessionsDir(this.projectPath))
    if (fs.existsSync(this.metadataFile)) {
      try {
        const data = JSON.parse(fs.readFileSync(this.metadataFile, "utf-8"))
        for (const session of data.sessions || []) {
          this.sessionsCache.set(session.id, session)
        }
      } catch (e) {
        // 文件损坏，重新开始
      }
    }
  }

  private saveMetadata(): void {
    const data = {
      sessions: Array.from(this.sessionsCache.values()),
      updated: Date.now(),
    }
    fs.writeFileSync(this.metadataFile, JSON.stringify(data, null, 2))
  }

  private getSessionFile(sessionId: string): string {
    return path.join(getSessionsDir(this.projectPath), `${sessionId}.jsonl`)
  }

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

    this.sessionsCache.set(session.id, session)
    this.saveMetadata()

    // 创建空的 JSONL 文件
    const file = this.getSessionFile(session.id)
    fs.writeFileSync(file, "")

    return session
  }

  async get(sessionId: string): Promise<Session | undefined> {
    return this.sessionsCache.get(sessionId)
  }

  async update(sessionId: string, updates: Partial<Session>): Promise<Session> {
    const session = this.sessionsCache.get(sessionId)
    if (!session) throw new Error(`Session not found: ${sessionId}`)

    const updated: Session = {
      ...session,
      ...updates,
      timestamp: { ...session.timestamp, updated: Date.now() },
    }

    this.sessionsCache.set(sessionId, updated)
    this.saveMetadata()

    return updated
  }

  async delete(sessionId: string): Promise<void> {
    this.sessionsCache.delete(sessionId)
    this.saveMetadata()

    const file = this.getSessionFile(sessionId)
    if (fs.existsSync(file)) {
      fs.unlinkSync(file)
    }
  }

  async list(projectId: string, options?: { status?: SessionStatus; limit?: number; offset?: number }): Promise<Session[]> {
    let sessions = Array.from(this.sessionsCache.values())
      .filter(s => s.projectId === projectId)

    if (options?.status) {
      sessions = sessions.filter(s => s.status === options.status)
    }

    // 按创建时间倒序
    sessions.sort((a, b) => b.timestamp.created - a.timestamp.created)

    if (options?.offset) {
      sessions = sessions.slice(options.offset)
    }
    if (options?.limit) {
      sessions = sessions.slice(0, options.limit)
    }

    return sessions
  }

  async getChildren(parentId: string): Promise<Session[]> {
    return Array.from(this.sessionsCache.values())
      .filter(s => s.parentId === parentId)
  }
}

// ============================================================================
// JSONL Message Store
// ============================================================================

/** JSONL 文件消息存储 */
export class JsonlMessageStore implements MessageStore {
  private projectPath: string
  private messagesCache: Map<string, Map<string, Message>> = new Map()

  constructor(projectPath: string) {
    this.projectPath = projectPath
  }

  private getSessionFile(sessionId: string): string {
    return path.join(getSessionsDir(this.projectPath), `${sessionId}.jsonl`)
  }

  private loadSessionMessages(sessionId: string): Map<string, Message> {
    if (this.messagesCache.has(sessionId)) {
      return this.messagesCache.get(sessionId)!
    }

    const messages = new Map<string, Message>()
    const file = this.getSessionFile(sessionId)

    if (fs.existsSync(file)) {
      const content = fs.readFileSync(file, "utf-8")
      const lines = content.split("\n").filter(line => line.trim())

      for (const line of lines) {
        try {
          const msg = JSON.parse(line) as Message
          messages.set(msg.id, msg)
        } catch (e) {
          // 跳过损坏的行
        }
      }
    }

    this.messagesCache.set(sessionId, messages)
    return messages
  }

  private appendToFile(sessionId: string, message: Message): void {
    const file = this.getSessionFile(sessionId)
    ensureDir(path.dirname(file))
    fs.appendFileSync(file, JSON.stringify(message) + "\n")
  }

  async append(sessionId: string, message: Omit<Message, "id" | "sessionId" | "timestamp">): Promise<Message> {
    const now = Date.now()
    const fullMessage: Message = {
      ...message,
      id: ulid(),
      sessionId,
      timestamp: { created: now, updated: now },
    }

    const messages = this.loadSessionMessages(sessionId)
    messages.set(fullMessage.id, fullMessage)
    this.appendToFile(sessionId, fullMessage)

    return fullMessage
  }

  async get(messageId: string): Promise<Message | undefined> {
    for (const sessionMessages of this.messagesCache.values()) {
      const msg = sessionMessages.get(messageId)
      if (msg) return msg
    }
    return undefined
  }

  async update(messageId: string, updates: Partial<Message>): Promise<Message> {
    for (const [sessionId, sessionMessages] of this.messagesCache) {
      const msg = sessionMessages.get(messageId)
      if (msg) {
        const updated: Message = {
          ...msg,
          ...updates,
          timestamp: { ...msg.timestamp, updated: Date.now() },
        }
        sessionMessages.set(messageId, updated)

        // 重写整个文件
        const file = this.getSessionFile(sessionId)
        const lines = Array.from(sessionMessages.values())
          .map(m => JSON.stringify(m))
          .join("\n")
        fs.writeFileSync(file, lines + "\n")

        return updated
      }
    }
    throw new Error(`Message not found: ${messageId}`)
  }

  async delete(messageId: string): Promise<void> {
    for (const [sessionId, sessionMessages] of this.messagesCache) {
      if (sessionMessages.has(messageId)) {
        sessionMessages.delete(messageId)

        // 重写文件
        const file = this.getSessionFile(sessionId)
        const lines = Array.from(sessionMessages.values())
          .map(m => JSON.stringify(m))
          .join("\n")
        fs.writeFileSync(file, lines + "\n")

        return
      }
    }
  }

  async *stream(sessionId: string, options?: { afterMessageId?: string; limit?: number }): AsyncGenerator<Message> {
    const messages = this.loadSessionMessages(sessionId)
    let found = !options?.afterMessageId
    let count = 0

    for (const msg of messages.values()) {
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
    const messages = this.loadSessionMessages(sessionId)
    let result = Array.from(messages.values())

    if (!options?.includeCompacted) {
      result = result.filter(m => !m.parts.some(p => p.type === "compaction"))
    }

    // 按时间排序
    result.sort((a, b) => a.timestamp.created - b.timestamp.created)

    if (options?.limit) {
      result = result.slice(-options.limit)
    }

    return result
  }
}

// ============================================================================
// Markdown Store
// ============================================================================

/** 解析 YAML frontmatter */
function parseFrontmatter(content: string): { frontmatter: Record<string, unknown>; body: string } {
  const match = content.match(/^---\n([\s\S]*?)\n---\n?([\s\S]*)$/)
  if (!match) {
    return { frontmatter: {}, body: content }
  }

  const frontmatter: Record<string, unknown> = {}
  const lines = match[1].split("\n")

  for (const line of lines) {
    const colonIndex = line.indexOf(":")
    if (colonIndex > 0) {
      const key = line.slice(0, colonIndex).trim()
      let value: unknown = line.slice(colonIndex + 1).trim()

      // 简单的类型转换
      if (value === "true") value = true
      else if (value === "false") value = false
      else if (/^\d+$/.test(value as string)) value = parseInt(value as string)
      else if (/^\[.*\]$/.test(value as string)) {
        try {
          value = JSON.parse(value as string)
        } catch {}
      }

      frontmatter[key] = value
    }
  }

  // 移除 body 前导换行
  let body = match[2]
  if (body.startsWith("\n")) {
    body = body.slice(1)
  }

  return { frontmatter, body }
}

/** 生成 YAML frontmatter */
function stringifyFrontmatter(frontmatter: Record<string, unknown>): string {
  const lines: string[] = []

  for (const [key, value] of Object.entries(frontmatter)) {
    if (value === undefined) continue

    if (Array.isArray(value)) {
      lines.push(`${key}: ${JSON.stringify(value)}`)
    } else if (typeof value === "string") {
      lines.push(`${key}: ${value}`)
    } else {
      lines.push(`${key}: ${value}`)
    }
  }

  return lines.join("\n")
}

/** Markdown 文件存储 */
export class FileMarkdownStore implements MarkdownStore {
  private projectPath: string

  constructor(projectPath: string) {
    this.projectPath = projectPath
  }

  async read(filePath: string): Promise<MarkdownDocument> {
    const fullPath = path.isAbsolute(filePath)
      ? filePath
      : path.join(this.projectPath, filePath)

    if (!fs.existsSync(fullPath)) {
      throw new Error(`File not found: ${fullPath}`)
    }

    const content = fs.readFileSync(fullPath, "utf-8")
    const { frontmatter, body } = parseFrontmatter(content)

    return {
      path: fullPath,
      content: body,
      frontmatter,
      metadata: {
        title: frontmatter.title as string | undefined,
        tags: frontmatter.tags as string[] | undefined,
        created: frontmatter.created as number | undefined,
        updated: frontmatter.updated as number | undefined,
      },
    }
  }

  async write(filePath: string, doc: Partial<MarkdownDocument>): Promise<void> {
    const fullPath = path.isAbsolute(filePath)
      ? filePath
      : path.join(this.projectPath, filePath)

    ensureDir(path.dirname(fullPath))

    const frontmatter = {
      ...doc.frontmatter,
      ...doc.metadata,
      updated: Date.now(),
    }

    let content = ""
    if (Object.keys(frontmatter).length > 0) {
      content = `---\n${stringifyFrontmatter(frontmatter)}\n---\n\n`
    }
    content += doc.content || ""

    fs.writeFileSync(fullPath, content)
  }

  parseFrontmatter(content: string): Record<string, unknown> {
    return parseFrontmatter(content).frontmatter
  }

  stringifyFrontmatter(frontmatter: Record<string, unknown>): string {
    return stringifyFrontmatter(frontmatter)
  }

  async search(query: string, options?: { paths?: string[]; tags?: string[] }): Promise<MarkdownDocument[]> {
    const results: MarkdownDocument[] = []
    const searchPaths = options?.paths || [this.projectPath]

    for (const searchPath of searchPaths) {
      await this.searchInDir(searchPath, query, options?.tags, results)
    }

    return results
  }

  private async searchInDir(dir: string, query: string, tags?: string[], results?: MarkdownDocument[]): Promise<void> {
    if (!fs.existsSync(dir)) return

    const entries = fs.readdirSync(dir, { withFileTypes: true })

    for (const entry of entries) {
      const fullPath = path.join(dir, entry.name)

      if (entry.isDirectory()) {
        // 跳过隐藏目录和 node_modules
        if (entry.name.startsWith(".") || entry.name === "node_modules") continue
        await this.searchInDir(fullPath, query, tags, results)
      } else if (entry.isFile() && entry.name.endsWith(".md")) {
        try {
          const doc = await this.read(fullPath)

          // 检查标签
          if (tags && tags.length > 0) {
            const docTags = doc.metadata.tags || []
            if (!tags.some(t => docTags.includes(t))) continue
          }

          // 检查内容
          if (doc.content.toLowerCase().includes(query.toLowerCase())) {
            results?.push(doc)
          }
        } catch {}
      }
    }
  }

  watch(callback: (event: { type: "create" | "update" | "delete"; path: string }) => void): () => void {
    // Bun 的文件监听
    const watcher = fs.watch(this.projectPath, { recursive: true }, (event, filename) => {
      if (filename && filename.endsWith(".md")) {
        callback({
          type: event === "rename" ? "create" : "update",
          path: path.join(this.projectPath, filename),
        })
      }
    })

    return () => watcher.close()
  }
}

// ============================================================================
// JSON Key-Value Store
// ============================================================================

/** JSON 文件键值存储 */
export class JsonKeyValueStore implements KeyValueStore {
  private filePath: string
  private data: Map<string, unknown> = new Map()

  constructor(filePath: string) {
    this.filePath = filePath
    this.load()
  }

  private load(): void {
    if (fs.existsSync(this.filePath)) {
      try {
        const content = fs.readFileSync(this.filePath, "utf-8")
        const obj = JSON.parse(content)
        for (const [key, value] of Object.entries(obj)) {
          this.data.set(key, value)
        }
      } catch {}
    }
  }

  private save(): void {
    ensureDir(path.dirname(this.filePath))
    const obj: Record<string, unknown> = {}
    for (const [key, value] of this.data) {
      obj[key] = value
    }
    fs.writeFileSync(this.filePath, JSON.stringify(obj, null, 2))
  }

  async get<T = unknown>(key: string): Promise<T | undefined> {
    return this.data.get(key) as T | undefined
  }

  async set<T = unknown>(key: string, value: T, options?: { ttl?: number }): Promise<void> {
    this.data.set(key, value)
    this.save()
  }

  async delete(key: string): Promise<void> {
    this.data.delete(key)
    this.save()
  }

  async getBatch<T = unknown>(keys: string[]): Promise<Map<string, T>> {
    const result = new Map<string, T>()
    for (const key of keys) {
      const value = this.data.get(key) as T | undefined
      if (value !== undefined) {
        result.set(key, value)
      }
    }
    return result
  }

  async setBatch<T = unknown>(entries: Array<{ key: string; value: T }>, options?: { ttl?: number }): Promise<void> {
    for (const { key, value } of entries) {
      this.data.set(key, value)
    }
    this.save()
  }

  async has(key: string): Promise<boolean> {
    return this.data.has(key)
  }

  async keys(pattern?: string): Promise<string[]> {
    let keys = Array.from(this.data.keys())
    if (pattern) {
      const regex = new RegExp("^" + pattern.replace(/\*/g, ".*") + "$")
      keys = keys.filter(k => regex.test(k))
    }
    return keys
  }

  async clear(): Promise<void> {
    this.data.clear()
    this.save()
  }
}

// ============================================================================
// 工厂函数
// ============================================================================

export interface StorageConfig {
  projectPath: string
}

export function createSessionStore(config: StorageConfig): SessionStore {
  return new JsonlSessionStore(config.projectPath)
}

export function createMessageStore(config: StorageConfig): MessageStore {
  return new JsonlMessageStore(config.projectPath)
}

export function createMarkdownStore(config: StorageConfig): MarkdownStore {
  return new FileMarkdownStore(config.projectPath)
}

export function createKeyValueStore(filePath: string): KeyValueStore {
  return new JsonKeyValueStore(filePath)
}
