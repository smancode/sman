# WS batch.test

Test generated code against the first item.

**Signature:** `batch.test` → `{ taskId: string }` → `batch.tested` with results

## Business Flow

Executes the generated code against item[0] only to validate the template before running the full batch. Returns stdout/stderr.

## Source

`server/index.ts` — `case 'batch.test'`
Calls: `batchEngine.testCode()` in `server/batch-engine.ts`
