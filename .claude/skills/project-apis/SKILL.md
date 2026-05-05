---
name: project-apis
description: "smanbase API endpoints catalog. Consult when modifying or adding endpoints."
_scanned:
  commitHash: "32289f752b24fd9424b2dd1c9e9e34938bf4a806"
  scannedAt: "2026-05-05T00:00:00.000Z"
  branch: "master"
---

# Smanbase — API Endpoints

> WebSocket-based API (port 5880). All messages are JSON with a `type` field.

## Connection
- `WS /ws` — WebSocket connection (requires Bearer auth for first message)

## Session Management

| Type | Direction | Description |
|------|-----------|-------------|
| `session.create` | Client→Server | Create session (params: workspace) |
| `session.list` | Client→Server | List all sessions |
| `session.delete` | Client→Server | Delete session (params: sessionId) |
| `session.history` | Client→Server | Get session history (params: sessionId) |
| `session.created` | Server→Client | Session created event |
| `session.list` | Server→Client | Session list response |
| `session.deleted` | Server→Client | Session deleted event |
| `session.history` | Server→Client | Session history response |
| `session.preheat` | Client→Server | Preheat session (lazy init) |
| `session.labelUpdated` | Server→Client | Label updated broadcast |
| `session.updateLabel` | Client→Server | Update label (params: sessionId, label) |
| `session.chatbotCreated` | Server→Client | Chatbot session created broadcast |

## Chat

| Type | Direction | Description |
|------|-----------|-------------|
| `chat.send` | Client→Server | Send message (params: sessionId, content, media?, autoConfirm?) |
| `chat.abort` | Client→Server | Abort current query (params: sessionId) |
| `chat.start` | Server→Client | Start streaming response |
| `chat.delta` | Server→Client | Streaming text/thinking/tool_use delta |
| `chat.tool_start` | Server→Client | Tool call started |
| `chat.tool_delta` | Server→Client | Tool call params delta |
| `chat.tool_end` | Server→Client | Tool call ended |
| `chat.done` | Server→Client | Response completed (with cost, usage) |
| `chat.aborted` | Server→Client | Response aborted |
| `chat.error` | Server→Client | Chat error |
| `chat.ask_user` | Server→Client | Claude asks user question |
| `chat.answer_question` | Client→Server | Answer Claude's question (params: sessionId, askId, answers) |

## Settings

| Type | Direction | Description |
|------|-----------|-------------|
| `settings.get` | Client→Server | Get config |
| `settings.get` | Server→Client | Config response |
| `settings.update` | Client→Server | Update config (any field) |
| `settings.updated` | Server→Client | Config updated broadcast |
| `settings.fetchModels` | Client→Server | Fetch available models (params: apiKey, baseUrl?) |
| `settings.modelsList` | Server→Client | Models list (with unsupported) |
| `settings.testAndSave` | Client→Server | Test & save LLM profile (params: apiKey, model, baseUrl?, profileName?) |
| `settings.testResult` | Server→Client | Test result (success, capabilities?, savedLlms?, error?) |
| `settings.selectLlmProfile` | Client→Server | Select LLM profile (params: profileName) |
| `settings.deleteLlmProfile` | Client→Server | Delete LLM profile (params: profileName) |

## Skills

| Type | Direction | Description |
|------|-----------|-------------|
| `skills.list` | Client→Server | List all global skills |
| `skills.list` | Server→Client | Skills list response |
| `skills.listProject` | Client→Server | List project skills (params: sessionId) |
| `skills.listProject` | Server→Client | Project skills response |

## Cron Tasks

| Type | Direction | Description |
|------|-----------|-------------|
| `cron.workspaces` | Client→Server | Get all workspaces |
| `cron.workspaces` | Server→Client | Workspaces list |
| `cron.skills` | Client→Server | Get skills for workspace (params: workspace) |
| `cron.skills` | Server→Client | Skills list (with workspace) |
| `cron.list` | Client→Server | List cron tasks (params: workspace?) |
| `cron.list` | Server→Client | Cron tasks list |
| `cron.get` | Client→Server | Get cron task (params: taskId) |
| `cron.get` | Server→Client | Cron task details |
| `cron.create` | Client→Server | Create cron task |
| `cron.create` | Server→Client | Cron task created |
| `cron.update` | Client→Server | Update cron task (params: taskId, updates) |
| `cron.update` | Server→Client | Cron task updated |
| `cron.delete` | Client→Server | Delete cron task (params: taskId) |
| `cron.delete` | Server→Client | Cron task deleted |
| `cron.run` | Client→Server | Trigger cron task manually (params: taskId) |
| `cron.runStatusChanged` | Server→Client | Run status changed broadcast |

