# WS settings.get

## Signature
```
WS message: { type: "settings.get" }
```

## Business Flow
Returns the full `SmanConfig` object (llm, webSearch, chatbot, auth, etc.). Used by the frontend settings page to populate form fields.

## Called Services
`settingsManager.getConfig()` → reads `~/.sman/config.json`

## Source
`server/index.ts`
