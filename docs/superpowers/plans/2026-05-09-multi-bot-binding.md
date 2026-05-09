# Multi Bot Binding Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Support binding multiple WeChat Work bots, each with its own workspace binding, permission mode (full/query), and skill whitelist. Bot sessions are fully isolated from local desktop sessions and from each other.

**Architecture:** Multi-bot profile configuration array replaces the single bot config. Each `WeComBotConnection` instance carries a `botProfileId`. `ChatbotSessionManager` routes messages by profile, enforces read-only mode via `canUseTool`, and manages a global 2-slot concurrency queue. Frontend groups bot sessions under a dedicated "Bot 会话" section in the sidebar.

**Tech Stack:** Express + WebSocket backend, React 19 + Zustand frontend, better-sqlite3 for session persistence, Claude Agent SDK V2 sessions.

**Spec:** `docs/superpowers/specs/2026-05-09-multi-bot-binding-design.md`

---

## File Structure

| Action | File | Responsibility |
|--------|------|---------------|
| Modify | `src/types/settings.ts` | Add `WeComBotProfile` type, change `ChatbotConfig.wecom` |
| Modify | `server/chatbot/types.ts` | Add `botProfileId` to `IncomingMessage` |
| Modify | `server/settings-manager.ts` | Add config migration from old single-bot to new multi-bot |
| Modify | `server/chatbot/chatbot-store.ts` | Add `bot_label` column, update userKey format |
| Modify | `server/chatbot/wecom-bot-connection.ts` | Add `botProfileId` to constructor and `IncomingMessage` |
| Modify | `server/chatbot/chatbot-session-manager.ts` | Multi-bot routing, concurrency queue, mode enforcement, command filtering |
| Modify | `server/claude-session.ts` | Add mode/blockedTools params to `sendMessageForChatbot`, inject `canUseTool` for bot sessions |
| Modify | `server/index.ts` | Multi-connection array, pass botProfiles to manager |
| Modify | `src/types/chat.ts` | Add `source` and `botLabel` to `ChatSession` |
| Modify | `src/stores/chat.ts` | Map `source`/`botLabel` from session.list response |
| Modify | `src/components/SessionTree.tsx` | Three-level grouping: local by workspace, bot by label |
| Modify | `src/features/settings/ChatbotSettings.tsx` | Bot list management UI |
| Modify | `src/stores/settings.ts` | Adapt `updateChatbot` to new structure |
| Modify | `src/locales/zh-CN.json` | New i18n keys |
| Modify | `src/locales/en-US.json` | New i18n keys |

---

## Chunk 1: Backend Data Model & Config Migration

### Task 1: Update TypeScript types (frontend + backend)

**Files:**
- Modify: `src/types/settings.ts:46-61`
- Modify: `server/chatbot/types.ts`
- Modify: `src/stores/settings.ts:62-67`

- [ ] **Step 1: Update `ChatbotConfig` in `src/types/settings.ts`**

Replace the `ChatbotConfig` interface (lines 46-61):

```ts
export interface WeComBotProfile {
  id: string;
  label: string;
  botId: string;
  secret: string;
  mode: 'full' | 'query';
  workspace: string;
  allowedSkills: string[];
  enabled: boolean;
}

export interface ChatbotConfig {
  enabled: boolean;
  wecom: {
    enabled: boolean;
    bots: WeComBotProfile[];
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
```

- [ ] **Step 2: Update `ChatbotConfig` in `server/chatbot/types.ts`**

The backend `SmanConfig` (in `server/types.ts:72`) imports `ChatbotConfig` from `server/chatbot/types.ts`. This file must also be updated to match. Replace the `ChatbotConfig` interface (lines 1-16):

```ts
export interface WeComBotProfile {
  id: string;
  label: string;
  botId: string;
  secret: string;
  mode: 'full' | 'query';
  workspace: string;
  allowedSkills: string[];
  enabled: boolean;
}

export interface ChatbotConfig {
  enabled: boolean;
  wecom: {
    enabled: boolean;
    bots: WeComBotProfile[];
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
```

Also add `botProfileId` to `IncomingMessage`:

```ts
export interface IncomingMessage {
  platform: 'wecom' | 'feishu' | 'weixin';
  userId: string;
  content: string;
  requestId: string;
  chatType: 'single' | 'group' | 'p2p';
  chatId: string;
  media?: MediaAttachment[];
  botProfileId: string;
}
```

- [ ] **Step 3: Update `DEFAULT_SETTINGS` in `src/stores/settings.ts`**

Change line 64 from:

```ts
wecom: { enabled: false, botId: '', secret: '' },
```

To:

```ts
wecom: { enabled: false, bots: [] },
```

- [ ] **Step 4: Commit**

```bash
git add src/types/settings.ts server/chatbot/types.ts src/stores/settings.ts
git commit -m "feat(bot): update types for multi-bot profile, sync frontend+backend"
```

### Task 2: Config migration in SettingsManager

**Files:**
- Modify: `server/settings-manager.ts:21-26`
- Modify: `server/types.ts` (if SmanConfig references ChatbotConfig from settings.ts)

- [ ] **Step 1: Update default config**

Change `DEFAULT_CONFIG.chatbot.wecom` from:

```ts
wecom: { enabled: false, botId: '', secret: '' },
```

To:

```ts
wecom: { enabled: false, bots: [] },
```

- [ ] **Step 2: Add migration logic in `read()` method**

After reading the config file, add migration check:

