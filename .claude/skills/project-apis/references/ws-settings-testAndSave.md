# WS settings.testAndSave

Test LLM credentials via API compatibility check, detect capabilities, then save as a named profile.

**Signature:** `settings.testAndSave` → `{ apiKey, model, baseUrl?, profileName? }` → `settings.testResult`

## Request Parameters

| Param | Type | Required | Description |
|-------|------|----------|-------------|
| `apiKey` | string | Yes | Anthropic API key |
| `model` | string | Yes | Model name (e.g. `claude-3-5-sonnet`) |
| `baseUrl` | string | No | Custom API base URL (e.g. for proxy) |
| `profileName` | string | No | Name for this saved profile |

## Business Flow

1. Calls `testAnthropicCompat(apiKey, model, baseUrl)` — tests API reachability
2. Calls `detectCapabilities(apiKey, model, baseUrl)` — 3-layer capability detection
3. Saves profile via `settingsManager.saveLlmProfile()` — upserts by name
4. Updates in-memory config and batch engine

## Source

`server/index.ts` — `case 'settings.testAndSave'`
Calls: `testAnthropicCompat()`, `detectCapabilities()` in `server/model-capabilities.ts`
