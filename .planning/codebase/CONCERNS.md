# Codebase Concerns

**Analysis Date:** 2026-04-07

## Tech Debt

### Monolithic Server Entry Point (server/index.ts — 1063 lines)
- Issue: All WebSocket handler registration, HTTP routes, service initialization, and chatbot wiring live in a single file. Every new feature adds more cases to the 500-line `switch` block.
- Files: `server/index.ts`
- Impact: Hard to navigate, risky to modify (any change touches the same file), difficult to test handlers in isolation. No unit tests exist for any handler.
- Fix approach: Extract handlers into separate modules per domain (session, chat, cron, batch, settings, chatbot). Each module exports a `register(ws)` function. The switch block in `index.ts` becomes a dispatch table.

### Excessive `any` Usage (Claude Agent SDK Interop)
- Issue: Over 80 `as any` casts across `server/claude-session.ts` alone, plus many in `server/web-access/cdp-engine.ts`, `server/web-access/mcp-server.ts`, and `server/batch-engine.ts`. The Claude Agent SDK types are incomplete or unstable, forcing every consumer to cast.
- Files: `server/claude-session.ts` (lines 103, 194, 220, 247, 260, 277, 311, 323, 487, 510, 574, 624, 637, 708, 709, 719, 781, 831, 842, 856, 912, 959, 992, 1004, 1023, 1036, 1040, 1041, 1045, 1128), `server/web-access/cdp-engine.ts`, `server/web-access/mcp-server.ts`
- Impact: Type safety is effectively disabled for all SDK interactions. Runtime errors from SDK API changes will not be caught at compile time.
- Fix approach: Create adapter interfaces in `server/types.ts` for SDK objects (SDKSession, SDKMessage, StreamEvent). Write type-narrowing helper functions once, use everywhere.

### Massive ClaudeSessionManager (server/claude-session.ts — 1199 lines)
- Issue: Three nearly identical stream-processing loops (sendMessage, sendMessageForCron, sendMessageForChatbot) with duplicated stall-detection logic, V2 session lifecycle management, and content extraction. The stall-detector alone is copy-pasted three times (~40 lines each).
- Files: `server/claude-session.ts` (lines 417-735, 740-864, 869-1032)
- Impact: Bug fixes must be applied in three places. Easy to miss one copy.
- Fix approach: Extract a shared `processStream()` method that accepts callbacks for different output strategies (WebSocket, headless, streaming). Extract stall detection into a reusable helper.

### SQLite Connection Multiplicity
- Issue: Four separate store classes each open their own `better-sqlite3` connection to the same `sman.db` file: `SessionStore`, `BatchStore`, `CronTaskStore`, `ChatbotStore`. Two use `new Database()` directly, two use `new DatabaseConstructor()` (different import patterns for ESM compat).
- Files: `server/session-store.ts` (line 50), `server/batch-store.ts` (line 48), `server/cron-task-store.ts` (line 25), `server/chatbot/chatbot-store.ts` (line 13)
- Impact: While WAL mode allows concurrent readers, each connection adds overhead. Inconsistent import patterns (`Database` vs `DatabaseConstructor`) suggest copy-paste evolution. Each store independently runs `pragma('journal_mode = WAL')` and `pragma('foreign_keys = ON')`.
- Fix approach: Create a single `DatabaseManager` that opens one connection and passes it to all stores. Normalize the ESM import pattern in one place.

### Ad-Hoc Schema Migrations (try/catch ALTER TABLE)
- Issue: `server/session-store.ts` uses 5 sequential try/catch blocks to detect and add missing columns (`label`, `sdk_session_id`, `content_blocks`, `is_cron`, `deleted_at`). `server/cron-task-store.ts` uses similar patterns plus a destructive `DROP TABLE` migration.
- Files: `server/session-store.ts` (lines 81-119), `server/cron-task-store.ts` (lines 66-91)
- Impact: Fragile — no migration ordering, no rollback. The `cron_tasks` DROP TABLE migration (line 69) silently destroys user data. As noted in project memory: "SQLite migrations must test old schema, not just new schema."
- Fix approach: Implement a versioned migration system with a `schema_version` table. Each migration runs once in order. Test migrations against old schema fixtures.

