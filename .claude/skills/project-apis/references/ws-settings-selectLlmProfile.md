# WS settings.selectLlmProfile

Switch the active LLM profile to a previously saved one.

**Signature:** `settings.selectLlmProfile` → `{ profileName: string }` → `settings.updated`

## Business Flow

Looks up saved profile by name, sets it as active, updates session manager and batch engine config.

## Source

`server/index.ts` — `case 'settings.selectLlmProfile'`
Calls: `settingsManager.selectLlmProfile()` in `server/settings-manager.ts`
