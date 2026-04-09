# 用户自动画像系统 Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 实现对话后自动分析用户行为、持续积累用户画像、在用户消息前拼接画像前缀让 Claude 懂用户（不是注入 system prompt）。

**Architecture:** 新建 `server/user-profile.ts` 画像管理器，Markdown 文件存储画像。三个 sendMessage* 方法中注入画像前缀 + result case 后 fire-and-forget 调用 LLM 更新画像。串行队列保证并发安全。

**Tech Stack:** TypeScript, fs (文件读写), fetch (直接 HTTP 调 Anthropic API), better-sqlite3 (现有), Vitest

**Spec:** `docs/superpowers/specs/2026-04-02-user-profile-design.md`

---

## File Structure

| Action | File | Responsibility |
|--------|------|---------------|
| Create | `server/user-profile.ts` | 画像管理器：加载/更新画像、LLM 调用、串行队列 |
| Create | `tests/server/user-profile.test.ts` | 画像管理器单元测试 |
| Modify | `server/types.ts:28-46` | SmanConfig 新增 `userProfile` 字段 |
| Modify | `server/claude-session.ts:46-55` | 注入 UserProfileManager |
| Modify | `server/claude-session.ts:399-600` | sendMessage 画像注入 + 更新 |
| Modify | `server/claude-session.ts:685-772` | sendMessageForChatbot 画像注入 + 更新 |
| Modify | `server/index.ts:74-75` | 初始化 UserProfileManager |
| Modify | `src/types/settings.ts:5-9` | 前端 LlmConfig 类型同步 |
| Modify | `src/features/settings/LLMSettings.tsx` | 画像开关 UI |

---

## Chunk 1: UserProfileManager Core (画像文件管理 + 测试)

### Task 1: 画像文件加载和空模板创建

**Files:**
- Create: `server/user-profile.ts`
- Create: `tests/server/user-profile.test.ts`

- [ ] **Step 1: 写测试 — 加载空画像模板**

```typescript
// tests/server/user-profile.test.ts
import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { UserProfileManager } from '../../server/user-profile.js';
import fs from 'fs';
import path from 'path';
import os from 'os';

describe('UserProfileManager', () => {
  let homeDir: string;
  let manager: UserProfileManager;

  beforeEach(() => {
    homeDir = path.join(os.tmpdir(), `sman-user-profile-${Date.now()}`);
    fs.mkdirSync(homeDir, { recursive: true });
    manager = new UserProfileManager(homeDir);
  });

  afterEach(() => {
    fs.rmSync(homeDir, { recursive: true, force: true });
  });

  describe('loadProfile', () => {
    it('should create empty template when profile file does not exist', () => {
      const profile = manager.loadProfile();
      expect(profile).toContain('# 用户画像');
      expect(profile).toContain('## 助手身份');
      expect(profile).toContain('## 用户身份');
      expect(profile).toContain('## 技术偏好');
      expect(profile).toContain('## 常用任务');
      expect(profile).toContain('## 失败任务');
      expect(profile).toContain('## 沟通风格');
      expect(profile).toContain('## 回复策略');
      // File should be created on disk
      const filePath = path.join(homeDir, 'user-profile.md');
      expect(fs.existsSync(filePath)).toBe(true);
    });

    it('should load existing profile from file', () => {
      const customProfile = `# 用户画像\n\n## 助手身份\n- 名字: Crumpet`;
      fs.writeFileSync(path.join(homeDir, 'user-profile.md'), customProfile, 'utf-8');
      const profile = manager.loadProfile();
      expect(profile).toBe(customProfile);
    });

    it('should recreate template when file is corrupted (empty)', () => {
      fs.writeFileSync(path.join(homeDir, 'user-profile.md'), '', 'utf-8');
      const profile = manager.loadProfile();
      expect(profile).toContain('# 用户画像');
    });
  });
});
```

- [ ] **Step 2: 运行测试确认失败**

Run: `pnpm vitest run tests/server/user-profile.test.ts`
Expected: FAIL — module not found

- [ ] **Step 3: 实现 UserProfileManager — loadProfile**

```typescript
// server/user-profile.ts
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
  private profilePath: string;
  private log: Logger;
  private updateQueue: Promise<void> = Promise.resolve();

  constructor(private homeDir: string) {
    this.profilePath = path.join(homeDir, PROFILE_FILENAME);
    this.log = createLogger('UserProfileManager');
  }

  /**
   * Load user profile. Creates empty template if not exists or corrupted.
   */
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

  /**
   * Get profile content formatted for prompt injection.
   * Returns empty string if profile is the default template (no user data).
   */
  getProfileForPrompt(): string {
    const profile = this.loadProfile();
    // Don't inject if profile is still the empty template
    if (profile === EMPTY_TEMPLATE) return '';
    return `[用户画像参考 - 仅供参考，不要在回复中提及此段]\n${profile}`;
  }

  private saveProfile(content: string): void {
    fs.writeFileSync(this.profilePath, content, 'utf-8');
  }

  // updateProfile will be implemented in Task 2
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `pnpm vitest run tests/server/user-profile.test.ts`
Expected: PASS (3 tests)

