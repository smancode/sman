---
id: project-structure
name: 项目结构
description: smanbase 目录布局、技术栈、模块组织和构建说明
category: structure
_scanned:
  commitHash: "70d53baa472e0b2f87d9b0080e3239118c1f1ec7"
  scannedAt: "2026-05-22T00:00:00.000Z"
  branch: "master"
---

# Smanbase — Project Structure

> Desktop AI platform: Claude integration, multi-bot support (WeCom/Feishu/Weixin), multi-agent collaboration (Stardom/Hub), group workspace management, instant messaging (IM).

## Tech Stack
**Frontend**: React 19 + TypeScript + TailwindCSS + Radix UI + Zustand + TanStack Query + Vite
**Backend**: Node.js + Express + WebSocket (ws) + better-sqlite3 + ESM
**Desktop**: Electron + electron-vite
**AI**: Claude Agent SDK v0.2 + Claude Code v2.1

## Directory Tree
```
server/                  # Backend: WebSocket/HTTP/SQLite
  im/                   # 🆕 Instant messaging (store, agent-bridge, ws-handler)
  hub/                  # Multi-agent collaboration client
  chatbot/              # WeCom/Feishu/Weixin
  stardom/              # Stardom bridge
  capabilities/         # Capability registry & MCP
src/                     # React frontend
  features/im/          # 🆕 IM UI (rooms, agents, chat)
  features/hub/         # Group workspace management
  stores/im.ts          # 🆕 IM state (Zustand)
electron/                # Electron main/preload
plugins/                 # Claude Code plugins
```

## Key Modules

| Module | Purpose | Reference |
|--------|---------|-----------|
| **Backend** | | |
| server/im/im-store.ts | 🆕 SQLite IM rooms/messages | im-store.md |
| server/im/im-agent-bridge.ts | 🆕 @mention agent activation | im-agent-bridge.md |
| server/im/im-ws-handler.ts | 🆕 IM WebSocket events | im-ws-handler.md |
| server/index.ts | HTTP/WebSocket routes | server-core.md |
| server/group-store.ts | SQLite groups/tasks/subtasks | group-store.md |
| **Frontend** | | |
| src/features/im/ | 🆕 IM rooms, agents, messaging | im-feature.md |
| src/features/chat/ | Chat UI, streaming, tool use | chat-feature.md |
| src/stores/im.ts | 🆕 IM state (Zustand) | N/A |

## Build & Run
**Dev**: `./dev.sh` or `pnpm dev` (5881) + `pnpm dev:server` (5880)
**Build**: `pnpm build` + `pnpm build:electron` + `pnpm electron:build`
**Test**: `pnpm test`
**Ports**: 5880 (HTTP/WS), 5881 (Vite dev)

## Recent Changes (since 3539892)

### ⚠️ 🆕 Instant Messaging (IM) System
**server/im/**: Real-time group chat with @agent activation
- `im-store.ts`: SQLite rooms/messages (im_rooms, im_messages tables)
- `im-agent-bridge.ts`: @mention → Claude session + streaming response
- `im-ws-handler.ts`: WS events (im.send, im.history, im.sync, im.typing)
**src/features/im/**: React UI (ChatWindow, ChatInput, SessionList, AgentCard)
**Impact**: Multi-user chat, @mention agents, Hub sync

### 🔄 Sidebar & Layout Refactor
Unified navigation in Sidebar.tsx (463 lines). Consistent across Chat/IM/Hub/Stardom/Paths.

### 🔄 Smart Path: Persistent User Edits
Users can customize AI-generated steps (persisted to DB, survive re-run).

### 🔄 Cron: Manual Trigger Support
On-demand task execution via UI.
