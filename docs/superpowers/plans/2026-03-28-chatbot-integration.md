# Chatbot Integration (WeCom + Feishu) Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add WeCom and Feishu bot long connection support to smanbase, enabling IM users to chat with Claude Code and switch workspaces via chat commands.

**Architecture:** New `server/chatbot/` module with 5 files: types, store (SQLite), command parser, session manager, and platform connections. A new `sendMessageForChatbot()` method on `ClaudeSessionManager` returns response content and supports streaming. WeCom uses full-duplex WebSocket with native streaming; Feishu uses SDK WSClient for events + REST API for responses.

**Tech Stack:** TypeScript, SQLite (better-sqlite3), ws (WeCom), @larksuiteoapi/node-sdk (Feishu), Vitest

**Design Doc:** `docs/superpowers/specs/2026-03-28-chatbot-integration-design.md`

---

## Chunk 1: Types + Store + Command Parser (Foundation)

### Task 1: Chatbot types

**Files:**
- Create: `server/chatbot/types.ts`
- Modify: `server/types.ts` (add ChatbotConfig to SmanConfig)
- Test: `tests/server/chatbot/chatbot-store.test.ts`

- [ ] **Step 1: Write failing test for ChatbotStore create/get**

Create `tests/server/chatbot/chatbot-store.test.ts`:

```typescript
import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { ChatbotStore } from '../../../server/chatbot/chatbot-store.js';
import fs from 'fs';
import path from 'path';
import os from 'os';

describe('ChatbotStore', () => {
  let store: ChatbotStore;
  let dbPath: string;

  beforeEach(() => {
    dbPath = path.join(os.tmpdir(), `chatbot-test-${Date.now()}.db`);
    store = new ChatbotStore(dbPath);
  });

  afterEach(() => {
    store.close();
    if (fs.existsSync(dbPath)) fs.unlinkSync(dbPath);
  });

  describe('user state', () => {
    it('should create and get user state', () => {
      store.setUserState('wecom:user1', '/data/projectA');
      const state = store.getUserState('wecom:user1');
      expect(state).toBeDefined();
      expect(state!.currentWorkspace).toBe('/data/projectA');
    });

    it('should return undefined for unknown user', () => {
      const state = store.getUserState('wecom:unknown');
      expect(state).toBeUndefined();
    });

    it('should update user workspace', () => {
      store.setUserState('wecom:user1', '/data/projectA');
      store.setUserState('wecom:user1', '/data/projectB');
      const state = store.getUserState('wecom:user1');
      expect(state!.currentWorkspace).toBe('/data/projectB');
    });
  });

  describe('sessions', () => {
    it('should create and get session', () => {
      store.setUserState('wecom:user1', '/data/projectA');
      store.setSession('wecom:user1', '/data/projectA', 'sess-1');
      const session = store.getSession('wecom:user1', '/data/projectA');
      expect(session).toBeDefined();
      expect(session!.sessionId).toBe('sess-1');
    });

    it('should update sdkSessionId', () => {
      store.setUserState('wecom:user1', '/data/projectA');
      store.setSession('wecom:user1', '/data/projectA', 'sess-1');
      store.updateSdkSessionId('wecom:user1', '/data/projectA', 'sdk-123');
      const session = store.getSession('wecom:user1', '/data/projectA');
      expect(session!.sdkSessionId).toBe('sdk-123');
    });
  });

  describe('workspaces', () => {
    it('should add and list workspaces', () => {
      store.addWorkspace('/data/projectA', 'projectA');
      store.addWorkspace('/data/projectB', 'projectB');
      const workspaces = store.listWorkspaces();
      expect(workspaces).toHaveLength(2);
      expect(workspaces[0].path).toBe('/data/projectA');
    });

    it('should not duplicate workspace', () => {
      store.addWorkspace('/data/projectA', 'projectA');
      store.addWorkspace('/data/projectA', 'projectA');
      const workspaces = store.listWorkspaces();
      expect(workspaces).toHaveLength(1);
    });

    it('should find workspace by name', () => {
      store.addWorkspace('/data/my-project', 'my-project');
      const found = store.findWorkspace('my-project');
      expect(found).toBe('/data/my-project');
    });

    it('should return null for unknown name', () => {
      const found = store.findWorkspace('non-existent');
      expect(found).toBeNull();
    });

    it('should check if workspace is registered', () => {
      store.addWorkspace('/data/projectA', 'projectA');
      expect(store.isWorkspaceRegistered('/data/projectA')).toBe(true);
      expect(store.isWorkspaceRegistered('/data/unknown')).toBe(false);
    });
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd /Users/nasakim/projects/smanbase && npx vitest run tests/server/chatbot/chatbot-store.test.ts`
Expected: FAIL — module not found

- [ ] **Step 3: Create chatbot types**

Create `server/chatbot/types.ts`:

```typescript
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
}

export interface ChatResponseSender {
  start(): void;
  sendChunk(content: string): void;
  finish(fullContent: string): void;
  error(message: string): void;
}

export interface CommandResult {
  command: 'cd' | 'pwd' | 'workspaces' | 'add' | 'help' | 'status';
  args: string;
}

export interface ParseResult {
  isCommand: boolean;
  command?: CommandResult;
}

export interface ChatbotUserState {
  currentWorkspace: string;
  lastActiveAt: string;
}

export interface ChatbotSession {
  sessionId: string;
  sdkSessionId?: string;
  createdAt: string;
  lastActiveAt: string;
}

export interface ChatbotWorkspace {
  path: string;
  name: string;
  addedAt: string;
}

export interface IncomingMessage {
  platform: 'wecom' | 'feishu';
  userId: string;
  content: string;
  requestId: string;
  chatType: 'single' | 'group' | 'p2p';
  chatId: string;
}
```

- [ ] **Step 4: Add ChatbotConfig to SmanConfig**

Modify `server/types.ts`, append after the existing `SmanConfig` interface:

```typescript
import type { ChatbotConfig } from './chatbot/types.js';

export interface SmanConfig {
  port: number;
  llm: {
    apiKey: string;
    model: string;
    baseUrl?: string;
  };
  webSearch: {
    provider: 'builtin' | 'brave' | 'tavily';
    braveApiKey: string;
    tavilyApiKey: string;
    maxUsesPerSession: number;
  };
  chatbot: ChatbotConfig;
}
```

Update `SettingsManager` default config in `server/settings-manager.ts`:

