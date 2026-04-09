# WS skills.listProject

## Signature
```
WS message: { type: "skills.listProject", sessionId: string }
```

## Business Flow
Returns project-specific skills from `{workspace}/.claude/skills/`. Resolves `sessionId` → workspace path.

## Called Services
`skillsRegistry.getProjectSkills(workspace)`

## Source
`server/index.ts`
