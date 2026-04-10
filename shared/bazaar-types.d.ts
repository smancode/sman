export type AgentStatus = 'idle' | 'busy' | 'afk' | 'offline';
export interface AgentProfile {
    id: string;
    username: string;
    hostname: string;
    name: string;
    avatar: string;
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
export interface BazaarMessage {
    id: string;
    type: string;
    inReplyTo?: string;
    payload: Record<string, unknown>;
}
export interface BazaarAck {
    type: 'ack';
    id: string;
}
export type AgentMessageType = 'agent.register' | 'agent.registered' | 'agent.heartbeat' | 'agent.update' | 'agent.offline';
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
export type TaskMessageType = 'task.create' | 'task.search_result' | 'task.offer' | 'task.incoming' | 'task.accept' | 'task.reject' | 'task.matched' | 'task.chat' | 'task.progress' | 'task.complete' | 'task.result' | 'task.timeout' | 'task.cancel' | 'task.cancelled';
export type WorldMessageType = 'world.move' | 'world.agent_update' | 'world.enter_zone' | 'world.leave_zone' | 'world.zone_snapshot' | 'world.agent_enter' | 'world.agent_leave' | 'world.event';
export type ServerMessageType = 'ack' | 'error' | 'server.maintenance' | 'agent.kicked' | 'agent.resume_tasks' | 'world.resync';
export type TaskStatus = 'created' | 'searching' | 'offered' | 'matched' | 'chatting' | 'completed' | 'rated' | 'failed';
export type CollaborationMode = 'auto' | 'notify' | 'manual';
export interface TaskCreatePayload {
    question: string;
    capabilityQuery: string;
    provenance?: string[];
    hopCount?: number;
}
export interface TaskOfferPayload {
    taskId: string;
    candidates: Array<{
        agentId: string;
        reputation: number;
    }>;
}
export interface BazaarConfig {
    server: string;
    agentName?: string;
    mode: CollaborationMode;
    maxConcurrentTasks: number;
}
