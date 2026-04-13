# WS settings.update

Update one or more config fields. Triggers re-initialization of affected services.

**Signature:** `settings.update` → partial `SmanConfig` fields → `settings.updated`

## Request Parameters

Any subset of `SmanConfig`: `llm`, `webSearch`, `chatbot`, `auth`, etc.

## Side Effects

- `sessionManager.updateConfig()` — reloads LLM/MCP config
- `batchEngine.setConfig()` — updates batch LLM config
- `chatbot` updates trigger restart of WeCom/Feishu/WeChat connections
- `auth.token` update changes the in-memory auth token

## Source

`server/index.ts` — `case 'settings.update'`
Calls: `settingsManager.updateConfig()` in `server/settings-manager.ts`
