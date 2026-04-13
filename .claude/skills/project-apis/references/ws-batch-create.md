# WS batch.create

Create a new batch task with markdown template and execution template.

**Signature:** `batch.create` → `{ workspace, skillName, mdContent, execTemplate, envVars?, concurrency?, retryOnFailure? }` → `batch.created`

## Request Parameters

| Param | Type | Required | Description |
|-------|------|----------|-------------|
| `workspace` | string | Yes | Project path |
| `skillName` | string | Yes | Skill name |
| `mdContent` | string | Yes | Markdown template with placeholder items |
| `execTemplate` | string | Yes | Execution template (Claude Code prompt) |
| `envVars` | object | No | Environment variables for execution |
| `concurrency` | number | No | Max parallel executions (default: semaphore limit) |
| `retryOnFailure` | number | No | Retry count for failed items |

## Business Flow

Creates a batch task in `BatchStore`. Items are parsed from `mdContent` by `BatchEngine.generateCode()` in a separate step.

## Source

`server/index.ts` — `case 'batch.create'`
Calls: `batchStore.createTask()` in `server/batch-store.ts`
