import type { BazaarConfig } from '../shared/bazaar-types.js';

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

export interface DetectedCapabilities {
  text: boolean;
  image: boolean;
  pdf: boolean;
  audio: boolean;
  video: boolean;
  maxInputTokens?: number;
  displayName?: string;
  source: 'api' | 'mapping' | 'probe';
}

export interface LlmProfile {
  name: string;
  apiKey: string;
  model: string;
  baseUrl?: string;
  profileModel?: string;
  userProfile?: boolean;
  capabilities?: DetectedCapabilities;
}

export interface SmanConfig {
  port: number;
  llm: {
    apiKey: string;
    model: string;
    baseUrl?: string;
    profileModel?: string;   // 画像分析用模型，不填则使用主模型
    userProfile?: boolean;   // 是否启用用户画像，默认 true
    capabilities?: DetectedCapabilities;
  };
  savedLlms: LlmProfile[];   // 保存的 LLM 配置列表
  currentLlmProfile: string; // 当前激活的 LLM 配置名称
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
  bazaar?: BazaarConfig;
}

// === Cron Task Types ===

export interface CronTask {
  id: string;
  workspace: string;
  skillName: string;
  cronExpression: string;
  source: 'scan' | 'manual';
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

// === Smart Path Types ===

export type SmartPathStatus = 'draft' | 'ready' | 'running' | 'completed' | 'failed';
export type SmartPathRunStatus = 'running' | 'completed' | 'failed';
export type SmartPathStepMode = 'serial' | 'parallel';

export interface SmartPathAction {
  type?: 'skill' | 'python';
  skillId?: string;
  code?: string;
  description?: string;
  userInput?: string;
  generatedContent?: string;
}

export interface SmartPathStep {
  mode: SmartPathStepMode;
  actions: SmartPathAction[];
}

export interface SmartPath {
  id: string;
  name: string;
  description?: string;
  workspace: string;
  steps: string; // JSON string of SmartPathStep[]
  status: SmartPathStatus;
  createdAt: string;
  updatedAt: string;
}

export interface SmartPathRun {
  id: string;
  pathId: string;
  status: SmartPathRunStatus;
  stepResults: string;
  startedAt: string;
  finishedAt?: string;
  errorMessage?: string;
}
