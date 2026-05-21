---
id: project-apis
name: API 端点目录
description: smanbase API endpoints catalog. Consult when modifying or adding endpoints.
category: api
_scanned:
  commitHash: "70d53baa472e0b2f87d9b0080e3239118c1f1ec7"
  scannedAt: "2026-05-22T00:00:00.000Z"
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
| `session.list` | Client→Server | List all sessions (includes bot sessions with mode) |
| `session.delete` | Client→Server | Delete session (params: sessionId) |
| `session.history` | Client→Server | Get session history (params: sessionId) |
| `session.preheat` | Client→Server | Preheat session (lazy init) |
| `session.updateLabel` | Client→Server | Update label (params: sessionId, label) |
| `session.created` | Server→Client | Session created event |
| `session.list` | Server→Client | Session list response (local + bot sessions) |
| `session.deleted` | Server→Client | Session deleted event |
| `session.history` | Server→Client | Session history response (with token usage) |
| `session.labelUpdated` | Server→Client | Label updated broadcast |

## Chat

| Type | Direction | Description |
|------|-----------|-------------|
| `chat.send` | Client→Server | Send message (params: sessionId, content, media?, autoConfirm?) |
| `chat.abort` | Client→Server | Abort current query (params: sessionId) |
| `chat.answer_question` | Client→Server | Answer Claude's question (params: sessionId, askId, answers) |
| `chat.start` | Server→Client | Start streaming response |
| `chat.delta` | Server→Client | Streaming text/thinking/tool_use delta |
| `chat.tool_start` | Server→Client | Tool call started |
| `chat.tool_delta` | Server→Client | Tool call params delta |
| `chat.tool_end` | Server→Client | Tool call ended |
| `chat.done` | Server→Client | Response completed (with cost, usage) |
| `chat.aborted` | Server→Client | Response aborted |
| `chat.error` | Server→Client | Chat error |
| `chat.ask_user` | Server→Client | Claude asks user question |

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
| `skills.listProject` | Server→Client | Project skills response (with paths) |

## Groups (Multi-Workspace Task Management)

| Type | Direction | Description |
|------|-----------|-------------|
| `group.create` | Client→Server | Create group (params: groupId, name, workspaceIds[]) |
| `group.created` | Server→Client | Group created event |
| `group.list` | Client→Server | List all groups |
| `group.list` | Server→Client | Groups list (with parsed workspaceIds[]) |
| `group.update` | Client→Server | Update group (params: groupId, name?, workspaceIds[]?) |
| `group.updated` | Server→Client | Group updated event |
| `group.delete` | Client→Server | Delete group (params: groupId) |
| `group.deleted` | Server→Client | Group deleted event |
| `group-task.create` | Client→Server | Create task (params: groupId, title, description?, autoDispatch?) |
| `group-task.created` | Server→Client | Task created event (with taskId, sessionId) |
| `group-task.list` | Client→Server | List tasks (params: groupId) |
| `group-task.list` | Server→Client | Tasks list (with status, autoDispatch) |
| `group-task.delete` | Client→Server | Delete task (params: taskId) |
| `group-task.deleted` | Server→Client | Task deleted event |
| `group-task.dispatch` | Client→Server | Dispatch task (params: taskId, subtasks[]) |
| `group-task.dispatched` | Server→Client | Task dispatched event (with created subtasks) |
| `group-subtask.list` | Client→Server | List subtasks (params: taskId) |
| `group-subtask.list` | Server→Client | Subtasks list (with sessionId, workspace, title) |

## Cron Tasks

| Type | Direction | Description |
|------|-----------|-------------|
| `cron.workspaces` | Client→Server | Get all workspaces |
| `cron.workspaces` | Server→Client | Workspaces list |
| `cron.skills` | Client→Server | Get skills for workspace (params: workspace, cached) |
| `cron.skills` | Server→Client | Skills list (with cronExpression) |
| `cron.list` | Client→Server | List cron tasks |
| `cron.list` | Server→Client | Cron tasks list (with latestRun, nextRunAt) |
| `cron.create` | Client→Server | Create cron task (params: workspace, skillName, cronExpression) |
| `cron.created` | Server→Client | Cron task created |
| `cron.update` | Client→Server | Update cron task (params: taskId, updates) |
| `cron.updated` | Server→Client | Cron task updated |
| `cron.delete` | Client→Server | Delete cron task (params: taskId) |
| `cron.deleted` | Server→Client | Cron task deleted |
| `cron.execute` | Client→Server | Trigger cron task manually (params: taskId) 🆕 |
| `cron.executed` | Server→Client | Cron task execution started |
| `cron.runs` | Client→Server | List cron runs (params: taskId, limit?) |
| `cron.runs` | Server→Client | Cron runs list |
| `cron.scan` | Client→Server | Scan workspace for crontab.md files |
| `cron.scanned` | Server→Client | Scan results (created, updated, deleted, tasks) |
| `cron.changed` | Server→Client | Cron task changed broadcast |

