# WS skills.list

List all global skills from `~/.sman/skills/`.

**Signature:** `skills.list` → `skills.list` with skill definitions

## Response

```json
{
  "skills": [
    { "name": "skill-name", "description": "...", "path": "/path/to/skill" }
  ]
}
```

## Business Flow

Reads the global skills registry (cached in memory). Global skills are loaded from `~/.sman/skills/` and the project skills from `{workspace}/.claude/skills/`.

## Source

`server/index.ts` — `case 'skills.list'`
Calls: `skillsRegistry.listSkills()` in `server/skills-registry.ts`
