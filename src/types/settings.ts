/**
 * Settings 类型定义 (Sman)
 */

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

export interface LlmConfig {
  apiKey: string;
  model: string;
  baseUrl?: string;
  profileModel?: string;
  userProfile?: boolean;
  capabilities?: DetectedCapabilities;
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

export type WebSearchProvider = 'builtin' | 'brave' | 'tavily';

export interface WebSearchConfig {
  provider: WebSearchProvider;
  braveApiKey: string;
  tavilyApiKey: string;
  bingApiKey: string;
  maxUsesPerSession: number;
}

export interface ChatbotConfig {
  enabled: boolean;
  wecom: {
    enabled: boolean;
    botId: string;
    secret: string;
  };
  feishu: {
    enabled: boolean;
    appId: string;
    appSecret: string;
  };
  weixin: {
    enabled: boolean;
  };
}

export interface BazaarSettingsConfig {
  server: string;
  agentName?: string;
  mode: 'auto' | 'notify' | 'manual';
  maxConcurrentTasks: number;
}

export interface SmanSettings {
  port: number;
  llm: LlmConfig;
  savedLlms: LlmProfile[];
  currentLlmProfile: string;
  webSearch: WebSearchConfig;
  chatbot: ChatbotConfig;
  bazaar?: BazaarSettingsConfig;
}

export const WEB_SEARCH_PROVIDER_OPTIONS: {
  value: WebSearchProvider;
  label: string;
  description: string;
}[] = [
  { value: 'builtin', label: '内置搜索', description: '仅 Anthropic 官方 API 可用，第三方代理需配 Brave/Tavily' },
  { value: 'brave', label: 'Brave Search', description: '$5/千次，有免费额度，国内可用' },
  { value: 'tavily', label: 'Tavily', description: '~$8/千次，1000次/月免费，国内可用' },
  // Bing Search API 已于 2025-08-11 停用，隐藏选项但保留后端支持以防复活
  // { value: 'bing', label: 'Bing Search', description: 'Azure 认知服务，$7/千次' },
];

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
  latestRun?: CronRun;
  nextRunAt?: string | null;
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

export interface CronSkill {
  name: string;
  hasCrontab: boolean;
  cronExpression?: string;
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
export type SmartPathActionType = 'skill' | 'python';

export interface SmartPathAction {
  type: SmartPathActionType;
  skillId?: string;
  code?: string;
}

export interface SmartPathStep {
  mode: SmartPathStepMode;
  actions: SmartPathAction[];
}

export interface SmartPath {
  id: string;
  name: string;
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
