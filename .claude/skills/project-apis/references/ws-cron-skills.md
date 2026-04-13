# WS cron.skills

List all skills in a workspace that have a `crontab.md` file.

**Signature:** `cron.skills` → `{ workspace: string }` → `cron.skills` with skills

## Response

```json
{
  "workspace": "/path/to/project",
  "skills": [
    { "name": "my-skill", "hasCrontab": true, "cronExpression": "0 9 * * *" }
  ]
}
```

## Business Flow

Reads `{workspace}/.claude/skills/<name>/crontab.md`, parses the cron expression. Results are cached for 1 minute.

## Source

`server/index.ts` — `case 'cron.skills'`
Parser: `parseCrontabMd()` in `server/cron-scheduler.ts`
