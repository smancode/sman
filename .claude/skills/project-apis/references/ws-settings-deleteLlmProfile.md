# WS settings.deleteLlmProfile

Delete a saved LLM profile by name.

**Signature:** `settings.deleteLlmProfile` → `{ profileName: string }` → `settings.updated`

## Business Flow

Removes the profile from `savedLlms[]` in config. Does not affect the currently active profile.

## Source

`server/index.ts` — `case 'settings.deleteLlmProfile'`
Calls: `settingsManager.deleteLlmProfile()` in `server/settings-manager.ts`
