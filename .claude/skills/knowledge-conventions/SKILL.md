---
_scanned.commitHash: "70d53baa472e0b2f87d9b0080e3239118c1f1ec7"
_scanned.scannedAt: "2026-05-22T00:00:00.000Z"
_scanned.branch: "master"
---

# Development Conventions

Top 11 conventions from incremental scan (commit 70d53baa472e0b2f87d9b0080e3239118c1f1ec7).

## 1. Zod Schema Defensive Parsing Pattern

> by nasakim | 验证: 2026-05 | ✅ [已验证] src/schemas/im.ts

All WebSocket/API data must use Zod schemas with defensive `parseWithFallback` functions:
- Define enum values as `const` arrays first: `export const Values = ['a', 'b'] as const`
- Use `.passthrough()` on object schemas to allow extra fields (forward compatibility)
- Provide `.default()` values for optional arrays (e.g., `mentionedAgents: z.array(z.string()).default([])`)
- Create `parseXxx(data: unknown): Xxx` functions that return safe fallbacks on parse failure
- Export convenience fallback constants: `EMPTY_MESSAGES`, `EMPTY_ROOMS`
- This pattern prevents crashes when backend adds new fields or sends malformed data

## 2. Dependency Injection for Cross-Module Decoupling

> by nasakim | 验证: 2026-05 | ✅ [已验证] server/im/im-agent-bridge.ts

Classes that need to interact with external modules must use dependency injection via constructor callbacks:
- Inject callback functions instead of direct dependencies: `private createOrGetSession: (workspace: string) => Promise<...>`
- Document each callback with JSDoc comments explaining contract and behavior
- Use injected callbacks for all external operations (DB, network, session management)
- This enables loose coupling, easier testing, and prevents circular dependencies
- Example: `IMAgentBridge` injects `getWorkspaceForAgent`, `createOrGetSession`, `streamSessionMessage`

## 3. React Query + WebSocket Hybrid Pattern

> by nasakim | 验证: 2026-05 | ✅ [已验证] src/queries/use-im.ts

Features that use both WebSocket (real-time) and HTTP (explicit actions) must standardize on React Query + WS hybrid:
- Create `xxxFetch<T>()` helper with 5s timeout, Bearer auth, and `unreachable` flag
- Use `useQuery` for initial data fetch with `EMPTY_XXX` fallback constants
- Use `useMutation` for user-triggered actions (send, create, delete)
- Wrap WebSocket messaging in `wrapWsHandler()` for one-shot request/response
- Invalidate queries via `queryClient.invalidateQueries()` on WebSocket updates
- Pattern: `imFetch()` → `useQuery('im.rooms')`, `wrapWsHandler()` → `useMutation({ sendMessage })`

## 4. Agent Output Message Lifecycle

> by nasakim | 验证: 2026-05 | ✅ [已验证] server/im/im-agent-bridge.ts

Agent responses in group chat must follow a strict message lifecycle with status transitions:
- Create `agent_output` message with `status: 'running'` and empty `content`
- Broadcast creation immediately so UI shows loading indicator
- Stream deltas via WebSocket (`im.agent_delta`) with incremental content updates
- On completion: update message with `status: 'completed'` and final content
- On error: update with `status: 'failed'` and error message
- Use `crypto.randomUUID()` for message IDs, store timestamp as `Date.now()`

## 5. Init-Triggered Task Database Guard

> by nasakim | 验证: 2026-05 | ✅ [已验证] server/cron-executor.ts

Cron tasks triggered at init (e.g., `skill-auto-updater`) must skip database operations:
- Detect init-triggered tasks by `task.id.startsWith('init-')` prefix
- Skip `createRun()` for init tasks (no DB entry, avoids FK constraint failure)
- Set `runId = -1` and conditionally skip `updateRun()` calls: `if (runId > 0) { ... }`
- Still write lock files and track in `activeRuns` for abort capability
- Init tasks are one-shot, don't need persistent run history

## 6. Smart Path Step Edit Persistence

> by nasakim | 验证: 2026-05 | ✅ [已验证] server/smart-path-engine.ts

Step edits during Smart Path execution must be written back to `path.md`:
- Accept `stepEdits?: Record<number, Partial<SmartPathStep>>` parameter in completion handler
- Merge edits into existing steps: `userInput`, `name`, `deliveryCheck` fields
- Update `path.md` via `this.store.update(pathId, workspace, { steps: JSON.stringify(steps) })`
- Only write if changes detected (compare before/after)
- Preserves user adjustments across multiple runs of the same path

## 7. Achievement Hidden State for Unimplemented Features

> by nasakim | 验证: 2026-05 | ✅ [已验证] server/achievement-definitions.ts

Achievements tracking unimplemented features must be `hidden: true`:
- Set `hidden: true` for skill, code view, git op achievements (events not yet wired)
- Set `hidden: false` for collaboration achievements (show as "teaser" for upcoming features)
- Change `hidden` to `false` only when the corresponding event is integrated
- Level thresholds updated: star(2500), king(4000), legend(6000), epic(9000), eternal(15000)
- Smart path scoring: completed=2pts, failed=0.5pts (other statuses ignored)

## 8. Smart Path Run Log Table Integration