```typescript
import type { ChatbotConfig } from './chatbot/types.js';

const DEFAULT_CHATBOT: ChatbotConfig = {
  enabled: false,
  wecom: { enabled: false, botId: '', secret: '' },
  feishu: { enabled: false, appId: '', appSecret: '' },
};

// Add to DEFAULT_CONFIG:
const DEFAULT_CONFIG: SmanConfig = {
  port: 5880,
  llm: { apiKey: '', model: '' },
  webSearch: {
    provider: 'builtin',
    braveApiKey: '',
    tavilyApiKey: '',
    maxUsesPerSession: 50,
  },
  chatbot: { ...DEFAULT_CHATBOT },
};
```

- [ ] **Step 5: Implement ChatbotStore**

Create `server/chatbot/chatbot-store.ts`:

```typescript
import betterSqlite3 from 'better-sqlite3';
import type { Database } from 'better-sqlite3';
// @ts-expect-error - better-sqlite3 ESM interop
const DatabaseConstructor = betterSqlite3 as unknown as typeof betterSqlite3.default;
import { createLogger, type Logger } from '../utils/logger.js';
import type { ChatbotUserState, ChatbotSession, ChatbotWorkspace } from './types.js';

export class ChatbotStore {
  private db: Database;
  private log: Logger;

  constructor(dbPath: string) {
    this.db = new DatabaseConstructor(dbPath);
    this.log = createLogger('ChatbotStore');
    this.init();
  }

  private init(): void {
    this.db.exec(`
      CREATE TABLE IF NOT EXISTS chatbot_users (
        user_key TEXT PRIMARY KEY,
        current_workspace TEXT NOT NULL,
        last_active_at TEXT NOT NULL DEFAULT (datetime('now'))
      );

      CREATE TABLE IF NOT EXISTS chatbot_sessions (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        user_key TEXT NOT NULL,
        workspace TEXT NOT NULL,
        session_id TEXT NOT NULL,
        sdk_session_id TEXT,
        created_at TEXT NOT NULL DEFAULT (datetime('now')),
        last_active_at TEXT NOT NULL DEFAULT (datetime('now')),
        UNIQUE(user_key, workspace)
      );

      CREATE TABLE IF NOT EXISTS chatbot_workspaces (
        path TEXT PRIMARY KEY,
        name TEXT NOT NULL,
        added_at TEXT NOT NULL DEFAULT (datetime('now'))
      );
    `);
    this.db.pragma('journal_mode = WAL');
    this.log.info('ChatbotStore initialized');
  }

  // --- User State ---

  getUserState(userKey: string): ChatbotUserState | undefined {
    return this.db.prepare(
      'SELECT current_workspace as currentWorkspace, last_active_at as lastActiveAt FROM chatbot_users WHERE user_key = ?'
    ).get(userKey) as ChatbotUserState | undefined;
  }

  setUserState(userKey: string, workspace: string): void {
    this.db.prepare(
      `INSERT INTO chatbot_users (user_key, current_workspace, last_active_at) VALUES (?, ?, datetime('now'))
       ON CONFLICT(user_key) DO UPDATE SET current_workspace = excluded.current_workspace, last_active_at = datetime('now')`
    ).run(userKey, workspace);
  }

  // --- Sessions ---

  getSession(userKey: string, workspace: string): ChatbotSession | undefined {
    return this.db.prepare(
      'SELECT session_id as sessionId, sdk_session_id as sdkSessionId, created_at as createdAt, last_active_at as lastActiveAt FROM chatbot_sessions WHERE user_key = ? AND workspace = ?'
    ).get(userKey, workspace) as ChatbotSession | undefined;
  }

  setSession(userKey: string, workspace: string, sessionId: string): void {
    this.db.prepare(
      `INSERT INTO chatbot_sessions (user_key, workspace, session_id, created_at, last_active_at) VALUES (?, ?, ?, datetime('now'), datetime('now'))
       ON CONFLICT(user_key, workspace) DO UPDATE SET session_id = excluded.session_id, last_active_at = datetime('now')`
    ).run(userKey, workspace, sessionId);
  }

  updateSdkSessionId(userKey: string, workspace: string, sdkSessionId: string): void {
    this.db.prepare(
      'UPDATE chatbot_sessions SET sdk_session_id = ?, last_active_at = datetime(\'now\') WHERE user_key = ? AND workspace = ?'
    ).run(sdkSessionId, userKey, workspace);
  }

  // --- Workspaces ---

  addWorkspace(wsPath: string, name: string): void {
    this.db.prepare(
      `INSERT OR IGNORE INTO chatbot_workspaces (path, name, added_at) VALUES (?, ?, datetime('now'))`
    ).run(wsPath, name);
  }

  listWorkspaces(): ChatbotWorkspace[] {
    return this.db.prepare(
      'SELECT path, name, added_at as addedAt FROM chatbot_workspaces ORDER BY name'
    ).all() as ChatbotWorkspace[];
  }

  findWorkspace(name: string): string | null {
    const row = this.db.prepare(
      'SELECT path FROM chatbot_workspaces WHERE name = ? OR path LIKE ?'
    ).get(name, `%/${name}`) as { path: string } | undefined;
    return row?.path ?? null;
  }

  isWorkspaceRegistered(wsPath: string): boolean {
    const row = this.db.prepare('SELECT 1 FROM chatbot_workspaces WHERE path = ?').get(wsPath);
    return !!row;
  }

  close(): void {
    this.db.close();
  }
}
```

- [ ] **Step 6: Run tests to verify they pass**

Run: `cd /Users/nasakim/projects/smanbase && npx vitest run tests/server/chatbot/chatbot-store.test.ts`
Expected: ALL PASS

- [ ] **Step 7: Commit**

```bash
git add server/chatbot/types.ts server/chatbot/chatbot-store.ts server/types.ts server/settings-manager.ts tests/server/chatbot/chatbot-store.test.ts
git commit -m "feat(chatbot): add chatbot types, store, and config extension"
```

---

### Task 2: Chat command parser

**Files:**
- Create: `server/chatbot/chat-command-parser.ts`
- Test: `tests/server/chatbot/chat-command-parser.test.ts`

- [ ] **Step 1: Write failing tests for command parser**

Create `tests/server/chatbot/chat-command-parser.test.ts`:

```typescript
import { describe, it, expect } from 'vitest';
import { parseChatCommand } from '../../../server/chatbot/chat-command-parser.js';

describe('parseChatCommand', () => {
  it('should parse /cd command with absolute path', () => {
    const result = parseChatCommand('/cd /data/projectA');
    expect(result.isCommand).toBe(true);
    expect(result.command!.command).toBe('cd');
    expect(result.command!.args).toBe('/data/projectA');
  });

  it('should parse /cd command with project name', () => {
    const result = parseChatCommand('/cd hello-halo');
    expect(result.isCommand).toBe(true);
    expect(result.command!.command).toBe('cd');
    expect(result.command!.args).toBe('hello-halo');
  });

  it('should parse /pwd command', () => {
    const result = parseChatCommand('/pwd');
    expect(result.isCommand).toBe(true);
    expect(result.command!.command).toBe('pwd');
  });

  it('should parse /workspaces command', () => {
    const result = parseChatCommand('/workspaces');
    expect(result.isCommand).toBe(true);
    expect(result.command!.command).toBe('workspaces');
  });

  it('should parse /add command with path', () => {
    const result = parseChatCommand('/add /Users/nasakim/projects/new');
    expect(result.isCommand).toBe(true);
    expect(result.command!.command).toBe('add');
    expect(result.command!.args).toBe('/Users/nasakim/projects/new');
  });

  it('should parse /help command', () => {
    const result = parseChatCommand('/help');
    expect(result.isCommand).toBe(true);
    expect(result.command!.command).toBe('help');
  });

  it('should parse /status command', () => {
    const result = parseChatCommand('/status');
    expect(result.isCommand).toBe(true);
    expect(result.command!.command).toBe('status');
  });

  it('should not treat plain text as command', () => {
    const result = parseChatCommand('hello how are you');
    expect(result.isCommand).toBe(false);
    expect(result.command).toBeUndefined();
  });

  it('should not treat /unknown as a valid command', () => {
    const result = parseChatCommand('/unknown something');
    expect(result.isCommand).toBe(false);
  });

  it('should trim whitespace', () => {
    const result = parseChatCommand('  /pwd  ');
    expect(result.isCommand).toBe(true);
    expect(result.command!.command).toBe('pwd');
  });

  it('should handle WeCom @mention prefix', () => {
    // WeCom messages may include @BotName prefix that should be stripped upstream
    const result = parseChatCommand('/cd projectA');
    expect(result.isCommand).toBe(true);
    expect(result.command!.args).toBe('projectA');
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd /Users/nasakim/projects/smanbase && npx vitest run tests/server/chatbot/chat-command-parser.test.ts`
Expected: FAIL — module not found

- [ ] **Step 3: Implement chat-command-parser.ts**

Create `server/chatbot/chat-command-parser.ts`:

```typescript
import type { ParseResult, CommandResult } from './types.js';

const VALID_COMMANDS = new Set(['cd', 'pwd', 'workspaces', 'add', 'help', 'status']);

export function parseChatCommand(input: string): ParseResult {
  const trimmed = input.trim();
  if (!trimmed.startsWith('/')) {
    return { isCommand: false };
  }

  const spaceIndex = trimmed.indexOf(' ');
  const cmdStr = spaceIndex === -1
    ? trimmed.substring(1)
    : trimmed.substring(1, spaceIndex);

  const command = cmdStr.toLowerCase() as CommandResult['command'];

  if (!VALID_COMMANDS.has(command)) {
    return { isCommand: false };
  }

  const args = spaceIndex === -1 ? '' : trimmed.substring(spaceIndex + 1).trim();

  return {
    isCommand: true,
    command: { command, args },
  };
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd /Users/nasakim/projects/smanbase && npx vitest run tests/server/chatbot/chat-command-parser.test.ts`
Expected: ALL PASS

- [ ] **Step 5: Commit**

```bash
git add server/chatbot/chat-command-parser.ts tests/server/chatbot/chat-command-parser.test.ts
git commit -m "feat(chatbot): add chat command parser with /cd /pwd /workspaces /add /help /status"
```

---

## Chunk 2: ClaudeSessionManager Extension

### Task 3: Add sendMessageForChatbot to ClaudeSessionManager

**Files:**
- Modify: `server/claude-session.ts` (add new method)
- Test: `tests/server/claude-session.test.ts` (add tests for new method)

- [ ] **Step 1: Write failing test**

Add to `tests/server/claude-session.test.ts`:

```typescript
describe('sendMessageForChatbot', () => {
  it('should exist as a method on ClaudeSessionManager', () => {
    const store = new SessionStore(path.join(os.tmpdir(), `test-chatbot-${Date.now()}.db`));
    const manager = new ClaudeSessionManager(store);
    expect(typeof manager.sendMessageForChatbot).toBe('function');
    store.close();
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd /Users/nasakim/projects/smanbase && npx vitest run tests/server/claude-session.test.ts -t sendMessageForChatbot`
Expected: FAIL — method does not exist

- [ ] **Step 3: Implement sendMessageForChatbot**

Add method to `ClaudeSessionManager` in `server/claude-session.ts`, after the existing `sendMessageForCron` method:

```typescript
/**
 * 发送消息（Chatbot 专用，支持流式回调 + SDK session 恢复 + 返回完整内容）
 */
async sendMessageForChatbot(
  sessionId: string,
  content: string,
  abortController: AbortController,
  onActivity: () => void,
  onResponse: (chunk: string) => void,
): Promise<string> {
  const session = this.sessions.get(sessionId);
  if (!session) throw new Error(`Session not found: ${sessionId}`);

  if (this.activeQueries.has(sessionId)) {
    throw new Error(`Session ${sessionId} already has an active query`);
  }

  if (!this.config?.llm?.apiKey) {
    throw new Error('缺少 API Key，请在设置中配置');
  }
  if (!this.config?.llm?.model) {
    throw new Error('缺少 Model 配置，请在设置中选择模型');
  }

  this.store.addMessage(sessionId, { role: 'user', content });

  const sessionConfig = this.getSessionConfig(session.workspace);
  this.abortControllers.set(sessionId, abortController);

  try {
    const options = this.buildOptions(sessionConfig, abortController);
    options.cwd = session.workspace;

    // Resume SDK session if available
    let sdkSessionId = this.sdkSessionIds.get(sessionId);
    if (!sdkSessionId) {
      sdkSessionId = this.store.getSdkSessionId(sessionId);
      if (sdkSessionId) {
        this.sdkSessionIds.set(sessionId, sdkSessionId);
      }
    }
    if (sdkSessionId) {
      options.resume = sdkSessionId;
    }

    this.log.info(`Starting chatbot query for session ${sessionId}, resume=${sdkSessionId || 'none'}`);

    const q = query({ prompt: content, options });
    this.activeQueries.set(sessionId, q);

    let fullContent = '';
    let msgCount = 0;

    for await (const sdkMsg of q) {
      msgCount++;
      if (msgCount <= 3 || msgCount % 10 === 0) {
        this.log.info(`Chatbot SDK message #${msgCount}: type=${sdkMsg.type}`);
      }
      onActivity();

      if (abortController.signal.aborted) break;

      switch (sdkMsg.type) {
        case 'assistant': {
          const text = this.extractTextContent(sdkMsg);
          if (text) fullContent = text;
          break;
        }
        case 'stream_event': {
          const delta = this.extractDeltaText((sdkMsg as SDKPartialAssistantMessage).event);
          if (delta && delta.type === 'text') {
            fullContent += delta.content;
            onResponse(delta.content);
          } else if (delta && delta.type === 'thinking') {
            // Ignore thinking chunks for chatbot response
          }
          break;
        }
        case 'result': {
          const result = sdkMsg as SDKResultMessage;
          if (result.session_id) {
            this.sdkSessionIds.set(sessionId, result.session_id);
            this.store.updateSdkSessionId(sessionId, result.session_id);
          }
          if (fullContent) {
            this.store.addMessage(sessionId, { role: 'assistant', content: fullContent });
          }
          break;
        }
      }
    }
    return fullContent;
  } catch (err: any) {
    if (err?.name !== 'AbortError' && !abortController.signal.aborted) {
      throw err;
    }
    return '';
  } finally {
    this.activeQueries.delete(sessionId);
    this.abortControllers.delete(sessionId);
  }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd /Users/nasakim/projects/smanbase && npx vitest run tests/server/claude-session.test.ts -t sendMessageForChatbot`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add server/claude-session.ts tests/server/claude-session.test.ts
git commit -m "feat(chatbot): add sendMessageForChatbot with streaming + session resumption"
```

