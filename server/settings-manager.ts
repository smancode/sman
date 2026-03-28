import fs from 'fs';
import path from 'path';
import { createLogger, type Logger } from './utils/logger.js';
import type { SmanConfig } from './types.js';

const DEFAULT_CONFIG: SmanConfig = {
  port: 5880,
  llm: { apiKey: '', model: '' },
  webSearch: {
    provider: 'builtin',
    braveApiKey: '',
    tavilyApiKey: '',
    maxUsesPerSession: 50,
  },
  chatbot: {
    enabled: false,
    wecom: { enabled: false, botId: '', secret: '' },
    feishu: { enabled: false, appId: '', appSecret: '' },
  },
};

export class SettingsManager {
  private configPath: string;
  private log: Logger;

  constructor(homeDir: string) {
    this.configPath = path.join(homeDir, 'config.json');
    this.log = createLogger('SettingsManager');
  }

  private read(): SmanConfig {
    if (!fs.existsSync(this.configPath)) {
      fs.writeFileSync(this.configPath, JSON.stringify(DEFAULT_CONFIG, null, 2), 'utf-8');
      return { ...DEFAULT_CONFIG };
    }
    const raw = fs.readFileSync(this.configPath, 'utf-8');
    return { ...DEFAULT_CONFIG, ...JSON.parse(raw) };
  }

  private write(config: SmanConfig): void {
    fs.writeFileSync(this.configPath, JSON.stringify(config, null, 2), 'utf-8');
    this.log.info('Config saved');
  }

  getConfig(): SmanConfig {
    return this.read();
  }

  updateLlm(updates: Partial<SmanConfig['llm']>): SmanConfig {
    const config = this.read();
    config.llm = { ...config.llm, ...updates };
    this.write(config);
    return config;
  }

  updateWebSearch(updates: Partial<SmanConfig['webSearch']>): SmanConfig {
    const config = this.read();
    config.webSearch = { ...config.webSearch, ...updates };
    this.write(config);
    return config;
  }

  updateConfig(updates: Partial<SmanConfig>): SmanConfig {
    const config = this.read();
    const updated = { ...config, ...updates };
    if (updates.llm) updated.llm = { ...config.llm, ...updates.llm };
    if (updates.webSearch) updated.webSearch = { ...config.webSearch, ...updates.webSearch };
    if (updates.chatbot) updated.chatbot = { ...config.chatbot, ...updates.chatbot };
    this.write(updated);
    return updated;
  }
}
