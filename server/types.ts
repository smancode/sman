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
    provider: 'builtin' | 'brave' | 'tavily';
    braveApiKey: string;
    tavilyApiKey: string;
    maxUsesPerSession: number;
  };
}

// === Cron Task Types ===

export interface CronTask {
  id: string;
  workspace: string;
  skillName: string;
  intervalMinutes: number;
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