---

## Chunk 3: ChatbotSessionManager (Core Coordinator)

### Task 4: ChatbotSessionManager

**Files:**
- Create: `server/chatbot/chatbot-session-manager.ts`
- Test: `tests/server/chatbot/chatbot-session-manager.test.ts`

- [ ] **Step 1: Write failing tests**

Create `tests/server/chatbot/chatbot-session-manager.test.ts`:

```typescript
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { ChatbotSessionManager } from '../../../server/chatbot/chatbot-session-manager.js';
import { ChatbotStore } from '../../../server/chatbot/chatbot-store.js';
import fs from 'fs';
import path from 'path';
import os from 'os';

// Mock ClaudeSessionManager
const mockCreateSessionWithId = vi.fn();
const mockSendMessageForChatbot = vi.fn();
const mockSessionManager = {
  createSessionWithId: mockCreateSessionWithId,
  sendMessageForChatbot: mockSendMessageForChatbot,
} as any;

describe('ChatbotSessionManager', () => {
  let manager: ChatbotSessionManager;
  let store: ChatbotStore;
  let dbPath: string;
  let homeDir: string;

  beforeEach(() => {
    dbPath = path.join(os.tmpdir(), `chatbot-mgr-test-${Date.now()}.db`);
    homeDir = path.join(os.tmpdir(), `chatbot-mgr-home-${Date.now()}`);
    fs.mkdirSync(homeDir, { recursive: true });
    store = new ChatbotStore(dbPath);
    manager = new ChatbotSessionManager(homeDir, mockSessionManager, store);
    vi.clearAllMocks();
  });

  afterEach(() => {
    store.close();
    if (fs.existsSync(dbPath)) fs.unlinkSync(dbPath);
    fs.rmSync(homeDir, { recursive: true, force: true });
  });

  describe('handleMessage - command routing', () => {
    it('should handle /cd command', async () => {
      store.addWorkspace('/data/projectA', 'projectA');
      // Create the workspace dir so path.exists check passes
      fs.mkdirSync('/data/projectA', { recursive: true });

      const responses: string[] = [];
      const sender = {
        start: () => {},
        sendChunk: (c: string) => {},
        finish: (c: string) => responses.push(c),
        error: (m: string) => responses.push(m),
      };

      await manager.handleMessage({
        platform: 'wecom',
        userId: 'user1',
        content: '/cd projectA',
        requestId: 'req-1',
        chatType: 'single',
        chatId: 'chat-1',
      }, sender);

      expect(responses[0]).toContain('projectA');
      expect(responses[0]).toContain('/data/projectA');
      // Cleanup
      fs.rmSync('/data', { recursive: true, force: true });
    });

    it('should handle /pwd without workspace', async () => {
      const responses: string[] = [];
      const sender = {
        start: () => {},
        sendChunk: () => {},
        finish: (c: string) => responses.push(c),
        error: (m: string) => responses.push(m),
      };

      await manager.handleMessage({
        platform: 'wecom',
        userId: 'user1',
        content: '/pwd',
        requestId: 'req-1',
        chatType: 'single',
        chatId: 'chat-1',
      }, sender);

      expect(responses[0]).toContain('未设置');
    });

    it('should handle /help command', async () => {
      const responses: string[] = [];
      const sender = {
        start: () => {},
        sendChunk: () => {},
        finish: (c: string) => responses.push(c),
        error: () => {},
      };

      await manager.handleMessage({
        platform: 'wecom',
        userId: 'user1',
        content: '/help',
        requestId: 'req-1',
        chatType: 'single',
        chatId: 'chat-1',
      }, sender);

      expect(responses[0]).toContain('/cd');
      expect(responses[0]).toContain('/pwd');
    });

    it('should reject chat when no workspace set', async () => {
      const responses: string[] = [];
      const sender = {
        start: () => {},
        sendChunk: () => {},
        finish: (c: string) => responses.push(c),
        error: (m: string) => responses.push(m),
      };

      await manager.handleMessage({
        platform: 'wecom',
        userId: 'user1',
        content: 'hello',
        requestId: 'req-1',
        chatType: 'single',
        chatId: 'chat-1',
      }, sender);

      expect(responses[0]).toContain('/cd');
    });
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd /Users/nasakim/projects/smanbase && npx vitest run tests/server/chatbot/chatbot-session-manager.test.ts`
Expected: FAIL — module not found

- [ ] **Step 3: Implement ChatbotSessionManager**

Create `server/chatbot/chatbot-session-manager.ts`:

```typescript
import { v4 as uuidv4 } from 'uuid';
import fs from 'fs';
import path from 'path';
import { createLogger, type Logger } from '../utils/logger.js';
import type { ClaudeSessionManager } from '../claude-session.js';
import { ChatbotStore } from './chatbot-store.js';
import { parseChatCommand } from './chat-command-parser.js';
import type { IncomingMessage, ChatResponseSender } from './types.js';

const QUERY_TIMEOUT_MS = 5 * 60 * 1000;

export class ChatbotSessionManager {
  private log: Logger;
  private store: ChatbotStore;
  private sessionManager: ClaudeSessionManager;
  private activeQueries = new Map<string, AbortController>(); // userKey -> abort

  constructor(
    private homeDir: string,
    sessionManager: ClaudeSessionManager,
    store: ChatbotStore,
  ) {
    this.log = createLogger('ChatbotSessionManager');
    this.sessionManager = sessionManager;
    this.store = store;
  }

  async handleMessage(msg: IncomingMessage, sender: ChatResponseSender): Promise<void> {
    const userKey = `${msg.platform}:${msg.userId}`;
    const parseResult = parseChatCommand(msg.content);

    if (parseResult.isCommand && parseResult.command) {
      await this.handleCommand(userKey, parseResult.command, sender);
      return;
    }

    // Regular chat message
    const userState = this.store.getUserState(userKey);
    if (!userState?.currentWorkspace) {
      sender.finish('尚未设置工作目录。请使用 /cd <项目名或路径> 切换工作目录。\n使用 /help 查看所有命令。');
      return;
    }

    // Check if user already has an active query
    if (this.activeQueries.has(userKey)) {
      sender.finish('当前还有未完成的请求，请稍后再试。');
      return;
    }

    await this.executeChatQuery(userKey, userState.currentWorkspace, msg.content, sender);
  }

  private async handleCommand(
    userKey: string,
    command: { command: string; args: string },
    sender: ChatResponseSender,
  ): Promise<void> {
    switch (command.command) {
      case 'cd':
        await this.handleCd(userKey, command.args, sender);
        break;
      case 'pwd':
        this.handlePwd(userKey, sender);
        break;
      case 'workspaces':
        this.handleWorkspaces(sender);
        break;
      case 'add':
        this.handleAdd(command.args, sender);
        break;
      case 'help':
        this.handleHelp(sender);
        break;
      case 'status':
        this.handleStatus(userKey, sender);
        break;
      default:
        sender.finish(`未知命令: /${command.command}\n使用 /help 查看所有命令。`);
    }
  }

  private async handleCd(userKey: string, args: string, sender: ChatResponseSender): Promise<void> {
    if (!args) {
      sender.finish('用法: /cd <项目名或路径>');
      return;
    }

    let workspacePath: string | null = null;

    // Absolute path
    if (args.startsWith('/')) {
      if (this.store.isWorkspaceRegistered(args)) {
        workspacePath = args;
      }
    } else {
      // Lookup by name
      workspacePath = this.store.findWorkspace(args);
    }

    if (!workspacePath) {
      const registered = this.store.listWorkspaces().map(w => w.name).join(', ');
      sender.finish(`目录 "${args}" 未注册。\n已注册的项目: ${registered || '无'}\n使用 /add <路径> 注册新目录。`);
      return;
    }

    if (!fs.existsSync(workspacePath)) {
      sender.finish(`目录不存在: ${workspacePath}`);
      return;
    }

    // Update user state
    this.store.setUserState(userKey, workspacePath);

    // Get or create session for this workspace
    let session = this.store.getSession(userKey, workspacePath);
    if (!session) {
      const sessionId = `chatbot-${Date.now()}-${uuidv4().substring(0, 8)}`;
      try {
        this.sessionManager.createSessionWithId(workspacePath, sessionId);
        this.store.setSession(userKey, workspacePath, sessionId);
        session = this.store.getSession(userKey, workspacePath);
      } catch (err) {
        sender.finish(`创建会话失败: ${err instanceof Error ? err.message : String(err)}`);
        return;
      }
    }

    const projectName = path.basename(workspacePath);
    sender.finish(`已切换到: ${projectName} (${workspacePath})`);
  }

  private handlePwd(userKey: string, sender: ChatResponseSender): void {
    const state = this.store.getUserState(userKey);
    if (!state?.currentWorkspace) {
      sender.finish('当前未设置工作目录。\n使用 /cd <项目名或路径> 切换工作目录。');
      return;
    }
    sender.finish(`当前工作目录: ${state.currentWorkspace}`);
  }

  private handleWorkspaces(sender: ChatResponseSender): void {
    const workspaces = this.store.listWorkspaces();
    if (workspaces.length === 0) {
      sender.finish('暂无注册的工作目录。\n使用 /add <路径> 注册新目录。');
      return;
    }
    const list = workspaces.map((w, i) => `${i + 1}. ${w.name} (${w.path})`).join('\n');
    sender.finish(`已知工作目录:\n${list}`);
  }

  private handleAdd(args: string, sender: ChatResponseSender): void {
    if (!args) {
      sender.finish('用法: /add <目录路径>');
      return;
    }
    const wsPath = args.trim();
    if (!fs.existsSync(wsPath)) {
      sender.finish(`目录不存在: ${wsPath}`);
      return;
    }
    const name = path.basename(wsPath);
    this.store.addWorkspace(wsPath, name);
    sender.finish(`已注册: ${name} (${wsPath})`);
  }

  private handleHelp(sender: ChatResponseSender): void {
    sender.finish(
      `可用命令:\n` +
      `/cd <项目名或路径> - 切换工作目录\n` +
      `/pwd - 显示当前工作目录\n` +
      `/workspaces - 列出已知工作目录\n` +
      `/add <路径> - 注册新的工作目录\n` +
      `/status - 显示连接状态\n` +
      `/help - 显示此帮助信息\n\n` +
      `直接发送消息即可与 Claude 对话。`
    );
  }

  private handleStatus(userKey: string, sender: ChatResponseSender): void {
    const state = this.store.getUserState(userKey);
    const workspace = state?.currentWorkspace || '未设置';
    const active = this.activeQueries.has(userKey) ? '处理中' : '空闲';
    sender.finish(`状态: ${active}\n工作目录: ${workspace}`);
  }

  private async executeChatQuery(
    userKey: string,
    workspace: string,
    content: string,
    sender: ChatResponseSender,
  ): Promise<void> {
    const session = this.store.getSession(userKey, workspace);
    if (!session) {
      sender.error('会话不存在，请重新 /cd 到目标目录。');
      return;
    }

    const abortController = new AbortController();
    this.activeQueries.set(userKey, abortController);

    // Timeout
    const timeout = setTimeout(() => {
      abortController.abort();
      this.log.info(`Query timeout for ${userKey}`);
    }, QUERY_TIMEOUT_MS);

    try {
      sender.start();

      const fullContent = await this.sessionManager.sendMessageForChatbot(
        session.sessionId,
        content,
        abortController,
        () => {}, // onActivity — could track lastActivityAt for zombie detection
        (chunk) => sender.sendChunk(chunk),
      );

      sender.finish(fullContent || '(空响应)');
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

  stop(): void {
    for (const [, controller] of this.activeQueries) {
      controller.abort();
    }
    this.activeQueries.clear();
  }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd /Users/nasakim/projects/smanbase && npx vitest run tests/server/chatbot/chatbot-session-manager.test.ts`
Expected: ALL PASS

- [ ] **Step 5: Commit**

```bash
git add server/chatbot/chatbot-session-manager.ts tests/server/chatbot/chatbot-session-manager.test.ts
git commit -m "feat(chatbot): add ChatbotSessionManager with command routing and query execution"
```

