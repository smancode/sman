# WS cron.runs

List execution history for a cron task.

**Signature:** `cron.runs` → `{ taskId: string, limit?: number }` → `cron.runs`

## Request Parameters

| Param | Type | Required | Description |
|-------|------|----------|-------------|
| `taskId` | string | Yes | Cron task UUID |
| `limit` | number | No | Max records (default 20) |

## Business Flow

Reads run records from `CronTaskStore`. Returns run ID, status, duration, output summary, and timestamps.

## Source

`server/index.ts` — `case 'cron.runs'`
Calls: `cronTaskStore.listRuns()` in `server/cron-task-store.ts`