## Batch Tasks

| Type | Direction | Description |
|------|-----------|-------------|
| `batch.list` | Client→Server | List batch tasks |
| `batch.list` | Server→Client | Batch tasks list |
| `batch.get` | Client→Server | Get batch task (params: taskId) |
| `batch.get` | Server→Client | Batch task details |
| `batch.create` | Client→Server | Create batch task (params: workspace, skillName, mdContent, execTemplate) |
| `batch.created` | Server→Client | Batch task created |
| `batch.update` | Client→Server | Update batch task (params: taskId, updates) |
| `batch.updated` | Server→Client | Batch task updated |
| `batch.delete` | Client→Server | Delete batch task (params: taskId) |
| `batch.deleted` | Server→Client | Batch deleted |
| `batch.generate` | Client→Server | Generate execution code (params: taskId) |
| `batch.generated` | Server→Client | Generated code |
| `batch.test` | Client→Server | Test single task (params: taskId) |
| `batch.tested` | Server→Client | Test result (with output) |
| `batch.save` | Client→Server | Save batch tasks to disk (params: taskId) |
| `batch.saved` | Server→Client | Batch saved |
| `batch.execute` | Client→Server | Run batch tasks (params: taskId) |
| `batch.started` | Server→Client | Batch execution started |
| `batch.pause` | Client→Server | Pause batch (params: taskId) |
| `batch.paused` | Server→Client | Batch paused |
| `batch.resume` | Client→Server | Resume batch (params: taskId) |
| `batch.resumed` | Server→Client | Batch resumed |
| `batch.cancel` | Client→Server | Cancel batch (params: taskId) |
| `batch.cancelled` | Server→Client | Batch cancelled |
| `batch.items` | Client→Server | List batch items (params: taskId, status?, offset?, limit?) |
| `batch.items` | Server→Client | Batch items list |
| `batch.retry` | Client→Server | Retry failed tasks (params: taskId) |
| `batch.retrying` | Server→Client | Batch retry started |
| `batch.completed` | Server→Client | Batch completed broadcast |
| `batch.progress` | Server→Client | Batch progress broadcast |

## Smart Paths (Earth Paths)

