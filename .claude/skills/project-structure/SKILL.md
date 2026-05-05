---
id: project-structure
name: 项目结构
description: smanbase 目录布局、技术栈、模块组织和构建说明
category: structure
_scanned:
  commitHash: "32289f752b24fd9424b2dd1c9e9e34938bf4a806"
  scannedAt: "2026-05-05T00:00:00.000Z"
  branch: "master"
---

# Smanbase — 项目结构

> 桌面 AI 平台，集成 Claude、多 Bot 支持（企业微信/飞书/微信）和多 Agent 协作网络（星域）。

## Tech Stack
**Frontend**: React 19 + TypeScript + TailwindCSS + Radix UI + Zustand + Vite
**Backend**: Node.js + Express + WebSocket (ws) + better-sqlite3 + ESM
**Desktop**: Electron + electron-vite
**AI**: Claude Agent SDK v0.2 + Claude Code v2.1
**Build**: Vite + tsc + pnpm + vitest
**Package**: electron-builder (NSIS/DMG)

## Directory Tree (top levels)
```
server/                  # Node.js backend (ESM): WebSocket/HTTP, sessions, SQLite
  capabilities/          # Capability registry & gateway MCP
  chatbot/              # WeCom/Feishu/Weixin bot connections
  init/                 # Session initialization flow
  stardom/              # Stardom bridge layer
  utils/
src/                     # React frontend
  features/             # Feature modules (chat/settings/cron-tasks/smart-paths/stardom)
  components/           # Common components (SessionTree, DirectorySelector, etc.)
  stores/               # Zustand state management
  lib/                  # Utilities (WebSocket client, auth, etc.)
electron/                # Electron main.ts + preload.ts
plugins/                 # Claude Code plugins (web-access, superpowers, gstack, office-skills)
stardom/                 # Standalone Stardom server (independent package)
shared/                  # Shared types (stardom-types.ts)
scripts/                 # Build tools (init-skills, init-system, patch-sdk)
tests/                   # Vitest unit tests
```

## Module List

