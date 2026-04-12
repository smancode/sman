// src/features/bazaar/world/types.ts

export interface WorldAgentUpdate {
  agentId: string;
  x: number;
  y: number;
  state: 'idle' | 'walking' | 'busy';
  facing: 'up' | 'down' | 'left' | 'right';
}

export interface WorldZoneEvent {
  agentId: string;
  zone: string;
  action: 'enter' | 'leave';
}

export type ActivePanel = 'leaderboard' | 'tasks' | 'chat' | 'agents';
