# Agent Collaboration Mechanism Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Enable async task collaboration with sender-side caching and task.sync recovery so agents don't lose results when peers go offline.

**Architecture:** Agent B judges task complexity autonomously, sends task.chat for both "async processing" and final results. When A is offline, B's bridge caches the result locally. A queries B via task.sync after reconnecting. Bazaar server only forwards — no new state management needed server-side.

**Tech Stack:** TypeScript + better-sqlite3 + WebSocket (ws) + Vitest

**Spec:** `docs/superpowers/specs/2026-04-15-agent-collaboration-design.md`

---

## Chunk 1: Protocol + Server-side task.sync (Bazaar Server)

### Task 1: Add task.sync to protocol validation

**Files:**
- Modify: `bazaar/src/protocol.ts`

- [ ] **Step 1: Add task.sync to VALID_TYPES and REQUIRED_FIELDS**

In `bazaar/src/protocol.ts`, add `'task.sync'` to the Task section of `VALID_TYPES` (after `'task.escalate'`):

```typescript
  'task.cancel', 'task.cancelled', 'task.escalate', 'task.sync',
```

Add to `REQUIRED_FIELDS`:

```typescript
  'task.sync': ['taskId'],
```

- [ ] **Step 2: Run tsc check**

Run: `cd bazaar && npx tsc --noEmit`
Expected: PASS

- [ ] **Step 3: Write test for task.sync validation**

Add to `bazaar/tests/protocol.test.ts`:

```typescript
it('should validate task.sync message', () => {
  const msg = {
    id: 'test-1',
    type: 'task.sync',
    payload: { taskId: 'task-123' },
  };
  const result = validateMessage(msg);
  expect(result.valid).toBe(true);
});

it('should reject task.sync without taskId', () => {
  const msg = {
    id: 'test-2',
    type: 'task.sync',
    payload: {},
  };
  const result = validateMessage(msg);
  expect(result.valid).toBe(false);
  expect(result.errors).toContain(expect.stringContaining('taskId'));
});
```

- [ ] **Step 4: Run tests**

Run: `cd bazaar && npx vitest run tests/protocol.test.ts`
Expected: PASS (both new tests)

- [ ] **Step 5: Commit**

```bash
git add bazaar/src/protocol.ts bazaar/tests/protocol.test.ts
git commit -m "feat(bazaar): add task.sync to protocol validation"
```

### Task 2: Add handleTaskSync to TaskEngine

**Files:**
- Modify: `bazaar/src/task-engine.ts`
- Modify: `bazaar/tests/task-engine.test.ts`

- [ ] **Step 1: Write failing test**

Add to `bazaar/tests/task-engine.test.ts`:

```typescript
describe('handleTaskSync', () => {
  it('should forward task.sync to the helper agent', () => {
    // Setup: create and match a task
    ctx.mockAgentStore.listOnlineAgents = vi.fn(() => [
      { id: 'helper-1', name: 'Helper', status: 'idle', reputation: 50, description: 'test' },
    ]);
    ctx.mockAgentStore.getAgent = vi.fn((id: string) =>
      id === 'helper-1' ? { id: 'helper-1', name: 'Helper', status: 'idle', reputation: 50 } : undefined
    );

    const createResult = engine.handleTaskCreate(
      { id: 'm1', payload: { question: 'help me', capabilityQuery: 'test' } },
      'requester-1',
    );
    const taskId = createResult.taskId;

    engine.handleTaskOffer(
      { id: 'm2', payload: { taskId, targetAgent: 'helper-1' } },
      'requester-1',
    );
    engine.handleTaskAccept(
      { id: 'm3', payload: { taskId } },
      'helper-1',
    );

    ctx.sent.length = 0; // Clear previous sends

    // Act: requester sends task.sync
    engine.handleTaskSync(
      { id: 'm4', payload: { taskId } },
      'requester-1',
    );

    // Assert: forwarded to helper
    expect(ctx.sent).toEqual([
      expect.objectContaining({
        agentId: 'helper-1',
        data: expect.objectContaining({
          type: 'task.sync',
          payload: expect.objectContaining({ taskId }),
        }),
      }),
    ]);
  });

  it('should reply with progress when helper is offline (no connection)', () => {
    // Create a task where helper has no active connection
    ctx.mockAgentStore.listOnlineAgents = vi.fn(() => [
      { id: 'helper-1', name: 'Helper', status: 'idle', reputation: 50, description: 'test' },
    ]);
    ctx.mockAgentStore.getAgent = vi.fn((id: string) =>
      id === 'helper-1' ? { id: 'helper-1', name: 'Helper', status: 'offline', reputation: 50 } : undefined
    );

    const createResult = engine.handleTaskCreate(
      { id: 'm1', payload: { question: 'help me', capabilityQuery: 'test' } },
      'requester-1',
    );
    const taskId = createResult.taskId;

    engine.handleTaskOffer(
      { id: 'm2', payload: { taskId, targetAgent: 'helper-1' } },
      'requester-1',
    );
    engine.handleTaskAccept(
      { id: 'm3', payload: { taskId } },
      'helper-1',
    );

    ctx.sent.length = 0;

    // Remove helper from connections to simulate offline
    ctx.connections.delete('helper-1');

    engine.handleTaskSync(
      { id: 'm4', payload: { taskId } },
      'requester-1',
    );

    // Should get a progress response about helper being offline
    expect(ctx.sent).toEqual([
      expect.objectContaining({
        agentId: 'requester-1',
        data: expect.objectContaining({
          type: 'task.progress',
          payload: expect.objectContaining({ taskId, status: 'waiting_helper' }),
        }),
      }),
    ]);
  });

  it('should reject sync from non-participant', () => {
    const result = engine.handleTaskSync(
      { id: 'm1', payload: { taskId: 'nonexistent' } },
      'random-agent',
    );
    expect(result.error).toBeTruthy();
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd bazaar && npx vitest run tests/task-engine.test.ts`
Expected: FAIL (handleTaskSync not defined)

