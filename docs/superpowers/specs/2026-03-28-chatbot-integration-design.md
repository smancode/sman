# Chatbot Integration Design: WeCom + Feishu Long Connection

> Date: 2026-03-28
> Status: Reviewed & Revised

## 1. Goal

Enable smanbase to receive and respond to messages from WeCom and Feishu bots via long connection (WebSocket), allowing users to interact with Claude Code through IM chat with workspace switching support.

## 2. Scope

- WeCom: Intelligent Bot long connection (`wss://openws.work.weixin.qq.com`)
- Feishu: Bot long connection via `@larksuiteoapi/node-sdk` (`WSClient`)
- Workspace switching via chat commands (`/cd`, `/pwd`, `/workspaces`, `/add`, `/help`)
- Streaming response support (WeCom native stream, Feishu complete message)
- Persistent user state across restarts via SQLite

## 3. Architecture

```
┌──────────────────────────────────────────────────────────────┐
│                      smanbase server                         │
│                                                              │
│  ┌─────────────┐  ┌─────────────┐  ┌────────────────────┐   │
│  │ WeComBot    │  │ FeishuBot   │  │ ChatCommand        │   │
│  │ Connection  │  │ Connection  │  │ Parser             │   │
│  └──────┬──────┘  └──────┬──────┘  └───────┬────────────┘   │
│         │                │                  │                 │
│         └────────┬───────┘                  │                 │
│                  ▼                          │                 │
│  ┌───────────────────────────┐              │                 │
│  │ ChatbotSessionManager     │◄─────────────┘                 │
│  │ - user state (SQLite)     │                                │
│  │ - workspace switching     │                                │
│  │ - session lifecycle       │                                │
│  │ - per-user message queue  │                                │
│  └───────────┬───────────────┘                                │
│              │                                                │
│              ▼                                                │
│  ┌───────────────────────────┐                                │
│  │ ClaudeSessionManager      │  (existing)                    │
│  │ .sendMessageForChatbot()  │  ← NEW method                  │
│  └───────────────────────────┘                                │
└──────────────────────────────────────────────────────────────┘
```

### Key Components

1. **`ChatbotSessionManager`** — Central coordinator for IM bot sessions
   - Manages per-user workspace state and Claude session mapping
   - Persists state to SQLite (same DB as existing sessions)
   - Per-user message queue with timeout

2. **`WeComBotConnection`** — WeCom long connection handler
   - WebSocket to `wss://openws.work.weixin.qq.com`
   - Manual subscribe, heartbeat (30s), auto-reconnect
   - Full-duplex: receive callbacks, send responses on same WS
   - Native streaming support via `stream` msgtype

3. **`FeishuBotConnection`** — Feishu long connection handler
   - Uses `@larksuiteoapi/node-sdk` WSClient
   - SDK handles heartbeat, auth, reconnect
   - Half-duplex: receive events via WS, send via REST API
   - Complete message delivery (no streaming)

4. **`ChatCommandParser`** — Chat command interpreter
   - Parses `/cd`, `/pwd`, `/workspaces`, `/add`, `/help`
   - Returns structured result: `ParseResult`

## 4. Data Structures

### 4.1 Config Extension (`SmanConfig`)

```typescript
interface ChatbotConfig {
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
```

Added to `SmanConfig`:
```typescript
interface SmanConfig {
  // ... existing fields
  chatbot: ChatbotConfig;
}
```

### 4.2 Unified Response Sender Interface

```typescript
interface ChatResponseSender {
  start(): void;
  sendChunk(content: string): void;
  finish(fullContent: string): void;
  error(message: string): void;
}
```

- WeCom: implements streaming via `aibot_respond_msg` with `stream` msgtype
- Feishu: collects full content, sends via REST API on `finish()`

### 4.3 ChatCommandParser Interface

```typescript
interface CommandResult {
  command: 'cd' | 'pwd' | 'workspaces' | 'add' | 'help' | 'status';
  args: string;
}

interface ParseResult {
  isCommand: boolean;
  command?: CommandResult;
}
```

### 4.4 SQLite Tables (added to existing `sman.db`)

```sql
CREATE TABLE IF NOT EXISTS chatbot_users (
  user_key TEXT PRIMARY KEY,           -- 'wecom:userid' or 'feishu:open_id'
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
```

This replaces the original JSON file approach — consistent with the existing SQLite-based persistence pattern.

