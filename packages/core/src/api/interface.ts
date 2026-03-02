/**
 * SmanCode Core - API 接口定义
 *
 * HTTP/WebSocket API 定义
 */

import type { Message, Session, AgentInfo, StreamEvent, ToolInfo, ModelInfo } from "../types"

// ============================================================================
// HTTP API 请求/响应
// ============================================================================

/** 创建会话请求 */
export interface CreateSessionRequest {
  projectId: string
  parentId?: string
  title?: string
}

/** 创建会话响应 */
export interface CreateSessionResponse {
  session: Session
}

/** 发送消息请求 */
export interface SendMessageRequest {
  message: string
  attachments?: Array<{
    name: string
    mimeType: string
    data: string // base64
  }>
  agent?: string
  context?: {
    systemPrompt?: string
    knowledge?: string[]
  }
}

/** 获取会话响应 */
export interface GetSessionResponse {
  session: Session
  messages: Message[]
}

/** 列出会话响应 */
export interface ListSessionsResponse {
  sessions: Session[]
  total: number
}

/** 获取 Agent 列表响应 */
export interface ListAgentsResponse {
  agents: AgentInfo[]
}

/** 获取工具列表响应 */
export interface ListToolsResponse {
  tools: ToolInfo[]
}

/** 获取模型列表响应 */
export interface ListModelsResponse {
  models: ModelInfo[]
}

/** 权限确认请求 */
export interface PermissionConfirmRequest {
  requestId: string
  allow: boolean
  remember?: boolean
}

/** 搜索请求 */
export interface SearchRequest {
  query: string
  topK?: number
  type?: string
  projectId?: string
}

/** 搜索响应 */
export interface SearchResponse {
  results: Array<{
    id: string
    content: string
    score: number
    metadata?: Record<string, unknown>
  }>
}

// ============================================================================
// WebSocket 消息
// ============================================================================

/** WebSocket 客户端消息 */
export type WSClientMessage =
  | { type: "ping" }
  | { type: "subscribe"; sessionId: string }
  | { type: "unsubscribe"; sessionId: string }
  | { type: "send_message"; sessionId: string; payload: SendMessageRequest }
  | { type: "abort"; sessionId: string }
  | { type: "permission_response"; requestId: string; allow: boolean }

/** WebSocket 服务端消息 */
export type WSServerMessage =
  | { type: "pong" }
  | { type: "subscribed"; sessionId: string }
  | { type: "unsubscribed"; sessionId: string }
  | { type: "stream_event"; sessionId: string; event: StreamEvent }
  | { type: "permission_request"; request: {
      id: string
      permission: string
      patterns: string[]
      context?: Record<string, unknown>
    } }
  | { type: "error"; message: string; code?: string }

// ============================================================================
// API 路由定义
// ============================================================================

/** API 路由 */
export interface APIRoutes {
  // 会话
  "POST /sessions": {
    body: CreateSessionRequest
    response: CreateSessionResponse
  }
  "GET /sessions/:id": {
    response: GetSessionResponse
  }
  "GET /sessions": {
    query: { projectId: string; limit?: number; offset?: number }
    response: ListSessionsResponse
  }
  "DELETE /sessions/:id": {
    response: { success: boolean }
  }

  // 消息
  "POST /sessions/:id/messages": {
    body: SendMessageRequest
    response: { messageId: string }
  }
  "GET /sessions/:id/messages": {
    query: { limit?: number; after?: string }
    response: { messages: Message[] }
  }

  // Agent
  "GET /agents": {
    response: ListAgentsResponse
  }
  "POST /agents/generate": {
    body: { description: string }
    response: { config: AgentInfo }
  }

  // 工具
  "GET /tools": {
    response: ListToolsResponse
  }

  // 模型
  "GET /models": {
    response: ListModelsResponse
  }

  // 搜索
  "POST /search": {
    body: SearchRequest
    response: SearchResponse
  }

  // 权限
  "POST /permissions/confirm": {
    body: PermissionConfirmRequest
    response: { success: boolean }
  }

  // WebSocket
  "GET /ws": {
    upgrade: WebSocket
  }
}

// ============================================================================
// API 服务接口
// ============================================================================

/** API 配置 */
export interface APIConfig {
  port: number
  host?: string
  cors?: {
    origins: string[]
    methods?: string[]
    headers?: string[]
  }
  auth?: {
    type: "bearer" | "api_key" | "none"
    validate?: (token: string) => Promise<{ userId: string } | null>
  }
}

/** API 服务接口 */
export interface APIService {
  /** 启动服务 */
  start(config: APIConfig): Promise<void>

  /** 停止服务 */
  stop(): Promise<void>

  /** 获取地址 */
  getAddress(): string

  /** 广播流事件 */
  broadcastStreamEvent(sessionId: string, event: StreamEvent): void

  /** 发送权限请求 */
  sendPermissionRequest(sessionId: string, request: {
    id: string
    permission: string
    patterns: string[]
    context?: Record<string, unknown>
  }): Promise<boolean>

  /** 获取活跃连接数 */
  getActiveConnections(): number
}