| Type | Direction | Description |
|------|-----------|-------------|
| `smartpath.list` | Client→Server | List paths (params: workspaces[]) |
| `smartpath.list` | Server→Client | Paths list (with nextRunAt) |
| `smartpath.create` | Client→Server | Create path (params: name, description?, workspace, steps) |
| `smartpath.created` | Server→Client | Path created |
| `smartpath.update` | Client→Server | Update path (params: pathId, workspace, ...updates) |
| `smartpath.updated` | Server→Client | Path updated (with nextRunAt) |
| `smartpath.delete` | Client→Server | Delete path (params: pathId, workspace) |
| `smartpath.deleted` | Server→Client | Path deleted |
| `smartpath.abort` | Client→Server | Abort running path (params: pathId) |
| `smartpath.aborted` | Server→Client | Path aborted |
| `smartpath.run` | Client→Server | Run path (params: pathId, workspace, args?, useRefs?) |
| `smartpath.running` | Server→Client | Path running |
| `smartpath.completed` | Server→Client | Path completed broadcast |
| `smartpath.failed` | Server→Client | Path failed broadcast |
| `smartpath.stepExecutionProgress` | Server→Client | Step execution progress (delta) |
| `smartpath.stepExecutionResult` | Server→Client | Step execution result |
| `smartpath.progress` | Server→Client | Path progress (state, step, message) |
| `smartpath.runs` | Client→Server | Get run history (params: pathId, workspace) |
| `smartpath.runs` | Server→Client | Runs list |
| `smartpath.report` | Client→Server | Get report (params: pathId, workspace, fileName) |
| `smartpath.report` | Server→Client | Report content |
| `smartpath.references` | Client→Server | Get references (params: pathId, workspace) |
| `smartpath.references` | Server→Client | References list |
| `smartpath.reference.read` | Client→Server | Read reference file (params: pathId, workspace, fileName) |
| `smartpath.reference.content` | Server→Client | File content |
| `smartpath.generateStep` | Client→Server | AI generate/execute step (params: userInput, workspace, previousSteps, execute?, pathId?, stepIndex?, skills?) |
| `smartpath.stepGenerated` | Server→Client | Step generated (generatedContent) |
| `smartpath.stepExecutionCompleted` | Server→Client | Step executed (result) |
| `smartpath.orchestrate` | Client→Server | Orchestrate only (params: pathId, workspace, args?, useRefs?) |
| `smartpath.orchestrated` | Server→Client | Orchestration complete (blueprint, runId) |
| `smartpath.runStep` | Client→Server | Run single step (params: pathId, workspace, runId, blueprint, stepIndex, priorResults?, args?, useRefs?) |
| `smartpath.finalize` | Client→Server | Finalize run (params: pathId, workspace, runId, blueprint, stepResults) |
| `smartpath.guideChat` | Client→Server | AI guide for step (params: pathId, workspace, stepIndex, stepResult, sessionId?, message?) |
| `smartpath.guideChat.delta` | Server→Client | Guide chat streaming response |
| `smartpath.guideChat.completed` | Server→Client | Guide completed (response, sessionId) |
| `smartpath.guideSave` | Client→Server | Save guide content (params: pathId, workspace, stepIndex, content, sessionId?) |
| `smartpath.guideSaved` | Server→Client | Guide saved (fileName, references) |

## IM (Instant Messaging) 🆕

| Type | Direction | Description |
|------|-----------|-------------|
| `im.send` | Client→Server | Send message (params: roomId, content, mentionedAgents[]?, quoteId?) |
| `im.sent` | Server→Client | Message sent confirmation |
| `im.message` | Server→Client | Broadcast message to room |
| `im.history` | Client→Server | Get message history (params: roomId, before?, limit?) |
| `im.history` | Server→Client | Message history response |
| `im.sync` | Client→Server | Sync messages after timestamp (params: roomId, afterTimestamp) |
| `im.sync` | Server→Client | Synced messages response |
| `im.typing` | Client→Server | Typing indicator (params: roomId) |
| `im.typing` | Server→Client | Broadcast typing to room |
| `im.error` | Server→Client | IM error response |

## Stardom (Collaboration)

| Type | Direction | Description |
|------|-----------|-------------|
| `stardom.*` | Client→Server | Forwarded to Stardom bridge (if configured) |
| `stardom.status` | Server→Client | Connection status (connected/disconnected) |
| `stardom.task.list` | Client→Server | Get collaboration tasks |
| `stardom.task.list.update` | Server→Client | Tasks list update |
| `stardom.agent.list` | Client→Server | Get online agents |
| `stardom.agent.list` | Server→Client | Agents list |
| `stardom.leaderboard` | Client→Server | Get reputation leaderboard |
| `stardom.task.accept` | Client→Server | Accept collaboration (params: taskId) |
| `stardom.task.reject` | Server→Server | Reject collaboration (params: taskId) |
| `stardom.task.cancel` | Client→Server | Cancel collaboration (params: taskId) |
| `stardom.config.update` | Client→Server | Update collaboration mode (params: mode: auto/notify/manual) |
| `stardom.world.move` | Client→Server | Send agent world coordinates (params: x, y) |
| `stardom.capabilities.list` | Client→Server | List learned capabilities |
| `stardom.capabilities.update` | Server→Client | Capabilities list update |
| `stardom.notify` | Server→Client | Collaboration request notification |
| `stardom.task.chat.delta` | Server→Client | Collaboration chat delta |
| `stardom.task.matched` | Server→Client | Task matched event |
| `stardom.task.timeout` | Server→Client | Task timeout event |
| `stardom.task.cancelled` | Server→Client | Task cancelled event |
| `stardom.reputation.update` | Server→Client | Reputation updated event |
| `stardom.world.agent_update` | Server→Client | Agent world position update |
| `stardom.world.agent_enter` | Server→Client | Agent entered world |
| `stardom.world.agent_leave` | Server→Client | Agent left world |
| `stardom.world.zone_snapshot` | Server→Client | Zone snapshot |
| `stardom.world.enter_zone` | Server→Client | Entered zone |
| `stardom.world.leave_zone` | Server→Client | Left zone |
| `stardom.world.event` | Server→Client | World event |

