# WS cron.workspaces

List all workspaces that have at least one session.

**Signature:** `cron.workspaces` → `cron.workspaces` with workspace list

## Response

```json
{ "workspaces": ["/path/to/project1", "/path/to/project2"] }
```

## Business Flow

Derived from session list — used to populate the workspace selector when creating/editing cron tasks.

## Source

`server/index.ts` — `case 'cron.workspaces'`
Calls: `store.listSessions()` in `server/session-store.ts`