---

## Chunk 4: WeCom Bot Connection

### Task 5: WeCom long connection

**Files:**
- Create: `server/chatbot/wecom-bot-connection.ts`
- Test: `tests/server/chatbot/wecom-bot-connection.test.ts`

- [ ] **Step 1: Write failing tests**

Create `tests/server/chatbot/wecom-bot-connection.test.ts`:

```typescript
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { WeComBotConnection } from '../../../server/chatbot/wecom-bot-connection.js';
import { WebSocketServer, WebSocket } from 'ws';

describe('WeComBotConnection', () => {
  it('should export a class with start/stop methods', () => {
    expect(typeof WeComBotConnection).toBe('function');
    const conn = new WeComBotConnection({
      botId: 'test-bot',
      secret: 'test-secret',
      onMessage: vi.fn(),
    });
    expect(typeof conn.start).toBe('function');
    expect(typeof conn.stop).toBe('function');
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd /Users/nasakim/projects/smanbase && npx vitest run tests/server/chatbot/wecom-bot-connection.test.ts`
Expected: FAIL — module not found

- [ ] **Step 3: Implement WeComBotConnection**

Create `server/chatbot/wecom-bot-connection.ts`:

```typescript
import WebSocket from 'ws';
import { v4 as uuidv4 } from 'uuid';
import { createLogger, type Logger } from '../utils/logger.js';
import type { IncomingMessage, ChatResponseSender } from './types.js';

interface WeComConfig {
  botId: string;
  secret: string;
  onMessage: (msg: IncomingMessage, sender: ChatResponseSender) => Promise<void>;
}

const WECOM_WS_URL = 'wss://openws.work.weixin.qq.com';
const HEARTBEAT_INTERVAL_MS = 30_000;
const RECONNECT_MAX_ATTEMPTS = 100;
const RECONNECT_BASE_DELAY_MS = 1000;

export class WeComBotConnection {
  private log: Logger;
  private ws: WebSocket | null = null;
  private heartbeatTimer: ReturnType<typeof setInterval> | null = null;
  private reconnectAttempts = 0;
  private stopped = false;
  private config: WeComConfig;

  constructor(config: WeComConfig) {
    this.config = config;
    this.log = createLogger('WeComBot');
  }

  start(): void {
    this.stopped = false;
    this.connect();
  }

  stop(): void {
    this.stopped = true;
    this.cleanup();
  }

  private connect(): void {
    if (this.stopped) return;

    this.log.info(`Connecting to WeCom: ${WECOM_WS_URL}`);
    this.ws = new WebSocket(WECOM_WS_URL);

    this.ws.on('open', () => {
      this.log.info('WebSocket connected, sending subscribe...');
      this.subscribe();
      this.startHeartbeat();
      this.reconnectAttempts = 0;
    });

    this.ws.on('message', (data) => {
      this.handleMessage(data.toString());
    });

    this.ws.on('close', (code, reason) => {
      this.log.info(`WebSocket closed: code=${code}, reason=${reason.toString()}`);
      this.stopHeartbeat();
      this.scheduleReconnect();
    });

    this.ws.on('error', (err) => {
      this.log.error('WebSocket error', { error: err.message });
    });
  }

  private subscribe(): void {
    this.send({
      cmd: 'aibot_subscribe',
      headers: { req_id: uuidv4() },
      body: {
        bot_id: this.config.botId,
        secret: this.config.secret,
      },
    });
  }

  private startHeartbeat(): void {
    this.stopHeartbeat();
    this.heartbeatTimer = setInterval(() => {
      this.send({ cmd: 'ping', headers: { req_id: uuidv4() } });
    }, HEARTBEAT_INTERVAL_MS);
  }

  private stopHeartbeat(): void {
    if (this.heartbeatTimer) {
      clearInterval(this.heartbeatTimer);
      this.heartbeatTimer = null;
    }
  }

  private scheduleReconnect(): void {
    if (this.stopped) return;
    if (this.reconnectAttempts >= RECONNECT_MAX_ATTEMPTS) {
      this.log.error('Max reconnect attempts reached, giving up');
      return;
    }
    const delay = Math.min(RECONNECT_BASE_DELAY_MS * Math.pow(2, this.reconnectAttempts), 60000);
    this.reconnectAttempts++;
    this.log.info(`Reconnecting in ${delay}ms (attempt ${this.reconnectAttempts})`);
    setTimeout(() => this.connect(), delay);
  }

  private async handleMessage(raw: string): Promise<void> {
    let msg: any;
    try {
      msg = JSON.parse(raw);
    } catch {
      this.log.warn('Failed to parse message', { raw: raw.substring(0, 200) });
      return;
    }

    const cmd = msg.cmd;

    if (cmd === 'aibot_msg_callback') {
      await this.handleMsgCallback(msg);
    } else if (cmd === 'aibot_event_callback') {
      this.handleEventCallback(msg);
    } else if (cmd === 'ping' || msg.errcode !== undefined) {
      // Subscribe response or pong — no action needed
      if (msg.errcode === 0) {
        this.log.info(`Command response OK: ${msg.headers?.req_id}`);
      } else if (msg.errcode !== undefined) {
        this.log.error(`Command error: ${msg.errcode} ${msg.errmsg}`);
      }
    }
  }

  private async handleMsgCallback(msg: any): Promise<void> {
    const userId = msg.body?.from?.userid;
    const content = msg.body?.text?.content?.replace(new RegExp(`@[^\\s]+\\s?`, 'g'), '').trim();
    const chatType = msg.body?.chattype === 'group' ? 'group' : 'single';
    const chatId = msg.body?.chatid || userId;
    const requestId = msg.headers?.req_id;

    if (!userId || !content) return;

    const incoming: IncomingMessage = {
      platform: 'wecom',
      userId,
      content,
      requestId: requestId || uuidv4(),
      chatType: chatType as 'single' | 'group',
      chatId: chatId || userId,
    };

    const sender: ChatResponseSender = this.createSender(requestId);
    await this.config.onMessage(incoming, sender);
  }

  private handleEventCallback(msg: any): void {
    const eventType = msg.body?.event?.eventtype;
    if (eventType === 'enter_chat') {
      const requestId = msg.headers?.req_id;
      this.send({
        cmd: 'aibot_respond_welcome_msg',
        headers: { req_id: requestId },
        body: {
          msgtype: 'text',
          text: { content: '你好！我是 Claude Code 助手。输入 /help 查看可用命令。' },
        },
      });
    } else if (eventType === 'disconnected_event') {
      this.log.info('Disconnected event received, will reconnect');
    }
  }

  private createSender(requestId?: string): ChatResponseSender {
    const reqId = requestId || uuidv4();
    let streamId = `stream-${Date.now()}-${uuidv4().substring(0, 8)}`;
    let started = false;

    return {
      start() { started = true; },
      sendChunk(content: string) {
        if (!started) return;
        // Will be called via parent's send method — need reference
        // This is handled by binding in the constructor approach below
      },
      finish(fullContent: string) {
        // Send as markdown (simpler than streaming for initial implementation)
        (this as any)._send({
          cmd: 'aibot_respond_msg',
          headers: { req_id: reqId },
          body: {
            msgtype: 'markdown',
            markdown: { content: fullContent },
          },
        });
      },
      error(message: string) {
        (this as any)._send({
          cmd: 'aibot_respond_msg',
          headers: { req_id: reqId },
          body: {
            msgtype: 'text',
            text: { content: message },
          },
        });
      },
    };
  }

  private send(data: object): void {
    if (this.ws?.readyState === WebSocket.OPEN) {
      this.ws.send(JSON.stringify(data));
    }
  }

  private cleanup(): void {
    this.stopHeartbeat();
    if (this.ws) {
      this.ws.close();
      this.ws = null;
    }
  }
}
```