> by nasakim | 验证: 2026-05 | ✅ [已验证] server/smart-path-engine.ts, server/smart-path-store.ts

Smart Path execution must insert run logs into `smartpath_run_log` table:
- Call `insertRunLog()` after `createRun()` with mode ('stepping'/'full'), step count, args
- Update run log status on completion: `updateRunLogStatus(runId, 'completed')`
- Update on failure with error message: `updateRunLogStatus(runId, 'failed', msg)`
- Update on abort: `updateRunLogStatus(runId, 'cancelled')`
- Run log enables history tracking and achievement calculation from DB instead of filesystem scan

## 9. Lazy-Loaded Feature Components with Suspense

> by nasakim | 验证: 2026-05 | ✅ [已 verified] src/components/layout/Sidebar.tsx

Heavy route components must be lazy-loaded to reduce initial bundle size:
- Use `lazy(() => import('@/features/xxx/xxx'))` for route components
- Wrap in `<Suspense>` during render with loading fallback
- Pattern: `const IMListPanel = lazy(() => import('@/features/im/IMListPanel'))`
- Apply to all `/im`, `/stardom`, `/hub`, `/achievements` route components
- Reduces main bundle size and speeds up initial page load

## 10. useMemo for Expensive Computations in Render

> by nasakim | 验证: 2026-05 | ✅ [已验证] src/features/im/AgentCard.tsx

Expensive computations in React components must use `useMemo` to avoid re-renders:
- Memoize derived values: color lookup, text parsing, status display objects
- Dependency arrays must include all reactive values: `[message.sender]`, `[displayContent]`
- Pattern: `const summary = useMemo(() => getFirstLine(displayContent), [displayContent])`
- Avoid inline function calls in JSX that would re-run on every render
- Critical for list components (e.g., message lists with 50+ items)

## 11. Git 操作必须异步化

> by nasakim | 验证: 2026-05 | ✅ [已验证] server/git-handler.ts

所有 Git 操作必须使用异步 `execFile` 而非同步 `execSync`：
- 使用 `promisify(execFile)` 包装成 Promise
- 参数拆分数组传入，避免 shell 注入
- 所有导出函数改为 `async`：`handleGitStatus`, `handleGitDiff`, `handleGitCommit` 等
- WebSocket 处理器用 `.then()/.catch()` 处理异步结果
- AI 冲突解决：git push 遇到冲突时创建 ephemeral session 自动解决

## 12. SDK 会话清理必须完整

> by nasakim | 验证: 2026-05 | ✅ [已验证] server/chatbot/chatbot-session-manager.ts, server/claude-session.ts

Chatbot 会话超时或重置时，必须完整清理 V2 SDK 进程和会话 ID：
1. `abort()` 停止正在进行的流式响应
2. `closeV2Session()` 终止 V2 SDK 进程并删除映射
3. `clearSdkSessionId()` 清除 SDK 会话 ID，防止下次复用旧上下文
- 三步缺一不可，否则会导致上下文残留或僵尸进程

## 13. 脚本文件扩展名白名单

> by nasakim | 验证: 2026-05 | ✅ [已验证] server/index.ts: SCRIPT_EXTENSIONS

Reference 文件只能保存脚本文件，禁止保存数据文件：
- 白名单扩展名：`.py, .sh, .bash, .zsh, .js, .ts, .mjs, .cjs, .bat, .cmd, .ps1, .sql, .r, .rb, .go, .java, .pl, .lua, .php, .rs, .dart, .kt, .scala, .clj`
- 应用场景：Smart Path 提取 `[REFERENCE:filename.ext]` 时过滤，Earth Path 步骤规则明确
- 禁止保存 `.json, .csv, .txt, .xlsx, .xml, .yaml, .yml` 等数据文件
- 设计原因：脚本可复用，数据文件应放在 `tmp/` 中避免耦合

## 14. Smart Path 规则放在 User Prompt

> by nasakim | 验证: 2026-05 | ✅ [已验证] server/smart-path-engine.ts: buildStepPrompt

Smart Path 的步骤执行规则必须放在 **user prompt** 的 `[规则]` 段落，**绝对不能**修改 system prompt：
- `STEP_SYSTEM_PROMPT` 设为空字符串，规则全部移到 user prompt
- System prompt 是 session 级的，path 不应该污染它
- 步骤级别的限制（workspace skills 限制、脚本文件限制等）放在 user prompt 的 `[规则]` 段落

## 15. Zustand Store 与 WebSocket 集成模式

> by nasakim | 验证: 2026-05 | ✅ [已验证] src/stores/achievement.ts, src/stores/smart-path.ts

Zustand store 与 WebSocket 的标准集成模式：
- 在 store 文件中定义 `useWsConnection` 辅助函数获取 WebSocket client
- 使用 `ensureListeners()` 确保监听器只注册一次（通过 `listenersRegistered` 标志）
- WebSocket 消息监听器中直接调用 `useXxxStore.setState()` 更新状态
- 返回 cleanup 函数：`client.off(event, handler)` 用于取消监听
- 订阅 `useWsConnection` 的变化，client 断开重连时重新注册监听器
- 所有异步操作通过 WebSocket 发送消息，不用 fetch API
