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

## 偏好
- 语言:
- 沟通风格:

## 注意
- 只记身份和偏好，不记业务数据
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

  private isValidProfile(content: string): boolean {
    return content.includes('# 用户画像');
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

    const systemPrompt = `根据对话更新用户画像。严格输出 Markdown，不超过 60 行 1500 字符。

## 必须遵守的格式
只有四个 ## 段落，不要增加新的：
\`\`\`
# 用户画像

## 我的身份
- 名字: Sman（用户改了才更新）
- 角色设定: Sman 数字助手

## 用户身份
- 用户名称:
- 角色:
- 团队/部门:

## 偏好
- 语言:
- 沟通风格:
（可加多条具体偏好，每条一行，如"喜欢简洁回复""偏好中文技术术语"）

## 注意
- 只记身份和偏好，不记业务数据
\`\`\`

## 规则
1. 只有确凿证据才更新，不猜测
2. 如果对话没有新的身份/偏好信息，原样返回已有画像
3. 每个偏好一行，不要展开解释
4. 严格禁止记录：项目名、代码路径、skills、插件、任务内容、账户信息、临时状态

## 关键约束
- 最多 4 个 ## 段落（我的身份、用户身份、偏好、注意）
- 每段不超过 15 行
- 总输出不超过 1500 字符
- 超过就删掉最不重要的内容`;

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
