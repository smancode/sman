# WS settings.testAndSave

## Signature
```
WS message: {
  type: "settings.testAndSave",
  apiKey: string,
  model: string,
  baseUrl?: string,
  profileName?: string
}
```

## Business Flow
3-step flow: (1) Test API compatibility via `testAnthropicCompat()`, (2) Detect 3-layer capabilities via `detectCapabilities()`, (3) Upsert LLM profile into `savedLlms[]`. Returns `settings.testResult` with success/capabilities/savedLlms.

## Called Services
`testAnthropicCompat()` + `detectCapabilities()` + `settingsManager.saveLlmProfile()`

## Source
`server/index.ts`
