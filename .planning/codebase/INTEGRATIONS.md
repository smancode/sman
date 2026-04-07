# External Integrations

**Analysis Date:** 2026-04-07

## APIs & External Services

**Anthropic Claude API:**
- Direct API calls for model capability detection and semantic search
  - SDK/Client: Native `fetch()` in `server/model-capabilities.ts` and `server/capabilities/registry.ts`
  - Auth: API key from `config.llm.apiKey`, optional base URL via `config.llm.baseUrl` (supports `ANTHROPIC_BASE_URL`)
  - Used for: `testAnthropicCompat()`, `detectCapabilities()`, capability semantic matching
- Claude Agent SDK V2 sessions for all AI interactions
  - SDK/Client: `@anthropic-ai/claude-agent-sdk` (`unstable_v2_createSession`)
  - CLI subprocess: `@anthropic-ai/claude-code` (`cli.js`)
  - Auth: API key injected via `ANTHROPIC_API_KEY` env var in session options
  - Session lifecycle managed by `server/claude-session.ts`
  - Session ID persisted to SQLite for crash recovery/resume

**Web Search MCP Servers:**
- Brave Search - `@anthropic-ai/mcp-server-brave` (npx stdio)
  - SDK/Client: MCP stdio server, configured in `server/mcp-config.ts`
  - Auth: `BRAVE_API_KEY` from `config.webSearch.braveApiKey`
  - Triggered when `config.webSearch.provider === 'brave'`
- Tavily Search - `@anthropic-ai/mcp-server-tavily` (npx stdio)
  - SDK/Client: MCP stdio server, configured in `server/mcp-config.ts`
  - Auth: `TAVILY_API_KEY` from `config.webSearch.tavilyApiKey`
  - Triggered when `config.webSearch.provider === 'tavily'`
- Bing Search - `@anthropic-ai/mcp-server-bing` (npx stdio)
  - SDK/Client: MCP stdio server, configured in `server/mcp-config.ts`
  - Auth: `BING_SEARCH_V7_SUBSCRIPTION_KEY` from `config.webSearch.bingApiKey`
  - Triggered when `config.webSearch.provider === 'bing'`
- Builtin - Claude Code's native web search (no MCP server needed)

**WeCom (WeChat Work) Bot:**
- WebSocket long connection to `wss://openws.work.weixin.qq.com`
  - SDK/Client: Custom `WebSocket` client in `server/chatbot/wecom-bot-connection.ts`
  - Auth: `botId` + `secret` via `aibot_subscribe` command
  - Features: heartbeat (30s), auto-reconnect (exponential backoff, max 100 attempts)
  - Message types: text, image, voice, file, video, mixed
  - Stream responses: `aibot_respond_msg` with `msgtype: 'stream'`, 2-second throttle
  - Media download: `server/chatbot/wecom-media.ts` (AES decryption)
  - Config: `config.chatbot.wecom.{enabled, botId, secret}`

**Feishu (Lark) Bot:**
- Long connection via `@larksuiteoapi/node-sdk` `WSClient`
  - SDK/Client: `@larksuiteoapi/node-sdk` (^1.60.0) in `server/chatbot/feishu-bot-connection.ts`
  - Auth: `appId` + `appSecret` from `config.chatbot.feishu`
  - Events: `im.message.receive_v1` for incoming messages
  - Message types: text, image, audio, file, video
  - File download: `client.im.messageResource.get()` with readable stream
  - Response: `client.im.message.create()` (text messages, auto-split at 3900 chars)
  - Config: `config.chatbot.feishu.{enabled, appId, appSecret}`

**WeChat Personal Bot:**
- HTTP API to `https://ilinkai.weixin.qq.com`
  - SDK/Client: Custom HTTP client in `server/chatbot/weixin-api.ts`
  - Auth: QR code login flow, token-based session
  - Features: QR login, long-polling message monitor, session management
  - State: `server/chatbot/weixin-store.ts` (persisted to `~/.sman/`)
  - Config: `config.chatbot.weixin.enabled`

## Data Storage

**Databases:**
- SQLite (embedded, no server)
  - Connection: `better-sqlite3` synchronous driver
  - Database path: `~/.sman/sman.db`
  - Client: Direct `Database` instances in store classes
  - Tables: sessions, messages, cron_tasks, cron_runs, batch_tasks, batch_items, chatbot_user_state, chatbot_sessions, chatbot_workspaces
  - Store classes: `server/session-store.ts`, `server/cron-task-store.ts`, `server/batch-store.ts`, `server/chatbot/chatbot-store.ts`

**File Storage:**
- Local filesystem at `~/.sman/` (config, skills, logs, profiles)
- `~/.sman/chrome-profile/` - Dedicated Chrome profile for browser automation
- Project workspaces selected by user (read/write by Claude agent)
- Skills stored in `~/.sman/skills/` (global) and `{workspace}/.claude/skills/` (project)

**Caching:**
- In-memory skills cache with 1-minute TTL (`server/index.ts` lines 167-184)
- In-memory capability registry with lazy loading (`server/capabilities/registry.ts`)
- V2 SDK sessions kept alive for 30-minute idle timeout (`server/claude-session.ts`)

## Authentication & Identity

