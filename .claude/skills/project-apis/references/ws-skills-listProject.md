# WS skills.listProject

List skills specific to the current session's workspace.

**Signature:** `skills.listProject` → `{ sessionId: string }` → `skills.listProject` with skills

## Request Parameters

| Param | Type | Required | Description |
|-------|------|----------|-------------|
| `sessionId` | string | Yes | Session UUID (to derive workspace) |

## Business Flow

Looks up session's workspace from SQLite, then reads skills from `{workspace}/.claude/skills/`. These are project-specific skills.

## Source

`server/index.ts` — `case 'skills.listProject'`
Calls: `skillsRegistry.getProjectSkills()` in `server/skills-registry.ts`
