# WS skills.list

## Signature
```
WS message: { type: "skills.list" }
```

## Business Flow
Returns all global skills registered in `~/.sman/skills/`. Used by the Settings → Skills panel.

## Called Services
`skillsRegistry.listSkills()` → reads `~/.sman/registry.json`

## Source
`server/index.ts`
