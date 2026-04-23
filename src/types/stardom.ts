// src/types/stardom.ts

export interface StardomAgentInfo {
  agentId: string;
  name: string;
  avatar: string;
  status: 'idle' | 'busy' | 'afk' | 'offline';
  reputation: number;
  projects: string[];
}

export interface StardomTask {
  taskId: string;
  direction: 'incoming' | 'outgoing';
  helperAgentId?: string;
  helperName?: string;
  requesterAgentId?: string;
  requesterName?: string;
  question: string;
  status: 'searching' | 'offered' | 'matched' | 'chatting' | 'completed' | 'timeout' | 'cancelled';
  rating?: number;
  createdAt: string;
  completedAt?: string;
}

export interface StardomChatMessage {
  taskId: string;
  from: string;
  text: string;
  timestamp: string;
}

export interface StardomNotification {
  notificationId: string;
  taskId: string;
  from: string;
  question: string;
  mode: 'auto' | 'notify' | 'manual';
  receivedAt: string;
  countdownEndsAt: string | null;
}

export interface StardomTaskChat {
  taskId: string;
  messages: StardomChatMessage[];
}

export interface StardomDigest {
  date: string;
  helpCount: number;
  helpedByCount: number;
  reputationDelta: number;
  timeSavedMinutes: number;
  details: Array<{
    agentName: string;
    question: string;
    duration: string;
    direction: 'in' | 'out';
  }>;
}

export type StardomMode = 'auto' | 'notify' | 'manual';

export interface StardomLeaderboardEntry {
  agentId: string;
  name: string;
  avatar: string;
  reputation: number;
  status: string;
  helpCount: number;
}

export interface StardomConnectionStatus {
  connected: boolean;
  agentId?: string;
  agentName?: string;
  server?: string;
  agentStatus?: 'idle' | 'busy' | 'afk';
  reputation?: number;
  activeSlots: number;
  maxSlots: number;
  collabMode?: StardomMode;
}

export interface WorldAgentPosition {
  agentId: string;
  x: number;
  y: number;
  state: 'idle' | 'walking' | 'busy';
  facing: 'up' | 'down' | 'left' | 'right';
}

export type ActivityType =
  | 'status_change'
  | 'task_event'
  | 'capability_search'
  | 'collab_start'
  | 'collab_complete'
  | 'reputation_change'
  | 'system';

export interface ActivityEntry {
  id: string;
  timestamp: number;
  type: ActivityType;
  agentId?: string;
  agentName?: string;
  agentAvatar?: string;
  description: string;
  metadata?: Record<string, unknown>;
}

export interface StardomCapability {
  capability: string;
  agentId: string;
  agentName: string;
  experience: string;
}
