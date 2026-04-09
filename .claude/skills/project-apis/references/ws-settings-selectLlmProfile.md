# WS settings.selectLlmProfile

## Signature
```
WS message: { type: "settings.selectLlmProfile", profileName: string }
```

## Business Flow
Sets the active LLM from `savedLlms[]`. Propagates to `sessionManager`, `userProfileManager`, `batchEngine`. Returns `settings.updated`.

## Source
`server/index.ts`
