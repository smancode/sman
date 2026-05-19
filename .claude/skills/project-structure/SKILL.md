---
id: project-structure
name: 项目结构
description: smanbase 目录布局、技术栈、模块组织和构建说明
category: structure
_scanned:
  commitHash: "c63e3fcf76ba9e8b362d9d73ebccab934d1d998c"
  scannedAt: "2026-05-20T00:00:00.000Z"
  branch: "master"
---

# Smanbase — Project Structure

> Desktop AI platform: Claude integration, multi-bot support (WeCom/Feishu/Weixin), multi-agent collaboration (Stardom/Hub), group workspace management.

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
  features/hub/         # 🆕 Group workspace management UI
  components/           # CreateGroupDialog, GroupItem, SessionTree
  stores/group.ts       # 🆕 Group state management
electron/                # Electron main.ts + preload.ts
plugins/                 # Claude Code plugins
```

## Key Modules

| Module | Purpose | Reference |
|--------|---------|-----------|
| **Backend** | | |
| server/index.ts | HTTP/WebSocket routes | server-core.md |
| server/claude-session.ts | V2 SDK lifecycle, idle timeout | claude-session.md |
| server/git-handler.ts | 🔄 Async Git operations (v26.520) | git-handler.md |
| server/group-store.ts | 🆕 SQLite groups/tasks/subtasks | group-store.md |
| server/chatbot/chatbot-session-manager.ts | 🔄 SDK session cleanup (v26.520) | chatbot-session-manager.md |
| server/smart-path-engine.ts | 🔄 Guide chat + skills (v26.520) | smart-path-engine.md |
| **Frontend** | | |
| src/features/chat/ | Chat UI, streaming, tool use | chat-feature.md |
| src/stores/group.ts | 🆕 Group state (Zustand) | N/A |
| src/stores/smart-path.ts | Frontend state + step UI | smart-path-store.md |

## Build & Run
**Dev**: `./dev.sh` or `pnpm dev` (5881) + `pnpm dev:server` (5880)
**Build**: `pnpm build` + `pnpm build:electron` + `pnpm electron:build`
**Test**: `pnpm test`
**Ports**: 5880 (HTTP/WS), 5881 (Vite dev)

## Recent Changes (since 1ddac60)

### 🆕 Group Workspace Management
- **server/group-store.ts**: SQLite store for groups/tasks/subtasks (3-table schema, cascade deletes)
- **src/stores/group.ts**: Zustand store for group state
- **src/components/CreateGroupDialog.tsx**: Multi-workspace selection UI
- **src/components/GroupItem.tsx**: Group tree item with expand/collapse
- **Impact**: Multi-project collaboration within single group workspace

### 🔄 Git Handler: Async Migration Complete
- **server/git-handler.ts**: All operations async with `execFile`, AI conflict resolution
- **Impact**: Non-blocking git ops, better UX for large repos

### 🔄 Chatbot Session: SDK Cleanup Logic
- **server/chatbot/chatbot-session-manager.ts**: Added `clearSdkSessionId()` calls
- Idle timeout cleanup: `abort()` → `closeV2Session()` → `clearSdkSessionId()`
- **Impact**: Prevents stale context leakage between bot sessions

### 🔄 Smart Path: Guide Chat + Skills Integration
- **server/smart-path-engine.ts**: Added `guideChat()` for step guide generation
- Guide content persisted to `references/guide{n}.md` and injected into step prompts
- Steps can specify workspace skills via `step.skills[]`
- Script file whitelist: .py, .sh, .js, .ts, .bat, .sql, .r, .rb, .go, .java, .ps1
- **Impact**: Reusable step guides, fine-grained skill control