Wait — the sender needs a reference back to `this.send()`. Let me fix this by using a factory pattern instead of inline:

The implementation should use a factory that creates senders bound to the connection:

```typescript
  private createSender(requestId?: string): ChatResponseSender {
    const reqId = requestId || uuidv4();
    let streamId = `stream-${Date.now()}-${uuidv4().substring(0, 8)}`;
    let started = false;
    const self = this;

    return {
      start() { started = true; },
      sendChunk(content: string) {
        if (!started) return;
        self.send({
          cmd: 'aibot_respond_msg',
          headers: { req_id: reqId },
          body: {
            msgtype: 'stream',
            stream: { id: streamId, finish: false, content },
          },
        });
      },
      finish(fullContent: string) {
        if (started) {
          // End the stream
          self.send({
            cmd: 'aibot_respond_msg',
            headers: { req_id: reqId },
            body: {
              msgtype: 'stream',
              stream: { id: streamId, finish: true, content: fullContent },
            },
          });
        } else {
          // Send as markdown
          self.send({
            cmd: 'aibot_respond_msg',
            headers: { req_id: reqId },
            body: {
              msgtype: 'markdown',
              markdown: { content: fullContent },
            },
          });
        }
      },
      error(message: string) {
        self.send({
          cmd: 'aibot_respond_msg',
          headers: { req_id: reqId },
          body: {
            msgtype: 'text',
            text: { content: message },
          },
        });
      },
    };
  }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd /Users/nasakim/projects/smanbase && npx vitest run tests/server/chatbot/wecom-bot-connection.test.ts`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add server/chatbot/wecom-bot-connection.ts tests/server/chatbot/wecom-bot-connection.test.ts
git commit -m "feat(chatbot): add WeCom bot long connection with subscribe, heartbeat, streaming"
```

---

## Chunk 5: Feishu Bot Connection

### Task 6: Feishu long connection

**Files:**
- Create: `server/chatbot/feishu-bot-connection.ts`
- Test: `tests/server/chatbot/feishu-bot-connection.test.ts`

- [ ] **Step 1: Install dependency**

Run: `cd /Users/nasakim/projects/smanbase && pnpm add @larksuiteoapi/node-sdk`

- [ ] **Step 2: Write failing test**

Create `tests/server/chatbot/feishu-bot-connection.test.ts`:

```typescript
import { describe, it, expect, vi } from 'vitest';
import { FeishuBotConnection } from '../../../server/chatbot/feishu-bot-connection.js';

describe('FeishuBotConnection', () => {
  it('should export a class with start/stop methods', () => {
    expect(typeof FeishuBotConnection).toBe('function');
    const conn = new FeishuBotConnection({
      appId: 'test-app',
      appSecret: 'test-secret',
      onMessage: vi.fn(),
    });
    expect(typeof conn.start).toBe('function');
    expect(typeof conn.stop).toBe('function');
  });
});
```

- [ ] **Step 3: Run test to verify it fails**

Run: `cd /Users/nasakim/projects/smanbase && npx vitest run tests/server/chatbot/feishu-bot-connection.test.ts`
Expected: FAIL — module not found

- [ ] **Step 4: Implement FeishuBotConnection**

Create `server/chatbot/feishu-bot-connection.ts`:

```typescript
import * as Lark from '@larksuiteoapi/node-sdk';
import { createLogger, type Logger } from '../utils/logger.js';
import type { IncomingMessage, ChatResponseSender } from './types.js';

interface FeishuConfig {
  appId: string;
  appSecret: string;
  onMessage: (msg: IncomingMessage, sender: ChatResponseSender) => Promise<void>;
}

export class FeishuBotConnection {
  private log: Logger;
  private client: Lark.Client;
  private wsClient: Lark.WSClient | null = null;
  private config: FeishuConfig;

  constructor(config: FeishuConfig) {
    this.config = config;
    this.log = createLogger('FeishuBot');
    this.client = new Lark.Client({
      appId: config.appId,
      appSecret: config.appSecret,
    });
  }

  start(): void {
    this.log.info('Starting Feishu bot long connection...');

    this.wsClient = new Lark.WSClient({
      appId: this.config.appId,
      appSecret: this.config.appSecret,
      loggerLevel: Lark.LoggerLevel.info,
    });

    this.wsClient.start({
      eventDispatcher: new Lark.EventDispatcher({}).register({
        'im.message.receive_v1': async (data: any) => {
          await this.handleMessage(data);
        },
      }),
    });

    this.log.info('Feishu bot connected');
  }

  stop(): void {
    // WSClient doesn't expose a clean stop method
    this.wsClient = null;
    this.log.info('Feishu bot stopped');
  }

  private async handleMessage(data: any): Promise<void> {
    const message = data.message;
    if (!message) return;

    const userId = message.sender?.sender_id?.open_id || message.sender?.sender_id?.user_id;
    const chatType = message.chat_type === 'p2p' ? 'p2p' : 'group';
    const chatId = message.chat_id;
    const messageType = message.message_type;

    if (messageType !== 'text') {
      // Only handle text messages for now
      return;
    }

    let content: string;
    try {
      const parsed = JSON.parse(message.content);
      content = parsed.text?.trim() || '';
    } catch {
      content = message.content || '';
    }

    if (!content || !userId) return;

    const incoming: IncomingMessage = {
      platform: 'feishu',
      userId,
      content,
      requestId: message.message_id || `feishu-${Date.now()}`,
      chatType: chatType as 'p2p' | 'group',
      chatId,
    };

    const sender = this.createSender(chatId);
    await this.config.onMessage(incoming, sender);
  }

