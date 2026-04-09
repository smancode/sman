---
name: project-apis
description: "smanbase API endpoints catalog — signatures, params, business flows. Use when understanding, modifying, or adding endpoints."
_scanned:
  commitHash: "74f4bbc6b4bfc811384eabcc73070c20f12be381"
  scannedAt: "2026-04-09T15:29:13.791Z"
  branch: "base"
---

# smanbase API Endpoints

Hybrid: **HTTP REST** (browser/file) + **WebSocket** (session/chat CRUD). Source: `server/index.ts`.

## HTTP REST

| Method | Path | Description | Ref |
|--------|------|-------------|-----|
| GET | `/api/health` | Health check (no auth) | `references/GET-api-health.md` |
| GET | `/api/auth/token` | Auth token (loopback only) | `references/GET-api-auth-token.md` |
| GET | `/api/directory/read?path=` | Read directory | `references/GET-api-directory-read.md` |
| GET | `/api/directory/home` | User home path | `references/GET-api-directory-home.md` |

## WS — Session (`session.*`)

`create` · `list` · `updateLabel` · `delete` · `history` · `preheat`
→ `references/ws-session-*.md`

## WS — Chat (`chat.*`)

`send` · `abort` → `references/ws-chat-*.md`

## WS — Skills (`skills.*`)

`list` · `listProject` → `references/ws-skills-*.md`

## WS — Settings (`settings.*`)

`get` · `update` · `testAndSave` · `selectLlmProfile` · `deleteLlmProfile`
→ `references/ws-settings-*.md`

## WS — Cron (`cron.*`)

`workspaces` · `skills` · `list` · `create` · `update` · `delete` · `runs` · `execute` · `scan`
→ `references/ws-cron-*.md`

## WS — Batch (`batch.*`)

`list` · `get` · `create` · `update` · `delete` · `generate` · `test` · `save` · `execute` · `pause` · `resume` · `cancel` · `items` · `retry`
→ `references/ws-batch-*.md`

## WS — WeChat Bot (`chatbot.weixin.*`)

`qr.request` · `qr.poll` · `disconnect` · `getStatus`
→ `references/ws-chatbot-weixin-*.md`