## Known Bugs

### chatbot-store.ts Does Not Close Database on Process Exit
- Symptoms: The `ChatbotStore` class has a `close()` method but it is never called in `shutdown()` in `server/index.ts`.
- Files: `server/index.ts` (lines 946-957), `server/chatbot/chatbot-store.ts` (line 109)
- Trigger: Server shutdown
- Workaround: WAL mode and process exit will clean up, but this is technically a resource leak.

### Feishu Bot stop() Does Not Actually Disconnect
- Symptoms: `FeishuBotConnection.stop()` sets `this.wsClient = null` but never calls any disconnect/stop method on the Lark WSClient.
- Files: `server/chatbot/feishu-bot-connection.ts` (lines 46-49)
- Trigger: Settings update triggers stop/restart of bot connections
- Workaround: The connection eventually times out, but may cause duplicate event handling during the overlap.

## Security Considerations

### Permissions Bypass on All Claude Sessions
- Risk: Every session is created with `permissionMode: 'bypassPermissions'` and `allowDangerouslySkipPermissions: true`. Claude can execute arbitrary shell commands, write files, and modify the system without any user confirmation.
- Files: `server/claude-session.ts` (lines 225-237), `server/batch-engine.ts` (lines 128-129)
- Current mitigation: The product is a single-user desktop tool, so this is by design. But if multi-user chatbot mode is active, any WeCom/Feishu user inherits full system access.
- Recommendations: For chatbot mode, consider a restricted permission profile. At minimum, document this tradeoff.

### Directory Traversal via API
- Risk: The `/api/directory/read` endpoint accepts any `path` parameter and lists its contents. While it requires auth, an authenticated user can browse any directory on the system.
- Files: `server/index.ts` (lines 293-326, specifically line 304)
- Current mitigation: Auth required, loopback-only token retrieval. Files starting with `.` are hidden.
- Recommendations: Consider restricting to user home directory subtree, or adding an allowlist.

### API Key Stored in Plaintext
- Risk: `~/.sman/config.json` stores `llm.apiKey`, `webSearch.braveApiKey`, `tavilyApiKey`, `bingApiKey`, and `chatbot.wecom.secret` in plaintext JSON. The `batch-store.ts` env_vars column also stores sensitive values in plaintext SQLite.
- Files: `server/settings-manager.ts`, `server/batch-store.ts` (line 62 — TODO comment acknowledges this)
- Current mitigation: File permissions on `~/.sman/`
- Recommendations: The TODO at `server/batch-store.ts:62` correctly identifies the fix: AES-256-GCM encryption for env_vars. API keys should similarly be encrypted at rest.

### web_access_evaluate Allows Arbitrary JavaScript Execution
- Risk: The `web_access_evaluate` MCP tool executes arbitrary JavaScript in the browser context. Combined with permission bypass, this enables full browser session hijacking.
- Files: `server/web-access/mcp-server.ts` (lines 200-211), `server/web-access/cdp-engine.ts` (lines 890-902)
- Current mitigation: None — this is by design for agent automation.
- Recommendations: Document as accepted risk. Consider audit logging of all evaluate calls.

## Performance Bottlenecks

### In-Memory Session State Not Persisted Across Restarts
- Problem: `ClaudeSessionManager.sessions` (Map), `v2Sessions` (Map), `sdkSessionIds` (Map) are all in-memory only. On server restart, all active sessions lose their V2 SDK process references. The in-memory maps are repopulated lazily from SQLite, but the V2 sessions must be recreated from scratch.
- Files: `server/claude-session.ts` (lines 52-57)
- Cause: Design tradeoff — V2 SDK sessions are process-bound and cannot be serialized.
- Improvement path: On restart, detect stale sessions and proactively clean up or mark them for re-creation. Current behavior works but causes a cold-start penalty.

### Chrome Profile Copy on Every Launch
- Problem: `CdpEngine.copyChromeProfile()` copies ~30 files from the user's Chrome profile directory every time a new CDP connection is needed. On Windows, it uses PowerShell for locked file access. This can be slow (seconds) if the Chrome profile is large.
- Files: `server/web-access/cdp-engine.ts` (lines 192-272)
- Cause: Chrome locks its profile files, requiring a copy to a separate directory.
- Improvement path: Cache the profile copy and only re-copy when files change (check mtimes). Or use a single long-lived Chrome instance instead of launching a new one per session.

