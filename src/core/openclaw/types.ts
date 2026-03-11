// src/core/openclaw/types.ts
/**
 * OpenClaw API Type Definitions
 *
 * These types define the interface between SMAN and OpenClaw Sidecar.
 * OpenClaw runs as a sidecar process and exposes WebSocket RPC Gateway.
 */

// ============================================
// Legacy Types (保持向后兼容)
// ============================================

export interface OpenClawConfig {
  skills?: {
    load?: {
      extraDirs?: string[];
    };
  };
}

export interface ChatMessage {
  role: "user" | "assistant" | "system";
  content: string;
}

export interface ChatRequest {
  messages: ChatMessage[];
  systemPrompt?: string;
  skillFilter?: string[];
}

export interface ChatResponse {
  message: ChatMessage;
  done: boolean;
}

export interface StreamChunk {
  type: "text" | "tool_call" | "tool_result" | "done";
  content?: string;
  toolName?: string;
  toolArgs?: Record<string, unknown>;
  toolResult?: string;
}

export interface SkillMeta {
  name: string;
  description: string;
  filePath: string;
}

export interface LearnRequest {
  conversation: ChatMessage[];
  projectContext: string;
}

export interface LearnResponse {
  updates: Array<{
    type: "habit" | "memory" | "skill";
    path: string;
    content: string;
    reason: string;
  }> | null;
}

export interface OpenClawHealth {
  status: "healthy" | "unhealthy";
  version?: string;
  uptime?: number;
}

// ============================================
// WebSocket Gateway Protocol Types
// ============================================

/** Gateway 请求消息 */
export interface GatewayRequest {
  type: "req";
  id: string;
  method: string;
  params?: Record<string, unknown>;
}

/** Gateway 响应消息 */
export interface GatewayResponse<T = unknown> {
  type: "res";
  id: string;
  ok: boolean;
  payload?: T;
  error?: {
    code: string;
    message: string;
  };
}

/** Gateway 事件消息 */
export interface GatewayEvent<T = unknown> {
  type: "event";
  event: string;
  payload: T;
  seq: number;
}

/** Gateway 消息联合类型 */
export type GatewayMessage = GatewayRequest | GatewayResponse | GatewayEvent;

// ============================================
// Chat Method Types
// ============================================

/** chat.send 参数 */
export interface ChatSendParams {
  sessionKey: string;
  message: string;
  idempotencyKey: string;
  thinking?: string;
  deliver?: boolean;
  attachments?: ChatAttachment[];
  timeoutMs?: number;
}

/** chat.send 响应 */
export interface ChatSendResult {
  runId: string;
  status: "started" | "in_flight";
}

/** chat.event 载荷 */
export interface ChatEventPayload {
  runId: string;
  sessionKey: string;
  seq: number;
  state: "delta" | "final" | "error" | "aborted";
  message?: {
    role: string;
    content: string;
  };
  errorMessage?: string;
  stopReason?: string;
  usage?: {
    inputTokens: number;
    outputTokens: number;
  };
}

/** chat.history 参数 */
export interface ChatHistoryParams {
  sessionKey: string;
  limit?: number;
}

/** chat.history 响应 */
export interface ChatHistoryResult {
  messages: ChatHistoryMessage[];
}

/** 聊天历史消息 */
export interface ChatHistoryMessage {
  role: "user" | "assistant" | "system";
  content: string;
  timestamp?: string;
}

/** chat.abort 参数 */
export interface ChatAbortParams {
  sessionKey: string;
  runId?: string;
}

/** 附件类型 */
export interface ChatAttachment {
  type?: string;
  mimeType?: string;
  fileName?: string;
  content?: unknown;
}

// ============================================
// Connection Types
// ============================================

/** connect 参数 */
export interface ConnectParams {
  token?: string;
  password?: string;
}

/** connect 响应 */
export interface ConnectResult {
  sessionId: string;
  role: string;
}

/** health 响应 */
export interface HealthResult {
  status: "ok" | "error";
  durationMs?: number;
}

// ============================================
// Reconnection & Health Check Types
// ============================================

/** 重连配置 */
export interface ReconnectConfig {
  /** 初始延迟（毫秒） */
  baseDelayMs: number;
  /** 最大延迟（毫秒） */
  maxDelayMs: number;
  /** 最大重试次数 */
  maxAttempts: number;
  /** 退避因子 */
  backoffFactor: number;
}

/** 健康检查配置 */
export interface HealthCheckConfig {
  /** 是否启用 */
  enabled: boolean;
  /** 心跳间隔（毫秒） */
  heartbeatIntervalMs: number;
  /** 心跳超时（毫秒） */
  heartbeatTimeoutMs: number;
}

// ============================================
// Client Types
// ============================================

/** WebSocket 客户端配置 */
export interface WSClientConfig {
  url: string;
  /** @deprecated 使用 reconnect 替代 */
  reconnectIntervalMs?: number;
  /** @deprecated 使用 reconnect.maxAttempts 替代 */
  maxReconnectAttempts?: number;
  requestTimeoutMs: number;
  /** 重连配置 */
  reconnect: ReconnectConfig;
  /** 健康检查配置 */
  healthCheck: HealthCheckConfig;
}

/** 待处理请求 */
export interface PendingRequest<T = unknown> {
  resolve: (value: T) => void;
  reject: (error: Error) => void;
  timeout: ReturnType<typeof setTimeout>;
}

/** 事件处理器 */
export type EventHandler<T = unknown> = (payload: T) => void;

/** 客户端状态 */
export type WSClientState =
  | "disconnected"
  | "connecting"
  | "connected"
  | "reconnecting";
