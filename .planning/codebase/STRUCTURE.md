# Codebase Structure

**Analysis Date:** 2026-04-07

## Directory Layout

```
smanbase/
├── server/                     # Node.js backend (TypeScript, compiled ESM)
│   ├── index.ts                # Server entry point (HTTP + WebSocket, all handler registration)
│   ├── claude-session.ts       # Claude Agent SDK V2 session manager
│   ├── session-store.ts        # SQLite session/message persistence
│   ├── settings-manager.ts     # ~/.sman/config.json read/write
│   ├── skills-registry.ts      # Skills loading and registry
│   ├── mcp-config.ts           # Web search MCP server auto-config
│   ├── model-capabilities.ts   # LLM capability detection (3-layer)
│   ├── user-profile.ts         # User profile auto-learning
│   ├── types.ts                # Shared TypeScript types (SmanConfig, CronTask, BatchTask, etc.)
│   ├── tsconfig.json           # Server TypeScript config (ES2022, ESM output)
│   ├── semaphore.ts            # Concurrency control primitive
│   ├── cron-scheduler.ts       # Cron task scheduling (node-cron)
│   ├── cron-executor.ts        # Cron task execution (headless Claude sessions)
│   ├── cron-task-store.ts      # Cron task SQLite storage
│   ├── batch-engine.ts         # Batch task execution engine (semaphore concurrency)
│   ├── batch-store.ts          # Batch task/item SQLite storage
│   ├── batch-utils.ts          # Batch template rendering, interpreter detection
│   ├── capabilities/           # On-demand capability system
│   │   ├── registry.ts         # Capability discovery, search, usage tracking
│   │   ├── gateway-mcp-server.ts # MCP server exposing capability_list/load tools
│   │   ├── init-registry.ts    # Auto-discover capabilities from plugins
│   │   ├── types.ts            # Capability type definitions
│   │   ├── office-skills-runner.ts  # Office documents capability runner
│   │   ├── frontend-slides-runner.ts # HTML slides capability runner
│   │   └── experience-learner.ts    # Learn capabilities from usage patterns
│   ├── chatbot/                # Chatbot subsystem (WeCom + Feishu + WeChat)
│   │   ├── chatbot-session-manager.ts  # Message routing, command handling
│   │   ├── chatbot-store.ts            # Chatbot user state SQLite storage
│   │   ├── chat-command-parser.ts      # //cd, //pwd, //help, etc.
│   │   ├── wecom-bot-connection.ts     # WeCom WebSocket (heartbeat, reconnect, stream)
│   │   ├── feishu-bot-connection.ts    # Feishu SDK event listener
│   │   ├── weixin-bot-connection.ts    # WeChat personal bot (QR login)
│   │   ├── wecom-media.ts             # WeCom media download/decrypt
│   │   └── types.ts                    # Chatbot types (IncomingMessage, ChatbotConfig, etc.)
│   ├── web-access/             # Browser automation subsystem
│   │   ├── index.ts            # Barrel exports
│   │   ├── web-access-service.ts  # Service layer (engine selection, tab management)
│   │   ├── browser-engine.ts      # Abstract BrowserEngine interface
│   │   ├── cdp-engine.ts          # Chrome DevTools Protocol implementation
│   │   ├── mcp-server.ts          # MCP server (11 web_access_* tools)
│   │   ├── chrome-sites.ts        # Chrome bookmark/history auto-discovery
│   │   └── url-experience-store.ts # Learned URL mapping persistence
│   └── utils/
│       ├── logger.ts           # Structured JSON logger (createLogger)
│       └── content-blocks.ts   # Multimodal content block builder
├── src/                        # React frontend (TypeScript + Vite)
│   ├── app/
│   │   ├── App.tsx             # Root component (auth init, WS connect, routing)
│   │   └── routes.tsx          # Route definitions (/chat, /settings, /cron-tasks, /batch-tasks)
│   ├── features/               # Feature-based modules
│   │   ├── chat/               # Chat feature
│   │   │   ├── index.tsx       # Chat page (message list + input)
│   │   │   ├── ChatInput.tsx   # Message input with media attachment support
│   │   │   ├── ChatMessage.tsx # Message rendering (Markdown, code highlight, tool_use)
│   │   │   ├── ChatToolbar.tsx # Chat toolbar actions
│   │   │   ├── message-utils.ts # Message processing utilities
│   │   │   └── highlighter.ts  # Shiki code highlighting
│   │   ├── settings/           # Settings feature (tabbed page)
│   │   │   ├── index.tsx       # Settings page entry (tab panel)
│   │   │   ├── LLMSettings.tsx # API Key, Model, BaseURL, capabilities
│   │   │   ├── WebSearchSettings.tsx # Search provider config
│   │   │   ├── ChatbotSettings.tsx   # WeCom/Feishu/WeChat bot config
│   │   │   ├── CronTaskSettings.tsx   # Cron task CRUD
│   │   │   ├── BatchTaskSettings.tsx  # Batch task CRUD
│   │   │   └── BackendSettings.tsx    # Backend URL config
│   │   ├── cron-tasks/         # Cron tasks page (placeholder)
│   │   └── batch-tasks/        # Batch tasks page (placeholder)
│   ├── components/             # Shared components
│   │   ├── SessionTree.tsx     # Session tree (grouped by directory, inline dir selector)
│   │   ├── DirectorySelectorDialog.tsx # Directory browsing dialog
│   │   ├── SkillPicker.tsx     # Skill selection picker
│   │   ├── layout/             # Layout components
│   │   │   ├── MainLayout.tsx  # Sidebar + content layout
│   │   │   ├── Sidebar.tsx     # Navigation sidebar
│   │   │   └── Titlebar.tsx    # Custom window titlebar
│   │   ├── common/             # Common utilities
│   │   │   ├── ErrorBoundary.tsx
│   │   │   ├── FeedbackState.tsx
│   │   │   └── LoadingSpinner.tsx
│   │   └── ui/                 # Radix UI primitives (button, dialog, input, etc.)
│   ├── stores/                 # Zustand state management
│   │   ├── chat.ts             # Chat state (messages, sessions, streaming)
│   │   ├── settings.ts         # Settings state (LLM, web search, chatbot config)
│   │   ├── cron.ts             # Cron task state
│   │   ├── batch.ts            # Batch task state
│   │   └── ws-connection.ts    # WebSocket connection singleton + status
│   ├── lib/                    # Utility libraries
│   │   ├── ws-client.ts        # WebSocket client (auto-reconnect, event system, auth)
│   │   ├── auth.ts             # Auth token storage
│   │   └── utils.ts            # cn() helper (tailwind-merge)
│   ├── hooks/                  # Custom React hooks
│   │   └── useTheme.ts         # Theme management
│   └── types/                  # TypeScript type definitions
│       ├── chat.ts             # ContentBlock, ChatSession, ToolStatus
│       └── settings.ts         # LlmConfig, WebSearchConfig, BatchTask, CronTask, etc.
├── electron/                   # Electron desktop app
│   ├── main.ts                 # Main process (window, IPC, server lifecycle)
│   └── preload.ts              # Context bridge (window.sman API)
├── plugins/                    # Claude Code plugins
│   ├── superpowers/            # Core skills (TDD, debugging, planning, code review)
│   ├── dev-workflow/           # Development workflow (brainstorm -> plan -> implement)
│   ├── web-access/             # Web access skill (browser operation instructions)
│   ├── office-skills/          # Office document generation (PPT/Word/Excel/PDF)
│   ├── frontend-slides/        # HTML presentation generation
│   └── gstack/                 # Symbolic link to external gstack plugin
├── tests/                      # Test files (Vitest)
│   └── server/                 # Server-side tests
│       ├── claude-session.test.ts
│       ├── session-store.test.ts
│       ├── settings-manager.test.ts
│       ├── mcp-config.test.ts
│       ├── semaphore.test.ts
│       ├── batch-engine.test.ts
│       ├── batch-store.test.ts
│       ├── batch-utils.test.ts
│       ├── cron-scheduler.test.ts
│       ├── cron-task-store.test.ts
│       ├── content-blocks.test.ts
│       ├── model-capabilities.test.ts
│       ├── skills-registry.test.ts
│       ├── profile-manager.test.ts
│       ├── user-profile.test.ts
│       ├── capabilities/        # Capability tests
│       ├── chatbot/             # Chatbot tests
│       │   ├── chatbot-session-manager.test.ts
│       │   ├── chatbot-store.test.ts
│       │   ├── chat-command-parser.test.ts
│       │   ├── wecom-bot-connection.test.ts
│       │   ├── feishu-bot-connection.test.ts
│       │   ├── wecom-media.test.ts
│       │   └── weixin-bot-connection.test.ts
│       └── web-access/          # Web access tests
│           ├── cdp-engine.test.ts
│           ├── mcp-server.test.ts
│           ├── url-experience-store.test.ts
│           └── web-access-service.test.ts
├── scripts/                    # Build/utility scripts
│   ├── init-skills.ts          # Initialize global skills
│   ├── init-system.ts          # System initialization
│   ├── init-capabilities.js    # Capability initialization (compiled)
│   ├── patch-sdk.mjs           # Claude Agent SDK postinstall patch
│   ├── setup-office-skills.sh  # Office skills dependency setup (Unix)
│   └── setup-office-skills.bat # Office skills dependency setup (Windows)
├── docs/                       # Documentation
│   ├── windows-packaging.md    # Windows packaging guide
│   └── superpowers/            # Design docs and specifications
├── build/                      # Build assets (icons, etc.)
├── public/                     # Static web assets
├── dist/                       # Build output (frontend + compiled server)
├── package.json                # Project manifest (type: module, pnpm)
├── tsconfig.json               # Root TypeScript config (frontend + type checking)
├── vite.config.ts              # Vite dev/build config (@ alias, proxy)
├── vitest.config.ts            # Vitest test config
├── electron-vite.config.ts     # Electron-vite build config
└── CLAUDE.md                   # Project context for Claude Code
```