## Batch Tasks

| Type | Direction | Description |
|------|-----------|-------------|
| `batch.list` | Client→Server | List batch tasks (params: workspace?) |
| `batch.list` | Server→Client | Batch tasks list |
| `batch.generate` | Client→Server | Generate batch tasks (params: taskSpec) |
| `batch.generate` | Server→Client | Generated tasks (with validation) |
| `batch.test` | Client→Server | Test single task (params: task, workspace) |
| `batch.test` | Server→Client | Test result (with output) |
| `batch.save` | Client→Server | Save batch tasks (params: name, workspace, tasks) |
| `batch.save` | Server→Client | Batch saved |
| `batch.run` | Client→Server | Run batch tasks (params: batchId) |
| `batch.pause` | Client→Server | Pause batch (params: batchId) |
| `batch.resume` | Client→Server | Resume batch (params: batchId) |
| `batch.cancel` | Client→Server | Cancel batch (params: batchId) |
| `batch.retry` | Client→Server | Retry failed tasks (params: batchId) |
| `batch.progress` | Server→Client | Batch progress broadcast |

## Smart Paths (Earth Paths)

| Type | Direction | Description |
|------|-----------|-------------|
| `smartpath.list` | Client→Server | List paths (params: workspaces[]) |
| `smartpath.list` | Server→Client | Paths list |
| `smartpath.create` | Client→Server | Create path (params: name, workspace, steps) |
| `smartpath.create` | Server→Client | Path created |
| `smartpath.update` | Client→Server | Update path (params: pathId, workspace, ...updates) |
| `smartpath.update` | Server→Client | Path updated |
| `smartpath.delete` | Client→Server | Delete path (params: pathId, workspace) |
| `smartpath.delete` | Server→Client | Path deleted |
| `smartpath.run` | Client→Server | Run path (params: pathId, workspace) |
| `smartpath.runs` | Client→Server | Get run history (params: pathId, workspace) |
| `smartpath.runs` | Server→Client | Runs list |
| `smartpath.report` | Client→Server | Get report (params: pathId, workspace, fileName) |
| `smartpath.report` | Server→Client | Report content |
| `smartpath.references` | Client→Server | Get references (params: pathId, workspace) |
| `smartpath.references` | Server→Client | References list |
| `smartpath.reference.read` | Client→Server | Read reference file (params: pathId, workspace, fileName) |
| `smartpath.reference.read` | Server→Client | File content |
| `smartpath.generateStep` | Client→Server | AI generate/execute step (params: userInput, workspace, previousSteps, execute?, pathId?, stepIndex?) |
| `smartpath.scheduledRun` | Server→Client | Scheduled run started broadcast |

## Stardom

| Type | Direction | Description |
|------|-----------|-------------|
| `stardom.status` | Server→Client | Connection status (connected/disconnected) |
| `stardom.task.list` | Client→Server | Get collaboration tasks |
| `stardom.task.list` | Server→Client | Tasks list |
| `stardom.agent.list` | Client→Server | Get online agents |
| `stardom.agent.list` | Server→Client | Agents list |
| `stardom.leaderboard` | Client→Server | Get reputation leaderboard |
| `stardom.leaderboard` | Server→Client | Leaderboard data |
| `stardom.task.accept` | Client→Server | Accept collaboration (params: taskId, agentId) |
| `stardom.task.accept` | Server→Client | Accept result |
| `stardom.task.reject` | Client→Server | Reject collaboration (params: taskId) |
| `stardom.task.reject` | Server→Client | Reject result |
| `stardom.config.update` | Client→Server | Update collaboration mode (params: mode: auto/notify/manual) |
| `stardom.config.update` | Server→Client | Config updated |
| `stardom.world.move` | Client→Server | Send agent world coordinates (params: x, y) |
| `stardom.notify` | Server→Client | Collaboration request notification |
| `stardom.task.chat.delta` | Server→Client | Collaboration chat delta |

## Chatbot (WeCom/Feishu/Weixin)

