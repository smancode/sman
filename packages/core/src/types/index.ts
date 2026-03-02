/**
 * SmanCode Core - 类型定义
 *
 * 核心类型系统，基于 Zod 进行运行时验证
 */

import { z } from "zod"

// ============================================================================
// 基础类型
// ============================================================================

/** 唯一标识符 */
export const Identifier = z.string().ulid()
export type Identifier = z.infer<typeof Identifier>

/** 时间戳 */
export const Timestamp = z.object({
  created: z.number(),
  updated: z.number(),
})
export type Timestamp = z.infer<typeof Timestamp>

// ============================================================================
// 消息类型（Message Parts）
// ============================================================================

/** 文本部分 */
export const TextPart = z.object({
  type: z.literal("text"),
  text: z.string(),
})
export type TextPart = z.infer<typeof TextPart>

/** 推理部分 */
export const ReasoningPart = z.object({
  type: z.literal("reasoning"),
  text: z.string(),
})
export type ReasoningPart = z.infer<typeof ReasoningPart>

/** 文件部分 */
export const FilePart = z.object({
  type: z.literal("file"),
  name: z.string(),
  mimeType: z.string(),
  data: z.string().optional(), // base64
  url: z.string().optional(),
})
export type FilePart = z.infer<typeof FilePart>

/** 工具调用部分 */
export const ToolCallPart = z.object({
  type: z.literal("tool_call"),
  id: z.string(),
  name: z.string(),
  arguments: z.record(z.unknown()),
})
export type ToolCallPart = z.infer<typeof ToolCallPart>

/** 工具结果部分 */
export const ToolResultPart = z.object({
  type: z.literal("tool_result"),
  id: z.string(),
  name: z.string(),
  result: z.string(),
  error: z.boolean().optional(),
})
export type ToolResultPart = z.infer<typeof ToolResultPart>

/** 步骤开始部分 */
export const StepStartPart = z.object({
  type: z.literal("step_start"),
  stepId: z.string(),
})
export type StepStartPart = z.infer<typeof StepStartPart>

/** 步骤结束部分 */
export const StepFinishPart = z.object({
  type: z.literal("step_finish"),
  stepId: z.string(),
  tokens: z.object({
    input: z.number(),
    output: z.number(),
    cached: z.number().optional(),
  }),
})
export type StepFinishPart = z.infer<typeof StepFinishPart>

/** 压缩标记部分 */
export const CompactionPart = z.object({
  type: z.literal("compaction"),
  summary: z.string(),
  originalTokens: z.number(),
  compressedTokens: z.number(),
})
export type CompactionPart = z.infer<typeof CompactionPart>

/** 子任务部分 */
export const SubtaskPart = z.object({
  type: z.literal("subtask"),
  taskId: z.string(),
  agent: z.string(),
  status: z.enum(["pending", "running", "completed", "failed"]),
})
export type SubtaskPart = z.infer<typeof SubtaskPart>

/** 消息部分联合类型 */
export const Part = z.discriminatedUnion("type", [
  TextPart,
  ReasoningPart,
  FilePart,
  ToolCallPart,
  ToolResultPart,
  StepStartPart,
  StepFinishPart,
  CompactionPart,
  SubtaskPart,
])
export type Part = z.infer<typeof Part>

// ============================================================================
// 消息类型
// ============================================================================

/** 消息角色 */
export const MessageRole = z.enum(["system", "user", "assistant"])
export type MessageRole = z.infer<typeof MessageRole>

/** 消息 */
export const Message = z.object({
  id: Identifier,
  sessionId: Identifier,
  role: MessageRole,
  parts: z.array(Part),
  timestamp: Timestamp,
  metadata: z.record(z.unknown()).optional(),
})
export type Message = z.infer<typeof Message>

// ============================================================================
// 会话类型
// ============================================================================

/** 会话状态 */
export const SessionStatus = z.enum(["active", "compacting", "archived", "error"])
export type SessionStatus = z.infer<typeof SessionStatus>

/** 会话 */
export const Session = z.object({
  id: Identifier,
  projectId: z.string(),
  parentId: Identifier.optional(),
  title: z.string(),
  status: SessionStatus,
  summary: z.string().optional(),
  tokens: z.object({
    input: z.number(),
    output: z.number(),
    total: z.number(),
  }),
  timestamp: Timestamp,
  metadata: z.record(z.unknown()).optional(),
})
export type Session = z.infer<typeof Session>

// ============================================================================
// Agent 类型
// ============================================================================

/** Agent 模式 */
export const AgentMode = z.enum(["primary", "subagent", "all"])
export type AgentMode = z.infer<typeof AgentMode>

/** Agent 信息 */
export const AgentInfo = z.object({
  name: z.string(),
  description: z.string().optional(),
  mode: AgentMode,
  model: z.object({
    providerId: z.string(),
    modelId: z.string(),
  }).optional(),
  systemPrompt: z.string().optional(),
  temperature: z.number().min(0).max(2).optional(),
  maxSteps: z.number().int().positive().optional(),
  tools: z.array(z.string()).optional(),
  hidden: z.boolean().optional(),
})
export type AgentInfo = z.infer<typeof AgentInfo>

// ============================================================================
// 工具类型
// ============================================================================