```ts
private migrateChatbotConfig(config: SmanConfig): void {
  const wecom = (config.chatbot as any).wecom;
  if (wecom && !Array.isArray(wecom.bots) && wecom.botId !== undefined) {
    // Old format: { enabled, botId, secret } → new format: { enabled, bots: [...] }
    const oldBotId = wecom.botId || '';
    const oldSecret = wecom.secret || '';
    const oldEnabled = wecom.enabled || false;
    const newWecom = {
      enabled: oldEnabled && !!oldBotId,
      bots: oldBotId ? [{
        id: crypto.randomUUID(),
        label: 'WeCom Bot',
        botId: oldBotId,
        secret: oldSecret,
        mode: 'full' as const,
        workspace: '',
        allowedSkills: [],
        enabled: true,
      }] : [],
    };
    (config.chatbot as any).wecom = newWecom;
    this.log.info('Migrated chatbot config from single-bot to multi-bot format');
    // Persist the migration
    this.save(config);
  }
}
```

Call `this.migrateChatbotConfig(config)` at the end of `read()` before returning.

- [ ] **Step 3: Verify migration works**

Check that the existing `config.json` in `~/.sman/` is handled correctly. The `save()` call ensures the new format is persisted.

- [ ] **Step 4: Commit**

```bash
git add server/settings-manager.ts
git commit -m "feat(bot): add config migration from single-bot to multi-bot"
```

### Task 3: ChatbotStore schema update

**Files:**
- Modify: `server/chatbot/chatbot-store.ts`

- [ ] **Step 1: Add `bot_label` column migration**

In the `init()` method, after the `CREATE TABLE` statements, add:

```ts
// Migrate: add bot_label column if missing
const columns = this.db.pragma('table_info(chatbot_sessions)') as Array<{ name: string }>;
if (!columns.find(c => c.name === 'bot_label')) {
  this.db.exec('ALTER TABLE chatbot_sessions ADD COLUMN bot_label TEXT');
  this.log.info('Migrated chatbot_sessions: added bot_label column');
}
```

- [ ] **Step 2: Update `setSession` to include `bot_label`**

Change method signature and SQL:

```ts
setSession(userKey: string, workspace: string, sessionId: string, botLabel?: string): void {
  this.db.prepare(
    `INSERT INTO chatbot_sessions (user_key, workspace, session_id, bot_label, created_at, last_active_at)
     VALUES (?, ?, ?, ?, datetime('now'), datetime('now'))
     ON CONFLICT(user_key, workspace) DO UPDATE SET
       session_id = excluded.session_id,
       bot_label = excluded.bot_label,
       last_active_at = datetime('now')`
  ).run(userKey, workspace, sessionId, botLabel ?? null);
}
```

- [ ] **Step 3: Add method to get sessions with bot info**

```ts
getSessionsWithBotInfo(): Array<{ userKey: string; sessionId: string; botLabel: string | null; workspace: string }> {
  return this.db.prepare(
    'SELECT user_key as userKey, session_id as sessionId, bot_label as botLabel, workspace FROM chatbot_sessions ORDER BY last_active_at DESC'
  ).all() as Array<{ userKey: string; sessionId: string; botLabel: string | null; workspace: string }>;
}
```

- [ ] **Step 4: Commit**

```bash
git add server/chatbot/chatbot-store.ts
git commit -m "feat(bot): add bot_label column and multi-bot store methods"
```

---

## Chunk 2: Backend Connection & Session Manager

### Task 4: WeComBotConnection carries botProfileId

**Files:**
- Modify: `server/chatbot/wecom-bot-connection.ts`

- [ ] **Step 1: Add `botProfileId` to config interface and constructor**

Change `WeComBotConfig` interface (line 7-11):

```ts
interface WeComBotConfig {
  botId: string;
  secret: string;
  botProfileId: string;
  onMessage: (msg: IncomingMessage, sender: ChatResponseSender) => Promise<void>;
}
```

Store it:

```ts
export class WeComBotConnection {
  // ... existing fields ...
  private readonly botProfileId: string;

  constructor(config: WeComBotConfig) {
    this.config = config;
    this.botProfileId = config.botProfileId;
    this.log = createLogger(`WeComBot:${config.botProfileId.substring(0, 8)}`);
  }
```

- [ ] **Step 2: Set `botProfileId` on outgoing `IncomingMessage`**

In `handleMsgCallback()`, when constructing the `IncomingMessage` object (around line 280), add:

```ts
const incoming: IncomingMessage = {
  platform: 'wecom',
  userId,
  content,
  requestId,
  chatType,
  chatId,
  media,
  botProfileId: this.botProfileId,
};
```

- [ ] **Step 3: Commit**

```bash
git add server/chatbot/wecom-bot-connection.ts
git commit -m "feat(bot): pass botProfileId through WeComBotConnection"
```

### Task 5: Multi-bot startChatbotConnections

**Files:**
- Modify: `server/index.ts:297-345`

- [ ] **Step 1: Change single connection variable to array**

Replace:

```ts
let wecomConnection: WeComBotConnection | null = null;
```

With:

```ts
let wecomConnections: WeComBotConnection[] = [];
```

- [ ] **Step 2: Rewrite `startChatbotConnections()` to iterate bots array**

