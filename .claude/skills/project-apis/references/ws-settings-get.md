# WS settings.get

Retrieve the full server configuration (LLM, web search, chatbot, auth, etc.).

**Signature:** `settings.get` → `settings.get` with full `SmanConfig`

## Response

```json
{
  "config": {
    "llm": { "apiKey": "...", "model": "...", "baseUrl": "...", "savedLlms": [...] },
    "webSearch": { "provider": "builtin", "braveApiKey": "", ... },
    "chatbot": { "enabled": false, "wecom": {...}, "feishu": {...} },
    "auth": { "token": "..." }
  }
}
```

## Business Flow

Returns the in-memory config (without exposing raw API keys in some views). API keys are partially masked in the frontend.

## Source

`server/index.ts` — `case 'settings.get'`
Calls: `settingsManager.getConfig()` in `server/settings-manager.ts`
