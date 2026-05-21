# Cron Manual Trigger Reference

**Files**: `server/cron-executor.ts`, `server/cron-scheduler.ts`, `server/index.ts`

## WebSocket Endpoint

### Client → Server

```typescript
{ type: 'cron.execute', taskId: string }
```

### Server → Client

```typescript
{ type: 'cron.executed', taskId: string }
```

**Note**: Response sent immediately, execution runs in background.

## Execution Flow

```
Client sends cron.execute
  ↓
server/index.ts: case 'cron.execute'
  ↓
ws.send({ type: 'cron.executed', taskId })  // Immediate ACK
  ↓
cronScheduler.executeNow(taskId)  // Fire-and-forget
  ↓
CronExecutor.execute(task, manual=true)
  ↓
[Same execution path as scheduled run]
```

## Code Paths

### server/index.ts (line 1852)

```typescript
case 'cron.execute': {
  if (!msg.taskId) throw new Error('Missing taskId');
  const execTaskId = msg.taskId as string;
  ws.send(JSON.stringify({ type: 'cron.executed', taskId: execTaskId }));
  cronScheduler.executeNow(execTaskId).catch((err) => {
    log.error(`Cron execute failed for task ${execTaskId}`, { error: err });
  });
  break;
}
```

### server/cron-scheduler.ts (line 129)

```typescript
async executeNow(taskId: string): Promise<void> {
  const task = this.taskStore.getTask(taskId);
  if (!task) {
    throw new Error(`Task not found: ${taskId}`);
  }
  await this.executor.execute(task, true);  // manual=true
}
```

### server/cron-executor.ts (line 234)

```typescript
async execute(task: CronTask, manual = false): Promise<void> {
  const isInitTrigger = task.id.startsWith('init-');

  // skill-auto-updater: check 5-min idle window and serial execution
  // Skip for init-triggered and manual-triggered runs
  if (task.skillName === SKILL_AUTO_UPDATER && !isInitTrigger && !manual) {
    const lastActivity = this.sessionManager.getLastStreamActivityAt();
    const IDLE_THRESHOLD_MS = 5 * 60 * 1000;
    if (lastActivity > 0 && Date.now() - lastActivity < IDLE_THRESHOLD_MS) {
      this.log.info(`Task ${task.id} skipped: SDK was active ${Math.round((Date.now() - lastActivity) / 1000)}s ago, need 5min idle`);
      return;
    }
    if (this.activeRuns.size > 0) {
      this.log.info(`Task ${task.id} skipped: ${this.activeRuns.size} other task(s) running`);
      return;
    }
  }

  // ... rest of execution logic unchanged
}
```

## Key Differences: Manual vs Scheduled

| Aspect | Scheduled Run | Manual Trigger |
|--------|--------------|----------------|
| `manual` parameter | `false` | `true` |
| Idle window check | Enforced (5-min) | **Skipped** |
| Serial execution check | Enforced | **Skipped** |
| Execution path | Same | Same |
| Database logging | Same | Same |
| Error handling | Same | Same |

## Skill-Auto-Updater Behavior

### Scheduled Run (manual=false)
1. Check `getLastStreamActivityAt()` - must be >5min ago
2. Check `activeRuns.size` - must be 0
3. If checks fail → skip execution (log info)
4. If checks pass → execute

### Manual Trigger (manual=true)
1. **Skip idle window check**
2. **Skip serial execution check**
3. Execute immediately

### Init Trigger (task.id starts with 'init-')
1. **Skip idle window check**
2. **Skip serial execution check**
3. Execute immediately

## Use Cases

- User clicks "Run Now" button in cron task list UI
- Testing cron task execution immediately
- Bypassing skill-auto-updater idle guard for urgent runs
- Debugging cron task failures without waiting for schedule

## Error Handling

- Task not found → throw error in `executeNow()`
- Execution errors → logged, client already received `cron.executed`
- No WebSocket error response (fire-and-forget pattern)

## Database Impact

- Manual runs create `cron_runs` records (same as scheduled)
- `runId` generated and stored
- Status transitions: running → success/error
- All existing monitoring/logging applies

## Breaking Changes

**None** - `manual` parameter has default value `false`

Internal callers of `CronExecutor.execute()`:
- `CronScheduler.executeTask()` → passes no `manual` (defaults to `false`)
- `CronScheduler.executeNow()` → passes `manual=true`