**Auth Provider:**
- Custom token-based auth
  - Implementation: `server/settings-manager.ts` (`ensureAuthToken()` generates 64-byte hex token on first run)
  - HTTP auth: Bearer token on `/api/*` endpoints (loopback `/api/auth/token` bypasses auth)
  - WebSocket auth: `auth.verify` message with token, 5-second timeout for unauthenticated clients
  - Token stored in `~/.sman/config.json` under `auth.token`
  - Auth boundary: Only `/api/health` and `/api/auth/token` are public; all other `/api/` endpoints require auth; static files are unauthenticated

**Chatbot User Identity:**
- Per-platform user tracking: `userId` from WeCom/Feishu/WeChat
- Workspace association per user stored in `chatbot_user_state` table
- No unified identity across platforms

## Monitoring & Observability

**Error Tracking:**
- None (no Sentry, no external error tracking)

**Logs:**
- Custom logger: `server/utils/logger.ts` (`createLogger()`)
- Log files at `~/.sman/logs/`
- Structured logging with named loggers per module (e.g., 'Server', 'ClaudeSessionManager', 'WeComBot')

**Runtime Metrics:**
- Claude API cost tracking per query (logged and sent to client via `chat.done`)
- Token usage tracking (input/output tokens per query)
- Capability usage statistics in `~/.sman/capability-usage.json`

## CI/CD & Deployment

**Hosting:**
- Local desktop application via Electron
- No cloud deployment

**CI Pipeline:**
- None (no GitHub Actions, no CI configuration detected)

**Build & Package:**
- `pnpm build` - Frontend (Vite) + Backend (tsc)
- `pnpm build:electron` - Electron main/preload (electron-vite)
- `pnpm electron:build` - Full build + electron-builder packaging
- Windows: NSIS installer (one-click, per-machine optional)
- macOS: DMG
- ASAR disabled for native module compatibility

## Environment Configuration

**Required env vars:**
- None strictly required (all have defaults)
- `PORT` - HTTP server port (default: 5880)
- `SMANBASE_HOME` - User data directory (default: `~/.sman`)

**Config-driven settings (in `~/.sman/config.json`):**
- `llm.apiKey` - Anthropic API key (required for AI features)
- `llm.model` - Model name (required for AI features)
- `llm.baseUrl` - Custom API endpoint (optional, for proxy/offline deployment)
- `llm.profileModel` - Separate model for user profile analysis (optional)
- `llm.userProfile` - Enable/disable user profiling (default: true)
- `llm.capabilities` - Detected model capabilities (text/image/pdf/audio/video)
- `webSearch.provider` - Search provider: `builtin` | `brave` | `tavily` | `bing`
- `webSearch.braveApiKey` / `tavilyApiKey` / `bingApiKey` - Search API keys
- `chatbot.wecom.{enabled, botId, secret}` - WeCom Bot credentials
- `chatbot.feishu.{enabled, appId, appSecret}` - Feishu Bot credentials
- `chatbot.weixin.enabled` - WeChat personal bot toggle
- `auth.token` - Auto-generated auth token

**Secrets location:**
- `~/.sman/config.json` - Contains API keys and auth token
- No vault integration; secrets stored in plaintext JSON

## Webhooks & Callbacks

**Incoming:**
- WeCom Bot: WebSocket messages on `wss://openws.work.weixin.qq.com` (`aibot_msg_callback`, `aibot_event_callback`)
- Feishu Bot: Event subscription `im.message.receive_v1` via `@larksuiteoapi/node-sdk` `WSClient`
- WeChat Bot: Long-polling from `https://ilinkai.weixin.qq.com`
- WebSocket clients on `ws://localhost:5880/ws` (desktop frontend, auth-verified)

**Outgoing:**
- Anthropic API calls (direct HTTP for model detection, SDK subprocess for agent sessions)
- Web search MCP servers (stdio subprocess for Brave/Tavily/Bing)
- WeCom Bot responses via WebSocket (`aibot_respond_msg` commands)
- Feishu Bot responses via `client.im.message.create()` REST API
- WeChat Bot responses via `ilinkai` HTTP API
- Chrome CDP WebSocket connections (`localhost:9222` or auto-launched Chrome)

## Browser Automation (Web Access)

**Chrome DevTools Protocol (CDP):**
- Engine: `server/web-access/cdp-engine.ts`
- Connects to user's Chrome via CDP WebSocket
- Auto-launches Chrome with `--remote-debugging-port=9222` if not running
- Uses dedicated profile at `~/.sman/chrome-profile/`
- Features: DOM stability detection, accessibility tree snapshots, login page detection
- MCP tools exposed: `web_access_navigate`, `web_access_snapshot`, `web_access_screenshot`, `web_access_click`, `web_access_fill`, `web_access_press_key`, `web_access_evaluate`, `web_access_list_tabs`, `web_access_close_tab`, `web_access_find_url`, `web_access_remember_url`

**URL Discovery:**
- Chrome bookmark/history scanning: `server/web-access/chrome-sites.ts`
- Learned URL experiences: `server/web-access/url-experience-store.ts` (persisted to `~/.sman/`)
- QR code generation for WeChat login: `qrcode` package

---

*Integration audit: 2026-04-07*
