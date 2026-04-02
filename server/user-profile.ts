import fs from 'fs';
import path from 'path';
import { createLogger, type Logger } from './utils/logger.js';

const PROFILE_FILENAME = 'user-profile.md';

const EMPTY_TEMPLATE = `# 用户画像

## 助手身份
- 名字: Sman（默认，用户可自定义）
- 角色设定: Sman 数字助手

## 用户身份
- 用户名称:
- 角色:
- 团队/部门:
- 业务领域:

## 技术偏好
- 主力语言/工具:
- 使用偏好:

## 常用任务
- 高频操作:
- 最近任务:

## 失败任务

## 沟通风格
- 偏好语言: 中文
- 回复详细度:
- 关注点:

## 回复策略
- 根据用户习惯调整回复风格和技术深度
- 注意事项:
`;

export class UserProfileManager {
  private static readonly MAX_USER_MSG_LENGTH = 2000;
  private static readonly MAX_ASSISTANT_MSG_LENGTH = 3000;
  private config: import('./types.js').SmanConfig | null = null;
  private profilePath: string;
  private log: Logger;
  private updateQueue: Promise<void> = Promise.resolve();

  constructor(private homeDir: string, config?: import('./types.js').SmanConfig) {
    this.profilePath = path.join(homeDir, PROFILE_FILENAME);
    this.log = createLogger('UserProfileManager');
    this.config = config ?? null;
  }

  updateConfig(config: import('./types.js').SmanConfig): void {
    this.config = config;
  }

  loadProfile(): string {
    try {
      if (!fs.existsSync(this.profilePath)) {
        this.saveProfile(EMPTY_TEMPLATE);
        return EMPTY_TEMPLATE;
      }
      const content = fs.readFileSync(this.profilePath, 'utf-8');
      if (!content.trim()) {
        this.log.warn('Profile file is empty, recreating template');
        this.saveProfile(EMPTY_TEMPLATE);
        return EMPTY_TEMPLATE;
      }
      return content;
    } catch (err) {
      this.log.error('Failed to load profile, recreating template', { error: String(err) });
      this.saveProfile(EMPTY_TEMPLATE);
      return EMPTY_TEMPLATE;
    }
  }

  getProfileForPrompt(): string {
    const profile = this.loadProfile();
    if (profile === EMPTY_TEMPLATE) return '';
    return `[用户画像参考 - 仅供参考，不要在回复中提及此段]\n${profile}`;
  }

  private saveProfile(content: string): void {
    fs.writeFileSync(this.profilePath, content, 'utf-8');
  }

  /**
   * Update user profile by analyzing conversation with LLM.
   * Serialized via queue to prevent concurrent writes.
   * Fire-and-forget: errors are logged but not thrown.
   */
  updateProfile(userMsg: string, assistantMsg: string): void {
    this.updateQueue = this.updateQueue.then(async () => {
      try {
        const existing = this.loadProfile();
        const truncatedUser = this.truncateInput(userMsg, UserProfileManager.MAX_USER_MSG_LENGTH);
        const truncatedAssistant = this.truncateInput(assistantMsg, UserProfileManager.MAX_ASSISTANT_MSG_LENGTH);
        const updated = await this.callLLMForUpdate(existing, truncatedUser, truncatedAssistant);
        if (updated && updated.includes('# 用户画像')) {
          this.saveProfile(updated);
          this.log.info('User profile updated');
        } else {
          this.log.warn('LLM returned invalid profile, keeping existing');
        }
      } catch (err) {
        this.log.error('Failed to update user profile', { error: String(err) });
      }
    });
  }

  private truncateInput(text: string, maxLength: number): string {
    if (text.length <= maxLength) return text;
    return text.slice(0, maxLength) + '...';
  }

  /**
   * Call LLM to analyze conversation and generate updated profile.
   * Uses direct HTTP call to Anthropic API.
   */
  private async callLLMForUpdate(existing: string, userMsg: string, assistantMsg: string): Promise<string> {
    const config = this.config;
    if (!config?.llm?.apiKey) {
      this.log.warn('No API key configured, skipping profile update');
      return existing;
    }

    const model = config.llm.profileModel || config.llm.model;
    const baseUrl = config.llm.baseUrl || 'https://api.anthropic.com';

    const systemPrompt = `你是用户画像分析引擎。根据已有画像和本轮对话，输出更新后的完整画像。

## 规则
1. 保持精简，总字数 < 800字，宁可少写不要多写
2. 确认的信息直接写，推测的加 [推测] 前缀
3. 失败任务只保留最近5条，相同原因不重复记录
4. 常用任务归纳为一句话，不罗列具体操作
5. 回复策略要具体可执行，不要空话
6. 不要编造信息，只在有明确证据时更新
7. 助手身份如果用户要求了就更新，否则保持默认`;

    const userPrompt = `## 输入
已有画像：
${existing}

本轮对话：
用户: ${userMsg}
助手: ${assistantMsg}

## 输出
直接输出更新后的完整画像 Markdown，不要输出其他内容。`;

    const response = await fetch(`${baseUrl}/v1/messages`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'x-api-key': config.llm.apiKey,
        'anthropic-version': '2023-06-01',
        'anthropic-dangerous-direct-browser-access': 'true',
      },
      body: JSON.stringify({
        model,
        max_tokens: 2048,
        system: systemPrompt,
        messages: [{ role: 'user', content: userPrompt }],
      }),
    });

    if (!response.ok) {
      const errorBody = await response.text();
      throw new Error(`LLM API error: ${response.status} ${errorBody}`);
    }

    const data = await response.json() as any;
    const text = data.content?.[0]?.text;
    if (!text) throw new Error('Empty response from LLM');
    return text;
  }
}
