# Technology Stack

**Analysis Date:** 2026-04-07

## Languages

**Primary:**
- TypeScript 5.7 - Server backend, frontend React, Electron main/preload

**Secondary:**
- JavaScript (ESM) - Build scripts (`scripts/patch-sdk.mjs`), PostCSS config
- Markdown - Skills definitions, user profile, documentation

## Runtime

**Environment:**
- Node.js 22 LTS (required; `better-sqlite3` prebuilt binaries target Node 22)
- Electron 33 (desktop packaging; VDI Windows environments need hardware acceleration disabled)

**Package Manager:**
- pnpm 10.x
- Lockfile: `pnpm-lock.yaml` (present)
- `onlyBuiltDependencies`: `better-sqlite3`, `esbuild`, `electron`

**Module System:**
- Server: ESM (`"type": "module"` in `package.json`; `server/tsconfig.json` compiles to `ES2022` modules)
- Frontend: Bundler module resolution via Vite (`moduleResolution: "bundler"`)
- Electron main: ESM output (`formats: ['es']` in `electron.vite.config.ts`)
- Electron preload: CJS output (`formats: ['cjs']`)

## Frameworks

**Core:**
- React 19 - UI framework (`react`, `react-dom` in devDependencies)
- Express 4 - HTTP server (static file serving, API endpoints)
- Electron 33 - Desktop application wrapper (`electron`, `electron-vite`, `electron-builder`)

**State Management:**
- Zustand 5 - Frontend stores (`src/stores/`)

**Routing:**
- react-router-dom 7 - Client-side routing (`src/app/routes.tsx`)

**Styling:**
- TailwindCSS 3.4 - Utility-first CSS (`tailwind.config.js`, `postcss.config.js`)
- tailwindcss-animate - Tailwind animation plugin
- Radix UI - Primitive headless components (dialog, select, tooltip, switch, context-menu, etc.)
- class-variance-authority + clsx + tailwind-merge - Variant-based component utilities (`src/lib/utils.ts`)

**Testing:**
- Vitest 2.1 - Test runner (`vitest.config.ts`, globals mode, node environment)
- No assertion library beyond Vitest built-in `expect`

**Build/Dev:**
- Vite 6 - Frontend bundler and dev server (port 5881)
- electron-vite 5 - Electron main/preload bundler
- tsx - TypeScript execution for dev server (`pnpm dev:server` uses `tsx watch`)
- TypeScript 5.7 - Type checking (`tsconfig.json`, `server/tsconfig.json`)
- electron-builder - Packaging (NSIS for Windows, DMG for macOS)

## Key Dependencies

**Critical:**
- `@anthropic-ai/claude-agent-sdk` ^0.1.0 - Claude Agent SDK V2 session management (core AI interaction)
- `@anthropic-ai/claude-code` ^2.1.0 - Claude Code CLI subprocess (spawned by SDK for agent execution)
- `better-sqlite3` ^11.0.0 - SQLite database (sessions, messages, cron tasks, batch tasks, chatbot state)
- `ws` ^8.18.0 - WebSocket server (real-time client communication)
- `express` ^4.21.0 - HTTP server framework

**Infrastructure:**
- `zod` ^4.3.6 - Schema validation for MCP tool parameters
- `uuid` ^10.0.0 - Unique ID generation
- `cron-parser` ^5.5.0 - Cron expression parsing and validation
- `node-cron` ^4.2.1 - Cron task scheduling
- `yaml` ^2.7.0 - YAML parsing (skills/config)
- `qrcode` ^1.5.4 - QR code generation (WeChat login)

**Frontend:**
- `react-markdown` ^10.1.0 + `remark-gfm` ^4.0.1 - Markdown rendering in chat messages
- `shiki` ^4.0.0 - Code syntax highlighting in chat
- `lucide-react` ^1.0.1 - Icon library

**Chatbot Integrations:**
- `@larksuiteoapi/node-sdk` ^1.60.0 - Feishu/Lark bot SDK (long connection, message send/receive, file download)

## Configuration

**Environment:**
- Config stored in `~/.sman/config.json` (managed by `server/settings-manager.ts`)
- Auth token auto-generated on first run, stored in config
- Runtime config includes: LLM settings (apiKey, model, baseUrl, capabilities), web search provider, chatbot credentials, auth token
- Environment variables: `PORT` (default 5880), `SMANBASE_HOME` (default `~/.sman`), `ANTHROPIC_API_KEY`, `ANTHROPIC_BASE_URL`

**Build:**
- `tsconfig.json` - Root TS config (frontend + shared types, target ES2022, bundler module resolution)
- `server/tsconfig.json` - Server TS config (ES2022 modules, Node module resolution, outputs to `dist/server/`)
- `electron.vite.config.ts` - Electron build config (main: ESM, preload: CJS, target node20)
- `vite.config.ts` - Frontend Vite config (React plugin, `@/` alias to `./src`, dev proxy to backend)
- `vitest.config.ts` - Test config (globals, node environment)
- `tailwind.config.js` - Tailwind config (dark mode via class, shadcn/ui CSS variable theme)
- `postcss.config.js` - PostCSS config (tailwindcss + autoprefixer)

**SDK Patching:**
- `scripts/patch-sdk.mjs` - Post-install patch for `@anthropic-ai/claude-agent-sdk`
  - Patches V2 session to forward all options (systemPrompt, mcpServers, plugins, permissionMode, cwd, etc.)
  - Removes `CLAUDE_CODE_ENTRYPOINT`/`CLAUDE_AGENT_SDK_VERSION` markers to appear as native CLI
  - Exposes subprocess PID, interrupt, setModel, setPermissionMode on SessionImpl

## Platform Requirements

**Development:**
- Node.js 22 LTS
- pnpm
- Google Chrome (for Web Access CDP integration)

**Production:**
- Electron desktop app (macOS DMG, Windows NSIS installer)
- ASAR disabled (required for `better-sqlite3` native module)
- Windows VDI: hardware acceleration disabled to prevent white screen

## Data Storage

**Database:**
- SQLite via `better-sqlite3` at `~/.sman/sman.db`
- Stores: sessions, messages, cron tasks/runs, batch tasks/items, chatbot user state
- Access via typed store classes: `SessionStore`, `CronTaskStore`, `BatchStore`, `ChatbotStore`

**File-based Config:**
- `~/.sman/config.json` - Main configuration
- `~/.sman/registry.json` - Skills registry
- `~/.sman/capabilities.json` - Capability manifests (system-generated)
- `~/.sman/user-capabilities.json` - User-learned capabilities (never auto-overwritten)
- `~/.sman/capability-usage.json` - Capability usage statistics
- `~/.sman/user-profile.md` - User profile (LLM-maintained)
- `~/.sman/chrome-profile/` - Dedicated Chrome profile for CDP
- `~/.sman/logs/` - Log files
- `~/.sman/skills/` - Global skills directory

---

*Stack analysis: 2026-04-07*
