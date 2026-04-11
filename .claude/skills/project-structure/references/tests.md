# tests/server/ — Vitest Test Suite

## Purpose
Vitest unit tests for all server-side modules. Run with `pnpm test`.

## Key Files

| File | Module Under Test |
|---|---|
| `claude-session.test.ts` | `server/claude-session.ts` |
| `session-store.test.ts` | `server/session-store.ts` |
| `settings-manager.test.ts` | `server/settings-manager.ts` |
| `skills-registry.test.ts` | `server/skills-registry.ts` |
| `mcp-config.test.ts` | `server/mcp-config.ts` |
| `model-capabilities.test.ts` | `server/model-capabilities.ts` |
| `cron-scheduler.test.ts` | `server/cron-scheduler.ts` |
| `cron-task-store.test.ts` | `server/cron-task-store.ts` |
| `batch-engine.test.ts` | `server/batch-engine.ts` |
| `batch-store.test.ts` | `server/batch-store.ts` |
| `batch-utils.test.ts` | `server/batch-utils.ts` |
| `semaphore.test.ts` | `server/semaphore.ts` |
| `user-profile.test.ts` | `server/user-profile.ts` |
| `content-blocks.test.ts` | Content block parsing |
| `chatbot/*.test.ts` | Chatbot module tests |
| `web-access/*.test.ts` | Web Access module tests |
| `bazaar/*.test.ts` | Bazaar module tests |
| `capabilities/*.test.ts` | Capabilities module tests |

## Dependencies
- Tests import directly from `server/` source files
- `vitest` as test runner
- `tsx` for running TypeScript test files
