import fs from 'fs';
import path from 'path';
import { createLogger, type Logger } from './utils/logger.js';

const PROFILE_FILENAME = 'user-profile.md';

const EMPTY_TEMPLATE = `# 用户画像

## 我的身份
- 名字: Sman（默认，用户可自定义）
- 角色设定: Sman 数字助手

## 用户身份
- 用户名称:
- 角色:
- 团队/部门:
- 业务领域:

## 技术偏好
- 主力语言:
- 使用偏好:
## 常用任务
- 高频操作:
- 最近任务:

## 失败任务
## 沟通风格
- 偏好语言: 中文
- 回复详细度:
- 关注点:

## 维护原则
- 画像只记录通用信息（身份、偏好、风格）
- 不记录业务数据（skills、插件、项目背景、常用任务、账户信息等）
- 业务数据属于运行时状态，应实时查询而非硬编码到画像

## 回复策略
- 根据用户习惯调整回复风格和技术深度
`;

export class UserProfileManager {
  private static readonly MAX_USER_MSG_LENGTH = 2000;
  private static readonly MAX_ASSISTANT_MSG_LENGTH = 3000;
  private static readonly UPDATE_INTERVAL_MS = 10 * 60 * 1000; // 10 minutes
  private config: import('./types.js').SmanConfig | null = null;
  private profilePath: string;
  private log: Logger;
  private updateQueue: Promise<void> = Promise.resolve();
  private lastUpdateTime: number = 0;
  private pendingConversations: Array<{ user: string; assistant: string }> = [];
  /** Callback to check if it's safe to call LLM (no active sessions streaming) */
  private isIdle: () => boolean = () => true;

  constructor(private homeDir: string, config?: import('./types.js').SmanConfig) {
    this.profilePath = path.join(homeDir, PROFILE_FILENAME);
    this.log = createLogger('UserProfileManager');
    this.config = config ?? null;
  }

  updateConfig(config: import('./types.js').SmanConfig): void {
    this.config = config;
  }

  /** Set a callback that returns true when no sessions are actively streaming */
  setIdleCheck(check: () => boolean): void {
    this.isIdle = check;
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
    return `[用户画像 - 必须优先遵循以下画像中的所有设定，包括身份、名字、回复策略等]\n${profile}`;
  }

  private saveProfile(content: string): void {
    fs.writeFileSync(this.profilePath, content, 'utf-8');
  }

  /**
   * Validate LLM-generated profile before saving.
   * Rejects profiles with duplicate sections or duplicate body paragraphs (LLM hallucination).
   */
  private isValidProfile(content: string): boolean {
    if (!content.includes('# 用户画像')) return false;
    // Check duplicate ## section headers
    const sections = content.match(/^## .+$/gm);
    if (!sections) return false;
    const seenSections = new Set<string>();
    for (const s of sections) {
      if (seenSections.has(s)) {
        this.log.warn(`Duplicate section detected: ${s}, rejecting profile update`);
        return false;
      }
      seenSections.add(s);
    }
    // Check duplicate body lines (strip whitespace, ignore empty/headers)
    const lines = content.split('\n').map(l => l.trim()).filter(l => l && !l.startsWith('#'));
    const seenLines = new Set<string>();
    let duplicateCount = 0;
    for (const line of lines) {
      if (seenLines.has(line)) {
        duplicateCount++;
        if (duplicateCount >= 2) {
          this.log.warn(`Duplicate body content detected (>=2 repeated lines), rejecting profile update`);
          return false;
        }
      }
      seenLines.add(line);
    }
    return true;
  }

  /**
   * Accumulate conversation data. Profile update runs when idle and 10min has elapsed.
   * Fire-and-forget: errors are logged but not thrown.
   */
  updateProfile(userMsg: string, assistantMsg: string): void {
    // Skip empty conversations
    if (!assistantMsg.trim()) return;

    this.pendingConversations.push({
      user: this.truncateInput(userMsg, UserProfileManager.MAX_USER_MSG_LENGTH),
      assistant: this.truncateInput(assistantMsg, UserProfileManager.MAX_ASSISTANT_MSG_LENGTH),
    });

    const now = Date.now();
    if (now - this.lastUpdateTime < UserProfileManager.UPDATE_INTERVAL_MS) {
      return; // Not yet time
    }

    this.tryFlush();
  }

  /**
   * Wait for all sessions to be idle, then flush pending conversations to LLM.
   * Safe to call anytime — won't run if already queued.
   */
  private tryFlush(): void {
    // Drain pending conversations
    const conversations = this.pendingConversations;
    if (conversations.length === 0) return;
    this.pendingConversations = [];
    this.lastUpdateTime = Date.now();

    this.updateQueue = this.updateQueue.then(async () => {
      // Poll until all sessions are idle (no active streaming)
      while (!this.isIdle()) {
        await new Promise(r => setTimeout(r, 2000));
      }
      try {
        const existing = this.loadProfile();
        const updated = await this.callLLMForUpdate(existing, conversations);
        if (updated && this.isValidProfile(updated)) {
          this.saveProfile(updated);
          this.log.info(`User profile updated (${conversations.length} conversations batched)`);
        } else {
          this.log.warn('LLM returned invalid profile, keeping existing');
        }
      } catch (err) {
        this.log.warn('Profile update skipped', { error: String(err) });
      }
    });
  }

  private truncateInput(text: string, maxLength: number): string {
    if (text.length <= maxLength) return text;
    return text.slice(0, maxLength) + '...';
  }

  /**
   * Call LLM to analyze batched conversations and generate updated profile.
   * Uses direct HTTP call to Anthropic API.
   */
  private async callLLMForUpdate(existing: string, conversations: Array<{ user: string; assistant: string }>): Promise<string> {
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
3. 回复策略要具体可执行，不要空话
4. 不要编造信息，只在有明确证据时更新
5. 助手身份如果用户要求了就更新，否则保持默认

## 严格禁止（不要记录以下内容）
- 已安装的 skills / 插件列表（这是运行时状态，实时查询）
- 项目背景、常用任务、业务领域（这些是业务数据）
- 账户信息、额度、密钥（这些是敏感数据）
- 任何会随时间失效的环境信息

只记录通用信息：身份、语言偏好、沟通风格、回复策略原则。`;

    const conversationText = conversations.map((c, i) =>
      `### 对话 ${i + 1}\n用户: ${c.user}\n助手: ${c.assistant}`
    ).join('\n\n');

    const userPrompt = `## 输入
已有画像：
${existing}

本轮对话（共 ${conversations.length} 条）：
${conversationText}

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
