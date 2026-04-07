# Architecture

**Analysis Date:** 2026-04-07

## Pattern Overview

**Overall:** Layered client-server architecture with WebSocket-based real-time communication and an embedded AI agent runtime.

**Key Characteristics:**
- Single-process Node.js server with in-process Claude Agent SDK sessions
- WebSocket message bus for all client-server communication (not REST)
- Three front-end entry points (Electron desktop, WeCom Bot, Feishu Bot) sharing one backend
- SQLite for persistent storage, in-memory maps for live session state
- Plugin/Capability system with on-demand loading via MCP (Model Context Protocol) servers
- Server is a monolithic entry point (`server/index.ts`, 1062 lines) with all WebSocket handler registration

## Layers

**Electron Shell Layer:**
- Purpose: Desktop window management, IPC for native dialogs, in-process server lifecycle
- Location: `electron/main.ts`, `electron/preload.ts`
- Contains: BrowserWindow creation, GPU compatibility, preload bridge (`window.sman` API)
- Depends on: Server layer (started in-process via dynamic import in production)
- Used by: End user (desktop application)

**Frontend Layer (React SPA):**
- Purpose: User interface for chat, settings, cron/batch task management
- Location: `src/` directory
- Contains: React components, Zustand stores, WebSocket client, TypeScript types
- Depends on: Backend via WebSocket (`src/lib/ws-client.ts`), Radix UI, TailwindCSS
- Used by: Electron renderer, or browser in dev mode

**Backend Layer (Node.js/Express):**
- Purpose: HTTP static file serving, WebSocket server, all business logic orchestration
- Location: `server/index.ts` (entry), `server/*.ts` (modules), `server/chatbot/`, `server/web-access/`, `server/capabilities/`
- Contains: WebSocket message routing, session management, settings, cron/batch scheduling
- Depends on: Claude Agent SDK, SQLite (better-sqlite3), ws, Express
- Used by: Frontend via WebSocket, Chatbot connections via platform SDKs

**AI Agent Layer (Claude Agent SDK V2):**
- Purpose: Manage Claude AI sessions, stream responses, execute tools
- Location: `server/claude-session.ts`
- Contains: V2 session lifecycle, streaming response parsing, stall detection, abort handling
- Depends on: `@anthropic-ai/claude-agent-sdk`, `@anthropic-ai/claude-code` (CLI binary)
- Used by: Backend layer (via `ClaudeSessionManager`)

**Plugin/Capability Layer:**
- Purpose: Extensible skill and tool system for Claude sessions
- Location: `server/capabilities/`, `plugins/`
- Contains: Capability registry, gateway MCP server, plugin runners (office-skills, frontend-slides)
- Depends on: Claude Agent SDK MCP API, plugin-specific dependencies
- Used by: AI Agent layer (injected as MCP servers into sessions)

## Data Flow

**Desktop Chat Flow:**

1. User opens Electron app -> `electron/main.ts` creates BrowserWindow, starts server (production) or connects to dev server
2. Frontend `App.tsx` initializes: fetches auth token from `/api/auth/token`, connects WebSocket, sends `auth.verify`
3. User selects directory (native dialog via IPC `dialog:selectDirectory`) -> frontend sends `session.create` with workspace path
4. User types message -> frontend sends `chat.send` -> backend `ClaudeSessionManager.sendMessage()` creates/resumes V2 SDK session
5. Claude session streams `assistant`, `stream_event`, `tool_progress`, `result` messages -> backend forwards as `chat.delta`, `chat.tool_start`, `chat.tool_delta`, `chat.done` to frontend
6. Frontend accumulates streaming state in Zustand store, renders in real-time via `ChatMessage.tsx`

**WeCom/Feishu Bot Flow:**

1. Bot connection (`wecom-bot-connection.ts` / `feishu-bot-connection.ts`) receives platform message
2. Parsed into `IncomingMessage` -> `ChatbotSessionManager.handleMessage()` processes commands or routes to Claude
3. Session auto-created per user+workspace pair -> `sendMessageForChatbot()` streams response
4. Response chunks sent back via platform-specific API (WeCom stream API with 2s throttle, or Feishu message API)
5. Chatbot sessions appear in desktop sidebar via `session.chatbotCreated` broadcast

**Cron Task Flow:**

1. User configures cron task (workspace + skill + cron expression) -> `CronTaskStore` persists to SQLite
2. `CronScheduler` schedules with `node-cron`, on trigger calls `CronExecutor`
3. Executor creates headless Claude session, calls `sendMessageForCron()` with stall detection
4. Results stored as `CronRun` records, zombie detection for stale runs

**Batch Task Flow:**

1. User creates batch task with markdown content and execution template -> `BatchStore` persists
2. `BatchEngine.generateCode()` uses Claude to generate batch execution script
3. User tests/saves -> engine executes with `Semaphore` for concurrency control
4. Progress broadcast via `batch.progress` WebSocket messages, retry support for failed items

**Capability Loading Flow:**

1. Claude session starts with `capability-gateway` MCP server injected
2. Claude calls `capability_list` (keyword/semantic search) -> `CapabilityRegistry` searches capabilities
3. Claude calls `capability_load` with capability ID -> gateway loads plugin MCP server dynamically
4. Plugin tools become available to Claude for the rest of the session

**State Management:**
- **Server-side:** In-memory `Map<string, V2SessionInfo>` for active V2 sessions, `Map<string, AbortController>` for active streams, SQLite for persistence
- **Client-side:** Zustand stores (`chat.ts`, `settings.ts`, `cron.ts`, `batch.ts`, `ws-connection.ts`) with WebSocket-driven updates
- **Cross-client:** Server broadcasts events (e.g., `session.chatbotCreated`, `batch.progress`) to all connected WebSocket clients

