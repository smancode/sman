# WS cron.skills

## Signature
```
WS message: { type: "cron.skills", workspace: string }
```

## Business Flow
Scans `{workspace}/.claude/skills/` for subdirectories with `crontab.md`. Parses cron expression from each file. Results cached for 1 minute (TTL). Returns skills with `{ name, hasCrontab, cronExpression }`.

## Called Services
`fs.readdirSync()` + `parseCrontabMd()` (from `cron-scheduler.js`)

## Source
`server/index.ts` — with 1-minute skills cache
