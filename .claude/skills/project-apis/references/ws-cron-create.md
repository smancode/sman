# WS cron.create

Create a new cron task and schedule it.

**Signature:** `cron.create` → `{ workspace, skillName, cronExpression }` → `cron.created`

## Request Parameters

| Param | Type | Required | Description |
|-------|------|----------|-------------|
| `workspace` | string | Yes | Project path |
| `skillName` | string | Yes | Skill directory name |
| `cronExpression` | string | Yes | Standard 5-field cron expression |

## Business Flow

1. Validates cron expression via `CronExpressionParser.parse()`
2. Creates SQLite record in `CronTaskStore`
3. Registers with `CronScheduler.schedule()`
4. Broadcasts `cron.changed` to all clients

## Source

`server/index.ts` — `case 'cron.create'`
Calls: `cronTaskStore.createTask()`, `cronScheduler.schedule()` in `server/cron-scheduler.ts`