- [ ] **Step 5: Commit**

```bash
git add server/user-profile.ts tests/server/user-profile.test.ts
git commit -m "feat(user-profile): add UserProfileManager with loadProfile and empty template"
```

---

### Task 2: 画像 LLM 更新 + 串行队列 + 输入截断

**Files:**
- Modify: `server/user-profile.ts`
- Modify: `tests/server/user-profile.test.ts`

- [ ] **Step 1: 写测试 — updateProfile 串行队列和截断**

```typescript
// 追加到 tests/server/user-profile.test.ts 的 describe 块内

  describe('updateProfile', () => {
    it('should truncate long inputs', () => {
      const longText = 'a'.repeat(5000);
      const truncated = (manager as any).truncateInput(longText, 2000);
      expect(truncated.length).toBe(2003); // 2000 + '...'
    });

    it('should not truncate short inputs', () => {
      const shortText = 'short message';
      const result = (manager as any).truncateInput(shortText, 2000);
      expect(result).toBe('short message');
    });

    it('should serialize concurrent updates via queue', async () => {
      // Mock the LLM call to track execution order
      const callOrder: number[] = [];
      (manager as any).callLLMForUpdate = async (_existing: string, _userMsg: string, _assistantMsg: string, tag: number) => {
        callOrder.push(tag);
        await new Promise(r => setTimeout(r, 50));
        return `# 用户画像\n\n## 助手身份\n- 更新 ${tag}`;
      };

      // Fire 3 concurrent updates
      const p1 = manager.updateProfile('user1', 'assistant1', 1);
      const p2 = manager.updateProfile('user2', 'assistant2', 2);
      const p3 = manager.updateProfile('user3', 'assistant3', 3);
      await Promise.all([p1, p2, p3]);

      // Should be serialized: 1, 2, 3
      expect(callOrder).toEqual([1, 2, 3]);
    });

    it('should not fail silently when LLM call throws', async () => {
      (manager as any).callLLMForUpdate = async () => {
        throw new Error('LLM error');
      };

      // Should not throw — fire and forget swallows errors
      const profileBefore = manager.loadProfile();
      await manager.updateProfile('user msg', 'assistant msg');
      const profileAfter = manager.loadProfile();

      // Profile should remain unchanged
      expect(profileAfter).toBe(profileBefore);
    });
  });
```

- [ ] **Step 2: 运行测试确认失败**

Run: `pnpm vitest run tests/server/user-profile.test.ts`
Expected: FAIL — updateProfile not defined

- [ ] **Step 3: 实现 updateProfile + truncateInput + callLLMForUpdate**

Add to `UserProfileManager` class in `server/user-profile.ts`:

```typescript
  private static readonly MAX_USER_MSG_LENGTH = 2000;
  private static readonly MAX_ASSISTANT_MSG_LENGTH = 3000;

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
```

Also add `config` field and setter to the class:

```typescript
  private config: import('./types.js').SmanConfig | null = null;

  updateConfig(config: import('./types.js').SmanConfig): void {
    this.config = config;
  }
```

Update constructor to accept optional config:

```typescript
  constructor(private homeDir: string, config?: import('./types.js').SmanConfig) {
    this.profilePath = path.join(homeDir, PROFILE_FILENAME);
    this.log = createLogger('UserProfileManager');
    this.config = config ?? null;
  }
```

- [ ] **Step 4: 运行测试确认通过**

Run: `pnpm vitest run tests/server/user-profile.test.ts`
Expected: PASS (6 tests)

- [ ] **Step 5: Commit**

```bash
git add server/user-profile.ts tests/server/user-profile.test.ts
git commit -m "feat(user-profile): add updateProfile with LLM analysis and serial queue"
```

---

## Chunk 2: 类型定义 + ClaudeSession 集成

### Task 3: SmanConfig 类型扩展

**Files:**
- Modify: `server/types.ts:28-46`
- Modify: `src/types/settings.ts:5-9`

- [ ] **Step 1: 写测试 — 配置中包含 userProfile 字段**

```typescript
// 追加到 tests/server/settings-manager.test.ts 末尾的新 describe 块