## Key Abstractions

**ClaudeSessionManager (`server/claude-session.ts`):**
- Purpose: Central orchestrator for all Claude AI interactions across three front-end channels
- Examples: `server/claude-session.ts`
- Pattern: Manager class with three send methods: `sendMessage()` (desktop), `sendMessageForChatbot()` (bots), `sendMessageForCron()` (scheduled tasks). Each handles streaming differently but shares V2 session lifecycle.
- Key behaviors: Idle timeout cleanup (30 min), stall detection (3 min no data, 2 hr hard limit), abort with partial save, session resume from persisted SDK session ID

**WebSocket Message Protocol:**
- Purpose: Unified JSON-based bidirectional message protocol
- Examples: `server/index.ts` (handler registration), `src/lib/ws-client.ts` (client), `src/stores/chat.ts` (consumer)
- Pattern: `type` field determines message kind. Client sends `type: 'chat.send'`, server responds with `type: 'chat.delta'` etc. Auth required before any other message.

**BrowserEngine Interface (`server/web-access/browser-engine.ts`):**
- Purpose: Abstract browser automation backend, currently implemented only by CdpEngine
- Examples: `server/web-access/browser-engine.ts` (interface), `server/web-access/cdp-engine.ts` (CDP implementation)
- Pattern: Strategy pattern. `WebAccessService` detects and selects engine. MCP tools (`web_access_*`) never know which engine is active.

**CapabilityRegistry (`server/capabilities/registry.ts`):**
- Purpose: Discover and manage on-demand capabilities with usage tracking
- Examples: `server/capabilities/registry.ts`, `~/.sman/capabilities.json` (system), `~/.sman/user-capabilities.json` (user-learned)
- Pattern: Three-tier search: keyword OR match -> LLM semantic search -> user-learned capabilities. Usage frequency tracking for result ranking.

**Semaphore (`server/semaphore.ts`):**
- Purpose: Concurrency control primitive with pause/resume/stop
- Examples: `server/semaphore.ts`, used by `server/batch-engine.ts`
- Pattern: Custom async semaphore with lifecycle states (normal, paused, stopped). `withLock()` helper for auto-release.

**SessionStore (`server/session-store.ts`):**
- Purpose: SQLite persistence for sessions and messages with online migration
- Examples: `server/session-store.ts`
- Pattern: Direct better-sqlite3 with manual schema migration (try/catch ALTER TABLE). WAL journal mode. Soft delete via `deleted_at` column.

## Entry Points

**Backend Server:**
- Location: `server/index.ts`
- Triggers: `pnpm dev:server` (dev), Electron `startServerInProcess()` (production)
- Responsibilities: HTTP server (static files + API), WebSocket server (all communication), service initialization (session store, settings, cron, batch, chatbot connections, web access, capability registry)

**Electron Main Process:**
- Location: `electron/main.ts`
- Triggers: `pnpm electron:dev` or packaged app launch
- Responsibilities: Window creation, IPC handlers (directory dialog, window controls), server lifecycle management, GPU compatibility

**Frontend SPA:**
- Location: `src/app/App.tsx`
- Triggers: Browser loads `index.html` (via Vite dev server on :5881, or via backend static serving on :5880)
- Responsibilities: Initialize auth token, establish WebSocket connection, load settings and sessions, render router

**WeCom Bot Connection:**
- Location: `server/chatbot/wecom-bot-connection.ts`
- Triggers: Server startup when chatbot config has wecom enabled
- Responsibilities: WebSocket connection to WeCom platform, heartbeat, reconnection, message parsing, stream throttled responses

**Feishu Bot Connection:**
- Location: `server/chatbot/feishu-bot-connection.ts`
- Triggers: Server startup when chatbot config has feishu enabled
- Responsibilities: Lark SDK WebSocket client, event dispatching, message format conversion

**WeChat Personal Bot Connection:**
- Location: `server/chatbot/weixin-bot-connection.ts`
- Triggers: Server startup when chatbot config has weixin enabled
- Responsibilities: QR login flow, message routing, status reporting via WebSocket broadcast

## Error Handling

**Strategy:** Layered with graceful degradation.

**Patterns:**
- Backend WebSocket handlers: try/catch per message, errors sent as `type: 'chat.error'` to client
- Claude session streaming: stall detection with AbortController, partial content saved on abort
- V2 session crash recovery: `process.kill(pid, 0)` checks process liveness, recreates dead sessions
- Bot connections: exponential backoff reconnection (up to 100 attempts for WeCom), timeout-based abort (5 min for chatbot queries)
- Frontend: Error state in Zustand stores, ErrorBoundary component for React crash recovery
- Plugin setup: Non-blocking, errors logged but don't prevent server startup

## Cross-Cutting Concerns

**Logging:** Structured JSON logging via `server/utils/logger.ts` (`createLogger(module)`). Output to stdout/stderr. LOG_LEVEL=env for debug mode. Module-based context in every log line.

**Validation:** Zod schemas in MCP tool definitions (`server/web-access/mcp-server.ts`, `server/capabilities/gateway-mcp-server.ts`). Manual parameter validation in WebSocket handlers with descriptive error messages. Cron expression validation via `cron-parser`.

**Authentication:** Bearer token auth for both HTTP API and WebSocket. Token auto-generated on first run, stored in `~/.sman/config.json`. Loopback-only `/api/auth/token` endpoint for Electron to retrieve token. WebSocket auth timeout (5 seconds). CORS restricted to localhost origins.

**Configuration:** `SettingsManager` reads/writes `~/.sman/config.json`. Config changes propagated to all dependent services (session manager, batch engine, chatbot connections). Settings synced to frontend via `settings.get`/`settings.update` WebSocket messages.

---

*Architecture analysis: 2026-04-07*
