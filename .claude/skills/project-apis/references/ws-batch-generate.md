# WS batch.generate

Parse markdown template and generate execution items.

**Signature:** `batch.generate` → `{ taskId: string }` → `batch.generated` with code

## Business Flow

Calls `batchEngine.generateCode(taskId)` which parses the task's `mdContent` markdown, extracts items (e.g. rows from a table), and stores the generated item list in the database.

## Source

`server/index.ts` — `case 'batch.generate'`
Calls: `batchEngine.generateCode()` in `server/batch-engine.ts`