describe('UserProfile config', () => {
  let homeDir: string;
  let manager: SettingsManager;

  beforeEach(() => {
    homeDir = path.join(os.tmpdir(), `sman-test-up-${Date.now()}`);
    fs.mkdirSync(homeDir, { recursive: true });
    manager = new SettingsManager(homeDir);
  });

  afterEach(() => {
    fs.rmSync(homeDir, { recursive: true, force: true });
  });

  it('should default userProfile to enabled', () => {
    const config = manager.getConfig();
    expect(config.llm.userProfile).toBe(true);
  });

  it('should persist userProfile config', () => {
    manager.updateLlm({ userProfile: false });
    const config = manager.getConfig();
    expect(config.llm.userProfile).toBe(false);
  });

  it('should persist profileModel config', () => {
    manager.updateLlm({ profileModel: 'claude-haiku-4-5-20251001' });
    const config = manager.getConfig();
    expect(config.llm.profileModel).toBe('claude-haiku-4-5-20251001');
  });
});
```

- [ ] **Step 2: 运行测试确认失败**

Run: `pnpm vitest run tests/server/settings-manager.test.ts`
Expected: FAIL — userProfile property does not exist

- [ ] **Step 3: 更新类型定义**

In `server/types.ts`, update the `llm` field:

```typescript
// server/types.ts line 30-34
export interface SmanConfig {
  port: number;
  llm: {
    apiKey: string;
    model: string;
    baseUrl?: string;
    profileModel?: string;   // 画像分析用模型，不填则使用主模型
    userProfile?: boolean;   // 是否启用用户画像，默认 true
  };
  // ... rest unchanged
}
```

In `src/types/settings.ts`, update `LlmConfig`:

```typescript
// src/types/settings.ts line 5-9
export interface LlmConfig {
  apiKey: string;
  model: string;
  baseUrl?: string;
  profileModel?: string;
  userProfile?: boolean;
}
```

Update `DEFAULT_CONFIG` in `server/settings-manager.ts`:

```typescript
// server/settings-manager.ts line 8
llm: { apiKey: '', model: '', userProfile: true },
```

Update `DEFAULT_SETTINGS` in `src/stores/settings.ts`:

```typescript
// src/stores/settings.ts line 39
llm: { apiKey: '', model: '', userProfile: true },
```

- [ ] **Step 4: 运行测试确认通过**

Run: `pnpm vitest run tests/server/settings-manager.test.ts`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add server/types.ts src/types/settings.ts server/settings-manager.ts src/stores/settings.ts tests/server/settings-manager.test.ts
git commit -m "feat(user-profile): add userProfile config fields to SmanConfig"
```

---

### Task 4: ClaudeSessionManager 集成画像注入和更新

**Files:**
- Modify: `server/claude-session.ts`

- [ ] **Step 1: 注入 UserProfileManager 到 ClaudeSessionManager**

Add import and field to `server/claude-session.ts`:

```typescript
// Add import near top of file
import { UserProfileManager } from './user-profile.js';
```

Add field and setter to `ClaudeSessionManager` class (after line 55):

```typescript
  private userProfile: UserProfileManager | null = null;

  setUserProfile(manager: UserProfileManager): void {
    this.userProfile = manager;
    this.log.info('UserProfileManager injected');
  }
```

- [ ] **Step 2: 修改 sendMessage — 画像注入 + 更新**

In `sendMessage` method (line 415-428), change to:

```typescript
    // Store original user message (no profile prefix)
    this.store.addMessage(sessionId, { role: 'user', content });

    const abortController = new AbortController();
    this.activeStreams.set(sessionId, abortController);

    try {
      const v2Session = await this.getOrCreateV2Session(sessionId);

      wsSend(JSON.stringify({
        type: 'chat.start',
        sessionId,
      }));

      // Inject user profile into content for Claude, but don't store it
      const profilePrefix = this.userProfile?.getProfileForPrompt() ?? '';
      const contentWithProfile = profilePrefix
        ? `${profilePrefix}\n\n${content}`
        : content;

      await v2Session.send(contentWithProfile);
```

In the `result` case of `sendMessage` (after line 573), add fire-and-forget update:

```typescript
            this.log.info(`Query completed for session ${sessionId}, cost: $${cost.toFixed(4)}`);

            // Fire-and-forget: update user profile after conversation turn
            if (this.userProfile && !isError) {
              this.userProfile.updateProfile(content, finalContent);
            }
            break;
```

- [ ] **Step 3: 修改 sendMessageForChatbot — 画像注入 + 更新**

In `sendMessageForChatbot` method (line 706-713), change to:

```typescript
    // Store original user message (no profile prefix)
    this.store.addMessage(sessionId, { role: 'user', content });

    this.activeStreams.set(sessionId, abortController);

    try {
      const v2Session = await this.getOrCreateV2Session(sessionId);

      // Inject user profile into content for Claude
      const profilePrefix = this.userProfile?.getProfileForPrompt() ?? '';
      const contentWithProfile = profilePrefix
        ? `${profilePrefix}\n\n${content}`
        : content;

      await v2Session.send(contentWithProfile);
```

In the `result` case of `sendMessageForChatbot` (after line 758), add fire-and-forget update:

```typescript
            if (fullContent) {
              this.store.addMessage(sessionId, { role: 'assistant', content: fullContent });
            }

            // Fire-and-forget: update user profile after chatbot conversation turn
            if (this.userProfile && !isError) {
              const originalContent = content;
              this.userProfile.updateProfile(originalContent, fullContent);
            }
            break;
```

Note: `sendMessageForCron` does NOT inject profile or update profile (headless execution per design).

- [ ] **Step 4: Commit**

```bash
git add server/claude-session.ts
git commit -m "feat(user-profile): integrate profile injection and update in sendMessage*"
```

---

### Task 5: Server 初始化集成

**Files:**
- Modify: `server/index.ts`

- [ ] **Step 1: 初始化 UserProfileManager 并注入**

Add import near top of `server/index.ts`:

```typescript
import { UserProfileManager } from './user-profile.js';
```

After line 75 (`const settingsManager = new SettingsManager(homeDir);`), add:

```typescript
// User profile manager
const userProfileManager = new UserProfileManager(homeDir, settingsManager.getConfig());
sessionManager.setUserProfile(userProfileManager);
```

Update the `settings.update` handler (around line 485) to sync config:

```typescript
        case 'settings.update': {
          const { type: _t, ...updates } = msg;
          const config = settingsManager.updateConfig(updates as Partial<import('./types.js').SmanConfig>);
          sessionManager.updateConfig(config);
          userProfileManager.updateConfig(config);
          batchEngine.setConfig(config.llm);
```

- [ ] **Step 2: Commit**

```bash
git add server/index.ts
git commit -m "feat(user-profile): initialize UserProfileManager in server startup"
```

---

## Chunk 3: 前端 UI + 最终验证

### Task 6: LLM 设置页添加画像开关

**Files:**
- Modify: `src/features/settings/LLMSettings.tsx`

- [ ] **Step 1: 添加画像开关 UI**

在 `LLMSettings.tsx` 的 Model 输入框后面、保存按钮之前添加:

```tsx
        {/* User Profile Toggle */}
        <div className="flex items-center justify-between py-2">
          <div className="space-y-0.5">
            <Label>用户画像</Label>
            <p className="text-xs text-muted-foreground">
              自动学习你的偏好，让助手更懂你
            </p>
          </div>
          <button
            type="button"
            onClick={() => updateLlm({ userProfile: !llm?.userProfile }).catch(() => {})}
            className={`relative inline-flex h-6 w-11 items-center rounded-full transition-colors ${
              llm?.userProfile !== false ? 'bg-primary' : 'bg-muted'
            }`}
          >
            <span
              className={`inline-block h-4 w-4 rounded-full bg-white transition-transform ${
                llm?.userProfile !== false ? 'translate-x-6' : 'translate-x-1'
              }`}
            />
          </button>
        </div>
```

- [ ] **Step 2: Commit**

```bash
git add src/features/settings/LLMSettings.tsx
git commit -m "feat(user-profile): add profile toggle in LLM settings"
```

---

### Task 7: 最终验证

- [ ] **Step 1: 运行全部测试**

Run: `pnpm vitest run`
Expected: ALL PASS

- [ ] **Step 2: TypeScript 编译检查**

Run: `pnpm tsc --noEmit`
Expected: No errors

- [ ] **Step 3: Final commit (if any fixes needed)**

```bash
git add -A
git commit -m "fix(user-profile): address test and type issues"
```
