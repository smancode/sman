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

export type WebSearchProvider = 'builtin' | 'searxng' | 'brave' | 'tavily' | 'baidu';

export interface WebSearchConfig {
  provider: WebSearchProvider;
  braveApiKey: string;
  tavilyApiKey: string;
  bingApiKey: string;
  baiduApiKey: string;
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

export interface StardomSettingsConfig {
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
  stardom?: StardomSettingsConfig;
}

export const WEB_SEARCH_PROVIDER_OPTIONS: {
  value: WebSearchProvider;
  label: string;
  description: string;
  link?: string;
}[] = [
  { value: 'builtin', label: '内置搜索', description: '优先使用内置 WebSearch，自动降级使用已配置的搜索 Key' },
  { value: 'baidu', label: '百度搜索', description: '百度 AI 搜索 API', link: 'https://console.bce.baidu.com/ai-search/overview' },
  { value: 'brave', label: 'Brave Search', description: 'API Key 搜索引擎', link: 'https://brave.com/search/api/' },
  { value: 'tavily', label: 'Tavily', description: 'AI 原生搜索引擎', link: 'https://tavily.com' },
  { value: 'searxng', label: 'SearXNG', description: '免费，无需 API Key，使用公共 SearXNG 实例' },
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

export interface SmartPathStep {
  name?: string;
  userInput: string;
  generatedContent?: string;
}

export interface SmartPath {
  id: string;
  name: string;
  workspace: string;
  steps: string; // JSON string of SmartPathStep[]
  status: SmartPathStatus;
  cronExpression?: string;
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
  reportFileName?: string;
}

export interface SmartPathReference {
  fileName: string;
  updatedAt: string;
}
