---
id: project-structure
name: 项目结构
description: smanbase 目录布局、技术栈、模块组织和构建说明
category: structure
_scanned:
  commitHash: "1ddac60bf3f5dbec4ced87ea1a0b7b680267f41c"
  scannedAt: "2026-05-19T00:00:00.000Z"
  branch: "master"
---

# Smanbase — Project Structure

> Desktop AI platform: Claude integration, multi-bot support (WeCom/Feishu/Weixin), multi-agent collaboration (Stardom/Hub).

## Tech Stack
**Frontend**: React 19 + TypeScript + TailwindCSS + Radix UI + Zustand + TanStack Query + Vite
**Backend**: Node.js + Express + WebSocket (ws) + better-sqlite3 + ESM
**Desktop**: Electron + electron-vite
**AI**: Claude Agent SDK v0.2 + Claude Code v2.1

## Directory Tree
```
server/                  # Node.js backend (WebSocket/HTTP, sessions, SQLite)
  capabilities/          # Capability registry & gateway MCP
  chatbot/              # WeCom/Feishu/Weixin connections
  hub/                  # Multi-agent collaboration client
  init/                 # Session initialization flow
  stardom/              # Stardom bridge layer
  web-access/           # Browser automation (CDP engine)
src/                     # React frontend
  features/             # Feature modules (chat/settings/cron/batch/smart-paths/git/hub)
  components/           # Common components (SessionTree, DirectorySelector)
  stores/               # Zustand state management
electron/                # Electron main.ts + preload.ts
plugins/                 # Claude Code plugins
```

## Key Modules

| Module | Path | Purpose | Reference |
|--------|------|---------|-----------|
| **Backend Core** | | | |
| Server Core | server/index.ts | HTTP/WebSocket, all route handlers | server-core.md |
| Claude Session | server/claude-session.ts | SDK V2 session, lifecycle, idle timeout | claude-session.md |
| Session Store | server/session-store.ts | SQLite session & message storage | session-store.md |
| Settings | server/settings-manager.ts | ~/.sman/config.json R/W | settings-manager.md |
| Skills Registry | server/skills-registry.ts | Skills registration & loading | skills-registry.md |
| Git Handler | server/git-handler.ts | 🔄 Async Git operations (v26.519) | git-handler.md |
| **Hub** | | | |
| Hub Client | server/hub/client.ts | Hub HTTP/WebSocket client | hub-client.md |
| Task Worker | server/hub/task-worker.ts | Async task execution | hub-task-worker.md |
| Crypto | server/hub/crypto.ts | PSK encryption | hub-crypto.md |
| **Chatbot** | | | |
| Session Manager | server/chatbot/chatbot-session-manager.ts | Message routing, commands | chatbot-session-manager.md |
| **Frontend** | | | |
| Chat Feature | src/features/chat/ | Chat UI, streaming, tool use | chat-feature.md |
| Chat Store | src/stores/chat.ts | Chat state management | chat-store.md |
| Smart Path Engine | server/smart-path-engine.ts | 🆕 Skills integration (v26.519) | smart-path-engine.md |
| Smart Path Store | src/stores/smart-path.ts | Frontend state + step UI | smart-path-store.md |

## Build & Run
**Dev**: `./dev.sh` or `pnpm dev` (5881) + `pnpm dev:server` (5880)
**Build**: `pnpm build` + `pnpm build:electron` + `pnpm electron:build`
**Test**: `pnpm test`
**Ports**: 5880 (HTTP/WS), 5881 (Vite dev)

## Recent Changes (since 57e98c3)

### 🔄 Git Handler: Async Migration
- **server/git-handler.ts**: Sync `execSync` → async `execFile` for non-blocking ops
- Parallel git commands (branch + status), depth limits (MAX=3), dir skipping
- **Impact**: Better UX for large repos (node_modules, .git excluded)

### 🆕 Smart Path: Skills Integration
- Steps can specify workspace skills to use (`step.skills[]`)
- Script file whitelist: .py, .sh, .js, .ts, .bat, .sql, .r, .rb, .go, .java, .ps1
- "Use References" toggle for optional context injection

### 🆕 DirectorySelectorDialog: Quick Workspace
- Recent workspaces quick-select UI (clickable buttons)

### 🔧 Electron: macOS Auto-Updater
- Manual download + ad-hoc signing (bypasses Squirrel.Mac for unsigned builds)

### 📚 Documentation
- New knowledge/technical refs: Chatbot multi-bot, Hub integration, Web Access refs