  private createSender(chatId: string): ChatResponseSender {
    let accumulated = '';
    const self = this;

    return {
      start() {},
      sendChunk(content: string) {
        accumulated += content;
      },
      async finish(fullContent: string) {
        const text = fullContent || accumulated;
        if (!text) return;

        // Feishu has a ~4000 char limit per message
        const chunks = self.splitMessage(text, 3900);
        for (const chunk of chunks) {
          try {
            await self.client.im.message.create({
              params: { receive_id_type: 'chat_id' },
              data: {
                receive_id: chatId,
                msg_type: 'text',
                content: JSON.stringify({ text: chunk }),
              },
            });
          } catch (err) {
            self.log.error('Failed to send Feishu message', { error: err });
          }
        }
      },
      async error(message: string) {
        try {
          await self.client.im.message.create({
            params: { receive_id_type: 'chat_id' },
            data: {
              receive_id: chatId,
              msg_type: 'text',
              content: JSON.stringify({ text: message }),
            },
          });
        } catch (err) {
          self.log.error('Failed to send Feishu error message', { error: err });
        }
      },
    };
  }

  private splitMessage(text: string, maxLen: number): string[] {
    if (text.length <= maxLen) return [text];
    const chunks: string[] = [];
    for (let i = 0; i < text.length; i += maxLen) {
      chunks.push(text.substring(i, i + maxLen));
    }
    return chunks;
  }
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `cd /Users/nasakim/projects/smanbase && npx vitest run tests/server/chatbot/feishu-bot-connection.test.ts`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add server/chatbot/feishu-bot-connection.ts tests/server/chatbot/feishu-bot-connection.test.ts
git commit -m "feat(chatbot): add Feishu bot long connection with SDK WSClient"
```

---

## Chunk 6: Server Integration

### Task 7: Wire chatbot into smanbase server

**Files:**
- Modify: `server/index.ts`

- [ ] **Step 1: Add chatbot imports and initialization**

In `server/index.ts`, after existing imports, add:

```typescript
import { ChatbotStore } from './chatbot/chatbot-store.js';
import { ChatbotSessionManager } from './chatbot/chatbot-session-manager.js';
import { WeComBotConnection } from './chatbot/wecom-bot-connection.js';
import { FeishuBotConnection } from './chatbot/feishu-bot-connection.js';
```

After batch engine initialization, add:

```typescript
// Chatbot integration
const chatbotStore = new ChatbotStore(dbPath);
const chatbotManager = new ChatbotSessionManager(homeDir, sessionManager, chatbotStore);

let wecomConnection: WeComBotConnection | null = null;
let feishuConnection: FeishuBotConnection | null = null;

function startChatbotConnections(): void {
  const chatbotConfig = settingsManager.getConfig().chatbot;
  if (!chatbotConfig?.enabled) return;

  if (chatbotConfig.wecom.enabled && chatbotConfig.wecom.botId && chatbotConfig.wecom.secret) {
    wecomConnection = new WeComBotConnection({
      botId: chatbotConfig.wecom.botId,
      secret: chatbotConfig.wecom.secret,
      onMessage: (msg, sender) => chatbotManager.handleMessage(msg, sender),
    });
    wecomConnection.start();
    log.info('WeCom bot connection started');
  }

  if (chatbotConfig.feishu.enabled && chatbotConfig.feishu.appId && chatbotConfig.feishu.appSecret) {
    feishuConnection = new FeishuBotConnection({
      appId: chatbotConfig.feishu.appId,
      appSecret: chatbotConfig.feishu.appSecret,
      onMessage: (msg, sender) => chatbotManager.handleMessage(msg, sender),
    });
    feishuConnection.start();
    log.info('Feishu bot connection started');
  }
}

startChatbotConnections();
```

In `settings.update` handler, add config propagation:

```typescript
case 'settings.update': {
  const { type: _t, ...updates } = msg;
  const config = settingsManager.updateConfig(updates as Partial<import('./types.js').SmanConfig>);
  sessionManager.updateConfig(config);
  batchEngine.setConfig(config.llm);
  // Restart chatbot connections if config changed
  if (updates.chatbot) {
    wecomConnection?.stop();
    feishuConnection?.stop();
    startChatbotConnections();
  }
  ws.send(JSON.stringify({ type: 'settings.updated', config }));
  break;
}
```

In shutdown handlers:

```typescript
process.on('SIGTERM', () => {
  log.info('SIGTERM received, shutting down...');
  wecomConnection?.stop();
  feishuConnection?.stop();
  chatbotManager.stop();
  batchEngine.stop();
  cronScheduler.stop();
  sessionManager.close();
  wss.close();
  server.close(() => process.exit(0));
});

process.on('SIGINT', () => {
  log.info('SIGINT received, shutting down...');
  wecomConnection?.stop();
  feishuConnection?.stop();
  chatbotManager.stop();
  batchEngine.stop();
  cronScheduler.stop();
  sessionManager.close();
  wss.close();
  server.close(() => process.exit(0));
});
```

- [ ] **Step 2: Add WebSocket commands for chatbot workspaces management**

In `server/index.ts` WebSocket handler, add new cases:

```typescript
// ── Chatbot Workspaces ──
case 'chatbot.workspaces': {
  const workspaces = chatbotStore.listWorkspaces();
  ws.send(JSON.stringify({ type: 'chatbot.workspaces', workspaces }));
  break;
}

case 'chatbot.addWorkspace': {
  if (!msg.path) throw new Error('Missing path');
  const name = path.basename(msg.path as string);
  chatbotStore.addWorkspace(msg.path as string, name);
  ws.send(JSON.stringify({ type: 'chatbot.workspaceAdded', path: msg.path, name }));
  break;
}
```

- [ ] **Step 3: Verify compilation**

Run: `cd /Users/nasakim/projects/smanbase && npx tsc -p server/tsconfig.json --noEmit`
Expected: No errors

- [ ] **Step 4: Run all tests**

Run: `cd /Users/nasakim/projects/smanbase && npx vitest run`
Expected: ALL PASS

- [ ] **Step 5: Commit**

```bash
git add server/index.ts
git commit -m "feat(chatbot): wire WeCom + Feishu bot into smanbase server lifecycle"
```

---

## Chunk 7: Final Verification

### Task 8: Full test suite + code review

- [ ] **Step 1: Run complete test suite**

Run: `cd /Users/nasakim/projects/smanbase && npx vitest run`
Expected: ALL PASS

- [ ] **Step 2: Run code-simplifier on all new files**

```bash
# Review all new chatbot files for quality
```

- [ ] **Step 3: Final commit if any cleanup needed**

```bash
git add -A
git commit -m "refactor(chatbot): code review cleanup"
```