## Directory Purposes

**`server/`:**
- Purpose: Complete Node.js backend — HTTP server, WebSocket server, business logic, AI integration
- Contains: TypeScript source files organized by feature (chatbot/, web-access/, capabilities/) and infrastructure (stores, utils)
- Key files: `server/index.ts` (monolithic entry), `server/claude-session.ts` (AI runtime), `server/types.ts` (shared types)
- Compiled to: `dist/server/` as ESM modules

**`server/chatbot/`:**
- Purpose: Multi-platform bot integration (WeCom, Feishu, WeChat personal)
- Contains: Connection classes for each platform, session management, command parser, media handling
- Key files: `server/chatbot/chatbot-session-manager.ts` (central router), `server/chatbot/types.ts` (interfaces)

**`server/web-access/`:**
- Purpose: Browser automation via Chrome DevTools Protocol
- Contains: Engine abstraction, CDP implementation, MCP server with 11 tools, URL learning
- Key files: `server/web-access/cdp-engine.ts` (CDP client), `server/web-access/mcp-server.ts` (tool definitions)

**`server/capabilities/`:**
- Purpose: On-demand capability loading system
- Contains: Registry with multi-strategy search (keyword, semantic LLM, user-learned), MCP gateway for Claude
- Key files: `server/capabilities/registry.ts` (search + usage tracking), `server/capabilities/gateway-mcp-server.ts` (MCP tools)