```ts
function startChatbotConnections(): void {
  const chatbotConfig = settingsManager.getConfig().chatbot;
  if (!chatbotConfig?.enabled) return;

  if (chatbotConfig.wecom.enabled && chatbotConfig.wecom.bots?.length > 0) {
    for (const bot of chatbotConfig.wecom.bots) {
      if (!bot.enabled || !bot.botId || !bot.secret) continue;
      const conn = new WeComBotConnection({
        botId: bot.botId,
        secret: bot.secret,
        botProfileId: bot.id,
        onMessage: (msg, sender) => chatbotManager.handleMessage(msg, sender),
      });
      conn.start();
      wecomConnections.push(conn);
    }
    log.info(`WeCom bot connections started: ${wecomConnections.length} bots`);
  }

  // Feishu: pass botProfileId='default' for single-instance compatibility
  if (chatbotConfig.feishu.enabled && chatbotConfig.feishu.appId && chatbotConfig.feishu.appSecret) {
    feishuConnection = new FeishuBotConnection({
      appId: chatbotConfig.feishu.appId,
      appSecret: chatbotConfig.feishu.appSecret,
      botProfileId: 'default',
      onMessage: (msg, sender) => chatbotManager.handleMessage(msg, sender),
    });
    feishuConnection.start();
    log.info('Feishu bot connection started');
  }

  // Weixin: pass botProfileId='default' for single-instance compatibility
  if (chatbotConfig.weixin?.enabled) {
    weixinConnection = new WeixinBotConnection({
      homeDir,
      botProfileId: 'default',
      onMessage: (msg, sender) => chatbotManager.handleMessage(msg, sender),
      onStatusChange: (status) => {
        broadcast(JSON.stringify({ type: 'chatbot.weixin.status', status }));
      },
    });
    weixinConnection.start();
    log.info('WeChat bot connection initialized');
  }
}
```

**Important:** `FeishuBotConnection` and `WeixinBotConnection` also need `botProfileId` added to their config interfaces, and they must set `msg.botProfileId` on outgoing `IncomingMessage` objects — same pattern as `WeComBotConnection` in Task 4. The `getBotProfile` function passed to `ChatbotSessionManager` must return a fallback profile for `'default'` (see Task 7).
```

- [ ] **Step 3: Update stop logic**

In the `settings.update` handler (around line 1183) and `SIGINT` handler (around line 2064), replace:

```ts
wecomConnection?.stop();
```

With:

```ts
wecomConnections.forEach(c => c.stop());
wecomConnections = [];
```

- [ ] **Step 4: Commit**

```bash
git add server/index.ts
git commit -m "feat(bot): support multiple WeCom bot connections"
```

### Task 6: ChatbotSessionManager multi-bot routing + concurrency

**Files:**
- Modify: `server/chatbot/chatbot-session-manager.ts`

This is the largest single-file change. The key modifications:

1. Accept `botProfiles` lookup in constructor
2. New userKey format `wecom:{botProfileId}:{userId}`
3. Mode-based workspace resolution (fixed for query, cd-able for full)
4. Command filtering (disable cd/workspaces for query mode)
5. Global 2-slot concurrency queue
6. Session ID format `chatbot-{botProfileId}-{ts}-{rand}`

- [ ] **Step 1: Update constructor to accept bot profile lookup**

```ts
export class ChatbotSessionManager {
  private log: Logger;
  private store: ChatbotStore;
  private sessionManager: ClaudeSessionManager;
  private activeQueries = new Map<string, AbortController>();
  private onSessionCreated?: (sessionId: string, label: string) => void;
  // Global bot concurrency: max 2 simultaneous bot queries
  private activeBotCount = 0;
  private maxBotConcurrency = 2;
  private botQueue: Array<{ userKey: string; sender: ChatResponseSender; execute: () => Promise<void> }> = [];
  // Bot profile lookup
  private getBotProfile: (botProfileId: string) => import('./types.js').WeComBotProfile | undefined;

