// server/bazaar/types.ts
import type { ClaudeSessionManager } from '../claude-session.js';
import type { SettingsManager } from '../settings-manager.js';
import type { SkillsRegistry } from '../skills-registry.js';

// ── 注入的一期公共 API ──

export interface BridgeDeps {
  sessionManager: ClaudeSessionManager;
  settingsManager: SettingsManager;
  skillsRegistry: SkillsRegistry;
  broadcast: (data: string) => void;
  homeDir: string;
}

// ── Agent 本地身份 ──

export interface LocalAgentIdentity {
  agentId: string;
  hostname: string;
  username: string;
  name: string;
  server: string;
}

// ── 协作任务状态 ──

export interface ActiveCollaboration {
  taskId: string;
  helperAgentId: string;
  helperName: string;
  question: string;
  sessionId: string;
  abortController: AbortController;
  startedAt: string;
  lastActivityAt: string;
}

// ── 协作 Slot 管理 ──

export interface CollaborationSlot {
  taskId: string;
  helperAgentId: string;
  helperName: string;
  question: string;
  startedAt: string;
}

// ── Notify 超时管理 ──

export interface NotifyTimeout {
  taskId: string;
  timer: ReturnType<typeof setTimeout>;
  accepted: boolean;
}

// ── 协作对话消息（本地存储） ──

export interface BazaarChatMessage {
  id: number;
  taskId: string;
  from: string;         // 'local' | 'remote' | 'system'
  text: string;
  createdAt: string;
}

// ── BazaarSession 依赖注入 ──

export interface BazaarSessionDeps {
  sessionManager: import('../claude-session.js').ClaudeSessionManager;
  client: import('./bazaar-client.js').BazaarClient;
  store: import('./bazaar-store.js').BazaarStore;
  broadcast: (data: string) => void;
  homeDir: string;
  maxConcurrentTasks: number;
}

// ── Bridge → 前端消息类型 ──

export type BazaarBridgeMessageType =
  | 'bazaar.status'          // Agent 状态更新
  | 'bazaar.task.list'       // 任务列表请求/响应
  | 'bazaar.task.list.update' // 任务列表推送
  | 'bazaar.task.detail'     // 任务详情
  | 'bazaar.task.chat.delta' // 协作对话流
  | 'bazaar.task.cancel'     // 强制结束任务
  | 'bazaar.task.takeover'   // 接手控制
  | 'bazaar.config.update'   // 更新配置
  | 'bazaar.notify'          // 协作请求通知
  | 'bazaar.digest';         // 每日摘要

// ── Bazaar 本地存储接口 ──

export interface BazaarLocalTask {
  taskId: string;
  direction: 'incoming' | 'outgoing';
  helperAgentId?: string;
  helperName?: string;
  requesterAgentId?: string;
  requesterName?: string;
  question: string;
  status: string;
  rating?: number;
  createdAt: string;
  completedAt?: string;
}

// ── MCP Server 依赖注入 ──

export interface BazaarMcpDeps {
  store: import('./bazaar-store.js').BazaarStore;
  client: import('./bazaar-client.js').BazaarClient;
  broadcast: (data: string) => void;
}
