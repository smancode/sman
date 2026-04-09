# WS settings.deleteLlmProfile

## Signature
```
WS message: { type: "settings.deleteLlmProfile", profileName: string }
```

## Business Flow
Removes named profile from `savedLlms[]`. Does NOT switch active LLM. Returns `settings.updated`.

## Source
`server/index.ts`
