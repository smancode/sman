/**
 * Settings 类型定义 (Sman)
 * 简化版：只有 LLM 配置 + Web Search 配置
 */

export interface LlmConfig {
  apiKey: string;
  model: string;
  baseUrl?: string;
}

export type WebSearchProvider = 'builtin' | 'brave' | 'tavily';

export interface WebSearchConfig {
  provider: WebSearchProvider;
  braveApiKey: string;
  tavilyApiKey: string;
  maxUsesPerSession: number;
}

export interface SmanSettings {
  port: number;
  llm: LlmConfig;
  webSearch: WebSearchConfig;
}

export const WEB_SEARCH_PROVIDER_OPTIONS: {
  value: WebSearchProvider;
  label: string;
  description: string;
}[] = [
  { value: 'builtin', label: 'Claude 内置', description: '零配置，$10/千次 + token 费用' },
  { value: 'brave', label: 'Brave Search', description: '$5/千次，有免费额度' },
  { value: 'tavily', label: 'Tavily', description: '~$8/千次，1000次/月免费' },
];