- [ ] **Step 3: Implement handleTaskSync**

Add to `bazaar/src/task-engine.ts`:

```typescript
  handleTaskSync(msg: { id: string; payload: Record<string, unknown> }, fromAgentId: string): { error?: string } {
    const taskId = msg.payload.taskId as string;
    const task = this.taskStore.getTask(taskId);
    if (!task) {
      return { error: 'Task not found' };
    }

    // Verify caller is a participant
    if (task.requesterId !== fromAgentId && task.helperId !== fromAgentId) {
      return { error: 'Not a task participant' };
    }

    // Determine the peer to forward to
    const peerId = task.requesterId === fromAgentId ? task.helperId : task.requesterId;
    if (!peerId) {
      return { error: 'No peer agent found for this task' };
    }

    // Check if peer has an active connection
    const peerWs = this.connections.get(peerId);
    if (!peerWs || peerWs.readyState !== 1) {
      // Peer is offline — inform the requester
      this.sendTo(fromAgentId, {
        type: 'task.progress',
        id: uuidv4(),
        payload: { taskId, status: 'waiting_helper', detail: `Agent ${peerId} is offline` },
      });
      return {};
    }

    // Forward task.sync to the peer
    this.sendTo(peerId, {
      type: 'task.sync',
      id: uuidv4(),
      payload: { taskId },
    });

    this.agentStore.logAudit('task.sync', fromAgentId, peerId, taskId, {});
    return {};
  }
```

- [ ] **Step 4: Run tests**

Run: `cd bazaar && npx vitest run tests/task-engine.test.ts`
Expected: PASS (all task-engine tests including new ones)

- [ ] **Step 5: Commit**

```bash
git add bazaar/src/task-engine.ts bazaar/tests/task-engine.test.ts
git commit -m "feat(bazaar): add handleTaskSync — forward sync to peer or reply waiting_helper"
```

### Task 3: Wire task.sync in message-router

**Files:**
- Modify: `bazaar/src/message-router.ts`

- [ ] **Step 1: Add task.sync handler in handleTaskMessage**

In `bazaar/src/message-router.ts`, in the `handleTaskMessage` method, add a new `else if` block after `task.cancel`:

```typescript
    } else if (type === 'task.cancel') {
      this.taskEngine.handleTaskCancel(msg, fromAgentId);
      return { handled: true };
    } else if (type === 'task.sync') {
      const result = this.taskEngine.handleTaskSync(msg, fromAgentId);
      if (result.error) {
        send({ type: 'error', id: uuidv4(), payload: { message: result.error } });
      }
      return { handled: true };
    }
```

- [ ] **Step 2: Run tsc check**

Run: `cd bazaar && npx tsc --noEmit`
Expected: PASS

- [ ] **Step 3: Run all bazaar tests**

Run: `cd bazaar && npx vitest run`
Expected: PASS

- [ ] **Step 4: Commit**

```bash
git add bazaar/src/message-router.ts
git commit -m "feat(bazaar): wire task.sync in message router"
```

---

## Chunk 2: Bridge-side Cached Results + Sync (Client Layer)

### Task 4: Add cached_results table to BazaarStore

**Files:**
- Modify: `server/bazaar/bazaar-store.ts`

- [ ] **Step 1: Add cached_results table and methods**

In `server/bazaar/bazaar-store.ts`, add to the `init()` method's `this.db.exec(...)` block, after the `pair_history` table and before `PRAGMA journal_mode=WAL;`:

```sql
      CREATE TABLE IF NOT EXISTS cached_results (
        task_id TEXT PRIMARY KEY,
        result_text TEXT NOT NULL,
        from_agent TEXT NOT NULL,
        cached_at TEXT NOT NULL DEFAULT (datetime('now'))
      );
```

Add three new methods to the `BazaarStore` class:

```typescript
  // ── Cached Results (async task delivery) ──

  saveCachedResult(input: { taskId: string; resultText: string; fromAgent: string }): void {
    this.db.prepare(`
      INSERT OR REPLACE INTO cached_results (task_id, result_text, from_agent, cached_at)
      VALUES (?, ?, ?, datetime('now'))
    `).run(input.taskId, input.resultText, input.fromAgent);
  }

  getCachedResult(taskId: string): { taskId: string; resultText: string; fromAgent: string; cachedAt: string } | undefined {
    return this.db.prepare(`
      SELECT task_id as taskId, result_text as resultText, from_agent as fromAgent, cached_at as cachedAt
      FROM cached_results WHERE task_id = ?
    `).get(taskId) as any;
  }

  deleteCachedResult(taskId: string): void {
    this.db.prepare('DELETE FROM cached_results WHERE task_id = ?').run(taskId);
  }

  /** List all tasks that are in an active state (chatting/matched) — used for sync on reconnect */
  listActiveTasks(): BazaarLocalTask[] {
    return this.db.prepare(`
      SELECT task_id as taskId, direction, helper_agent_id as helperAgentId,
        helper_name as helperName, requester_agent_id as requesterAgentId,
        requester_name as requesterName, question, status, rating,
        created_at as createdAt, completed_at as completedAt
      FROM tasks WHERE status IN ('chatting', 'matched')
      ORDER BY created_at DESC
    `).all() as BazaarLocalTask[];
  }
```

- [ ] **Step 2: Run tsc check**

Run: `npx tsc --noEmit`
Expected: PASS

- [ ] **Step 3: Commit**

```bash
git add server/bazaar/bazaar-store.ts
git commit -m "feat(bazaar): add cached_results table and listActiveTasks to BazaarStore"
```

### Task 5: Add sendTaskSync to BazaarClient + trigger on reconnect

**Files:**
- Modify: `server/bazaar/bazaar-client.ts`

- [ ] **Step 1: Add sendTaskSync method and onReconnect hook**

Add a public callback property to `BazaarClient`:

```typescript
  // Called after successful connection/reconnection
  public onReconnect: (() => void) | null = null;
```

In the `ws.on('open')` handler, after `this.startHeartbeat(identity.agentId);` and before `resolve();`, add:

```typescript
        // Notify bridge to sync active tasks
        if (this.onReconnect) {
          this.onReconnect();
        }
```

Add the `sendTaskSync` method:

```typescript
  /** Send task.sync for a specific task to the bazaar server */
  sendTaskSync(taskId: string): void {
    this.send({
      id: uuidv4(),
      type: 'task.sync',
      payload: { taskId },
    });
  }
```

- [ ] **Step 2: Run tsc check**

Run: `npx tsc --noEmit`
Expected: PASS

- [ ] **Step 3: Commit**

```bash
git add server/bazaar/bazaar-client.ts
git commit -m "feat(bazaar): add sendTaskSync and onReconnect callback to BazaarClient"
```

### Task 6: Wire sync logic in BazaarBridge

**Files:**
- Modify: `server/bazaar/bazaar-bridge.ts`

- [ ] **Step 1: Wire onReconnect to sync active tasks**

In the `start()` method, after `this.bazaarSession = new BazaarSession(...)` and before the log line, add:

```typescript
      // On reconnect, sync all active tasks
      this.client.onReconnect = () => this.syncActiveTasks();
```

Add the `syncActiveTasks` method to `BazaarBridge`:

```typescript
  /** Called on (re)connect — send task.sync for all locally active tasks */
  private syncActiveTasks(): void {
    const activeTasks = this.store.listActiveTasks();
    if (activeTasks.length === 0) return;

    this.log.info(`Syncing ${activeTasks.length} active tasks on reconnect`);
    for (const task of activeTasks) {
      this.client.sendTaskSync(task.taskId);
    }
  }
```

- [ ] **Step 2: Handle task.sync from bazaar — check local cache**

In `handleBazaarMessage`, add a new case in the switch statement (before `default:`):

```typescript
      case 'task.sync':
        this.handleIncomingSync(msg.payload);
        break;
```

Add the `handleIncomingSync` method:

```typescript
  private handleIncomingSync(payload: Record<string, unknown>): void {
    const taskId = payload.taskId as string;
    this.log.info(`Received task.sync for ${taskId}`);

    // Check if we have a cached result for this task
    const cached = this.store.getCachedResult(taskId);
    if (cached) {
      // Send the cached result back via task.chat
      this.client.send({
        id: uuidv4(),
        type: 'task.chat',
        payload: { taskId, text: cached.resultText },
      });

      // Push to frontend
      this.deps.broadcast(JSON.stringify({
        type: 'bazaar.task.chat.delta',
        taskId,
        from: 'local',
        text: cached.resultText,
      }));

      // Clear the cache
      this.store.deleteCachedResult(taskId);
      this.log.info(`Delivered cached result for task ${taskId}`);
    } else {
      // No cached result — check if collaboration is still active
      const activeCollab = this.bazaarSession?.getActiveCollaboration(taskId);
      if (activeCollab) {
        // Still processing — let the requester know
        this.client.send({
          id: uuidv4(),
          type: 'task.chat',
          payload: { taskId, text: '还在处理中' },
        });
      }
      // If no active collaboration and no cache, the task may have been lost
      // The requester will see no response and can decide to cancel
    }
  }
```

- [ ] **Step 3: Cache result when task.chat send fails (A is offline)**

In `handleBazaarMessage`, find the `handleIncomingChat` case. Currently it does:

```typescript
    // 推送到前端
    this.deps.broadcast(JSON.stringify({
      type: 'bazaar.task.chat.delta',
      taskId,
      from,
      text,
    }));
```

After the broadcast, add caching logic for local agent replies when the peer might be offline:

```typescript
    // If this is our local agent's reply, cache it for task.sync recovery
    if (from === 'local') {
      // Check if the peer connection is alive by seeing if we got an ack
      // Simple approach: always cache local replies for active tasks
      const task = this.store.getTask(taskId);
      if (task && (task.status === 'chatting' || task.status === 'matched')) {
        this.store.saveCachedResult({
          taskId,
          resultText: text,
          fromAgent: 'local',
        });
      }
    }
```

- [ ] **Step 4: Run tsc check**

Run: `npx tsc --noEmit`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add server/bazaar/bazaar-bridge.ts
git commit -m "feat(bazaar): wire task.sync — sync on reconnect, cache local replies, deliver on sync"
```

---

## Chunk 3: Integration Verification

### Task 7: Add task.sync to shared types

**Files:**
- Modify: `shared/bazaar-types.ts`

- [ ] **Step 1: Add task.sync to TaskMessageType**

Read `shared/bazaar-types.ts`, find the `TaskMessageType` union, and add `'task.sync'`:

```typescript
export type TaskMessageType =
  | 'task.create' | 'task.search_result' | 'task.offer' | 'task.incoming'
  | 'task.accept' | 'task.reject' | 'task.matched' | 'task.chat'
  | 'task.progress' | 'task.complete' | 'task.result' | 'task.timeout'
  | 'task.cancel' | 'task.cancelled' | 'task.escalate' | 'task.sync';
```

- [ ] **Step 2: Run tsc check**

Run: `npx tsc --noEmit`
Expected: PASS

- [ ] **Step 3: Commit**

```bash
git add shared/bazaar-types.ts
git commit -m "feat(bazaar): add task.sync to shared TaskMessageType"
```

### Task 8: Final verification

- [ ] **Step 1: Run bazaar server tsc**

Run: `cd bazaar && npx tsc --noEmit`
Expected: PASS

- [ ] **Step 2: Run main project tsc**

Run: `npx tsc --noEmit`
Expected: PASS

- [ ] **Step 3: Run bazaar tests**

Run: `cd bazaar && npx vitest run`
Expected: All tests PASS

- [ ] **Step 4: Run main project tests**

Run: `npx vitest run`
Expected: All tests PASS

- [ ] **Step 5: Run build**

Run: `pnpm build`
Expected: Build succeeds

---

## File Summary

### Modified

| File | Change |
|------|--------|
| `bazaar/src/protocol.ts` | Add `task.sync` to VALID_TYPES + REQUIRED_FIELDS |
| `bazaar/src/task-engine.ts` | Add `handleTaskSync()` — forward to peer or reply waiting_helper |
| `bazaar/src/message-router.ts` | Wire `task.sync` in `handleTaskMessage` |
| `server/bazaar/bazaar-store.ts` | Add `cached_results` table, `saveCachedResult`, `getCachedResult`, `deleteCachedResult`, `listActiveTasks` |
| `server/bazaar/bazaar-client.ts` | Add `sendTaskSync()`, `onReconnect` callback |
| `server/bazaar/bazaar-bridge.ts` | Wire `syncActiveTasks` on reconnect, `handleIncomingSync`, cache local replies |
| `shared/bazaar-types.ts` | Add `task.sync` to `TaskMessageType` |

### New test coverage

| Test file | Tests added |
|-----------|-------------|
| `bazaar/tests/protocol.test.ts` | `task.sync` validation (valid + missing taskId) |
| `bazaar/tests/task-engine.test.ts` | `handleTaskSync` — forward to helper, waiting_helper when offline, reject non-participant |