### User Profile LLM Call on Every Conversation Turn
- Problem: `UserProfileManager.updateProfile()` makes a synchronous LLM API call after every user-assistant exchange. This is fire-and-forget but consumes API tokens and adds latency to the backend processing.
- Files: `server/user-profile.ts` (lines 92-108)
- Cause: Design choice — profile is updated in real-time.
- Improvement path: Batch updates (e.g., after every 3rd exchange, or on a timer). Truncate input more aggressively (currently 2000 chars user + 3000 chars assistant per turn).

### Broadcast to All WebSocket Clients
- Problem: The `broadcast()` function iterates all connected WebSocket clients for batch progress, session label updates, and chatbot notifications. With many clients, this could become a bottleneck.
- Files: `server/index.ts` (lines 95-104)
- Cause: Simple implementation.
- Improvement path: For batch/cron events, only send to clients that have subscribed to that specific task. Use a topic-based subscription model.

## Fragile Areas

### V2 SDK Session Lifecycle (PID-Based Liveness Check)
- Files: `server/claude-session.ts` (lines 277-292, 509-528, 774-790, 905-928)
- Why fragile: The code accesses `(session as any).pid` — an undocumented property of the SDK's internal session object. If the SDK changes its internal structure, liveness detection breaks silently. The fallback path (no pid info) just keeps using the session and hopes for the best.
- Safe modification: Wrap PID access in a try/catch with a warning log. Consider filing an issue with the SDK to expose a public `isAlive()` method.
- Test coverage: Minimal — `tests/server/claude-session.test.ts` is only 120 lines and does not test session lifecycle or PID detection.

### WebSocket Message Router (No Schema Validation)
- Files: `server/index.ts` (lines 429-930)
- Why fragile: Every message handler manually casts `msg.workspace as string`, `msg.taskId as string`, etc. There is no schema validation on incoming WebSocket messages. A malformed message (e.g., `taskId: 123` instead of `taskId: "abc"`) will pass through and potentially cause subtle bugs downstream.
- Safe modification: Add Zod schemas (already a project dependency) for each message type and validate before the switch block.
- Test coverage: None — no unit tests for WebSocket message handling.

### Batch Engine Semaphore + Promise Chain
- Files: `server/batch-engine.ts` (lines 246-311), `server/semaphore.ts`
- Why fragile: The `processItem` function is fire-and-forget (`processItem(item).finally(() => semaphore.release())`), but errors inside `processItem` are caught internally and never propagated. If `semaphore.release()` is not called (e.g., unhandled rejection path), the batch execution will deadlock waiting for permits.
- Safe modification: Wrap `processItem` in a guaranteed `.finally()` at the call site. Add a timeout-based deadlock detector.
- Test coverage: `tests/server/batch-engine.test.ts` and `tests/server/semaphore.test.ts` exist but semaphore edge cases (release without acquire, double release) should be verified.

### Skills Cache with No Invalidation
- Files: `server/index.ts` (lines 166-184)
- Why fragile: The skills cache uses a simple 1-minute TTL with no explicit invalidation. If skills are added/removed on disk, the cache will serve stale data for up to 1 minute. The cache key is `${workspace}:${skillType}` but there is only one `skillType` value (`'cron'`).
- Safe modification: Add cache invalidation when settings or skills are modified. Or use a file-watcher approach.
- Test coverage: None.

## Scaling Limits

### Single-Process Architecture
- Current capacity: One Node.js process handles all WebSocket connections, HTTP requests, Claude sessions, cron jobs, and batch tasks.
- Limit: Memory usage grows linearly with active V2 sessions (each spawns a Claude Code child process). Batch tasks with high concurrency can exhaust system resources.
- Scaling path: For multi-user scenarios, extract services behind a message queue. Each Claude session could run in a separate worker process.

