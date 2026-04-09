# WS cron.workspaces

## Signature
```
WS message: { type: "cron.workspaces" }
```

## Business Flow
Returns unique workspace paths from all sessions in SQLite. Used to populate the workspace dropdown when creating cron tasks.

## Called Services
`store.listSessions()` → deduplicate workspaces

## Source
`server/index.ts`
