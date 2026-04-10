// src/types/bazaar.ts

export interface BazaarAgentInfo {
  agentId: string;
  name: string;
  avatar: string;
  status: 'idle' | 'busy' | 'afk' | 'offline';
  reputation: number;
  projects: string[];
}

export interface BazaarTask {
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

export interface BazaarChatMessage {
  taskId: string;
  from: string;
  text: string;
  timestamp: string;
}

export interface BazaarNotification {
  notificationId: string;
  taskId: string;
  from: string;
  question: string;
  mode: 'auto' | 'notify' | 'manual';
  receivedAt: string;
  countdownEndsAt: string | null;
}

export interface BazaarTaskChat {
  taskId: string;
  messages: BazaarChatMessage[];
}

export interface BazaarDigest {
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

export type BazaarMode = 'auto' | 'notify' | 'manual';

export interface BazaarConnectionStatus {
  connected: boolean;
  agentId?: string;
  agentName?: string;
  server?: string;
  agentStatus?: 'idle' | 'busy' | 'afk';
  reputation?: number;
  activeSlots: number;
  maxSlots: number;
}
