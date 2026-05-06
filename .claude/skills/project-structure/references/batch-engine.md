# Batch Engine (server/batch-engine.ts)

Executes batch tasks with concurrency control via semaphore.

## Concurrency Control

Uses `Semaphore` to limit parallel task execution (default: 3 concurrent).

## Key Methods

- `executeBatch()`: Run batch tasks with semaphore
- `getProgress()`: Query batch execution status
- `cancelBatch()`: Stop in-progress batch

## Batch Flow

1. Parse prompt to extract task list
2. For each task: create isolated SDK session
3. Execute task with semaphore (wait if slot unavailable)
4. Collect results (success/failure counts)

## Persistence

Batch metadata stored in SQLite via `BatchStore`. Task logs written to files.

## Task Format

Tasks can be:
- Simple one-line prompts
- Multi-step instructions
- Code generation requests
- Analysis tasks

## Important

Batch sessions are isolated. Each task gets fresh SDK context (no conversation history).