**`src/`:**
- Purpose: React frontend single-page application
- Contains: Feature modules, shared components, Zustand stores, utility libraries, TypeScript types
- Key files: `src/app/App.tsx` (root), `src/stores/chat.ts` (chat state), `src/lib/ws-client.ts` (WebSocket client)
- Built by: Vite to `dist/`

**`src/features/`:**
- Purpose: Feature-based module organization (one directory per feature)
- Contains: Page components and feature-specific sub-components
- Key files: `src/features/chat/index.tsx`, `src/features/settings/index.tsx`

**`src/components/`:**
- Purpose: Shared reusable UI components
- Contains: Layout components, common utilities, Radix UI primitives (`ui/`), domain components (SessionTree, SkillPicker)
- Key files: `src/components/SessionTree.tsx` (sidebar session list), `src/components/ui/button.tsx`

**`src/stores/`:**
- Purpose: Zustand state management stores
- Contains: One file per domain (chat, settings, cron, batch, ws-connection)
- Key files: `src/stores/chat.ts` (largest store, handles streaming state), `src/stores/ws-connection.ts` (singleton WS client)

**`electron/`:**
- Purpose: Electron desktop application wrapper
- Contains: Main process and preload script only
- Key files: `electron/main.ts` (window + server lifecycle)

**`plugins/`:**
- Purpose: Claude Code plugins that inject skills and tools into AI sessions
- Contains: Plugin directories with skill definitions, some with Node.js/Python dependencies
- Key files: `plugins/superpowers/` (core dev skills), `plugins/office-skills/` (doc generation)

