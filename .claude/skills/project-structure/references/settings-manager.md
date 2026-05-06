# Settings Manager (server/settings-manager.ts)

Manages `~/.sman/config.json` with atomic reads/writes.

## Config Structure (SmanConfig)

```typescript
{
  port: number,
  llm: { apiKey: string, model: string, userProfile: boolean },
  savedLlms: Array<{name: string, apiKey: string, model: string}>,
  currentLlmProfile: string,
  webSearch: {
    provider: 'builtin' | 'brave' | 'tavily' | 'bing' | 'baidu',
    braveApiKey: string,
    tavilyApiKey: string,
    bingApiKey: string,
    baiduApiKey: string,
    maxUsesPerSession: number
  },
  chatbot: {
    enabled: boolean,
    wecom: { enabled: boolean, botId: string, secret: string },
    feishu: { enabled: boolean, appId: string, appSecret: string },
    weixin: { enabled: boolean }
  },
  auth: { token: string }
}
```

## Key Methods

- `getConfig()`: Read full config
- `updateLlm()`: Update LLM settings
- `updateWebSearch()`: Update web search provider
- `updateChatbot()`: Update chatbot config
- `updateAuth()`: Update auth token
- `saveLlmProfile()`: Save named LLM profile
- `loadLlmProfile()`: Load profile by name

## File Permissions

Config file created with mode `0o600` (owner read/write only).

## Important

Each read returns fresh config from disk (no caching). Writes are atomic (full file replace).