### 4.5 Per-User Message Queue

```typescript
// In-memory queue, not persisted (acceptable loss on crash)
interface UserQueue {
  userId: string;
  active: boolean;              // true = Claude query in progress
  pending: Array<{
    content: string;
    requestId: string;
    sender: ChatResponseSender;
    receivedAt: Date;
  }>;
}
```

- Queue timeout: 5 minutes (discard stale pending messages)
- Max queue depth: 5 messages per user
- On server restart: queue is empty (acceptable)

## 5. Chat Commands

| Command | Description | Example |
|---------|-------------|---------|
| `/cd <path\|name>` | Switch workspace | `/cd /path/to/project` or `/cd project-name` |
| `/pwd` | Show current workspace | `/pwd` |
| `/workspaces` | List known workspaces | `/workspaces` |
| `/add <path>` | Register workspace | `/add /Users/nasakim/projects/new` |
| `/help` | Show command help | `/help` |
| `/status` | Show connection status | `/status` |

**Security**: `/cd` only allows paths registered in `chatbot_workspaces` table. Unregistered paths are rejected with a message suggesting `/add` first.

## 6. Message Flow

### 6.1 Incoming Message Processing

```
IM message received
  → Extract userId, content, requestId
  → Create ChatResponseSender for the platform
  → Parse command via ChatCommandParser
    YES → Execute command → Respond immediately via sender
    NO  → Check user has workspace set
        → If no workspace → respond with help
        → Get/create user state from SQLite
        → Enqueue message in per-user queue
        → If no active query → process next from queue
        → Create/resume Claude session for workspace
        → Send via ClaudeSessionManager.sendMessageForChatbot()
        → Deliver response via ChatResponseSender
        → Process next queued message (if any)
```

### 6.2 New Method: `sendMessageForChatbot()`

**CRITICAL**: The existing `sendMessageForCron()` is `Promise<void>` and does not return content. It also lacks SDK session resumption. We add a new method to `ClaudeSessionManager`:

```typescript
async sendMessageForChatbot(
  sessionId: string,
  content: string,
  abortController: AbortController,
  onActivity: () => void,
  onResponse: (chunk: string) => void,  // NEW: streaming callback
): Promise<string>  // NEW: returns full content
```

This method:
1. Supports SDK session resumption (loads `sdkSessionId` and sets `options.resume`)
2. Calls `onResponse(chunk)` for each text delta (enables WeCom streaming)
3. Returns the complete response text as `string`
4. Follows the same pattern as `sendMessage()` for streaming, but routes output to the callback instead of WebSocket

### 6.3 Response Handling

**WeCom**: `ChatResponseSender` implements streaming:
```
1. start() → send initial stream message with unique streamId
2. sendChunk(content) → push content via stream update
3. finish(fullContent) → send final stream with finish: true
4. error(message) → send error as text message
```

**Feishu**: `ChatResponseSender` collects and sends complete:
```
1. start() → no-op
2. sendChunk(content) → accumulate content
3. finish(fullContent) → send via client.im.message.create (split if > 4000 chars)
4. error(message) → send error as text message
```

### 6.4 Workspace Switching Flow

```
User: /cd hello-halo
  → Lookup in chatbot_workspaces: find "/Users/nasakim/projects/hello-halo"
  → Validate path exists on filesystem
  → Get/create user state entry
  → If workspace already has a session in chatbot_sessions:
      → Load sdkSessionId for resumption
  → If new workspace:
      → Create new Claude session via sessionManager.createSessionWithId()
  → Update current_workspace in chatbot_users
  → Respond: "已切换到: hello-halo (/Users/nasakim/projects/hello-halo)"
```

### 6.5 Workspace Discovery from Existing Sessions

The `/workspaces` command merges two sources:
1. Paths registered in `chatbot_workspaces` table (via `/add`)
2. Unique workspaces from existing `sessions` table (via `store.listSessions()`)

This avoids the dual-registry problem — the system shows all known workspaces regardless of how they were discovered.

## 7. Error Handling & Timeout

