/**
 * SmanCode Core - Provider LLM 提供商接口定义
 *
 * 支持多种 LLM 提供商的统一接口
 */

import type { ModelInfo, ModelCapabilities, ModelLimits, ModelCost, Message, Part, StreamEvent } from "../types"

// ============================================================================
// Provider 配置
// ============================================================================

/** Provider 配置 */
export interface ProviderConfig {
  /** Provider ID */
  id: string
  /** Provider 名称 */
  name: string
  /** API Key */
  apiKey?: string
  /** Base URL */
  baseUrl?: string
  /** 默认模型 */
  defaultModel?: string
  /** 其他配置 */
  options?: Record<string, unknown>
}

// ============================================================================
// 模型定义
// ============================================================================

/** 模型配置 */
export interface ModelConfig {
  /** 模型 ID */
  id: string
  /** Provider ID */
  providerId: string
  /** 显示名称 */
  name: string
  /** 能力 */
  capabilities: ModelCapabilities
  /** 限制 */
  limits: ModelLimits
  /** 成本 */
  cost: ModelCost
  /** 别名 */
  aliases?: string[]
}

// ============================================================================
// LLM 请求/响应
// ============================================================================

/** LLM 请求选项 */
export interface LLMRequestOptions {
  /** 模型 ID */
  model: string
  /** 系统提示词 */
  systemPrompt?: string | string[]
  /** 消息 */
  messages: Array<{
    role: "system" | "user" | "assistant"
    content: string | Part[]
  }>
  /** 工具 */
  tools?: Array<{
    name: string
    description: string
    parameters: Record<string, unknown>
  }>
  /** 工具选择 */
  toolChoice?: "auto" | "required" | "none" | { name: string }
  /** 温度 */
  temperature?: number
  /** Top P */
  topP?: number
  /** Top K */
  topK?: number
  /** 最大输出 token */
  maxOutputTokens?: number
  /** 中止信号 */
  abortSignal?: AbortSignal
  /** 缓存控制 */
  cacheControl?: {
    enabled: boolean
    ttl?: number
  }
}

/** LLM 响应 */
export interface LLMResponse {
  /** 文本内容 */
  content: string
  /** 工具调用 */
  toolCalls?: Array<{
    id: string
    name: string
    arguments: Record<string, unknown>
  }>
  /** 结束原因 */
  finishReason: "stop" | "tool_calls" | "length" | "error"
  /** Token 使用 */
  usage: {
    input: number
    output: number
    cached?: number
  }
  /** 模型信息 */
  model: {
    id: string
    providerId: string
  }
}

/** LLM 流式响应 */
export type LLMStreamEvent =
  | { type: "text_start" }
  | { type: "text_delta"; delta: string }
  | { type: "text_end" }
  | { type: "tool_call_start"; id: string; name: string }
  | { type: "tool_call_delta"; id: string; delta: string }
  | { type: "tool_call_end"; id: string; arguments: Record<string, unknown> }
  | { type: "reasoning_start" }
  | { type: "reasoning_delta"; delta: string }
  | { type: "reasoning_end" }
  | { type: "finish"; response: LLMResponse }
  | { type: "error"; error: Error }

// ============================================================================
// Provider 接口
// ============================================================================

/** Provider 接口 */
export interface LLMProvider {
  /** Provider ID */
  readonly id: string

  /** Provider 名称 */
  readonly name: string

  /** 初始化 */
  init(config: ProviderConfig): Promise<void>

  /** 获取可用模型 */
  getModels(): Promise<ModelConfig[]>

  /** 获取模型信息 */
  getModel(modelId: string): Promise<ModelConfig | undefined>

  /** 检查模型是否可用 */
  isModelAvailable(modelId: string): Promise<boolean>

  /** 发送请求 */
  request(options: LLMRequestOptions): Promise<LLMResponse>

  /** 发送流式请求 */
  stream(options: LLMRequestOptions): AsyncGenerator<LLMStreamEvent, LLMResponse>

  /** 销毁 */
  destroy(): Promise<void>
}

// ============================================================================
// Provider 注册表
// ============================================================================

/** Provider 注册表接口 */
export interface ProviderRegistry {
  /** 注册 Provider */
  register(provider: LLMProvider, config: ProviderConfig): Promise<void>

  /** 注销 Provider */
  unregister(providerId: string): Promise<void>

  /** 获取 Provider */
  get(providerId: string): Promise<LLMProvider | undefined>

  /** 获取所有 Provider */
  getAll(): Promise<LLMProvider[]>

  /** 获取所有可用模型 */
  getAllModels(): Promise<ModelConfig[]>

  /** 根据模型 ID 查找 Provider */
  getProviderForModel(modelId: string): Promise<LLMProvider | undefined>

  /** 故障转移 */
  failover(modelId: string, fallbackModels: string[]): Promise<LLMProvider>
}

// ============================================================================
// 认证配置
// ============================================================================

/** 认证类型 */
export type AuthType =
  | { type: "api_key"; key: string }
  | { type: "oauth"; accessToken: string; refreshToken?: string; expiresAt?: number }
  | { type: "none" }

/** 认证配置 */
export interface AuthConfig {
  /** Provider ID */
  providerId: string
  /** Profile ID */
  profileId: string
  /** 认证信息 */
  auth: AuthType
  /** 是否默认 */
  isDefault?: boolean
  /** 冷却时间（秒） */
  cooldownSeconds?: number
  /** 最后使用时间 */
  lastUsed?: number
}

/** 认证管理器接口 */
export interface AuthManager {
  /** 添加认证配置 */
  addAuth(config: AuthConfig): Promise<void>

  /** 获取认证配置 */
  getAuth(providerId: string, profileId?: string): Promise<AuthConfig | undefined>

  /** 获取所有认证配置 */
  getAllAuths(providerId?: string): Promise<AuthConfig[]>

  /** 更新认证配置 */
  updateAuth(providerId: string, profileId: string, updates: Partial<AuthConfig>): Promise<void>

  /** 删除认证配置 */
  removeAuth(providerId: string, profileId: string): Promise<void>

  /** 轮换到下一个可用配置 */
  rotate(providerId: string): Promise<AuthConfig>

  /** 检查是否在冷却中 */
  isInCooldown(providerId: string, profileId: string): Promise<boolean>
}
