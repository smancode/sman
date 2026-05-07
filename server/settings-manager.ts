import fs from 'fs';
import path from 'path';
import crypto from 'crypto';
import { createLogger, type Logger } from './utils/logger.js';
import type { SmanConfig } from './types.js';

const DEFAULT_CONFIG: SmanConfig = {
  port: 5880,
  language: 'zh-CN',
  llm: { apiKey: '', model: '', userProfile: true },
  savedLlms: [],
  currentLlmProfile: '',
  webSearch: {
    provider: 'builtin',
    braveApiKey: '',
    tavilyApiKey: '',
    bingApiKey: '',
    baiduApiKey: '',
    maxUsesPerSession: 50,
  },
  chatbot: {
    enabled: false,
    wecom: { enabled: false, botId: '', secret: '' },
    feishu: { enabled: false, appId: '', appSecret: '' },
    weixin: { enabled: false },
  },
  auth: {
    token: '',
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
      // 首次创建配置，检测系统语言
      const detectedLanguage = this.detectSystemLanguage();
      const initialConfig = { ...DEFAULT_CONFIG, language: detectedLanguage };
      fs.writeFileSync(this.configPath, JSON.stringify(initialConfig, null, 2), { encoding: 'utf-8', mode: 0o600 });
      this.log.info(`Initial config created with language: ${detectedLanguage}`);
      return initialConfig;
    }
    const raw = fs.readFileSync(this.configPath, 'utf-8');
    const config = { ...DEFAULT_CONFIG, ...JSON.parse(raw) };

    // 兼容旧配置：如果没有 language 字段，补充默认值
    if (!config.language) {
      config.language = 'zh-CN';
      this.write(config);
    }

    return config;
  }

  /**
   *检测系统语言
   *中文环境 → zh-CN，其他 → en-US
   */
  private detectSystemLanguage(): string {
    const envLang = process.env.LANG || process.env.LC_ALL || process.env.LC_MESSAGES || '';
    const sysLocale = envLang.split('.')[0]; // 移除编码部分，如 'zh_CN.UTF-8' → 'zh_CN'

    if (sysLocale.toLowerCase().startsWith('zh')) {
      return 'zh-CN';
    }
    return 'en-US';
  }

  private write(config: SmanConfig): void {
    fs.writeFileSync(this.configPath, JSON.stringify(config, null, 2), { encoding: 'utf-8', mode: 0o600 });
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

  saveLlmProfile(profile: import('./types.js').LlmProfile): SmanConfig {
    const config = this.read();
    const existing = config.savedLlms.findIndex(p => p.name === profile.name);
    if (existing >= 0) {
      config.savedLlms[existing] = profile;
    } else {
      config.savedLlms.push(profile);
    }
    config.currentLlmProfile = profile.name;
    config.llm = {
      apiKey: profile.apiKey,
      model: profile.model,
      baseUrl: profile.baseUrl,
      profileModel: profile.profileModel,
      userProfile: profile.userProfile,
      capabilities: profile.capabilities,
    };
    this.write(config);
    return config;
  }

  selectLlmProfile(name: string): SmanConfig {
    const config = this.read();
    const profile = config.savedLlms.find(p => p.name === name);
    if (!profile) return config;
    config.currentLlmProfile = name;
    config.llm = {
      apiKey: profile.apiKey,
      model: profile.model,
      baseUrl: profile.baseUrl,
      profileModel: profile.profileModel,
      userProfile: profile.userProfile,
      capabilities: profile.capabilities,
    };
    this.write(config);
    return config;
  }

  deleteLlmProfile(name: string): SmanConfig {
    const config = this.read();
    config.savedLlms = config.savedLlms.filter(p => p.name !== name);
    if (config.currentLlmProfile === name) {
      config.currentLlmProfile = config.savedLlms[0]?.name ?? '';
      if (config.savedLlms[0]) {
        const p = config.savedLlms[0];
        config.llm = { apiKey: p.apiKey, model: p.model, baseUrl: p.baseUrl, profileModel: p.profileModel, userProfile: p.userProfile, capabilities: p.capabilities };
      } else {
        config.llm = { apiKey: '', model: '', userProfile: true };
      }
    }
    this.write(config);
    return config;
  }

  ensureAuthToken(): string {
    const config = this.read();
    if (config.auth?.token) {
      return config.auth.token;
    }
    const token = crypto.randomBytes(32).toString('hex');
    config.auth = { token };
    this.write(config);
    this.log.info('Generated new auth token');
    return token;
  }
}