  constructor(
    private homeDir: string,
    sessionManager: ClaudeSessionManager,
    store: ChatbotStore,
    getBotProfile: (botProfileId: string) => import('./types.js').WeComBotProfile | undefined,
    onSessionCreated?: (sessionId: string, label: string) => void,
  ) {
    this.log = createLogger('ChatbotSessionManager');
    this.sessionManager = sessionManager;
    this.store = store;
    this.getBotProfile = getBotProfile;
    this.onSessionCreated = onSessionCreated;
  }
```

Note: `WeComBotProfile` must be exported from `server/chatbot/types.ts` (or import from `src/types/settings.ts` on the server side). Since the server compiles to ESM and the types file is shared, define `WeComBotProfile` in `server/chatbot/types.ts` or import it from the shared settings type. For simplicity, import from `../../src/types/settings.js` at build time or define inline.

**Recommended:** Define `WeComBotProfile` in `server/chatbot/types.ts` to keep server types self-contained:

```ts
// In server/chatbot/types.ts
export interface WeComBotProfile {
  id: string;
  label: string;
  botId: string;
  secret: string;
  mode: 'full' | 'query';
  workspace: string;
  allowedSkills: string[];
  enabled: boolean;
}
```

The frontend `src/types/settings.ts` imports from this same shape. Keep both in sync — or share via a common types file. Given the existing pattern (frontend has its own `ChatbotConfig` type), keep both but ensure they match.

- [ ] **Step 2: Update `handleMessage` with bot profile routing**

Replace the existing `handleMessage` method:

```ts
async handleMessage(msg: IncomingMessage, sender: ChatResponseSender): Promise<void> {
  const botProfile = this.getBotProfile(msg.botProfileId);
  if (!botProfile) {
    sender.finish('Bot 配置未找到，请联系管理员。');
    return;
  }

  const userKey = `${msg.platform}:${botProfile.id}:${msg.userId}`;
  const mode = botProfile.mode;

  const parseResult = parseChatCommand(msg.content);

  if (parseResult.isCommand) {
    if (parseResult.command) {
      await this.handleCommand(userKey, parseResult.command, sender, mode, botProfile);
    } else {
      this.handleHelp(sender, true, mode);
    }
    return;
  }

  // Resolve workspace based on mode
  let workspace: string;
  if (mode === 'query') {
    // Query mode: workspace is fixed from bot profile
    workspace = botProfile.workspace;
    if (!workspace) {
      sender.finish('Bot 未绑定项目目录，请联系管理员配置。');
      return;
    }
  } else {
    // Full mode: use stored user state or auto-select
    let userState = this.store.getUserState(userKey);
    if (!userState?.currentWorkspace) {
      const workspaces = this.getDesktopWorkspaces();
      if (workspaces.length > 0) {
        workspace = workspaces[0].path;
        this.store.setUserState(userKey, workspace);
      } else {
        sender.finish('暂无可用的项目目录。请先在桌面端打开一个项目。');
        return;
      }
    } else {
      workspace = userState.currentWorkspace;
    }
  }

  // Reject if this user already has an active query
  if (this.activeQueries.has(userKey)) {
    sender.finish('你有一条消息正在处理中，请稍后再试。');
    return;
  }

  // Global bot concurrency control
  if (this.activeBotCount >= this.maxBotConcurrency) {
    const queuePos = this.botQueue.length + 1;
    // Use sendChunk (not finish) so the sender stays alive for the actual response later
    sender.sendChunk(`当前有 ${this.activeBotCount} 个请求在处理中，你排在第 ${queuePos} 位，请稍候...`);
    return new Promise<void>((resolve) => {
      this.botQueue.push({
        userKey,
        sender,
        execute: async () => {
          sender.sendChunk('开始处理你的问题...');
          await this.executeChatQuery(userKey, workspace, msg.content, sender, msg.media, mode, botProfile);
          resolve();
        },
      });
    });
  }

  this.activeBotCount++;
  try {
    await this.executeChatQuery(userKey, workspace, msg.content, sender, msg.media, mode, botProfile);
  } finally {
    this.activeBotCount--;
    this.processQueue();
  }
}

private processQueue(): void {
  while (this.botQueue.length > 0 && this.activeBotCount < this.maxBotConcurrency) {
    const next = this.botQueue.shift()!;
    // Skip if the queued user now has an active query (they sent another message that was processed first)
    if (this.activeQueries.has(next.userKey)) {
      next.sender.finish('你有一条消息正在处理中，跳过排队消息。');
      continue;
    }
    this.activeBotCount++;
    next.execute().finally(() => {
      this.activeBotCount--;
      this.processQueue();
    });
  }
}
```

- [ ] **Step 3: Update `handleCommand` with mode filtering**

```ts
private async handleCommand(
  userKey: string,
  command: { command: string; args: string; rawCommand: string },
  sender: ChatResponseSender,
  mode: 'full' | 'query',
  botProfile: import('./types.js').WeComBotProfile,
): Promise<void> {
  switch (command.command) {
    case 'cd':
      if (mode === 'query') {
        sender.finish('当前为只读答疑模式，不支持切换项目。');
        return;
      }
      await this.handleCd(userKey, command.args, sender);
      break;
    case 'workspaces':
      if (mode === 'query') {
        sender.finish(`当前绑定项目: ${path.basename(botProfile.workspace)}\n(只读模式，不支持切换)`);
        return;
      }
      this.handleWorkspaces(sender);
      break;
    case 'pwd':
      if (mode === 'query') {
        sender.finish(`当前项目: ${botProfile.workspace}`);
      } else {
        this.handlePwd(userKey, sender);
      }
      break;
    case 'help':
      this.handleHelp(sender, false, mode);
      break;
    case 'status':
      this.handleStatus(userKey, sender);
      break;
    case 'new':
      this.handleNew(userKey, sender);
      break;
    default:
      sender.finish(`未知命令: //${command.rawCommand}\n使用 //help 查看所有命令。`);
  }
}
```

- [ ] **Step 4: Update `handleHelp` with mode-aware output**

```ts
private handleHelp(sender: ChatResponseSender, showUnknownHint = false, mode: 'full' | 'query' = 'full'): void {
  const prefix = showUnknownHint ? '未知命令，' : '';
  if (mode === 'query') {
    sender.finish(
      `${prefix}可用命令:\n` +
      `//new - 新建会话，清空上下文重新开始\n` +
      `//help - 显示此帮助信息\n` +
      `//status - 显示当前状态\n\n` +
      `直接发送消息即可提问。`
    );
  } else {
    sender.finish(
      `${prefix}可用系统命令:\n` +
      `//workspaces  or  //wss - 列出桌面端已打开的项目\n` +
      `//cd <项目名或路径> - 切换工作目录 (支持 ~ 路径)\n` +
      `//pwd - 显示当前工作目录\n` +
      `//new - 新建会话，清空上下文重新开始\n` +
      `//help - 显示此帮助信息\n\n` +
      `直接发送消息开启对话。`
    );
  }
}
```

- [ ] **Step 5: Update `ensureSession` with new session ID format and bot label**

```ts
private ensureSession(userKey: string, workspacePath: string, platform: string, botProfile: import('./types.js').WeComBotProfile): void {
  let session = this.store.getSession(userKey, workspacePath);
  if (session) {
    const restored = this.sessionManager.restoreSession(session.sessionId);
    const active = this.sessionManager.listSessions().find(s => s.id === session!.sessionId);
    if (active) {
      if (restored) {
        this.onSessionCreated?.(session.sessionId, active.label || '');
      }
      return;
    }
    this.log.info(`Session ${session.sessionId} was deleted, recreating...`);
    this.store.deleteSession(userKey, workspacePath);
  }

  const sessionId = `chatbot-${botProfile.id}-${Date.now()}-${uuidv4().substring(0, 8)}`;
  this.sessionManager.createSessionWithId(workspacePath, sessionId, false);

  const label = this.buildLabel(platform, userKey, workspacePath);
  this.sessionManager.updateSessionLabel(sessionId, label);

  this.store.setSession(userKey, workspacePath, sessionId, botProfile.label);

  this.onSessionCreated?.(sessionId, label);
}
```

- [ ] **Step 6: Update `executeChatQuery` to pass mode and botProfile**

Add `mode` and `botProfile` parameters:

```ts
private async executeChatQuery(
  userKey: string,
  workspace: string,
  content: string,
  sender: ChatResponseSender,
  media?: import('./types.js').MediaAttachment[],
  mode: 'full' | 'query' = 'full',
  botProfile?: import('./types.js').WeComBotProfile,
): Promise<void> {
  const session = this.store.getSession(userKey, workspace);
  if (!session) {
    try {
      this.ensureSession(userKey, workspace, userKey.split(':')[0], botProfile!);
    } catch (err) {
      sender.error(`创建会话失败: ${err instanceof Error ? err.message : String(err)}`);
      return;
    }
  }

  const sessionId = this.store.getSession(userKey, workspace)!.sessionId;
  const abortController = new AbortController();
  this.activeQueries.set(userKey, abortController);

  const timeout = setTimeout(() => {
    abortController.abort();
    this.log.info(`Query timeout for ${userKey}`);
  }, QUERY_TIMEOUT_MS);

  try {
    sender.start();

    const fullContent = await this.sessionManager.sendMessageForChatbot(
      sessionId,
      content,
      abortController,
      () => {},
      (chunk) => sender.sendChunk(chunk),
      media,
      (chunk) => sender.sendThinking(chunk),
      (toolName, status) => sender.sendToolStatus(toolName, status),
      mode,
      botProfile?.allowedSkills,
    );

    const cleaned = fullContent.replace(/\(no content\)/g, '').trim();
    sender.finish(cleaned || '(空响应)');
  } catch (err) {
    const message = err instanceof Error ? err.message : String(err);
    if (abortController.signal.aborted) {
      sender.error('请求超时，请稍后再试。');
    } else {
      sender.error(`处理失败: ${message}`);
    }
  } finally {
    clearTimeout(timeout);
    this.activeQueries.delete(userKey);
  }
}
```

- [ ] **Step 7: Update `handleNew` to pass botProfile**

In `handleNew`, the `ensureSession` call needs a botProfile. Store the botProfileId in userKey so we can derive it:

```ts
private handleNew(userKey: string, sender: ChatResponseSender): void {
  const state = this.store.getUserState(userKey);
  if (!state?.currentWorkspace) {
    sender.finish('当前未设置工作目录，无需新建会话。');
    return;
  }

  const workspace = state.currentWorkspace;
  const oldSession = this.store.getSession(userKey, workspace);

  if (oldSession?.sessionId) {
    this.sessionManager.abort(oldSession.sessionId);
    this.sessionManager.closeV2Session(oldSession.sessionId);
  }

  this.store.deleteSession(userKey, workspace);

  // Extract botProfileId from userKey: "wecom:{botProfileId}:{userId}"
  const parts = userKey.split(':');
  const botProfileId = parts[1];
  const botProfile = this.getBotProfile(botProfileId);
  if (botProfile) {
    this.ensureSession(userKey, workspace, parts[0], botProfile);
  }

  const projectName = path.basename(workspace);
  sender.finish(`已新建会话: ${projectName}\n旧会话的聊天记录仍可在桌面端查看。`);
}
```

- [ ] **Step 8: Update `handleCd` to work with new userKey format**

The `handleCd` method splits userKey to get platform. Update `ensureSession` call inside `handleCd`:

```ts
// In handleCd, after resolving workspacePath:
this.store.setUserState(userKey, workspacePath);
const parts = userKey.split(':');
const botProfileId = parts.length >= 2 ? parts[1] : '';
const botProfile = this.getBotProfile(botProfileId);
if (botProfile) {
  this.ensureSession(userKey, workspacePath, parts[0], botProfile);
} else {
  // Fallback for old-format userKey
  this.ensureSession(userKey, workspacePath, parts[0], { id: botProfileId, label: '', mode: 'full', workspace: workspacePath } as any);
}
```

- [ ] **Step 9: Commit**

```bash
git add server/chatbot/chatbot-session-manager.ts server/chatbot/types.ts
git commit -m "feat(bot): multi-bot routing, concurrency control, mode enforcement"
```

### Task 7: Pass botProfile lookup to ChatbotSessionManager

**Files:**
- Modify: `server/index.ts:297-302`

**Note:** This task must be done BEFORE Task 5 (or merged with it), because Task 5 changes `startChatbotConnections()` which uses `chatbotManager`, and the constructor signature change here must land first to avoid a broken build.

- [ ] **Step 1: Update ChatbotSessionManager constructor call**

The lookup function must handle `'default'` botProfileId (used by Feishu/Weixin) by returning a synthetic full-mode profile:

```ts
const chatbotManager = new ChatbotSessionManager(
  homeDir,
  sessionManager,
  chatbotStore,
  (botProfileId: string) => {
    // Feishu/Weixin use 'default' as botProfileId — return a synthetic full-mode profile
    if (botProfileId === 'default') {
      return {
        id: 'default',
        label: 'Bot',
        botId: '',
        secret: '',
        mode: 'full' as const,
        workspace: '',
        allowedSkills: [],
        enabled: true,
      };
    }
    const config = settingsManager.getConfig().chatbot;
    if (!config?.wecom?.bots) return undefined;
    return config.wecom.bots.find(b => b.id === botProfileId);
  },
  (sessionId: string, label: string) => {
    sendToSessionClients(sessionId, JSON.stringify({ type: 'session.chatbotCreated', sessionId, label }));
  },
);
```

- [ ] **Step 2: Commit (merge with Task 5 if both are in the same chunk)**

```bash
git add server/index.ts
git commit -m "feat(bot): wire bot profile lookup into ChatbotSessionManager"
```

---

## Chunk 3: Read-Only Mode & Skill Filtering

### Task 8: sendMessageForChatbot mode + canUseTool injection

**Files:**
- Modify: `server/claude-session.ts:2230-2442` (sendMessageForChatbot)
- Modify: `server/claude-session.ts:813-897` (getOrCreateV2Session)

- [ ] **Step 1: Add mode and allowedSkills params to sendMessageForChatbot**

Update signature:

```ts
async sendMessageForChatbot(
  sessionId: string,
  content: string,
  abortController: AbortController,
  onActivity: () => void,
  onResponse: (chunk: string) => void,
  media?: MediaAttachment[],
  onThinking?: (chunk: string) => void,
  onToolStatus?: (toolName: string, status: 'start' | 'end') => void,
  mode: 'full' | 'query' = 'full',
  allowedSkills?: string[],
): Promise<string> {
```

- [ ] **Step 2: Inject query-mode context prefix**

After building `smanContext` and `profilePrefix` (around line 2327), add mode-specific prefix:

```ts
let modePrefix = '';
if (mode === 'query') {
  const projectName = path.basename(workspace);
  const skillList = allowedSkills?.length ? allowedSkills.join(', ') : '全部';
  modePrefix = `\n[只读答疑模式] 你是一个只读答疑助手，绑定项目: ${projectName}。\n你可以查阅代码和文档来回答问题，但不能修改任何文件。\n你可用的技能: ${skillList}\n如果用户要求修改文件或执行命令，请告知你只有查询权限。\n`;
}
const messagePrefix = [smanContext, profilePrefix, modePrefix]
  .filter(Boolean).join('\n');
```

- [ ] **Step 3: Pass mode to getOrCreateV2Session for canUseTool injection**

The key change: `getOrCreateV2Session` needs to accept a `canUseTool` override so that `sendMessageForChatbot` can inject the read-only filter.

Add parameter to `getOrCreateV2Session`:

```ts
private async getOrCreateV2Session(
  sessionId: string,
  canUseToolOverride?: (params: { toolName: string; input: Record<string, unknown> }) => Promise<{ behavior: string; updatedInput?: Record<string, unknown> }>,
): Promise<{ session: SDKSession; isFresh: boolean }> {
```

In the V2 session creation block (around line 862), replace the `canUseTool` injection:

```ts
// Inject canUseTool callback
if (canUseToolOverride) {
  // Bot session with custom tool policy (e.g., query mode read-only)
  (options as any).canUseTool = canUseToolOverride;
} else if (!isScanner) {
  // Desktop session: bridge AskUserQuestion to frontend
  const capturedSessionId = sessionId;
  (options as any).canUseTool = async (params) => {
    if (params.toolName !== 'AskUserQuestion') {
      return { behavior: 'allow' as const };
    }
    // ... existing AskUserQuestion bridge logic unchanged ...
  };
}
```

- [ ] **Step 4: Build canUseTool override for query mode in sendMessageForChatbot**

Before calling `getOrCreateV2Session`:

```ts
const READ_BLOCKED_TOOLS = new Set(['Write', 'Edit', 'Bash', 'NotebookEdit']);

let canUseToolOverride: ((params: { toolName: string; input: Record<string, unknown> }) => Promise<{ behavior: string; updatedInput?: Record<string, unknown> }>) | undefined;

if (mode === 'query') {
  canUseToolOverride = async (params) => {
    if (params.toolName === 'AskUserQuestion') {
      // No frontend bridge for bot sessions — deny
      return { behavior: 'deny' as const };
    }
    if (READ_BLOCKED_TOOLS.has(params.toolName)) {
      return { behavior: 'deny' as const };
    }
    // Skill whitelist enforcement: if allowedSkills is set and the tool call
    // references a skill name not in the whitelist, deny it.
    // Claude calls skills via the Skill tool or MCP tools named after skills.
    if (allowedSkills && allowedSkills.length > 0) {
      const toolName = params.toolName;
      // Skill tool: check the skill name in the input
      if (toolName === 'Skill' && params.input?.skill) {
        const skillName = String(params.input.skill);
        if (!allowedSkills.some(s => skillName === s || skillName.endsWith(`:${s}`))) {
          return { behavior: 'deny' as const };
        }
      }
      // MCP skill tools: typically named like "skill--name" or "skill_name"
      // Check if the tool name contains a skill name not in the whitelist
      if (toolName.startsWith('skill-') || toolName.startsWith('skill_')) {
        const isAllowed = allowedSkills.some(s =>
          toolName.includes(s) || toolName.endsWith(`-${s}`) || toolName.endsWith(`_${s}`)
        );
        if (!isAllowed) {
          return { behavior: 'deny' as const };
        }
      }
    }
    return { behavior: 'allow' as const };
  };
}
```

Then pass it:

```ts
const { session: v2Session } = await this.getOrCreateV2Session(sessionId, canUseToolOverride);
```

- [ ] **Step 5: Verify full mode still works**

When `mode === 'full'`, `canUseToolOverride` is `undefined`, so `getOrCreateV2Session` falls back to the existing desktop session logic. This preserves backward compatibility.

- [ ] **Step 6: Commit**

```bash
git add server/claude-session.ts
git commit -m "feat(bot): read-only mode via canUseTool, query-mode context prefix"
```

---

## Chunk 4: Frontend — Session Grouping & Settings UI

### Task 9: Update ChatSession type and session.list mapping

**Files:**
- Modify: `src/types/chat.ts:44-50`
- Modify: `src/stores/chat.ts` (loadSessions mapping)
- Modify: `server/index.ts:973-981` (session.list response)

- [ ] **Step 1: Add `source` and `botLabel` to `ChatSession`**

```ts
export interface ChatSession {
  key: string;
  label?: string;
  workspace?: string;
  createdAt?: string;
  lastActiveAt?: string;
  source?: 'local' | 'bot';
  botLabel?: string;
}
```

- [ ] **Step 2: Update backend session.list response to include bot sessions**

In `server/index.ts`, the `session.list` handler currently only returns desktop sessions from `sessionManager.listSessions()`. Add bot sessions:

```ts
case 'session.list': {
  const localSessions = sessionManager.listSessions().map(s => ({
    id: s.id,
    workspace: s.workspace,
    label: s.label,
    createdAt: s.createdAt,
    lastActiveAt: s.lastActiveAt,
    source: 'local' as const,
    botLabel: null as string | null,
  }));

  // Add bot sessions from ChatbotStore
  const botSessions = chatbotStore.getSessionsWithBotInfo()
    .filter(bs => {
      // Only include sessions that exist in sessionManager
      return sessionManager.listSessions().some(s => s.id === bs.sessionId);
    })
    .map(bs => {
      const s = sessionManager.listSessions().find(s => s.id === bs.sessionId);
      return {
        id: bs.sessionId,
        workspace: bs.workspace,
        label: s?.label || '',
        createdAt: s?.createdAt,
        lastActiveAt: s?.lastActiveAt,
        source: 'bot' as const,
        botLabel: bs.botLabel,
      };
    });

  const sessions = [...localSessions, ...botSessions];
  ws.send(JSON.stringify({ type: 'session.list', sessions }));
```

- [ ] **Step 3: Update frontend loadSessions to map new fields**

In `src/stores/chat.ts` line 371-377, the mapping is explicit (not spread). Add `source` and `botLabel`:

```ts
const sessions: ChatSession[] = data.sessions.map((s: Record<string, unknown>) => ({
  key: String(s.id),
  label: s.label ? String(s.label) : undefined,
  workspace: s.workspace ? String(s.workspace) : undefined,
  createdAt: s.createdAt ? String(s.createdAt) : undefined,
  lastActiveAt: s.lastActiveAt ? String(s.lastActiveAt) : undefined,
  source: s.source === 'bot' ? 'bot' : 'local',
  botLabel: s.botLabel ? String(s.botLabel) : undefined,
}));
```

- [ ] **Step 4: Commit**

```bash
git add src/types/chat.ts server/index.ts src/stores/chat.ts
git commit -m "feat(bot): add source/botLabel to ChatSession, include bot sessions in list"
```

### Task 10: SessionTree three-level grouping

**Files:**
- Modify: `src/components/SessionTree.tsx`

- [ ] **Step 1: Update grouping logic to separate local and bot sessions**

Replace the `useMemo` that derives `systems` and `sessionsBySystem` (around line 266-287):

```ts
const { localSystems, botGroups, localSessionsBySystem, botSessionsByLabel } = useMemo(() => {
  const localMap = new Map<string, { systemId: string; name: string; workspace: string }>();
  const botLabelSet = new Map<string, string>();

  for (const session of sessions) {
    if (session.source === 'bot') {
      const label = session.botLabel || 'Unknown Bot';
      if (!botLabelSet.has(label)) {
        botLabelSet.set(label, label);
      }
    } else {
      if (!session.workspace) return;
      if (!localMap.has(session.workspace)) {
        const name = session.workspace.split(/[/\\]/).pop() || session.workspace;
        localMap.set(session.workspace, {
          systemId: session.workspace,
          name,
          workspace: session.workspace,
        });
      }
    }
  }

  const localSystems = Array.from(localMap.values());
  const botGroups = Array.from(botLabelSet.keys());

  const localSessionsBySystem = sessions.reduce<Record<string, ChatSession[]>>((acc, session) => {
    if (session.source === 'bot') return acc;
    const sysId = session.workspace || '__default__';
    if (!acc[sysId]) acc[sysId] = [];
    acc[sysId].push(session);
    return acc;
  }, {});

  const botSessionsByLabel = sessions.reduce<Record<string, ChatSession[]>>((acc, session) => {
    if (session.source !== 'bot') return acc;
    const label = session.botLabel || 'Unknown Bot';
    if (!acc[label]) acc[label] = [];
    acc[label].push(session);
    return acc;
  }, {});

  return { localSystems, botGroups, localSessionsBySystem, botSessionsByLabel };
}, [sessions]);
```

- [ ] **Step 2: Render bot sessions section after local sessions**

In the JSX, after the local `SystemGroup` mapping, add:

```tsx
{botGroups.length > 0 && (
  <>
    <div className="px-3 py-1.5 text-[11px] font-medium text-muted-foreground/50 uppercase tracking-wider">
      {t('session.botSessions')}
    </div>
    {botGroups.map((label) => (
      <SystemGroup
        key={`bot-${label}`}
        systemId={`bot-${label}`}
        name={`🤖 ${label}`}
        sessions={botSessionsByLabel[label] || []}
        currentSessionId={currentSessionId}
        expanded={expandedSystems.has(`bot-${label}`)}
        onToggle={() => toggleSystem(`bot-${label}`)}
        onSelect={handleSessionSelect}
        onDelete={handleSessionDelete}
        onDuplicate={() => {}}
        isBotGroup
      />
    ))}
  </>
)}
```

- [ ] **Step 3: Add `isBotGroup` prop to SystemGroup to hide duplicate button**

In the `SystemGroup` component, pass `isBotGroup` down to `SessionItem`, which already hides duplicate for chatbot sessions.

- [ ] **Step 4: Auto-expand bot groups**

Update the auto-expand effect to include bot groups:

```ts
useEffect(() => {
  localSystems.forEach((system) => {
    if (!expandedSystems.has(system.systemId)) {
      toggleSystemExpanded(system.systemId, true);
    }
  });
  botGroups.forEach((label) => {
    if (!expandedSystems.has(`bot-${label}`)) {
      toggleSystemExpanded(`bot-${label}`, true);
    }
  });
}, [localSystems, botGroups]); // eslint-disable-line react-hooks/exhaustive-deps
```

- [ ] **Step 5: Commit**

```bash
git add src/components/SessionTree.tsx
git commit -m "feat(bot): three-level session grouping with bot sessions section"
```

### Task 11: ChatbotSettings multi-bot management UI

**Files:**
- Modify: `src/features/settings/ChatbotSettings.tsx`
- Modify: `src/locales/zh-CN.json`
- Modify: `src/locales/en-US.json`

- [ ] **Step 1: Add i18n keys**

In `zh-CN.json`, add:

```json
"chatbot.botList": "Bot 列表",
"chatbot.addBot": "添加 Bot",
"chatbot.removeBot": "删除",
"chatbot.botLabel": "Bot 名称",
"chatbot.botMode": "模式",
"chatbot.modeFull": "完全权限",
"chatbot.modeQuery": "只读答疑",
"chatbot.botWorkspace": "绑定项目",
"chatbot.selectWorkspace": "选择项目",
"chatbot.allowedSkills": "允许的 Skills",
"chatbot.noSkills": "该项目下暂无 Skills",
"chatbot.loadingSkills": "加载中...",
"chatbot.skillWhitelist": "Skill 白名单",
"session.botSessions": "Bot 会话"
```

In `en-US.json`, add corresponding English translations.

- [ ] **Step 2: Rewrite ChatbotSettings component**

The component needs to:

1. Show a list of configured bots
2. Each bot is expandable/collapsible for configuration
3. Add/Remove bot buttons
4. For each bot:
   - label (text input)
   - botId + secret (text inputs)
   - mode (radio: full / query)
   - workspace (dropdown from desktop sessions)
   - when mode=query and workspace selected: load skills from `{workspace}/.claude/skills/` via a new WS API, show checkboxes
5. enabled toggle per bot

The workspace skill loading needs a new backend endpoint. Add a simple WebSocket handler:

```ts
// In server/index.ts message handler
case 'chatbot.listWorkspaceSkills': {
  const wsPath = msg.workspace as string;
  if (!wsPath || typeof wsPath !== 'string') {
    ws.send(JSON.stringify({ type: 'chatbot.listWorkspaceSkills', error: 'Missing workspace' }));
    break;
  }
  const skillsDir = path.join(wsPath, '.claude', 'skills');
  const skills: string[] = [];
  try {
    if (fs.existsSync(skillsDir)) {
      const entries = fs.readdirSync(skillsDir, { withFileTypes: true });
      for (const entry of entries) {
        if (entry.isDirectory()) {
          skills.push(entry.name);
        }
      }
    }
  } catch { /* ignore */ }
  ws.send(JSON.stringify({ type: 'chatbot.listWorkspaceSkills', skills }));
  break;
}
```

- [ ] **Step 3: Implement bot list UI**

Use a collapsible card pattern for each bot. When adding a new bot, generate a UUID and create a default profile:

```ts
const newBot: WeComBotProfile = {
  id: crypto.randomUUID(),
  label: '',
  botId: '',
  secret: '',
  mode: 'full',
  workspace: '',
  allowedSkills: [],
  enabled: true,
};
```

Save by calling `updateChatbot()` with the full bots array.

- [ ] **Step 4: Implement skill loading for selected workspace**

When user selects a workspace for a query-mode bot, send `chatbot.listWorkspaceSkills` via WebSocket, display checkboxes.

- [ ] **Step 5: Commit**

```bash
git add src/features/settings/ChatbotSettings.tsx src/locales/zh-CN.json src/locales/en-US.json server/index.ts src/stores/settings.ts
git commit -m "feat(bot): multi-bot management UI with skill whitelist"
```

---

## Chunk 5: Integration & Testing

### Task 12: Wire everything together — verify compilation

- [ ] **Step 1: Build and check for type errors**

```bash
cd /Users/nasakim/projects/sman && pnpm build 2>&1 | head -50
```

Fix any TypeScript errors.

- [ ] **Step 2: Start dev server and verify no runtime crashes**

```bash
pnpm dev:server
```

Check logs for:
- Config migration runs correctly
- `ChatbotStore` migration adds `bot_label` column
- Server starts without errors

- [ ] **Step 3: Commit any fixes**

```bash
git add -A && git commit -m "fix(bot): integration fixes"
```

### Task 13: Manual testing checklist

- [ ] **Step 1: Test config migration**
  - With existing single-bot config, start server
  - Verify config.json is migrated to `bots[]` format
  - Verify existing bot connection still works

- [ ] **Step 2: Test multi-bot setup**
  - Go to Settings → Chatbot
  - Add two bots with different configurations
  - Verify both connect and respond to messages

- [ ] **Step 3: Test query mode**
  - Configure a bot with mode=query, workspace bound, skills selected
  - Send a question → should get answer
  - Ask it to modify a file → should refuse
  - Verify `//cd` is blocked
  - Verify `//help` shows limited commands

- [ ] **Step 4: Test session isolation**
  - User A sends message to bot → creates session
  - User B sends message to same bot → different session
  - Verify context doesn't leak between users

- [ ] **Step 5: Test concurrency control**
  - Have 3 users send messages simultaneously
  - Verify only 2 are processed at once
  - Verify 3rd gets queued notification

- [ ] **Step 6: Test sidebar grouping**
  - Verify bot sessions appear under "Bot 会话" section
  - Verify local sessions are unaffected
  - Verify clicking bot session opens it in chat view

- [ ] **Step 7: Commit final state**

```bash
git add -A && git commit -m "feat(bot): multi-bot binding complete — tested"
```