## Chatbot (WeCom/Feishu/Weixin)

| Type | Direction | Description |
|------|-----------|-------------|
| `chatbot.message` | Server→Server | Incoming bot message (WeCom/Feishu/Weixin → backend) |
| `chatbot.weixin.qr.request` | Client→Server | Request QR code for login |
| `chatbot.weixin.qr.response` | Server→Client | QR code response (qrcodeUrl, sessionKey) |
| `chatbot.weixin.qr.poll` | Client→Server | Poll login status |
| `chatbot.weixin.qr.status` | Server→Client | Login status update (status, connected?, message?) |
| `chatbot.weixin.qr.error` | Server→Client | QR code error |
| `chatbot.weixin.disconnect` | Client→Server | Disconnect Weixin bot |
| `chatbot.weixin.getStatus` | Client→Server | Get current connection status |
| `chatbot.weixin.status` | Server→Client | Weixin bot connection status |
| `chatbot.listWorkspaceSkills` | Client→Server | List skills in workspace (params: workspace) |
| `chatbot.listWorkspaceSkills` | Server→Client | Skills list |
| `chatbot.getCollectFiles` | Client→Server | Get collect bot files (params: botProfileId) |
| `chatbot.getCollectFiles` | Server→Client | Collect files list |

## Hub (Multi-Agent Coordination)

| Type | Direction | Description |
|------|-----------|-------------|
| `hub:query` | Client→Server | Query recent broadcasts |
| `hub:broadcasts` | Server→Client | Broadcasts response |
| `hub:status` | Client→Server | Get hub connection status |
| `hub:status` | Server→Client | Hub status response |

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
| `code.searchFiles` | Client→Server | Search files (params: workspace, query, sourceOnly?) |
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
| `git.logSearch` | Client→Server | Search commits (params: workspace, query) |
| `git.logSearch` | Server→Client | Search results |
| `git.aheadCommits` | Client→Server | Get ahead commits (params: workspace) |
| `git.aheadCommits` | Server→Client | Ahead commits |
| `git.branchList` | Client→Server | List branches (params: workspace) |
| `git.branchList` | Server→Client | Branch list |
| `git.checkout` | Client→Server | Checkout branch (params: workspace, branch) |
| `git.checkout` | Server→Client | Checkout result |
| `git.fetch` | Client→Server | Fetch from remote (params: workspace) |
| `git.fetch` | Server→Client | Fetch result |
| `git.remoteDiff` | Client→Server | Get remote diff (params: workspace) |
| `git.remoteDiff` | Server→Client | Remote diff |
| `git.generateCommit` | Client→Server | AI generate commit message (params: workspace, template?, files?) |
| `git.generateCommit` | Server→Client | Generated commit message |
| `git.push` | Client→Server | Push to remote (params: workspace) |
| `git.push` | Server→Client | Push result |

## Feedback

| Type | Direction | Description |
|------|-----------|-------------|
| `feedback.submit` | Client→Server | Submit feedback (params: message, workspace?) |
| `feedback.submit.ack` | Server→Client | Submission acknowledgment (success, error?, path?) |

## HTTP Endpoints

### Public (No Auth)

| Path | Method | Description |
|------|--------|-------------|
| `/api/health` | GET | Health check |
| `/api/hub-status` | GET | Hub diagnostics |
| `/api/language` | GET | Get language setting |
| `/api/searxng/test` | POST | Test SearXNG connectivity |
| `/api/open-external` | POST | Open URL in system browser |
| `/api/auth/token` | GET | Get auth token (loopback only) |

### Auth Required (Bearer Token)