### SQLite Concurrency
- Current capacity: WAL mode allows concurrent reads but writes are serialized. Four separate database connections compete for write locks.
- Limit: Under heavy batch execution (many items updating simultaneously), write contention can cause timeouts.
- Scaling path: Use a single connection with write serialization, or move to PostgreSQL for production multi-user deployments.

### No Message History Pagination
- Current capacity: `session-store.ts` loads up to 1000 messages per session (`LIMIT ?` with default 1000).
- Limit: Long-running sessions with many tool_use content blocks will have large JSON payloads. The `getHistory()` method returns all messages including full contentBlocks.
- Scaling path: Implement cursor-based pagination. Load message metadata first, fetch content blocks on demand.

## Dependencies at Risk

### @anthropic-ai/claude-agent-sdk (Unstable API)
- Risk: The SDK is imported via `unstable_v2_createSession` — the `unstable_` prefix signals that the API may change without notice. The code extensively accesses undocumented internal properties (`.pid`, `.interrupt()`).
- Impact: SDK updates could break session lifecycle management, content extraction, and streaming.
- Migration plan: Pin the SDK version in package.json. When upgrading, test thoroughly against the session lifecycle tests.

### @anthropic-ai/claude-code (Bundled CLI)
- Risk: The application bundles the `claude-code` CLI and patches it postinstall (`scripts/patch-sdk.mjs`). Updates to `claude-code` could break the patching or the V2 session integration.
- Impact: Broken V2 sessions, inability to create new sessions.
- Migration plan: Pin the version. Document the patching mechanism so it can be updated.

## Missing Critical Features

### No Rate Limiting on WebSocket Messages
- Problem: Any authenticated client can send unlimited messages per second. A misbehaving client could flood the server with `session.create` or `chat.send` messages.
- Blocks: Production multi-user deployment

### No Request Timeout on Session Creation
- Problem: `session.create` in `src/stores/chat.ts` has a 10-second timeout on the client side, but the server side has no timeout. If the Claude SDK hangs during session creation, the server resource is consumed indefinitely.
- Blocks: Reliable cleanup of stuck sessions

### No Graceful Degradation When Claude API Is Down
- Problem: If the Anthropic API is unreachable, all chat sends will fail with unhelpful error messages. There is no retry logic, no queue, and no user-friendly messaging about API status.
- Blocks: Reliable operation during API outages

## Test Coverage Gaps

### WebSocket Message Handlers
- What's not tested: None of the 30+ WebSocket message handlers in `server/index.ts` have unit tests. The entire message routing, authentication flow, and handler logic is untested.
- Files: `server/index.ts` (lines 399-944)
- Risk: Any refactor of the message handler will likely introduce regressions.
- Priority: High

### Claude Session Lifecycle
- What's not tested: Session creation with V2 SDK, idle cleanup, abort handling, stall detection, crash recovery, resume from persisted SDK session ID. The test file `tests/server/claude-session.test.ts` is only 120 lines.
- Files: `server/claude-session.ts`, `tests/server/claude-session.test.ts`
- Risk: The most complex and critical code path has minimal test coverage. PID-based liveness detection and stall timeouts are completely untested.
- Priority: High

### Frontend Store Logic
- What's not tested: `src/stores/chat.ts` contains complex state management (streaming state accumulation, partial message saving, content block assembly) with zero test coverage.
- Files: `src/stores/chat.ts` (498 lines)
- Risk: Frontend state bugs are hard to debug and affect user experience directly.
- Priority: Medium

### WebAccess Service Integration
- What's not tested: The integration between `WebAccessService`, `CdpEngine`, and the MCP server tools. While individual units have tests, the full flow (navigate -> snapshot -> click -> extract) is not tested.
- Files: `server/web-access/`
- Risk: Browser automation is inherently fragile and environment-dependent.
- Priority: Medium

### Database Migration Correctness
- What's not tested: Schema migrations in `session-store.ts` and `cron-task-store.ts` are only tested against fresh databases. There are no tests that verify migrations work correctly against old schema versions.
- Files: `server/session-store.ts` (init method), `server/cron-task-store.ts` (init method)
- Risk: Migration bugs silently corrupt user data.
- Priority: High

---

*Concerns audit: 2026-04-07*
