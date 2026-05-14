export interface HubConfig {
  serverUrl: string;
  enabled: boolean;
}

export interface ReportPayload {
  clientId: string;
  version: string;
  hostname: string;
  ip: string;
  reportTime: string;
  activeSessions: number;
  workspaces?: string[];
}

export interface BroadcastQueryPayload {
  clientId: string;
  since: string;
}

export interface AckPayload {
  clientId: string;
  broadcastIds: string[];
}

export interface BroadcastMessage {
  id: string;
  title: string;
  body: string;
  createdAt: string;
}

export interface EncryptedRequest {
  payload: string;
  timestamp: number;
  pskVersion: number;
}