| Path | Method | Description |
|------|--------|-------------|
| `/api/hub/*` | *ANY* | Hub proxy (forwards to sman-server) |
| `/api/code/image` | GET | Serve image files from workspace |
| `/api/directory/read` | GET | Read directory contents |
| `/api/im/rooms` | GET | List IM rooms 🆕 |
| `/api/im/rooms` | POST | Create IM room 🆕 |

### Static Files

| Path | Description |
|------|-------------|
| `/` | Frontend static files (production) |
| `/*` | SPA fallback |

## Error Reporting

| Type | Direction | Description |
|------|-----------|-------------|
| `error.report` | Client→Server | Report error to hub or local file |
| `error.report.ack` | Server→Client | Error report acknowledgment |
| `error` | Server→Client | Generic error (params: error) |

## Notes
- **Protocol**: WebSocket messages are JSON with `type` field
- **Auth**: First message must be `auth.verify` with Bearer token from settings
- **Session isolation**: Session-specific messages sent to subscribed clients only (multi-tab support)
- **Bot sessions**: Bot sessions (WeCom/Feishu/Weixin) include `botLabel` and `botMode` (full/query/collect)
- **Backpressure**: Streaming messages (`chat.delta`, `batch.progress`) may be dropped when client buffer is full
- **Cron scan**: Auto-discovers Skills with `crontab.md` files in workspaces
- **SmartPath execution**: Steps create ephemeral sessions (not persisted to SQLite)
- **Path commands**: `/pathName` syntax in chat triggers path execution
- **Stardom**: All `stardom.*` messages forwarded to bridge if hub configured
- **Git async**: All Git operations now async (non-blocking) to prevent event loop stalls
- **Reference files**: Only script files (.py, .sh, .js, .ts, .bat, .sql, etc.) can be saved as references
- **Group tasks**: Sessions linked to group tasks via `parentTaskId` field for hierarchical tracking
- **IM rooms**: Multi-user chat rooms with agent mention support, messages broadcast to Hub WS for sync

## Recent Changes (since c63e3fc)

### 🆕 NEW - IM APIs (9 WS + 2 REST)
- **WebSocket**: `im.send`, `im.history`, `im.sync`, `im.typing` (client→server); `im.sent`, `im.message`, `im.history`, `im.sync`, `im.typing`, `im.error` (server→client)
- **REST**: `GET /api/im/rooms` (list rooms), `POST /api/im/rooms` (create room)
- **Database**: New tables `im_rooms`, `im_messages` with FK cascade delete
- **Agent integration**: `@agentId` mentions trigger ephemeral Claude sessions with streaming responses
- **Hub sync**: All `im.message` events forwarded to Hub WebSocket for cross-device sync
- **Impact**: Real-time multi-user chat with AI agent assistance

### 🆕 NEW - Cron Manual Trigger (1 endpoint)
- **`cron.execute`**: Trigger cron task immediately (params: taskId)
- **Response**: `cron.executed` sent immediately, execution runs in background
- **Implementation**: `cronScheduler.executeNow(taskId)` passes `manual=true` flag
- **Behavior**: Skips idle window check (5-min activity threshold) enforced on scheduled runs
- **Use case**: User clicks "Run Now" button in cron task list UI
- **Impact**: Same execution flow as scheduled run, but bypasses skill-auto-updater idle guard

### 🔄 MODIFIED - Cron Executor
- **Signature**: `execute(task: CronTask, manual = false)` - new `manual` parameter
- **Idle check**: `if (!isInitTrigger && !manual)` - skip for both init and manual triggers
- **Impact**: Breaking change for code calling `executor.execute()` directly (unlikely in production)

### ⚠️ BREAKING - Cron Executor Signature
- **New parameter**: `execute(task, manual)` - default `false` preserves backward compatibility
- **Callers**: Only `CronScheduler.executeNow()` passes `manual=true`
- **Internal**: `CronTaskStore` and `CronScheduler` unaffected

### 📌 ORPHAN - IM REST API Validation
- **Missing validation**: Room creation does not validate `type` field (only checks `name`)
- **Risk**: Invalid `type` values (not 'group'/'direct') may cause frontend display issues
- **Recommendation**: Add enum validation for `type` field in `POST /api/im/rooms`

## See Also
- **Reference**: `ref/im-ws-handler.md` - IM WebSocket message flow
- **Reference**: `ref/cron-manual-trigger.md` - Cron manual trigger implementation