**`tests/`:**
- Purpose: Vitest unit tests
- Contains: Mirror of `server/` structure — one test file per source module
- Key files: All `*.test.ts` files

## Key File Locations

**Entry Points:**
- `server/index.ts`: Backend entry — HTTP + WebSocket server, all service initialization, all WebSocket handler registration
- `electron/main.ts`: Electron entry — window management, server lifecycle
- `src/app/App.tsx`: Frontend entry — auth, WebSocket, routing
- `src/app/routes.tsx`: Route definitions

**Configuration:**
- `package.json`: Dependencies, scripts, electron-builder config
- `tsconfig.json`: Frontend TypeScript config (bundler mode, `@/` alias)
- `server/tsconfig.json`: Server TypeScript config (ES2022, ESM output to `dist/server/`)
- `vite.config.ts`: Vite config (dev port 5881, proxy to backend 5880, `@/` alias)
- `~/.sman/config.json`: Runtime user configuration (LLM, web search, chatbot, auth)

**Core Logic:**
- `server/claude-session.ts`: AI session management (V2 SDK lifecycle, streaming, abort)
- `server/chatbot/chatbot-session-manager.ts`: Bot message routing and command handling
- `server/capabilities/registry.ts`: Capability discovery and search
- `server/web-access/cdp-engine.ts`: Chrome DevTools Protocol client
- `server/batch-engine.ts`: Batch execution with concurrency control

**Shared Types:**
- `server/types.ts`: Backend types (SmanConfig, CronTask, BatchTask, DetectedCapabilities)
- `server/chatbot/types.ts`: Chatbot types (IncomingMessage, ChatbotConfig, MediaAttachment)
- `src/types/chat.ts`: Frontend chat types (ContentBlock, ChatSession)
- `src/types/settings.ts`: Frontend settings types (mirrors backend types)

**Database:**
- `server/session-store.ts`: Sessions + messages tables
- `server/cron-task-store.ts`: Cron tasks + runs tables
- `server/batch-store.ts`: Batch tasks + items tables
- `server/chatbot/chatbot-store.ts`: Chatbot user state + sessions tables
- All share single SQLite file at `~/.sman/sman.db`

**Testing:**
- `tests/server/`: All server-side test files
- `vitest.config.ts` (inferred): Vitest configuration

## Naming Conventions

**Files:**
- Server source: `kebab-case.ts` — e.g., `claude-session.ts`, `batch-engine.ts`, `chatbot-session-manager.ts`
- Server test: `<module-name>.test.ts` — mirrors source filename — e.g., `claude-session.test.ts`
- React components: `PascalCase.tsx` — e.g., `ChatInput.tsx`, `SessionTree.tsx`, `MainLayout.tsx`
- Zustand stores: `kebab-case.ts` — e.g., `chat.ts`, `ws-connection.ts`, `settings.ts`
- UI primitives: `kebab-case.tsx` — e.g., `button.tsx`, `scroll-area.tsx`
- Types: `kebab-case.ts` — e.g., `chat.ts`, `settings.ts`
- Plugins: `kebab-case/` directory — e.g., `office-skills/`, `dev-workflow/`

**Directories:**
- Server modules: `kebab-case/` — e.g., `chatbot/`, `web-access/`, `capabilities/`
- Frontend features: `kebab-case/` — e.g., `chat/`, `cron-tasks/`, `batch-tasks/`
- Frontend shared: `kebab-case/` — e.g., `layout/`, `common/`, `ui/`

