# `cron.create` WebSocket Endpoint

## Signature
```
Client → Server: { type: 'cron.create', workspace: string, skillName: string, cronExpression: string }
Server → Client: { type: 'cron.created', task: CronTask }
```

## Request Parameters
- `workspace` (string, required): Project directory path
- `skillName` (string, required): Skill name from .claude/skills/
- `cronExpression` (string, required): Cron expression (e.g., "0 9 * * 1-5")

## Business Flow
1. Validate required fields
2. Parse cron expression (cron-parser)
3. Create task in CronTaskStore (source: 'manual')
4. Schedule task in CronScheduler
5. Broadcast `cron.changed` to all clients
6. Return created task

## Called Services
- `CronTaskStore.createTask()`: Persist task to SQLite
- `CronScheduler.schedule()`: Register task for execution
- `CronExpressionParser.parse()`: Validate cron syntax

## Source File
`server/index.ts:1085-1103`