/** 工具参数 Schema */
export type ToolParameters = z.ZodType<any, any, any>

/** 工具元数据 */
export const ToolMetadata = z.object({
  title: z.string(),
  duration: z.number().optional(),
  error: z.boolean().optional(),
})
export type ToolMetadata = z.infer<typeof ToolMetadata>

/** 工具执行结果 */
export const ToolResult = z.object({
  title: z.string(),
  output: z.string(),
  metadata: ToolMetadata.optional(),
  attachments: z.array(FilePart).optional(),
})
export type ToolResult = z.infer<typeof ToolResult>

/** 工具信息 */
export const ToolInfo = z.object({
  id: z.string(),
  description: z.string(),
  parameters: z.any(), // Zod Schema
  dangerous: z.boolean().optional(),
  requiresConfirmation: z.boolean().optional(),
})
export type ToolInfo = z.infer<typeof ToolInfo>

// ============================================================================
// 模型类型
// ============================================================================

/** 模型能力 */
export const ModelCapabilities = z.object({
  text: z.boolean(),
  image: z.boolean(),
  audio: z.boolean(),
  video: z.boolean(),
  toolCall: z.boolean(),
  reasoning: z.boolean(),
  caching: z.boolean(),
})
export type ModelCapabilities = z.infer<typeof ModelCapabilities>

/** 模型限制 */
export const ModelLimits = z.object({
  contextWindow: z.number(),
  maxOutput: z.number(),
})
export type ModelLimits = z.infer<typeof ModelLimits>

/** 模型成本 */
export const ModelCost = z.object({
  inputPerMillion: z.number(),
  outputPerMillion: z.number(),
  cachedPerMillion: z.number().optional(),
})
export type ModelCost = z.infer<typeof ModelCost>

/** 模型信息 */
export const ModelInfo = z.object({
  id: z.string(),
  providerId: z.string(),
  name: z.string(),
  capabilities: ModelCapabilities,
  limits: ModelLimits,
  cost: ModelCost,
})
export type ModelInfo = z.infer<typeof ModelInfo>

// ============================================================================
// 权限类型
// ============================================================================

/** 权限动作 */
export const PermissionAction = z.enum(["allow", "deny", "ask"])
export type PermissionAction = z.infer<typeof PermissionAction>

/** 权限规则 */
export const PermissionRule = z.object({
  permission: z.string(),
  pattern: z.string(),
  action: PermissionAction,
})
export type PermissionRule = z.infer<typeof PermissionRule>

/** 权限请求 */
export const PermissionRequest = z.object({
  id: Identifier,
  permission: z.string(),
  patterns: z.array(z.string()),
  context: z.record(z.unknown()).optional(),
})
export type PermissionRequest = z.infer<typeof PermissionRequest>

// ============================================================================
// 向量存储类型
// ============================================================================

/** 向量文档 */
export const VectorDocument = z.object({
  id: Identifier,
  content: z.string(),
  embedding: z.array(z.number()),
  metadata: z.record(z.unknown()),
  timestamp: Timestamp,
})
export type VectorDocument = z.infer<typeof VectorDocument>

/** 搜索结果 */
export const SearchResult = z.object({
  id: Identifier,
  content: z.string(),
  score: z.number(),
  metadata: z.record(z.unknown()).optional(),
})
export type SearchResult = z.infer<typeof SearchResult>

// ============================================================================
// 用户偏好类型
// ============================================================================

/** 用户偏好 */
export const UserPreference = z.object({
  key: z.string(),
  value: z.unknown(),
  source: z.enum(["explicit", "learned", "inferred"]),
  confidence: z.number().min(0).max(1),
  timestamp: Timestamp,
})
export type UserPreference = z.infer<typeof UserPreference>

/** 用户习惯 */
export const UserHabit = z.object({
  pattern: z.string(),
  frequency: z.number(),
  lastTriggered: z.number(),
  context: z.record(z.unknown()),
})
export type UserHabit = z.infer<typeof UserHabit>

// ============================================================================
// 事件类型
// ============================================================================

/** 事件基础 */
export const BaseEvent = z.object({
  type: z.string(),
  timestamp: z.number(),
  sessionId: z.string().optional(),
})
export type BaseEvent = z.infer<typeof BaseEvent>

/** 流式事件 */
export const StreamEvent = z.discriminatedUnion("type", [
  z.object({ type: z.literal("text_start"), partId: z.string() }),
  z.object({ type: z.literal("text_delta"), partId: z.string(), delta: z.string() }),
  z.object({ type: z.literal("text_end"), partId: z.string() }),
  z.object({ type: z.literal("tool_call"), part: ToolCallPart }),
  z.object({ type: z.literal("tool_result"), part: ToolResultPart }),
  z.object({ type: z.literal("reasoning_start"), partId: z.string() }),
  z.object({ type: z.literal("reasoning_delta"), partId: z.string(), delta: z.string() }),
  z.object({ type: z.literal("reasoning_end"), partId: z.string() }),
  z.object({ type: z.literal("step_start"), part: StepStartPart }),
  z.object({ type: z.literal("step_finish"), part: StepFinishPart }),
  z.object({ type: z.literal("error"), error: z.string() }),
  z.object({ type: z.literal("done"), finishReason: z.string() }),
])
export type StreamEvent = z.infer<typeof StreamEvent>