**TypeScript:**
- Classes: `PascalCase` — e.g., `ClaudeSessionManager`, `CdpEngine`, `Semaphore`
- Interfaces: `PascalCase` — e.g., `SmanConfig`, `IncomingMessage`, `BatchTask`
- Type aliases: `PascalCase` — e.g., `BatchTaskStatus`, `BatchItemStatus`, `ConnectionStatus`
- Constants: `UPPER_SNAKE_CASE` — e.g., `SESSION_IDLE_TIMEOUT_MS`, `STREAM_STALL_MS`

## Where to Add New Code

**New Backend Module (e.g., notification system):**
- Module file: `server/notification-engine.ts`
- Store file: `server/notification-store.ts`
- Types: Add to `server/types.ts`
- Initialization: Wire up in `server/index.ts`
- WebSocket handlers: Add cases to the switch statement in `server/index.ts`
- Tests: `tests/server/notification-engine.test.ts`, `tests/server/notification-store.test.ts`

**New Chatbot Platform (e.g., DingTalk):**
- Connection class: `server/chatbot/dingtalk-bot-connection.ts`
- Register in: `server/index.ts` (new `startChatbotConnections()` branch)
- Config type: Add to `server/chatbot/types.ts` `ChatbotConfig`
- Settings UI: `src/features/settings/ChatbotSettings.tsx`
- Tests: `tests/server/chatbot/dingtalk-bot-connection.test.ts`

**New MCP Tool (e.g., database query tool):**
- MCP server: `server/db-query/mcp-server.ts` (using `createSdkMcpServer` + `tool()`)
- Service layer: `server/db-query/db-query-service.ts`
- Register in: `server/claude-session.ts` `buildSessionOptions()` — add to `mcpServers`
- Tests: `tests/server/db-query/mcp-server.test.ts`

**New Frontend Page (e.g., analytics dashboard):**
- Page component: `src/features/analytics/index.tsx`
- Route: Add to `src/app/routes.tsx`
- Store (if needed): `src/stores/analytics.ts`
- Sidebar link: Add to `src/components/layout/Sidebar.tsx`

**New Capability Plugin (e.g., QA testing):**
- Plugin directory: `plugins/qa-testing/` with skill files
- Runner module: `server/capabilities/qa-testing-runner.ts`
- Register in: `server/capabilities/init-registry.ts`
- Entry in: `server/capabilities/gateway-mcp-server.ts` (add to loadCapability switch)
- Tests: `tests/server/capabilities/qa-testing-runner.test.ts`

**New Shared Component:**
- Component file: `src/components/ComponentName.tsx`
- If UI primitive: `src/components/ui/component-name.tsx`

**New Utility:**
- Backend: `server/utils/utility-name.ts`
- Frontend: `src/lib/utility-name.ts`

## Special Directories

**`~/.sman/` (Runtime User Data):**
- Purpose: All user-specific runtime data
- Contains: `config.json` (settings), `sman.db` (SQLite), `skills/` (global skills), `logs/`, `capabilities.json`, `user-capabilities.json`, `capability-usage.json`, `user-profile.md`, `chrome-profile/` (CDP profile)
- Generated: Yes, by `ensureHomeDir()` at startup
- Committed: No, excluded via `.gitignore`

**`dist/`:**
- Purpose: Build output
- Contains: `dist/` (Vite frontend output), `dist/server/` (tsc-compiled backend ESM)
- Generated: Yes, by `pnpm build`
- Committed: No

**`plugins/office-skills/`:**
- Purpose: Office document generation with Python + Node dependencies
- Contains: Python venv (auto-created), Node modules (auto-installed), PPT/Word templates
- Generated: Partially (venv/node_modules auto-created at server startup)
- Committed: Yes (excluding venv/node_modules/outputs)

**`plugins/gstack`:**
- Purpose: External plugin via symbolic link
- Contains: Symlink to `/Users/nasakim/projects/gstack`
- Generated: No
- Committed: Symlink only

---

*Structure analysis: 2026-04-07*