| Type | Direction | Description |
|------|-----------|-------------|
| `chatbot.weixin.status` | Server→Client | Weixin bot connection status |

## Auth

| Type | Direction | Description |
|------|-----------|-------------|
| `auth.verify` | Client→Server | Verify auth token (params: token) |
| `auth.verified` | Server→Client | Token verified |
| `auth.failed` | Server→Client | Token invalid (params: error) |

## Code Viewer

| Type | Direction | Description |
|------|-----------|-------------|
| `code.listDir` | Client→Server | List directory (params: workspace, dirPath?) |
| `code.listDir` | Server→Client | Directory contents |
| `code.readFile` | Client→Server | Read file (params: workspace, filePath) |
| `code.readFile` | Server→Client | File content |
| `code.searchSymbols` | Client→Server | Search symbols (params: workspace, symbol, fileExt?) |
| `code.searchSymbols` | Server→Client | Search results |
| `code.saveFile` | Client→Server | Save file (params: workspace, filePath, content) |
| `code.saveFile` | Server→Client | Save result |
| `code.searchFiles` | Client→Server | Search files (params: workspace, query) |
| `code.searchFiles` | Server→Client | File search results |

## Git

| Type | Direction | Description |
|------|-----------|-------------|
| `git.status` | Client→Server | Get git status (params: workspace) |
| `git.status` | Server→Client | Git status |
| `git.diff` | Client→Server | Get diff (params: workspace, filePath?, staged?) |
| `git.diff` | Server→Client | Diff output |
| `git.diffFile` | Client→Server | Get single file diff (params: workspace, filePath) |
| `git.diffFile` | Server→Client | File diff |
| `git.commit` | Client→Server | Commit (params: workspace, message, files?) |
| `git.commit` | Server→Client | Commit result |
| `git.log` | Client→Server | Get commit log (params: workspace, maxCount?) |
| `git.log` | Server→Client | Commit log |
| `git.logGraph` | Client→Server | Get graph log (params: workspace, maxCount?) |
| `git.logGraph` | Server→Client | Graph log |
| `git.branchList` | Client→Server | List branches (params: workspace) |
| `git.branchList` | Server→Client | Branch list |
| `git.checkout` | Client→Server | Checkout branch (params: workspace, branch) |
| `git.checkout` | Server→Client | Checkout result |
| `git.fetch` | Client→Server | Fetch from remote (params: workspace) |
| `git.fetch` | Server→Client | Fetch result |
| `git.remoteDiff` | Client→Server | Get remote diff (params: workspace) |
| `git.remoteDiff` | Server→Client | Remote diff |
| `git.generateCommit` | Client→Server | AI generate commit message (params: workspace, template?) |
| `git.generateCommit` | Server→Client | Generated commit message |
| `git.push` | Client→Server | Push to remote (params: workspace) |
| `git.push` | Server→Client | Push result |

## HTTP Endpoints

### Public (No Auth)

| Path | Method | Description |
|------|--------|-------------|
| `/api/health` | GET | Health check |
| `/api/searxng/test` | POST | Test SearXNG connectivity |
| `/api/open-external` | POST | Open URL in system browser |
| `/api/auth/token` | GET | Get auth token (loopback only) |

### Auth Required (Bearer Token)

| Path | Method | Description |
|------|--------|-------------|
| `/api/directory/read` | GET | Read directory contents |
| `/api/directory/home` | GET | Get user home directory |

### Static Files

| Path | Description |
|------|-------------|
| `/` | Frontend static files (production) |
| `/*` | SPA fallback |

## Error

| Type | Direction | Description |
|------|-----------|-------------|
| `error` | Server→Client | Generic error (params: error) |

## Notes
- All WebSocket messages require a `type` field
- Auth: First message must be `auth.verify` with Bearer token
- Broadcast: Some messages (like `session.labelUpdated`) are broadcast to all clients
- Message isolation: Session-specific messages are not broadcast to avoid cross-session leakage
- Chatbot status: Connection status changes are broadcast to all clients
- Backpressure: Streaming messages (`chat.delta`, `batch.progress`) may be dropped when client buffer is full
- Cron scan: Auto-discovers Skills with `crontab.md` files in workspaces
- SmartPath steps: Execution creates ephemeral sessions (not persisted to SQLite)
- WeChat QR: Flow is `qr.request` → `qr.response` → `qr.poll` (loop until `status: connected`)