| Scenario | Behavior |
|----------|----------|
| No workspace set | Respond with help message asking to `/cd` first |
| Invalid workspace path | Respond: "目录不存在或未注册" |
| Claude query timeout (>5 min) | `AbortController.abort()` + respond with timeout message |
| Connection lost | Auto-reconnect with exponential backoff (max 100 attempts) |
| Rate limit exceeded | Drop queued messages older than 5 min, respond with retry hint |
| Multiple concurrent messages | Queue per-user, process sequentially |
| Queue full (>5 messages) | Respond: "请求繁忙，请稍后再试" |
| Zombie query (>30 min idle) | Abort via periodic check (reuse CronExecutor pattern) |

### Timeout Enforcement

```typescript
// In ChatbotSessionManager
const CHATBOT_QUERY_TIMEOUT_MS = 5 * 60 * 1000; // 5 minutes
const CHATBOT_ZOMBIE_THRESHOLD_MS = 30 * 60 * 1000; // 30 minutes

// Set timeout on each query
setTimeout(() => {
  if (activeQuery) abortController.abort();
}, CHATBOT_QUERY_TIMEOUT_MS);
```

## 8. File Structure

```
server/
  chatbot/
    chatbot-session-manager.ts   # Central coordinator + queue
    chat-command-parser.ts       # Command parsing
    chatbot-store.ts             # SQLite persistence for chatbot tables
    wecom-bot-connection.ts      # WeCom long connection
    feishu-bot-connection.ts     # Feishu long connection
    types.ts                     # Chatbot-specific types
```

New test files:
```
tests/server/chatbot/
  chatbot-session-manager.test.ts
  chat-command-parser.test.ts
  chatbot-store.test.ts
  wecom-bot-connection.test.ts
  feishu-bot-connection.test.ts
```

## 9. Dependencies

| Package | Version | Purpose |
|---------|---------|---------|
| `ws` | (existing) | WeCom WebSocket connection |
| `@larksuiteoapi/node-sdk` | ^1.24.0 | Feishu WSClient + REST API client |

## 10. Integration with Existing Server

In `index.ts`, after existing initialization:

```typescript
import { ChatbotSessionManager } from './chatbot/chatbot-session-manager.js';

// After sessionManager, settingsManager are initialized
const chatbotManager = new ChatbotSessionManager(
  homeDir,
  sessionManager,
  settingsManager,
);

// Start connections based on config
chatbotManager.start();

// Wire config updates
// In settings.update handler:
chatbotManager.updateConfig(config);
```

Graceful shutdown additions:
```typescript
process.on('SIGTERM', () => {
  // ... existing shutdown
  chatbotManager.stop();
});
```

## 11. Configuration UI

Settings page needs new `chatbot` section:
- Toggle WeCom/Feishu enabled
- Input fields for WeCom botId + secret
- Input fields for Feishu appId + appSecret
- Workspace management (add/remove known workspaces)

Handled through existing `settings.update` WebSocket command.

## 12. Message Authentication

### WeCom
- Long connection mode does NOT require message signature verification
- Authentication is implicit: only the subscriber with valid `bot_id` + `secret` receives callbacks
- The `aibot_subscribe` command with correct credentials is the auth mechanism
- Each connection is tied to a specific bot identity

### Feishu
- Long connection mode delivers events as **plaintext** (no encryption/decryption needed)
- Authentication is via `app_access_token` obtained during WebSocket handshake
- Only the app with correct `appId` + `appSecret` can establish the connection
- Events are only delivered to the authenticated app's connections

## 13. Log Correlation

Each incoming message is assigned a `correlationId` (UUID) that propagates through all layers:
- ChatbotSessionManager logs include `correlationId`
- Claude session message stored with `correlationId` in metadata
- Response delivery logged with `correlationId`

This enables tracing the full message lifecycle for debugging.

## 14. Key Design Decisions

1. **Per-user isolation**: Each IM user has independent workspace state
2. **SQLite persistence**: Consistent with existing pattern (not JSON files)
3. **New `sendMessageForChatbot()` method**: Returns content + supports streaming + session resumption
4. **WeCom streaming**: Use native stream msgtype for real-time output
5. **Feishu no streaming**: Send complete response (simpler, avoids rate limits)
6. **Whitelist security**: Only registered workspaces accessible via `/cd`
7. **Per-user message queue**: Sequential processing, 5-message depth, 5-min timeout
8. **Unified `ChatResponseSender`**: Abstract platform-specific delivery
9. **Workspace discovery merge**: Combine explicit `/add` with existing session history
10. **Correlation IDs**: Full lifecycle tracing for debugging