| Module | Path | Purpose | See Reference |
|--------|------|---------|---------------|
| **Backend Core** | | | |
| HTTP/WebSocket Server | server/index.ts | Main backend entry, all handlers registered | references/server-core.md |
| Claude Session | server/claude-session.ts | SDK V2 session mgmt, lifecycle, idle cleanup | references/claude-session.md |
| Session Store | server/session-store.ts | SQLite session & message storage | references/session-store.md |
| Settings Manager | server/settings-manager.ts | ~/.sman/config.json R/W | references/settings-manager.md |
| Skills Registry | server/skills-registry.ts | Skills registration & loading | references/skills-registry.md |
| MCP Config | server/mcp-config.ts | Web Search MCP auto-configuration | references/mcp-config.md |
| Model Capabilities | server/model-capabilities.ts | Model capability detection | references/model-capabilities.md |
| User Profile | server/user-profile.ts | User profile mgmt (injected to system prompt) | references/user-profile.md |
| Knowledge Extractor | server/knowledge-extractor.ts | Extract knowledge from conversations | references/knowledge-extractor.md |
| Cron Scheduler | server/cron-scheduler.ts | Cron task scheduler | references/cron-scheduler.md |
| Batch Engine | server/batch-engine.ts | Batch task execution engine (semaphore) | references/batch-engine.md |
| Semaphore | server/semaphore.ts | Concurrency control primitive | references/semaphore.md |
| Smart Path Engine | server/smart-path-engine.ts | Earth Path execution (step-by-step) | references/smart-path-engine.md |
| Smart Path Store | server/smart-path-store.ts | Earth Path file storage | references/smart-path-store.md |
| **Chatbot** | | | |
| Session Manager | server/chatbot/chatbot-session-manager.ts | Message routing, command parsing | references/chatbot-session-manager.md |
| Command Parser | server/chatbot/chat-command-parser.ts | //cd, //pwd, //help commands | references/chat-command-parser.md |
| WeCom Bot | server/chatbot/wecom-bot-connection.ts | WeCom WebSocket (heartbeat, reconnect) | references/wecom-bot-connection.md |
| Feishu Bot | server/chatbot/feishu-bot-connection.ts | Feishu Bot connection | references/feishu-bot-connection.md |
| Weixin Bot | server/chatbot/weixin-bot-connection.ts | Weixin Bot connection | references/weixin-bot-connection.md |
| **Capabilities** | | | |
| Registry | server/capabilities/registry.ts | Capability registry (search, match, load) | references/capability-registry.md |
| Project Scanner | server/capabilities/project-scanner.ts | Scan project for capabilities | references/project-scanner.md |
| Experience Learner | server/capabilities/experience-learner.ts | Learn experience from conversations | references/experience-learner.md |
| Gateway MCP | server/capabilities/gateway-mcp-server.ts | Gateway MCP (injected to each session) | references/gateway-mcp.md |
| **Web Access** | | | |
| CDP Engine | server/web-access/cdp-engine.ts | Chrome DevTools Protocol engine | references/cdp-engine.md |
| Web Access MCP | server/web-access/mcp-server.ts | Web Access MCP Server (9 tools) | references/web-access-mcp.md |
| **Stardom** | | | |
| Stardom Bridge | server/stardom/stardom-bridge.ts | Bridge layer (connection, experience extraction) | references/stardom-bridge.md |
| Stardom Client | server/stardom/stardom-client.ts | WebSocket client (register, heartbeat) | references/stardom-client.md |
| Stardom MCP | server/stardom/stardom-mcp.ts | MCP tools (stardom_search, stardom_collaborate) | references/stardom-mcp.md |
| Stardom Session | server/stardom/stardom-session.ts | Collaboration session mgmt | references/stardom-session.md |
| **Frontend Features** | | | |
| Chat | src/features/chat/ | Chat functionality (message rendering, streaming) | references/chat-feature.md |
| Settings | src/features/settings/ | Settings pages (LLM, WebSearch, Chatbot, etc.) | references/settings-feature.md |
| Cron Tasks | src/features/cron-tasks/ | Cron task management UI | references/cron-tasks-feature.md |
| Batch Tasks | src/features/batch-tasks/ | Batch task management UI | references/batch-tasks-feature.md |
| Smart Paths | src/features/smart-paths/ | Earth Path UI | references/smart-paths-feature.md |
| Stardom | src/features/stardom/ | Stardom UI (dashboard + pixel world) | references/stardom-feature.md |
| **Components** | | | |
| Session Tree | src/components/SessionTree.tsx | Session tree + directory selector | references/session-tree.md |
| **Stores** | | | |
| Chat Store | src/stores/chat.ts | Chat state (messages, streaming, queue) | references/chat-store.md |
| Stardom Store | src/stores/stardom.ts | Stardom state | references/stardom-store.md |
| **Stardom Server** | | | |
| Main | stardom/src/index.ts | Stardom server entry (HTTP API + WebSocket) | references/stardom-server.md |
| Message Router | stardom/src/message-router.ts | WS message routing | references/stardom-message-router.md |
| Agent Store | stardom/src/agent-store.ts | Agent registration/heartbeat | references/stardom-agent-store.md |
| Task Engine | stardom/src/task-engine.ts | Task routing/queuing | references/stardom-task-engine.md |

## How to Build and Run

### Development
```bash
# Start backend + frontend (one command)
./dev.sh

# Or separately
pnpm dev          # Frontend (port 5881)
pnpm dev:server   # Backend (port 5880)
```

### Build
```bash
pnpm build         # Build frontend + backend
pnpm build:electron  # Compile Electron main process
pnpm electron:build  # Full build + package (build + build:electron + electron-builder)
```

### Test
```bash
pnpm test          # Run tests
pnpm test:watch    # Watch mode
```

### Key Ports
- 5880: HTTP + WebSocket (production)
- 5881: Vite dev server (development only)
