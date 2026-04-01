export interface SkillEntry {
  name: string;
  description: string;
  version: string;
  path: string;
  triggers: ('auto-on-init' | 'manual')[];
  tags: string[];
}

export interface Registry {
  version: string;
  skills: Record<string, SkillEntry>;
}

export interface Profile {
  systemId: string;
  name: string;
  workspace: string;
  description: string;
  skills: string[];
  autoTriggers: {
    onInit: string[];
    onConversationStart: string[];
  };
  claudeMdTemplate?: string;
}

export interface SmanConfig {
  port: number;
  llm: {
    apiKey: string;
    model: string;
    baseUrl?: string;
  };
  webSearch: {
    provider: 'builtin' | 'brave' | 'tavily' | 'bing';
    braveApiKey: string;
    tavilyApiKey: string;
    bingApiKey: string;
    maxUsesPerSession: number;
  };
  chatbot: import('./chatbot/types.js').ChatbotConfig;
  auth: {
    token: string;
  };
}

// === Cron Task Types ===

export interface CronTask {
  id: string;
  workspace: string;
  skillName: string;
  cronExpression: string;
  enabled: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface CronRun {
  id: number;
  taskId: string;
  sessionId: string;
  status: 'running' | 'success' | 'failed';
  startedAt: string;
  finishedAt: string | null;
  lastActivityAt: string | null;
  errorMessage: string | null;
}

// === Batch Task Types ===

export type BatchTaskStatus =
  | 'draft' | 'generating' | 'generated' | 'testing' | 'tested'
  | 'saved' | 'queued' | 'running' | 'paused' | 'completed' | 'failed';

export type BatchItemStatus = 'pending' | 'queued' | 'running' | 'success' | 'failed' | 'skipped';

export interface BatchTask {
  id: string;
  workspace: string;
  skillName: string;
  mdContent: string;
  execTemplate: string;
  generatedCode?: string;
  envVars: string;
  concurrency: number;
  retryOnFailure: number;
  status: BatchTaskStatus;
  totalItems: number;
  successCount: number;
  failedCount: number;
  totalCost: number;
  startedAt?: string;
  finishedAt?: string;
  cronEnabled: boolean;
  cronIntervalMinutes?: number;
  createdAt: string;
  updatedAt: string;
}

export interface BatchItem {
  id: number;
  taskId: string;
  itemData: string;
  itemIndex: number;
  status: BatchItemStatus;
  sessionId?: string;
  startedAt?: string;
  finishedAt?: string;
  errorMessage?: string;
  cost: number;
  retries: number;
}
