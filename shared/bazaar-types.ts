// shared/bazaar-types.ts
// Bazaar 集市服务器与 Sman Bridge 层的共享消息协议类型

// ── Agent 相关 ──

export type AgentStatus = 'idle' | 'busy' | 'afk' | 'offline';

export interface AgentProfile {
  id: string;              // UUID
  username: string;
  hostname: string;
  name: string;            // 显示名
  avatar: string;          // emoji
  status: AgentStatus;
  reputation: number;
  projects: AgentProject[];
  privateCapabilities: PrivateCapability[];
  joinedAt: string;
}

export interface AgentProject {
  repo: string;
  path: string;
  skills: SkillSummary[];
}

export interface SkillSummary {
  id: string;
  name: string;
  triggers: string[];
}

export interface PrivateCapability {
  id: string;
  name: string;
  triggers: string[];
}

// ── 消息协议 ──

export interface BazaarMessage {
  id: string;              // 消息 UUID（幂等去重）
  type: string;
  inReplyTo?: string;
  payload: Record<string, unknown>;
}

export interface BazaarAck {
  type: 'ack';
  id: string;              // 原消息 ID
}

// ── Agent 消息类型 ──

export type AgentMessageType =
  | 'agent.register'
  | 'agent.registered'
  | 'agent.heartbeat'
  | 'agent.update'
  | 'agent.offline';

export interface AgentRegisterPayload {
  agentId: string;
  username: string;
  hostname: string;
  name: string;
  avatar?: string;
  projects: AgentProject[];
  privateCapabilities: PrivateCapability[];
  protocolVersion?: string;
}

export interface AgentHeartbeatPayload {
  agentId: string;
  status: AgentStatus;
  activeTaskCount: number;
}

export interface AgentUpdatePayload {
  agentId: string;
  projects?: AgentProject[];
  privateCapabilities?: PrivateCapability[];
  status?: AgentStatus;
}

// ── Task 消息类型 ──

export type TaskMessageType =
  | 'task.create'
  | 'task.search_result'
  | 'task.offer'
  | 'task.incoming'
  | 'task.accept'
  | 'task.reject'
  | 'task.matched'
  | 'task.chat'
  | 'task.progress'
  | 'task.complete'
  | 'task.result'
  | 'task.timeout'
  | 'task.cancel'
  | 'task.cancelled'
  | 'task.escalate';

// ── World 消息类型 ──

export type WorldMessageType =
  | 'world.move'
  | 'world.agent_update'
  | 'world.enter_zone'
  | 'world.leave_zone'
  | 'world.zone_snapshot'
  | 'world.agent_enter'
  | 'world.agent_leave'
  | 'world.event';

// ── Server 消息类型 ──

export type ServerMessageType =
  | 'ack'
  | 'error'
  | 'server.maintenance'
  | 'agent.kicked'
  | 'agent.resume_tasks'
  | 'world.resync';

// ── Task 状态和协作模式枚举 ──

export type TaskStatus = 'created' | 'searching' | 'offered' | 'matched' | 'chatting' | 'completed' | 'rated' | 'failed';

export type CollaborationMode = 'auto' | 'notify' | 'manual';

// ── Task Payload 骨架（后续 Chunk 扩展） ──

export interface TaskCreatePayload {
  question: string;
  capabilityQuery: string;
  provenance?: string[];
  hopCount?: number;
}

export interface TaskOfferPayload {
  taskId: string;
  candidates: Array<{ agentId: string; reputation: number }>;
}

export interface TaskSearchResultPayload {
  taskId: string;
  matches: Array<{ agentId: string; name: string; status: AgentStatus; reputation: number; repo: string }>;
}

export interface TaskIncomingPayload {
  taskId: string;
  from: string;
  fromName: string;
  question: string;
  deadline: string;
}

export interface TaskAcceptPayload {
  taskId: string;
}

export interface TaskRejectPayload {
  taskId: string;
  reason?: string;
}

export interface TaskMatchedPayload {
  taskId: string;
  helper: { agentId: string; name: string };
}

export interface TaskChatPayload {
  taskId: string;
  text: string;
}

export interface TaskProgressPayload {
  taskId: string;
  status: TaskStatus;
  detail: string;
}

export interface TaskCompletePayload {
  taskId: string;
  rating: number;
  feedback?: string;
}

export interface TaskResultPayload {
  taskId: string;
  reputationDelta: number;
}

export interface ReputationUpdatePayload {
  agentId: string;
  delta: number;
  reason: string;
  newTotal: number;
}

export interface TaskEscalatePayload {
  taskId: string;
  reason: string;
  options: string[];
}

// ── Bazaar 配置（嵌入 SmanConfig） ──

export interface BazaarConfig {
  server: string;          // 集市服务器地址，如 "bazaar.company.com:5890"
  agentName?: string;      // Agent 显示名
  mode: CollaborationMode;  // 协作模式
  maxConcurrentTasks: number;  // 最大并发槽位，默认 3
}
