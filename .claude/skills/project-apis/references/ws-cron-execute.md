# WS cron.execute

## Signature
```
WS message: { type: "cron.execute", taskId: string }
```

## Business Flow
Triggers the task immediately (bypasses cron schedule). Async — returns `cron.executed` when done or `chat.error` on failure.

## Called Services
`cronScheduler.executeNow()`

## Source
`server/index.ts`
