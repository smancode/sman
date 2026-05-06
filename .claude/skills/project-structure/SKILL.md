---
id: project-structure
name: 项目结构
description: smanbase 目录布局、技术栈、模块组织和构建说明
category: structure
_scanned:
  commitHash: "4db35f24f89dda0c11aa6aad83ba7bb7f8df368a"
  scannedAt: "2026-05-06T00:00:00.000Z"
  branch: "master"
---

# Smanbase — Project Structure

> Desktop AI platform: Claude integration, multi-bot support (WeCom/Feishu/Weixin), multi-agent collaboration (Stardom).

## Tech Stack
**Frontend**: React 19 + TypeScript + TailwindCSS + Radix UI + Zustand + Vite
**Backend**: Node.js + Express + WebSocket (ws) + better-sqlite3 + ESM
**Desktop**: Electron + electron-vite
**AI**: Claude Agent SDK v0.2 + Claude Code v2.1

## Directory Tree
```
server/                  # Node.js backend (WebSocket/HTTP, sessions, SQLite)
  capabilities/          # Capability registry & gateway MCP
  chatbot/              # WeCom/Feishu/Weixin connections
  init/                 # Session initialization flow
  stardom/              # Stardom bridge layer
src/                     # React frontend
  features/             # Feature modules (chat/settings/cron/batch/smart-paths/stardom/git)
  components/           # Common components (SessionTree, DirectorySelector)
  stores/               # Zustand state management
electron/                # Electron main.ts + preload.ts
plugins/                 # Claude Code plugins
stardom/                 # Standalone Stardom server
```

## Key Modules

| Module | Path | Purpose | Reference |
|--------|------|---------|-----------|
| **Backend** | | | |
| Server Core | server/index.ts | HTTP/WebSocket, all route handlers | server-core.md |
| Claude Session | server/claude-session.ts | SDK V2 session, lifecycle, idle timeout | claude-session.md |
| Session Store | server/session-store.ts | SQLite session & message storage | session-store.md |
| Settings | server/settings-manager.ts | ~/.sman/config.json R/W | settings-manager.md |
| Skills Registry | server/skills-registry.ts | Skills registration & loading | skills-registry.md |
| MCP Config | server/mcp-config.ts | Web Search MCP auto-config | mcp-config.md |
| Cron Scheduler | server/cron-scheduler.ts | Cron task scheduling | cron-scheduler.md |
| Batch Engine | server/batch-engine.ts | Batch execution with semaphore | batch-engine.md |
| Smart Path Engine | server/smart-path-engine.ts | Earth Path step-by-step execution | smart-path-engine.md |
| **Chatbot** | | | |
| Session Manager | server/chatbot/chatbot-session-manager.ts | Message routing, commands | chatbot-session-manager.md |
| **Capabilities** | | | |
| Registry | server/capabilities/registry.ts | Capability registry & matching | capability-registry.md |
| **Web Access** | | | |
| Web Access MCP | server/web-access/mcp-server.ts | Browser automation (9 tools) | web-access-mcp.md |
| **Stardom** | | | |
| Stardom Bridge | server/stardom/stardom-bridge.ts | Connection, experience extraction | stardom-bridge.md |
| Stardom Server | stardom/src/index.ts | Multi-agent collaboration server | stardom-server.md |
| **Frontend** | | | |
| Chat Feature | src/features/chat/ | Chat UI, streaming, tool use | chat-feature.md |
| Chat Store | src/stores/chat.ts | Chat state management | chat-store.md |

## Build & Run

**Development**: `./dev.sh` or `pnpm dev` (5881) + `pnpm dev:server` (5880)
**Build**: `pnpm build` + `pnpm build:electron` + `pnpm electron:build`
**Test**: `pnpm test` / `pnpm test:watch`
**Ports**: 5880 (HTTP/WS), 5881 (Vite dev)
