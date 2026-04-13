---
name: project-apis
description: "smanbase API endpoints catalog. Consult when modifying or adding endpoints."
_scanned:
  commitHash: "719682a1415b0f56538eee4bcf2a429abb1f4f8d"
  scannedAt: "2026-04-13T00:00:00.000Z"
  branch: "base"
---

# Smanbase API Endpoints

All use WebSocket `/ws` unless **HTTP**. Auth: WS `auth.verify` or HTTP `Bearer <token>`.

## HTTP (no auth except `/api/`)

| Method | Path | Description | Reference |
|--------|------|-------------|-----------|
| GET | `/api/health` | Health check | `references/GET-api-health.md` |
| GET | `/api/auth/token` | Auth token (loopback) | `references/GET-api-auth-token.md` |
| GET | `/api/directory/read?path=` | Read directory | `references/GET-api-directory-read.md` |
| GET | `/api/directory/home` | User home path | `references/GET-api-directory-home.md` |

## Session

| Type | Description | Reference |
|------|-------------|-----------|
| `session.create/list/updateLabel/delete/history/preheat` | Session lifecycle | `references/ws-session-create.md` |

## Chat

| Type | Description | Reference |
|------|-------------|-----------|
| `chat.send` | Send + stream response | `references/ws-chat-send.md` |
| `chat.abort` | Abort active query | `references/ws-chat-abort.md` |

## Skills

| Type | Description | Reference |
|------|-------------|-----------|
| `skills.list/listProject` | Global + project skills | `references/ws-skills-list.md` |

## Settings

| Type | Description | Reference |
|------|-------------|-----------|
| `settings.get/update/testAndSave/selectLlmProfile/deleteLlmProfile` | Config + LLM profiles | `references/ws-settings-get.md` |

## Cron Tasks

| Type | Description | Reference |
|------|-------------|-----------|
| `cron.workspaces/skills/list/create/update/delete/runs/execute/scan` | Full lifecycle | `references/ws-cron-list.md` |

## Batch Tasks

| Type | Description | Reference |
|------|-------------|-----------|
| `batch.list/get/create/update/delete` | CRUD | `references/ws-batch-list.md` |
| `batch.generate/test/save` | Code gen | `references/ws-batch-generate.md` |
| `batch.execute/pause/resume/cancel/retry` | Execution | `references/ws-batch-execute.md` |
| `batch.items` | List items | `references/ws-batch-items.md` |

## WeChat Personal Bot

| Type | Description | Reference |
|------|-------------|-----------|
| `chatbot.weixin.qr.request/poll` | QR login | `references/ws-chatbot-weixin-qr-request.md` |
| `chatbot.weixin.disconnect/getStatus` | Connection mgmt | `references/ws-chatbot-weixin-disconnect.md` |

## Bazaar Bridge

| Type | Description | Reference |
|------|-------------|-----------|
| `bazaar.*` | Protocol messages | `server/bazaar/` |
