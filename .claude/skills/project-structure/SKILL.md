---
id: project-structure
name: 项目结构
description: smanbase 目录布局、技术栈、模块组织和构建说明
category: structure
_scanned:
  commitHash: "57e98c308c1cd0fc5693b3ebab5282836e02a241"
  scannedAt: "2026-05-17T00:00:00.000Z"
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
  hub/                  # ⭐ NEW: Multi-agent collaboration client
  init/                 # Session initialization flow
  stardom/              # Stardom bridge layer
  web-access/           # Browser automation (CDP engine)
src/                     # React frontend
  features/             # Feature modules (chat/settings/cron/batch/smart-paths/stardom/git/hub)
    hub/                # ⭐ NEW: Hub dashboard UI
  components/           # Common components (SessionTree, DirectorySelector)
  stores/               # Zustand state management
  queries/              # ⭐ NEW: TanStack Query hooks
electron/                # Electron main.ts + preload.ts
plugins/                 # Claude Code plugins
stardom/                 # Standalone Stardom server
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
| **Hub (NEW)** | | | |
| Hub Client | server/hub/client.ts | Hub HTTP/WebSocket client | hub-client.md |
| Task Worker | server/hub/task-worker.ts | Async task execution | hub-task-worker.md |
| Crypto | server/hub/crypto.ts | PSK encryption | hub-crypto.md |
| **Chatbot** | | | |
| Session Manager | server/chatbot/chatbot-session-manager.ts | Message routing, commands | chatbot-session-manager.md |
| **Web Access** | | | |
| CDP Engine | server/web-access/cdp-engine.ts | Chrome DevTools Protocol driver | web-access-cdp.md |
| Web Access MCP | server/web-access/mcp-server.ts | Browser automation (9 tools) | web-access-mcp.md |
| **Frontend** | | | |
| Chat Feature | src/features/chat/ | Chat UI, streaming, tool use | chat-feature.md |
| Chat Store | src/stores/chat.ts | Chat state management | chat-store.md |
| **Smart Path** | | | |
| Smart Path Engine | server/smart-path-engine.ts | Earth Path step-by-step execution | smart-path-engine.md |
| Smart Path Store | src/stores/smart-path.ts | Frontend state + step UI | smart-path-store.md |

## Build & Run

**Development**: `./dev.sh` or `pnpm dev` (5881) + `pnpm dev:server` (5880)
**Build**: `pnpm build` + `pnpm build:electron` + `pnpm electron:build`
**Test**: `pnpm test` / `pnpm test:watch`
**Ports**: 5880 (HTTP/WS), 5881 (Vite dev)

## Recent Changes (v26.517.1)

### ⭐ NEW: Hub Module
- **server/hub/**: Multi-agent collaboration client with WebSocket/HTTP
- **src/features/hub/**: Hub dashboard UI (TaskBoard, AgentList, TaskDetail)
- New query hooks: **src/queries/use-hub.ts** (TanStack Query)
- Dependencies: HubClient, HubWsClient, EvaluationHandler, TaskWorker

### ⭐ NEW: Smart Path Step-by-Step Execution
- Split `smart-path-engine.ts` into `orchestrate/runStep/finalize` methods
- Added `PathBlueprint` type for structured path execution
- UI: Step-by-step execution with editable results, status animations
- Storage: `.sman/paths/{pathId}/` directory with references/ subdirectory

### ⭐ NEW: Code Viewer Enhancements
- Navigation history and file tree following
- Symbol search with Ctrl/Cmd+click
- Ripgrep search backend (server/code-viewer-handler.ts)
- Optimized UI and highlighting

### ⭐ NEW: Dynamic Port Selection
- **server/server-url.ts**: Dynamic port detection and reporting
- Port reporting to Hub for service discovery
- CORS configuration for cross-origin Hub communication

### Other Changes
- Web Access: ref-based element targeting (ref priority, CSS selector fallback)
- Broadcast system: **server/broadcast-store.ts** + **src/stores/broadcast.ts**
- Update detection: **src/stores/update.ts** + UpdateBanner component
- Better type safety across all modules
