# WS settings.update

## Signature
```
WS message: { type: "settings.update", ...partialConfig }
```

## Business Flow
Deep-merges partial config into `~/.sman/config.json`. Propagates changes to `sessionManager`, `userProfileManager`, `batchEngine`. If chatbot config changed, restarts all bot connections. Returns `{ type: "settings.updated", config }`.

## Called Services
`settingsManager.updateConfig()` → file write + in-memory update

## Source
`server/index.ts`
